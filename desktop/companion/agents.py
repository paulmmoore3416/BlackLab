"""BlackLab Desktop agents (companion 1.4): work the phone hands off and picks up later.

  * Jobs: one queue, one worker (the GPUs do one heavy thing at a time), persisted in
    ~/.cache/blacklab-desktop/jobs.json so a restart resumes where it stopped. The phone uploads files
    (/phone/upload), starts jobs (/phone/jobs), and collects finished results on its next check-in.
  * transcribe: a phone recording → offline Whisper transcript → minutes, decisions and action items
    (saved in Documents/Meetings; the action items go back to the phone as tasks).
  * equipment: a nameplate photo → local vision model → the Equipment registry (Equipment/equipment.db),
    with a history of photos, work orders, meetings and chats for every unit, and repeat-issue flags.
  * brief / weekly: the overnight operations analyst, from the phone's context snapshot (/phone/context).
  * ask: any desktop-assistant request, run in the background with a bigger tool budget.
  * reverse: a reverse-engineering report on one file (REA + Ghidra, read-only; see reverse.py).

Every job is a task in the task log, and the privacy guard applies to everything stored.
"""
from __future__ import annotations

import base64
import datetime as dt
import json
import os
import queue
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

C = None  # the companion module (set by setup)
JOB_KINDS = ("transcribe", "equipment", "brief", "weekly", "ask", "reverse")
_LOCK = threading.RLock()
_Q: "queue.Queue[str]" = queue.Queue()
JOBS: dict[str, dict] = {}
UPLOADS: dict[str, str] = {}
MAX_UPLOAD = 2_000_000_000


# ---------------------------------------------------------------- setup + paths

def setup(core):
    global C, JOBS_FILE, CONTEXT_FILE, INBOX, MEETINGS_DIR, BRIEFS_DIR, EQUIP_DIR, EQUIP_DB, WHISPER_PY
    C = core
    JOBS_FILE = C.CACHE_DIR / "jobs.json"
    CONTEXT_FILE = C.CACHE_DIR / "phone-context.json"
    INBOX = C.DOWNLOADS_DIR / "From phone"
    MEETINGS_DIR = C.DOCS_DIR / "Meetings"
    BRIEFS_DIR = C.DOCS_DIR / "Briefs"
    EQUIP_DIR = C._cfg_dir("equipment_dir", C.AI_DIR / "Equipment")
    EQUIP_DB = EQUIP_DIR / "equipment.db"
    WHISPER_PY = Path(os.path.expanduser(C.CONFIG.get("whisper_python", "~/.local/share/blacklab-desktop/whisper/bin/python")))
    for k in ("equipment", "equipment list", "assets"):
        C.FOLDERS.setdefault(k, str(EQUIP_DIR.relative_to(C.HOME)) if str(EQUIP_DIR).startswith(str(C.HOME) + "/") else "")
    C.DOC_ROOTS.append(EQUIP_DIR / "Cards")
    for sub, names in (("Business Intelligence", ("business intelligence", "bi", "marketing")), ("Crypto Desk", ("crypto desk", "crypto"))):
        d = C.AI_DIR / sub
        if d.is_dir():
            C.DOC_ROOTS.append(d)  # its guides and knowledge base become searchable ("search my documents for …")
            for k in names:
                C.FOLDERS.setdefault(k, str(d.relative_to(C.HOME)) if str(d).startswith(str(C.HOME) + "/") else "")
    _load()
    threading.Thread(target=_worker, daemon=True, name="jobs").start()
    threading.Thread(target=_scheduler, daemon=True, name="schedule").start()


def _write_private(path: Path, text: str):
    C._private_dir(path.parent)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.replace(tmp, path)


def _slug(s: str, n=60) -> str:
    return re.sub(r"[^A-Za-z0-9]+", " ", s or "").strip()[:n].strip() or "untitled"


# ---------------------------------------------------------------- job store

def _load():
    data = C.load_json(JOBS_FILE, {})
    with _LOCK:
        JOBS.update(data.get("jobs", {}))
        UPLOADS.update(data.get("uploads", {}))
        cutoff = time.time() - 30 * 86400  # keep a month of history
        for jid in [j for j, v in JOBS.items() if v.get("finished", time.time()) < cutoff and v.get("delivered")]:
            del JOBS[jid]
        for j in sorted(JOBS.values(), key=lambda j: j["created"]):
            if j["status"] in ("queued", "running"):
                j["status"], j["progress"] = "queued", "Waiting (resumed after a restart)"
                _Q.put(j["id"])
    _save()


def _save():
    with _LOCK:
        _write_private(JOBS_FILE, json.dumps({"jobs": JOBS, "uploads": UPLOADS}, ensure_ascii=False))


def public(j: dict) -> dict:
    """What the phone and the desktop UI see (no internal paths beyond the user's own home)."""
    keys = ("id", "kind", "title", "status", "progress", "created", "started", "finished", "origin", "private", "result", "error", "delivered")
    return {k: j.get(k) for k in keys}


def status() -> dict:
    vm = C.CONFIG.get("vision_model", "qwen2.5vl:7b")
    have = C.ollama_models(include_embed=True)
    n = 0
    if EQUIP_DB.exists():
        con = db()
        n = con.execute("SELECT count(*) FROM assets").fetchone()[0]
        con.close()
    js = list(JOBS.values())
    return {"whisper": whisper_ready(), "vision": vm if any(m.split(":")[0] == vm.split(":")[0] for m in have) else None,
            "running": sum(j["status"] == "running" for j in js), "queued": sum(j["status"] == "queued" for j in js), "assets": n,
            "brief_time": C.CONFIG.get("brief_time", default_brief_time()), "mining": mining_active(), "weekly_time": C.CONFIG.get("weekly_time", "15:30"),
            "folders": {"equipment": str(EQUIP_DIR), "meetings": str(MEETINGS_DIR), "briefs": str(BRIEFS_DIR)}}


def submit(kind: str, title: str = "", params: dict | None = None, upload: str | None = None, origin="BlackLab Desktop (this computer)",
           private=False) -> dict:
    if kind not in JOB_KINDS:
        raise ValueError(f"unknown job kind: {kind} (one of {', '.join(JOB_KINDS)})")
    path = None
    if upload:
        path = UPLOADS.get(upload)
        if not path or not Path(path).is_file():
            raise ValueError("that upload isn't here; send the file again")
    j = {"id": uuid.uuid4().hex[:12], "kind": kind, "title": (title or kind.title())[:120], "params": params or {}, "file": path,
         "status": "queued", "progress": "Waiting", "created": time.time(), "started": None, "finished": None,
         "origin": origin, "private": bool(private), "result": None, "error": None, "delivered": False}
    with _LOCK:
        JOBS[j["id"]] = j
    _save()
    _Q.put(j["id"])
    return j


def _progress(j: dict, text: str):
    j["progress"] = text
    _save()


MINING_UNITS = ("mining-gpu-nvidia.service", "mining-gpu-amd.service", "mining-gpu-qtc.service", "mining-orange-xmrig.service")


def mining_active() -> bool:
    """Night Mining (~/Desktop/mining) owns both GPUs and every CPU core while it runs."""
    if C.CONFIG.get("run_during_mining"):
        return False
    try:
        out = subprocess.run(["systemctl", "--user", "is-active", *MINING_UNITS], capture_output=True, text=True, timeout=5).stdout
        return "active" in out.split()
    except (OSError, subprocess.SubprocessError):
        return False


def mining_stop_time() -> str | None:
    try:
        out = subprocess.run(["systemctl", "--user", "cat", "mining-stop.timer"], capture_output=True, text=True, timeout=5).stdout
        m = re.search(r"OnCalendar=.*?(\d{1,2}):(\d{2})", out)
        return f"{int(m.group(1)):02d}:{m.group(2)}" if m else None
    except (OSError, subprocess.SubprocessError):
        return None


def default_brief_time() -> str:
    """05:30, or 10 minutes after Night Mining hands the GPUs back when it stops between 03:00 and 09:00."""
    stop = mining_stop_time()
    if stop and "03:00" <= stop <= "09:00":
        t = dt.datetime.strptime(stop, "%H:%M") + dt.timedelta(minutes=10)
        return t.strftime("%H:%M")
    return "05:30"


def _worker():
    while True:
        jid = _Q.get()
        j = JOBS.get(jid)
        if not j or j["status"] != "queued":
            continue
        while mining_active():
            _progress(j, "Waiting: the computer is mining (it resumes when mining stops)")
            time.sleep(60)
        j["status"], j["started"] = "running", time.time()
        _progress(j, "Starting")
        try:
            with C.task_log(f"Background job ({j['kind']}): {j['title']}", j["origin"], j["private"]) as log:
                if j["kind"] in ("brief", "weekly", "ask", "reverse"):
                    rebalance()  # text jobs want the main model whole
                j["result"] = RUNNERS[j["kind"]](j)
                if j["kind"] in ("equipment", "transcribe"):
                    rebalance()
                if log is not None:
                    log.reply = (j["result"] or {}).get("text", "")
                    log.source = f"Desktop jobs · {j['kind']}"
            j["status"], j["progress"] = "done", "Done"
        except Exception as e:
            j["status"], j["error"], j["progress"] = "failed", f"{type(e).__name__}: {e}"[:500], "Failed"
            print(f"job {jid} failed: {e}", file=sys.stderr, flush=True)
        j["finished"] = time.time()
        _save()


def save_upload(name: str, length: int, stream) -> str:
    if length <= 0 or length > MAX_UPLOAD:
        raise ValueError("empty or too large (2 GB max)")
    base = Path(name or "upload").name
    ext = re.sub(r"[^A-Za-z0-9]", "", Path(base).suffix[1:])[:6].lower()
    stem = _slug(Path(base).stem, 70)
    folder = INBOX / f"{dt.date.today():%Y-%m}"
    C._private_dir(INBOX, folder)
    dest = folder / f"{dt.datetime.now():%Y-%m-%d_%H-%M-%S} {stem}{'.' + ext if ext else ''}"
    fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    left = length
    with os.fdopen(fd, "wb") as out:
        while left > 0:
            chunk = stream.read(min(1 << 20, left))
            if not chunk:
                break
            out.write(chunk)
            left -= len(chunk)
    if left:
        dest.unlink(missing_ok=True)
        raise ValueError("upload was cut off")
    uid = uuid.uuid4().hex[:16]
    with _LOCK:
        UPLOADS[uid] = str(dest)
    _save()
    C.task_file("downloaded", dest)
    return uid


def notice(title: str, text: str, origin="Phone (notice)") -> dict:
    """A finished 'job' that only carries a notification (Crypto Desk approvals, alerts). The phone shows it on its next check-in."""
    j = {"id": uuid.uuid4().hex[:12], "kind": "notice", "title": title[:120], "params": {}, "file": None, "status": "done", "progress": "Done",
         "created": time.time(), "started": time.time(), "finished": time.time(), "origin": origin, "private": False,
         "result": {"text": text[:1000], "notify": {"title": title[:80], "text": text[:300]}}, "error": None, "delivered": False}
    with _LOCK:
        JOBS[j["id"]] = j
    _save()
    return j


def pending_for_phone() -> list[dict]:
    with _LOCK:
        return [public(j) for j in sorted(JOBS.values(), key=lambda j: j["created"])
                if (j["status"] in ("done", "failed") and not j["delivered"] and j["origin"].startswith("Phone"))
                or j["status"] in ("queued", "running")]


def ack(ids: list[str]):
    with _LOCK:
        for i in ids:
            if i in JOBS and JOBS[i]["status"] in ("done", "failed"):
                JOBS[i]["delivered"] = True
                if JOBS[i]["private"]:  # nothing private stays here once the phone has it
                    JOBS[i]["result"] = {"text": "(private: delivered to the phone, not kept)"}
                    if JOBS[i].get("file"):
                        Path(JOBS[i]["file"]).unlink(missing_ok=True)
    _save()


# ---------------------------------------------------------------- model helpers

def generate(prompt: str, schema: dict | None = None, max_tokens=900, temperature=0.2, model: str | None = None, images=None) -> str:
    models = C.ollama_models()
    if not models:
        raise RuntimeError("Ollama isn't running")
    model = model or C.default_model(models)
    body = {"model": model, "stream": False, "think": False, "keep_alive": C.keep_alive() if model == C.default_model(models) else "2m",
            "messages": [{"role": "user", "content": prompt, **({"images": images} if images else {})}],
            "options": {"temperature": temperature, "num_predict": max_tokens, "num_ctx": C.num_ctx()}}
    if schema:
        body["format"] = schema
    r = C.http_json(f"{C.OLLAMA}/api/chat", body, timeout=600)
    return re.sub(r"<think>.*?</think>", "", r.get("message", {}).get("content", ""), flags=re.S).strip()


def rebalance():
    """After a heavy job: unload helper models and make sure the main model is fully back on the GPUs."""
    try:
        main = C.default_model(C.ollama_models())
        ps = C.http_json(f"{C.OLLAMA}/api/ps", timeout=5).get("models", [])
        for m in ps:
            if m["name"] != main and "embed" not in m["name"]:
                C.http_json(f"{C.OLLAMA}/api/generate", {"model": m["name"], "keep_alive": 0}, timeout=30)
        cur = next((m for m in ps if m["name"] == main), None)
        if cur and cur.get("size_vram", 0) < cur.get("size", 1) * 0.98:
            C.http_json(f"{C.OLLAMA}/api/generate", {"model": main, "keep_alive": 0}, timeout=30)
            time.sleep(2)
            C.http_json(f"{C.OLLAMA}/api/generate", {"model": main, "prompt": "hi", "stream": False, "think": False, "keep_alive": C.keep_alive(),
                                                    "options": {"num_ctx": C.num_ctx(), "num_predict": 1}}, timeout=180)
            C.task_step("model", f"Reloaded {main} fully onto the GPUs")
    except Exception as e:
        print(f"rebalance: {e}", file=sys.stderr, flush=True)


def generate_json(prompt: str, schema: dict, **kw) -> dict:
    out = generate(prompt, schema, **kw)
    try:
        return json.loads(out[out.index("{"): out.rindex("}") + 1])
    except ValueError:
        return {}


# ---------------------------------------------------------------- transcribe

MINUTES_SCHEMA = {"type": "object", "properties": {
    "title": {"type": "string"}, "summary": {"type": "string"},
    "decisions": {"type": "array", "items": {"type": "string"}},
    "actions": {"type": "array", "items": {"type": "object", "properties": {
        "task": {"type": "string"}, "owner": {"type": "string"}, "due": {"type": "string"}}, "required": ["task"]}},
    "equipment": {"type": "array", "items": {"type": "string"}}},
    "required": ["title", "summary", "decisions", "actions"]}


def _ts(sec: float) -> str:
    sec = int(sec)
    return f"{sec // 3600}:{sec % 3600 // 60:02d}:{sec % 60:02d}" if sec >= 3600 else f"{sec // 60}:{sec % 60:02d}"


def whisper_ready() -> bool:
    return WHISPER_PY.exists()


def whisper_hints() -> str:
    """Proper nouns from memory.md plus the equipment registry, so Whisper spells them right."""
    words = []
    for f in C.memory_facts():
        words += re.findall(r"\b[A-Z][A-Za-z0-9&.-]+(?:-\d+)?\b", re.sub(r"\s*_\(.*?\)_$", "", f[2:]))
    if EQUIP_DB.exists():
        con = db()
        for r in con.execute("SELECT tag, manufacturer FROM assets"):
            words += [r["tag"], r["manufacturer"] or ""]
        con.close()
    seen = list(dict.fromkeys(w for w in words if w and len(w) > 2 and w not in ("The", "User", "They", "Their")))
    return ("Facilities meeting. Names and equipment: " + ", ".join(seen[:60]) + ".") if seen else ""


def run_whisper(j: dict, audio: str) -> dict:
    if not whisper_ready():
        raise RuntimeError("Whisper isn't installed: run ./daddyshome --install-whisper")
    model = C.CONFIG.get("whisper_model", "large-v3-turbo")
    C.task_step("tool", f"Whisper {model}", f"audio: {C._home_rel(audio)}")
    p = subprocess.Popen([str(WHISPER_PY), str(Path(__file__).with_name("transcribe.py")), audio, model, whisper_hints()],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    final, notes = None, []
    for line in p.stdout:
        try:
            m = json.loads(line)
        except ValueError:
            continue
        if "progress" in m:
            _progress(j, f"Transcribing {m['progress'] * 100:.0f}%")
        elif m.get("note"):
            notes.append(m["note"])
        elif m.get("done"):
            final = m
        elif m.get("error"):
            raise RuntimeError(m["error"])
    err = p.stderr.read()
    p.wait()
    if not final:
        raise RuntimeError("transcription failed: " + (err.strip().splitlines() or ["no output"])[-1][:300])
    C.task_step("tool", f"Transcribed {_ts(final['duration'])} on the {final['device'].upper()}", "; ".join(notes))
    return final


def minutes(transcript: str, title_hint: str) -> dict:
    """Map-reduce over ~10k-character parts so long meetings fit the context."""
    parts = [transcript[i:i + 10000] for i in range(0, len(transcript), 9000)] or [""]
    got = []
    for n, part in enumerate(parts[:12], 1):
        got.append(generate_json(
            "You are taking meeting minutes for the person who recorded this meeting, a Director of Facility Operations. From the transcript"
            + (f" (part {n} of {len(parts)})" if len(parts) > 1 else "") + " fill in:\n"
            "- title: 3-7 words naming what the meeting covered\n"
            "- summary: 2-4 full sentences on what was discussed and why it matters\n"
            "- decisions: things that were agreed or changed (not tasks)\n"
            "- actions: every commitment to do something. task starts with a verb (\"Call Trane for a direct-drive retrofit quote\"); "
            "owner is the person's name, or \"me\" when the speaker says I/I'll/I need to; due is the deadline as said (\"Wednesday\", \"Nov 20\") or empty\n"
            "- equipment: specific units only (tags like RTU-4, AHU-2, Chiller 2, or make and model), not parts or supplies\n"
            "Use only what was said.\n\nTranscript:\n" + part,
            MINUTES_SCHEMA, max_tokens=900))
    if len(got) == 1:
        return got[0] | {"title": got[0].get("title") or title_hint or "Meeting"}
    merged = generate_json("Combine these partial meeting minutes into one: a short title, one 3-5 sentence summary, and the "
                           "decisions, action items and equipment without duplicates.\n\n" + json.dumps(got, ensure_ascii=False)[:20000],
                           MINUTES_SCHEMA, max_tokens=1400)
    return merged or {"title": title_hint, "summary": " ".join(g.get("summary", "") for g in got),
                      "decisions": [d for g in got for d in g.get("decisions", [])], "actions": [a for g in got for a in g.get("actions", [])]}


def run_transcribe(j: dict) -> dict:
    if not j.get("file"):
        raise ValueError("no audio file")
    w = run_whisper(j, j["file"])
    lines = [f"[{_ts(s['start'])}] {s['text']}" for s in w["segments"] if s["text"]]
    transcript = "\n".join(lines)
    if not transcript.strip():
        raise RuntimeError("no speech found in the recording")
    hits = len(C.CLINICAL_RE.findall(transcript))
    private = j["private"] or hits >= 3
    if private and not j["private"]:
        j["private"] = True
        C.task_step("guard", "Clinical content: results go to the phone only, nothing is kept here", f"{hits} clinical terms")
    _progress(j, "Writing the minutes")
    m = minutes(transcript, "")
    given = (j["params"].get("title") or j["title"] or "").strip()
    generic = not given or given == j["kind"].title() or re.match(r"^(recording|rec|new recording|audio|voice|meeting)\b|^\d", given, re.I)
    title = ((m.get("title") if generic else given) or given or "Meeting")[:90]
    actions = [a for a in m.get("actions", []) if (a.get("task") or "").strip()]
    tasks = []
    for a in actions[:25]:
        owner = (a.get("owner") or "").strip()
        me = not owner or owner.lower() in ("me", "i", "myself", "user", "you", "speaker", "narrator", "unassigned", "recorder",
                                            (C.CONFIG.get("user_name") or "").lower().split(" ")[0] or "-")
        t = a["task"].strip().rstrip(".")
        tasks.append({"title": (t if me else f"Follow up: {owner} to {t[0].lower() + t[1:]}")[:140],
                      "due": (a.get("due") or "").strip(), "notes": f"From the meeting “{title}”"})
    md = [f"# {title}", "", f"_{dt.datetime.fromtimestamp(j['created']):%A, %B %d %Y %I:%M %p} · {_ts(w['duration'])} · "
          f"transcribed offline ({w['model']}, {w['device'].upper()})_", "", "## Summary", "", m.get("summary", "").strip() or "_None._", ""]
    md += ["## Decisions", "", *([f"- {d}" for d in m.get("decisions", [])] or ["_None recorded._"]), ""]
    md += ["## Action items", "", *([f"- [ ] {a['task']}" + (f" ({a['owner']})" if a.get("owner") else "") + (f", due {a['due']}" if a.get("due") else "")
                                     for a in actions] or ["_None._"]), ""]
    if m.get("equipment"):
        md += ["## Equipment mentioned", "", *[f"- {e}" for e in m["equipment"]], ""]
    md += ["## Transcript", "", *lines, ""]
    doc_md = "\n".join(md)
    doc = None
    if not private:
        doc = MEETINGS_DIR / f"{dt.date.today():%Y-%m-%d} {_slug(title)}.md"
        n = 2
        while doc.exists():
            doc = MEETINGS_DIR / f"{dt.date.today():%Y-%m-%d} {_slug(title)} ({n}).md"
            n += 1
        _write_private(doc, C.redact(doc_md))
        C.task_file("created", doc)
        link_mentions(transcript + "\n" + "\n".join(m.get("equipment", [])), "meeting", f"Meeting: {title}", j["created"], doc)
    return {"text": m.get("summary", ""), "title": title, "minutes_md": doc_md if private else C.redact(doc_md),
            "transcript": transcript[:200_000], "tasks": tasks, "doc": C._home_rel(doc) if doc else None,
            "recording_id": j["params"].get("recording_id"), "duration": w["duration"], "language": w["language"],
            "notify": {"title": f"Meeting notes ready: {title}"[:80],
                       "text": f"{len(tasks)} action item{'s' if len(tasks) != 1 else ''} added to To-Do. " + (m.get("summary", "")[:160])}}


# ---------------------------------------------------------------- equipment registry

NAMEPLATE_SCHEMA = {"type": "object", "properties": {k: {"type": ["string", "null"]} for k in (
    "equipment_type", "manufacturer", "model", "serial", "voltage", "phase", "hertz", "amps", "mca", "mocp", "horsepower", "refrigerant",
    "refrigerant_charge", "capacity", "manufacture_date", "other")}}
FIELD_LABELS = {"equipment_type": "Type", "manufacturer": "Manufacturer", "model": "Model", "serial": "Serial", "voltage": "Voltage",
                "phase": "Phase", "hertz": "Hz", "amps": "Amps", "mca": "MCA", "mocp": "MOCP / max fuse", "horsepower": "HP", "refrigerant": "Refrigerant", "refrigerant_charge": "Charge",
                "capacity": "Capacity", "manufacture_date": "Mfg date", "other": "Other"}
PHONE_FIELDS = {"Manufacturer": "manufacturer", "Model": "model", "Serial": "serial", "Equipment type": "equipment_type", "Voltage": "voltage",
                "Phase": "phase", "FLA": "amps", "Horsepower": "horsepower", "Refrigerant": "refrigerant", "Charge": "refrigerant_charge",
                "Capacity": "capacity", "Mfg date": "manufacture_date"}
TYPE_TAGS = [("rooftop|rtu|packaged unit", "RTU"), ("air handl|ahu", "AHU"), ("chiller", "CH"), ("boiler", "BLR"), ("cooling tower", "CT"),
             ("water heater", "WH"), ("pump", "P"), ("motor", "M"), ("fan|exhaust", "EF"), ("compressor", "COMP"), ("condens", "CU"),
             ("furnace", "FURN"), ("heat pump", "HP"), ("generator", "GEN"), ("panel|switchboard|breaker", "PNL"), ("transformer", "XFMR"),
             ("vav", "VAV"), ("ice machine", "ICE"), ("elevator", "ELEV")]
ISSUE_RE = re.compile(r"\b(fail\w*|broke\w*|leak\w*|trip\w*|replac\w*|belt|bearing|alarm|fault|noise|not (?:working|cooling|heating)|down|repair\w*|"
                      r"overheat\w*|frozen|freez\w*|short\w*|seiz\w*|vibrat\w*)\b", re.I)


def db() -> sqlite3.Connection:
    C._private_dir(EQUIP_DIR)
    new = not EQUIP_DB.exists()
    con = sqlite3.connect(EQUIP_DB, timeout=10)
    con.row_factory = sqlite3.Row
    con.executescript("""
        CREATE TABLE IF NOT EXISTS assets (id INTEGER PRIMARY KEY, tag TEXT UNIQUE NOT NULL, type TEXT, manufacturer TEXT, model TEXT,
            serial TEXT, location TEXT, fields TEXT NOT NULL DEFAULT '{}', created REAL, updated REAL);
        CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, asset_id INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
            at REAL NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, source TEXT, UNIQUE(asset_id, kind, text));
        CREATE INDEX IF NOT EXISTS events_asset ON events(asset_id, at);""")
    if new:
        os.chmod(EQUIP_DB, 0o600)
    return con


def _norm_serial(s: str | None) -> str:
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


def _next_tag(con, etype: str) -> str:
    prefix = next((t for rx, t in TYPE_TAGS if re.search(rx, etype or "", re.I)), "EQ")
    n = 1
    while con.execute("SELECT 1 FROM assets WHERE tag = ?", (f"{prefix}-{n}",)).fetchone():
        n += 1
    return f"{prefix}-{n}"


def upsert_asset(fields: dict, tag: str = "", location: str = "") -> tuple[sqlite3.Row, bool]:
    con = db()
    with con:
        row = None
        serial = _norm_serial(fields.get("serial"))
        if tag:
            row = con.execute("SELECT * FROM assets WHERE upper(tag) = upper(?)", (tag,)).fetchone()
        if not row and len(serial) >= 4:
            row = next((r for r in con.execute("SELECT * FROM assets") if _norm_serial(r["serial"]) == serial), None)
        now = time.time()
        if row:
            old = json.loads(row["fields"] or "{}")
            merged = {**old, **{k: v for k, v in fields.items() if v}}
            con.execute("UPDATE assets SET type=?, manufacturer=?, model=?, serial=?, location=?, fields=?, updated=? WHERE id=?",
                        (merged.get("equipment_type") or row["type"], merged.get("manufacturer") or row["manufacturer"], merged.get("model") or row["model"],
                         merged.get("serial") or row["serial"], location or row["location"], json.dumps(merged), now, row["id"]))
            created = False
            aid = row["id"]
        else:
            tag = tag or _next_tag(con, fields.get("equipment_type") or "")
            cur = con.execute("INSERT INTO assets (tag, type, manufacturer, model, serial, location, fields, created, updated) VALUES (?,?,?,?,?,?,?,?,?)",
                              (tag, fields.get("equipment_type"), fields.get("manufacturer"), fields.get("model"), fields.get("serial"), location,
                               json.dumps({k: v for k, v in fields.items() if v}), now, now))
            created = True
            aid = cur.lastrowid
        out = con.execute("SELECT * FROM assets WHERE id = ?", (aid,)).fetchone()
    con.close()
    return out, created


def add_event(asset_id: int, kind: str, text: str, source: str = "", at: float | None = None) -> bool:
    con = db()
    with con:
        cur = con.execute("INSERT OR IGNORE INTO events (asset_id, at, kind, text, source) VALUES (?,?,?,?,?)",
                          (asset_id, at or time.time(), kind, C.redact(text)[:600], source))
    con.close()
    return cur.rowcount > 0


def _aliases(r) -> list[str]:
    out = [r["tag"]]
    if r["serial"] and len(_norm_serial(r["serial"])) >= 5:
        out.append(r["serial"])
    return out


def link_mentions(text: str, kind: str, source: str, at: float | None = None, doc: Path | None = None) -> int:
    """Adds a history event to every known asset named in the text (by tag or serial)."""
    if not text or not EQUIP_DB.exists():
        return 0
    con = db()
    rows = list(con.execute("SELECT * FROM assets"))
    con.close()
    n = 0
    for r in rows:
        for alias in _aliases(r):
            rx = re.compile(r"(?<![A-Za-z0-9])" + re.escape(alias).replace(r"\-", r"[\s-]?") + r"(?![A-Za-z0-9])", re.I)
            m = rx.search(text)
            if m:
                lo, hi = max(0, text.rfind("\n", 0, m.start()) + 1), text.find("\n", m.end())
                line = text[lo: hi if hi > 0 else len(text)].strip()[:300]
                text_ = source if source and (line in source or source.endswith(line[:60])) else (f"{source}: {line}" if source else line)
                if add_event(r["id"], kind, text_, C._home_rel(doc) if doc else "", at):
                    n += 1
                    write_card(r["id"])
                break
    return n


def asset_flags(con, asset_id: int, days=120) -> list[str]:
    since = time.time() - days * 86400
    issues = [e for e in con.execute("SELECT * FROM events WHERE asset_id=? AND at>=? AND kind != 'photo'", (asset_id, since)) if ISSUE_RE.search(e["text"])]
    if len(issues) >= 3:
        return [f"{len(issues)} problems in {days} days (last: {issues[-1]['text'][:90]})"]
    return []


def write_card(asset_id: int) -> Path:
    con = db()
    a = con.execute("SELECT * FROM assets WHERE id=?", (asset_id,)).fetchone()
    ev = list(con.execute("SELECT * FROM events WHERE asset_id=? ORDER BY at DESC LIMIT 200", (asset_id,)))
    flags = asset_flags(con, asset_id)
    con.close()
    f = json.loads(a["fields"] or "{}")
    md = [f"# {a['tag']} · {' '.join(x for x in (a['manufacturer'], a['model']) if x) or a['type'] or 'Equipment'}", ""]
    if flags:
        md += [f"> ⚠ Repeat issues: {'; '.join(flags)}", ""]
    md += ["| | |", "|---|---|", f"| Tag | {a['tag']} |", f"| Location | {a['location'] or '—'} |"]
    md += [f"| {FIELD_LABELS.get(k, k)} | {v} |" for k, v in f.items() if v and k in FIELD_LABELS]
    md += ["", "## History", ""]
    md += [f"- {dt.datetime.fromtimestamp(e['at']):%Y-%m-%d} · {e['kind']}: {e['text']}" for e in ev] or ["_Nothing yet._"]
    path = EQUIP_DIR / "Cards" / f"{_slug(a['tag'], 40)}.md"
    _write_private(path, "\n".join(md) + "\n")
    return path


def read_nameplate(photo: str) -> dict:
    model = C.CONFIG.get("vision_model", "qwen2.5vl:7b")
    if not any(m.split(":")[0] == model.split(":")[0] for m in C.ollama_models(include_embed=True)):
        C.task_step("tool", f"Vision model {model} isn't installed; using the phone's reading only")
        return {}
    img = base64.b64encode(Path(photo).read_bytes()).decode()
    C.task_step("model", f"Asked {model} to read the nameplate", C._home_rel(photo))
    plate = generate("Transcribe every line of text on this equipment nameplate exactly as printed, top to bottom, keeping each label "
                     "with its value (for example 'MODEL NO. YCD150C3HCBA'). Output only the text.", model=model, images=[img],
                     max_tokens=600, temperature=0.0)
    C.task_step("tool", "Nameplate text", plate)
    if len(plate.strip()) < 8:
        return {}
    out = generate_json(
        "Map this equipment nameplate text to fields. Copy values exactly; use null for anything not present.\n"
        "manufacturer: the brand. model: the model number. serial: the serial number. equipment_type: what the unit is (rooftop unit, "
        "chiller, boiler, pump motor…). voltage: volts only (460, 208/230). phase: 1 or 3. hertz: 50 or 60. amps: FLA/RLA/rated amps. "
        "mca: minimum circuit amps. mocp: max fuse / max breaker / MOCP. horsepower: only a value marked HP. refrigerant: the type, like "
        "R-410A. refrigerant_charge: its amount with units. capacity: tons, BTU/h, MBH, kW, GPM or CFM with units. manufacture_date: as "
        "printed. other: anything else useful.\n\nNameplate text:\n" + plate[:3000], NAMEPLATE_SCHEMA, max_tokens=500, temperature=0.0)
    f = {k: (str(v).strip() if v not in (None, "null", "") else None) for k, v in out.items() if k in NAMEPLATE_SCHEMA["properties"]}
    every = " ".join(v for v in f.values() if v)
    if not f.get("refrigerant") and (m := re.search(r"\bR-?(22|410A|32|454B|134A|407C|404A|513A|1234YF|123|290|744)\b", every, re.I)):
        f["refrigerant"] = "R-" + m.group(1).upper()
    if f.get("horsepower") and f["horsepower"].strip() in ("50", "60") and not f.get("hertz"):
        f["hertz"], f["horsepower"] = f["horsepower"], None  # Hz misread as HP
    if f.get("capacity") and not re.search(r"[A-Za-z]", f["capacity"]):
        f["capacity"] = None  # a bare number is almost always amps, not capacity
    return f


def run_equipment(j: dict) -> dict:
    p = j["params"]
    phone_fields = {PHONE_FIELDS[k]: v for k, v in (p.get("fields") or {}).items() if k in PHONE_FIELDS and v}
    fields = dict(phone_fields)
    if j.get("file"):
        _progress(j, "Reading the nameplate")
        vision = read_nameplate(j["file"])
        fields.update({k: v for k, v in vision.items() if v})  # the desktop's reading wins; phone OCR fills gaps
    if not any(fields.get(k) for k in ("manufacturer", "model", "serial")):
        raise RuntimeError("couldn't read a manufacturer, model or serial number from that photo")
    row, created = upsert_asset(fields, (p.get("tag") or "").strip(), (p.get("location") or "").strip())
    if j.get("file"):
        dest = EQUIP_DIR / "Photos" / f"{_slug(row['tag'], 30)} {dt.datetime.now():%Y-%m-%d %H-%M-%S}{Path(j['file']).suffix.lower() or '.jpg'}"
        C._private_dir(dest.parent)
        shutil.copy2(j["file"], dest)
        os.chmod(dest, 0o600)
        C.task_file("created", dest)
        add_event(row["id"], "photo", "Nameplate photo" + (f" ({p['wo']})" if p.get("wo") else ""), C._home_rel(dest))
    if p.get("wo") or p.get("note"):
        add_event(row["id"], "work_order", " · ".join(x for x in (p.get("wo"), p.get("note")) if x))
    card = write_card(row["id"])
    C.task_file("created" if created else "edited", card)
    con = db()
    n_events = con.execute("SELECT count(*) FROM events WHERE asset_id=?", (row["id"],)).fetchone()[0]
    flags = asset_flags(con, row["id"])
    con.close()
    label = " ".join(x for x in (row["manufacturer"], row["model"]) if x) or row["type"] or "Equipment"
    text = (f"{'Added' if created else 'Updated'} {row['tag']}: {label}" + (f", serial {row['serial']}" if row["serial"] else "") +
            f". {n_events} item{'s' if n_events != 1 else ''} in its history." + (f" ⚠ {flags[0]}" if flags else ""))
    return {"text": text, "asset": {"tag": row["tag"], "label": label, "serial": row["serial"], "fields": json.loads(row["fields"]), "new": created},
            "task_id": p.get("task_id"), "notify": {"title": f"Equipment {'added' if created else 'updated'}: {row['tag']}", "text": text}}


def equipment_lookup(query: str) -> str:
    if not EQUIP_DB.exists():
        return "No equipment on file yet. Snap a nameplate on the phone (“nameplate”) to start the registry."
    q = (query or "").strip()
    con = db()
    rows = list(con.execute("SELECT * FROM assets ORDER BY tag"))
    if q and q.lower() not in ("all", "list", "everything", "equipment"):
        words = [w for w in re.findall(r"[a-z0-9.-]+", q.lower()) if w not in ("the", "on", "for", "history", "of", "what", "which", "units", "unit",
                                                                                 "equipment", "still", "use", "uses", "using", "show", "me", "is", "are", "about")]
        def hay(r):
            return " ".join(str(r[k] or "") for k in ("tag", "type", "manufacturer", "model", "serial", "location")).lower() + " " + r["fields"].lower()
        rows = [r for r in rows if words and all(w.replace("-", "") in hay(r).replace("-", "") for w in words)]
    if not rows:
        con.close()
        return f"No equipment matches “{q}”."
    if len(rows) > 1:
        out = f"{len(rows)} unit(s):\n" + "\n".join(
            f"• {r['tag']} · {' '.join(x for x in (r['manufacturer'], r['model']) if x)}" + (f" · {r['location']}" if r["location"] else "") +
            (f" · {json.loads(r['fields']).get('refrigerant')}" if json.loads(r["fields"]).get("refrigerant") else "") +
            ("" if not asset_flags(con, r["id"]) else " ⚠ repeat issues") for r in rows[:30])
        con.close()
        return out
    r = rows[0]
    ev = list(con.execute("SELECT * FROM events WHERE asset_id=? ORDER BY at DESC LIMIT 10", (r["id"],)))
    flags = asset_flags(con, r["id"])
    con.close()
    f = json.loads(r["fields"])
    return (f"{r['tag']} · {' '.join(x for x in (r['manufacturer'], r['model']) if x)}" + (f" at {r['location']}" if r["location"] else "") + "\n" +
            ", ".join(f"{FIELD_LABELS[k]} {v}" for k, v in f.items() if v and k in FIELD_LABELS and k not in ("manufacturer", "model")) +
            (f"\n⚠ {flags[0]}" if flags else "") + "\nHistory:\n" +
            ("\n".join(f"• {dt.datetime.fromtimestamp(e['at']):%b %d %Y} {e['kind'].replace('_', ' ')}: {e['text'][:160]}" for e in ev) or "• nothing yet"))


def sync_work_orders(ctx: dict):
    """Links the phone's work-order and task titles to assets they name (serial in the notes or tag in the title)."""
    if not EQUIP_DB.exists():
        return
    for t in ctx.get("tasks_open", []) + ctx.get("tasks_done_7d", []):
        text = f"{t.get('title', '')}\n{t.get('notes', '')}"
        kind = "work_order" if t.get("workorder") else "task"
        at = (t.get("created") or 0) / 1000 or None
        link_mentions(text, kind, ("Done: " if t.get("completed") else "") + t.get("title", "")[:120], at)


# ---------------------------------------------------------------- overnight analyst

def save_context(ctx: dict):
    ctx = dict(ctx, received=time.time())
    _write_private(CONTEXT_FILE, json.dumps(ctx, ensure_ascii=False))
    try:
        sync_work_orders(ctx)
    except Exception as e:
        print(f"work-order sync skipped: {e}", file=sys.stderr, flush=True)


def _context() -> tuple[dict, str]:
    ctx = C.load_json(CONTEXT_FILE, {})
    if not ctx:
        return {}, "The phone hasn't sent its schedule yet (it does when the desktop link is on)."
    age = (time.time() - ctx.get("received", 0)) / 3600
    return ctx, (f"The phone's data is {age:.0f} hours old." if age > 12 else "")


def _recent_docs(folder: Path, days: int) -> list[Path]:
    if not folder.is_dir():
        return []
    cutoff = time.time() - days * 86400
    return sorted((p for p in folder.glob("*.md") if p.stat().st_mtime >= cutoff), key=lambda p: p.stat().st_mtime)


def _equipment_watch() -> list[str]:
    if not EQUIP_DB.exists():
        return []
    con = db()
    out = [f"{r['tag']} ({' '.join(x for x in (r['manufacturer'], r['model']) if x)}): {f}" for r in con.execute("SELECT * FROM assets")
           for f in asset_flags(con, r["id"])]
    con.close()
    return out


def _practice_questions(ctx: dict) -> str:
    soon = [a for a in ctx.get("assignments", []) + ctx.get("exams", []) if re.search(r"exam|quiz|test|midterm|final|cert", f"{a.get('kind', '')} {a.get('title', '')}", re.I)]
    if not soon:
        return ""
    target = soon[0]
    topic = f"{target.get('course', '')} {target.get('title', '')}".strip()
    try:
        hits = [h for h in C.doc_search(topic, 4) if h[0] >= 0.45]
    except Exception:
        hits = []
    if not hits:
        return ""
    material = "\n\n".join(c for _, _, c in hits)[:6000]
    qs = generate(f"Write 5 short practice questions with one-line answers for “{topic}”, using only this study material. "
                  f"Format: 'Q: …' then 'A: …'.\n\n{material}", max_tokens=600, temperature=0.3)
    return f"## Practice for {topic}\n\n{qs}\n"


def clean_md(text: str) -> str:
    """Drops a ```markdown fence and the model's own top-level title."""
    t = re.sub(r"^\s*```[a-z]*\s*\n|\n```\s*$", "", text.strip())
    return re.sub(r"^\s*#\s[^\n]*\n+", "", t.strip()).strip()


def run_brief(j: dict) -> dict:
    ctx, stale = _context()
    now = dt.datetime.now()
    meetings = _recent_docs(MEETINGS_DIR, 1)
    data = {
        "today": now.strftime("%A, %B %d %Y"), "stale_note": stale,
        "weather": ctx.get("weather"), "events": ctx.get("events", []), "tasks_due_or_overdue": [t for t in ctx.get("tasks_open", []) if t.get("due") and t["due"] <= now.date().isoformat()],
        "tasks_soon": [t for t in ctx.get("tasks_open", []) if t.get("due") and now.date().isoformat() < t["due"] <= (now.date() + dt.timedelta(days=3)).isoformat()],
        "open_work_orders": [t for t in ctx.get("tasks_open", []) if t.get("workorder")],
        "assignments_and_exams": ctx.get("assignments", []) + ctx.get("exams", []), "flashcards_due": ctx.get("flashcards_due", 0),
        "equipment_watch": _equipment_watch(),
        "yesterdays_meetings": [p.read_text(errors="ignore").split("## Transcript")[0][:1500] for p in meetings],
        "memory": [re.sub(r"\s*_\(.*?\)_$", "", f) for f in C.memory_facts()][:30],
    }
    _progress(j, "Writing the brief")
    body = generate("You are the overnight operations analyst for a Director of Facility Operations who is also a student. Write their morning "
                    "brief in Markdown from the JSON below. Rank 'Top 3 today' by risk: weather alerts and life-safety first, then overdue "
                    "high-priority work orders and equipment with repeat failures, then today's deadlines, then meetings. "
                    "Sections, skipping any that are empty: '## Top 3 today' (ranked, one line each with why), "
                    "'## Weather prep' (alerts with the checklist), '## Schedule', '## Due and overdue', '## Work orders', '## Equipment watch', "
                    "'## From yesterday's meetings', '## Study'. Be specific and short; never invent items that aren't in the data.\n\n"
                    + json.dumps(data, ensure_ascii=False, default=str)[:22000], max_tokens=1200, temperature=0.3)
    body += "\n\n" + _practice_questions(ctx)
    body = clean_md(body)
    md = f"# Morning brief · {now:%A, %B %d}\n\n" + (f"_{stale}_\n\n" if stale else "") + body.strip() + "\n"
    path = BRIEFS_DIR / f"{now:%Y-%m-%d} Morning brief.md"
    _write_private(path, C.redact(md))
    C.task_file("created", path)
    top = re.search(r"## Top 3 today\s*\n(.+?)(?:\n## |\Z)", body, re.S)
    preview = re.sub(r"[#*_>]", "", (top.group(1) if top else body)).strip()[:300]
    return {"text": md, "title": f"Morning brief · {now:%a %b %d}", "doc": C._home_rel(path),
            "notify": {"title": f"Morning brief · {now:%a %b %d}", "text": preview}}


def run_weekly(j: dict) -> dict:
    ctx, stale = _context()
    now = dt.datetime.now()
    con = db() if EQUIP_DB.exists() else None
    eq = []
    if con:
        since = time.time() - 7 * 86400
        eq = [f"{r['tag']}: {r['kind']} {r['text'][:120]}" for r in con.execute(
            "SELECT a.tag, e.kind, e.text FROM events e JOIN assets a ON a.id = e.asset_id WHERE e.at >= ? ORDER BY e.at", (since,))]
        con.close()
    data = {"week_ending": now.strftime("%B %d %Y"), "completed_tasks": ctx.get("tasks_done_7d", []), "open_work_orders": [t for t in ctx.get("tasks_open", []) if t.get("workorder")],
            "overdue": [t for t in ctx.get("tasks_open", []) if t.get("due") and t["due"] < now.date().isoformat()],
            "meetings": [p.read_text(errors="ignore").split("## Transcript")[0][:1200] for p in _recent_docs(MEETINGS_DIR, 7)],
            "equipment_activity": eq[:80], "equipment_watch": _equipment_watch()}
    body = generate("Write a one-page weekly operations summary a Director of Facility Operations can forward to leadership, in Markdown, from the JSON "
                    "below. Sections: '## Highlights', '## Completed', '## Work orders', '## Equipment and recurring issues', '## Risks and next week'. "
                    "Factual and concise; only use the data given.\n\n" + json.dumps(data, ensure_ascii=False, default=str)[:22000],
                    max_tokens=1400, temperature=0.3)
    body = clean_md(body)
    md = f"# Weekly operations summary · week ending {now:%B %d %Y}\n\n" + (f"_{stale}_\n\n" if stale else "") + body.strip() + "\n"
    path = BRIEFS_DIR / f"{now:%Y-%m-%d} Weekly summary.md"
    _write_private(path, C.redact(md))
    C.task_file("created", path)
    return {"text": md, "title": f"Weekly summary · {now:%b %d}", "doc": C._home_rel(path),
            "notify": {"title": "Weekly operations summary is ready", "text": re.sub(r"[#*_>]", "", body).strip()[:300]}}


def run_ask(j: dict) -> dict:
    text = j["params"].get("text") or j["title"]
    r = C.assistant({"text": text, "target": "desktop", "phone": None, "origin": j["origin"], "private": j["private"], "rounds": 8})
    return {"text": r.get("text", ""), "source": r.get("source", ""), "notify": {"title": "Your computer finished: " + text[:60], "text": r.get("text", "")[:300]}}


def run_reverse(j: dict) -> dict:
    import reverse
    return reverse.run_job(j)


RUNNERS = {"transcribe": run_transcribe, "equipment": run_equipment, "brief": run_brief, "weekly": run_weekly, "ask": run_ask, "reverse": run_reverse}


def _scheduler():
    """Morning brief daily (config "brief_time", default 05:30) and the weekly summary on Fridays ("weekly_time", 15:30)."""
    state_path = C.CACHE_DIR / "schedule-state.json"
    while True:
        try:
            now = dt.datetime.now()
            st = C.load_json(state_path, {})
            for kind, key, default, day_ok in (("brief", "brief_time", default_brief_time(), True), ("weekly", "weekly_time", "15:30", now.weekday() == 4)):
                at = C.CONFIG.get(key, default)
                if not at or not day_ok:
                    continue
                hh, mm = (int(x) for x in str(at).split(":"))
                due = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                if due <= now < due + dt.timedelta(hours=3) and st.get(kind) != now.date().isoformat():
                    submit(kind, "Morning brief" if kind == "brief" else "Weekly summary", origin="Phone (scheduled)")
                    st[kind] = now.date().isoformat()
                    C.CACHE_DIR.mkdir(parents=True, exist_ok=True)
                    state_path.write_text(json.dumps(st))
        except Exception as e:
            print(f"scheduler: {e}", file=sys.stderr, flush=True)
        time.sleep(60)
