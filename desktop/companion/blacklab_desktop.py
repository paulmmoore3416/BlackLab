#!/usr/bin/env python3
"""BlackLab Desktop companion.

A small local server (Python standard library only) that
  * serves the BlackLab Desktop web UI at http://127.0.0.1:8770,
  * links to the phone over USB (`adb forward`) and relays to the phone's link API,
  * runs an offline desktop assistant: deterministic rules for common desktop tasks plus a local
    Ollama model with tool calling for everything else (no cloud needed),
  * speaks with ElevenLabs and searches Google developer docs when keys are configured.

Listens on 127.0.0.1 only. Secrets live in ~/.config/blacklab-desktop/secrets.json (chmod 600),
never in the repository.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1.0.0"
HOST, PORT = "127.0.0.1", int(os.environ.get("BLACKLAB_DESKTOP_PORT", "8770"))
HOME = Path.home()
WEB_DIR = Path(__file__).resolve().parent.parent / "web"
CONFIG_DIR = HOME / ".config" / "blacklab-desktop"
CACHE_DIR = HOME / ".cache" / "blacklab-desktop"
OUT_DIR = HOME / "BlackLab-Desktop"
OLLAMA = os.environ.get("OLLAMA_HOST", "127.0.0.1:11434").rstrip("/")
if not OLLAMA.startswith("http"):  # Ollama's own convention allows "host:port"
    OLLAMA = "http://" + OLLAMA.replace("0.0.0.0", "127.0.0.1")
ALLOWED_ORIGINS = {
    f"http://{HOST}:{PORT}", f"http://localhost:{PORT}", "https://paulmmoore3416.github.io",
}
PREFERRED_MODELS = ["qwen3:8b", "granite4:tiny-h", "paul-assistant:latest", "qwen2.5:14b"]


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default


SECRETS = load_json(CONFIG_DIR / "secrets.json", {})
CONFIG = load_json(CONFIG_DIR / "config.json", {})


# ---------------------------------------------------------------- helpers

def http_json(url: str, body=None, headers=None, timeout=30, method=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


def adb_path() -> str | None:
    for p in [shutil.which("adb"), str(HOME / "Android/Sdk/platform-tools/adb")]:
        if p and os.path.exists(p):
            return p
    return None


def adb_devices():
    adb = adb_path()
    if not adb:
        return {"available": False, "devices": []}
    try:
        out = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True, timeout=8).stdout
    except Exception:
        return {"available": True, "devices": []}
    devs = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), "")
            devs.append({"serial": parts[0], "state": parts[1], "model": model.replace("_", " "),
                         "emulator": parts[0].startswith("emulator-")})
    devs.sort(key=lambda d: (d["emulator"], d["state"] != "device"))
    return {"available": True, "devices": devs}


def adb_forward(port: int = 8765):
    adb = adb_path()
    if not adb:
        return False, "adb not found — install Android platform-tools"
    devs = [d for d in adb_devices()["devices"] if d["state"] == "device" and not d["emulator"]]
    if not devs:
        unauth = [d for d in adb_devices()["devices"] if d["state"] == "unauthorized"]
        return False, ("Unlock the phone and tap Allow on the USB debugging prompt" if unauth
                       else "No phone on USB — plug it in with USB debugging on")
    serial = devs[0]["serial"]
    r = subprocess.run([adb, "-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"], capture_output=True, text=True, timeout=8)
    if r.returncode != 0:
        return False, r.stderr.strip() or "adb forward failed"
    return True, f"USB link ready: 127.0.0.1:{port} → {devs[0]['model'] or serial}"


def ollama_models():
    try:
        tags = http_json(f"{OLLAMA}/api/tags", timeout=3)
        return [m["name"] for m in tags.get("models", []) if "embed" not in m["name"]]
    except Exception:
        return []


def default_model(models):
    want = CONFIG.get("model")
    if want in models:
        return want
    return next((m for m in PREFERRED_MODELS if m in models), models[0] if models else "")


def xdg_open(target: str):
    subprocess.Popen(["xdg-open", target], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


def safe_home_path(p: str) -> Path | None:
    path = Path(os.path.expanduser(p)).resolve()
    return path if str(path).startswith(str(HOME)) else None


# ---------------------------------------------------------------- desktop tools

def tool_system_status(_=None):
    du = shutil.disk_usage(HOME)
    mem = {}
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            k, v = line.split(":", 1)
            mem[k] = int(v.split()[0]) * 1024
    except Exception:
        pass
    load = os.getloadavg()
    up = float(Path("/proc/uptime").read_text().split()[0]) if Path("/proc/uptime").exists() else 0
    bat = ""
    for b in Path("/sys/class/power_supply").glob("BAT*"):
        try:
            bat = f", battery {(b / 'capacity').read_text().strip()}% ({(b / 'status').read_text().strip().lower()})"
        except Exception:
            pass
    phone = adb_devices()["devices"]
    phone_s = ", ".join(f"{d['model'] or d['serial']} ({d['state']})" for d in phone if not d["emulator"]) or "no phone on USB"
    gb = 1024 ** 3
    return (f"Disk: {du.free / gb:.0f} GB free of {du.total / gb:.0f} GB. "
            f"Memory: {mem.get('MemAvailable', 0) / gb:.1f} GB free of {mem.get('MemTotal', 0) / gb:.0f} GB. "
            f"Load {load[0]:.2f}, up {up / 3600:.1f} h{bat}. Phone: {phone_s}. "
            f"Ollama: {len(ollama_models())} model(s).")


def tool_disk_hogs(_=None):
    try:
        out = subprocess.run(["du", "-xsh", "--max-depth=1", str(HOME)], capture_output=True, text=True, timeout=40).stdout
    except subprocess.TimeoutExpired:
        return "That scan took too long — your home folder is big."
    rows = []
    for line in out.splitlines():
        size, path = line.split("\t", 1)
        if path != str(HOME):
            rows.append((size, Path(path).name))
    units = {"K": 1, "M": 1e3, "G": 1e6, "T": 1e9}
    rows.sort(key=lambda r: float(r[0][:-1] or 0) * units.get(r[0][-1], 0.001) if r[0][-1] in units else 0, reverse=True)
    return "Biggest folders in your home:\n" + "\n".join(f"• {name}: {size}" for size, name in rows[:10])


SKIP_DIRS = {".cache", "node_modules", ".git", ".gradle", ".local", "Android", ".npm", ".cargo", ".rustup", "snap", ".var", ".ollama", ".mozilla"}


def tool_find_files(args):
    q = (args.get("query") if isinstance(args, dict) else str(args)).lower().strip()
    if not q:
        return "What should I look for?"
    hits, scanned, start = [], 0, time.time()
    for root, dirs, files in os.walk(HOME):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        for f in files:
            scanned += 1
            if q in f.lower():
                p = Path(root) / f
                try:
                    hits.append((p.stat().st_mtime, p))
                except OSError:
                    pass
        if len(hits) > 200 or time.time() - start > 12:
            break
    hits.sort(reverse=True)
    if not hits:
        return f"No files named like “{q}” (checked {scanned:,} files)."
    return f"{len(hits)} match(es) for “{q}”, newest first:\n" + "\n".join(
        f"• {p.relative_to(HOME)} — {dt.datetime.fromtimestamp(m):%b %d %Y}" for m, p in hits[:15])


FOLDERS = {"downloads": "Downloads", "documents": "Documents", "desktop": "Desktop", "pictures": "Pictures", "music": "Music",
           "videos": "Videos", "home": "", "blacklab": "Desktop/BlackLab", "blacklab-desktop": "BlackLab-Desktop", "notes": "BlackLab-Desktop/Notes"}


def tool_open_path(args):
    p = args.get("path") if isinstance(args, dict) else str(args)
    key = p.lower().strip().strip("~/").replace(" folder", "")
    target = safe_home_path("~/" + FOLDERS[key]) if key in FOLDERS else safe_home_path(p)
    if not target or not target.exists():
        return f"Couldn't find {p} in your home folder."
    xdg_open(str(target))
    return f"Opened {target.relative_to(HOME) if target != HOME else '~'}."


def tool_open_url(args):
    url = args.get("url") if isinstance(args, dict) else str(args)
    if not re.match(r"^https?://", url or ""):
        url = "https://" + url
    xdg_open(url)
    return f"Opened {url}."


def tool_launch_app(args):
    name = (args.get("name") if isinstance(args, dict) else str(args)).lower().strip()
    dirs = [HOME / ".local/share/applications", Path("/usr/share/applications"), Path("/var/lib/flatpak/exports/share/applications"),
            Path("/var/lib/snapd/desktop/applications")]
    best = None
    for d in dirs:
        for f in d.glob("*.desktop"):
            try:
                text = f.read_text(errors="ignore")
            except OSError:
                continue
            m = re.search(r"^Name=(.+)$", text, re.M)
            label = (m.group(1) if m else f.stem).lower()
            if "NoDisplay=true" in text:
                continue
            if label == name or f.stem.lower() == name:
                best = f
                break
            if name in label and best is None:
                best = f
        if best is not None and (best.stem.lower() == name):
            break
    if not best:
        return f"No app called “{name}” found."
    subprocess.Popen(["gtk-launch", best.stem], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return f"Launching {best.stem}."


def tool_desktop_note(args):
    text = args.get("text") if isinstance(args, dict) else str(args)
    title = (args.get("title") if isinstance(args, dict) else "") or " ".join(text.split()[:6])
    folder = OUT_DIR / "Notes"
    folder.mkdir(parents=True, exist_ok=True)
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:50] or "note"
    path = folder / f"{dt.date.today():%Y-%m-%d}-{slug}.md"
    path.write_text(f"# {title}\n\n{text}\n\n_{dt.datetime.now():%Y-%m-%d %H:%M} · BlackLab Desktop_\n")
    return f"Saved note to ~/{path.relative_to(HOME)}."


def tool_read_file(args):
    p = safe_home_path(args.get("path") if isinstance(args, dict) else str(args))
    if not p or not p.is_file():
        return "File not found in your home folder."
    if p.stat().st_size > 400_000:
        return "That file is too big to read here."
    return p.read_text(errors="ignore")[:6000]


def tool_dev_docs(args):
    q = args.get("query") if isinstance(args, dict) else str(args)
    key = SECRETS.get("developer_knowledge")
    if not key:
        return "Add developer_knowledge to ~/.config/blacklab-desktop/secrets.json to search Google developer docs."
    try:
        r = http_json("https://developerknowledge.googleapis.com/v1/documents:searchDocumentChunks?" +
                      urllib.parse.urlencode({"query": q, "key": key}), timeout=15)
    except Exception as e:
        return f"Docs search failed (offline?): {e}"
    res = r.get("results", [])[:3]
    if not res:
        return f"No developer docs matched “{q}”."
    out = []
    for x in res:
        snippet = re.sub(r"\s+", " ", x.get("content", ""))[:400]
        out.append(f"• https://{x['parent'].removeprefix('documents/')}\n  {snippet}…")
    return "\n\n".join(out)


def phone_call(phone, path, body=None, timeout=60):
    if not phone or not phone.get("url"):
        raise RuntimeError("The phone isn't linked — pair it under Connect.")
    return http_json(phone["url"].rstrip("/") + path, body, {"Authorization": "Bearer " + phone.get("token", "")}, timeout=timeout,
                     method="POST" if body is not None else "GET")


def tool_phone(args, phone):
    text = args.get("command") if isinstance(args, dict) else str(args)
    r = phone_call(phone, "/api/assistant", {"text": text})
    return r.get("text", "")


def pull(kind: str, phone) -> str:
    roots = {"backups": ["Backups"], "documents": ["Personal/Documents", "Professional/Documents"],
             "photos": ["Personal/Photos", "Professional/Photos"], "scans": ["Personal/Scans", "Professional/Scans"],
             "recordings": ["Personal/Recordings", "Professional/Recordings"], "dictation": ["Personal/Dictation", "Professional/Dictation"]}
    if kind not in roots:
        return f"I can pull: {', '.join(roots)}."
    got, skipped, total = 0, 0, 0

    def walk(rel):
        nonlocal got, skipped, total
        try:
            listing = phone_call(phone, "/api/files?" + urllib.parse.urlencode({"path": rel}))
        except Exception:
            return
        for f in listing.get("files", []):
            if f["dir"]:
                walk(f["path"])
                continue
            dest = OUT_DIR / f["path"]
            if dest.exists() and dest.stat().st_size == f["size"]:
                skipped += 1
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            url = phone["url"].rstrip("/") + "/api/file?" + urllib.parse.urlencode({"path": f["path"]})
            req = urllib.request.Request(url, headers={"Authorization": "Bearer " + phone.get("token", "")})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as out:
                shutil.copyfileobj(r, out)
            got += 1
            total += f["size"]

    for rel in roots[kind]:
        walk(rel)
    return f"Pulled {got} new {kind} file(s) ({total / 1e6:.1f} MB) into ~/BlackLab-Desktop; {skipped} already up to date."


# ---------------------------------------------------------------- assistant

PHONE_WORDS = re.compile(r"\b(task|tasks|to-?do|remind|reminder|calendar|schedule|meeting|event|agenda|brief|due|overdue|journal|note|notes|dictat\w*|record\w*|"
                         r"email|gmail|inbox|drive|keep|ceu|gpa|course|class|exam|focus|pomodoro|my files|work order|nameplate|photo|camera|"
                         r"personal|professional)\b", re.I)


def desktop_rules(text: str, phone):
    t = text.strip()
    low = t.lower()
    if re.search(r"\b(system|computer|pc|desktop) (status|health|info)\b|^status$|how('s| is) (my )?(computer|pc|system)", low):
        return tool_system_status(), "Desktop · rules"
    if re.search(r"(disk|storage) (space|usage|hogs?)|using (my )?(disk|storage)|what('s| is) taking (up )?space", low):
        return tool_disk_hogs(), "Desktop · rules"
    m = re.match(r"^(?:find|search for|look for|locate) (?:files? )?(?:named |called |like )?(.+?)(?: on (?:my )?(?:computer|desktop|pc))?$", low)
    if m and not PHONE_WORDS.search(low):
        return tool_find_files({"query": m.group(1)}), "Desktop · rules"
    m = re.match(r"^open (?:my |the )?(downloads|documents|desktop|pictures|music|videos|home|blacklab-desktop|notes)(?: folder)?$", low)
    if m:
        return tool_open_path({"path": m.group(1)}), "Desktop · rules"
    m = re.match(r"^(?:open|go to|browse to) (https?://\S+|[\w-]+\.(?:com|org|net|io|dev|edu|gov)\S*)$", low)
    if m:
        return tool_open_url({"url": m.group(1)}), "Desktop · rules"
    m = re.match(r"^(?:launch|start|open) (?:the )?(?:app )?([\w .+-]+?)(?: app)?$", low)
    if m and not PHONE_WORDS.search(low) and m.group(1) not in FOLDERS:
        r = tool_launch_app({"name": m.group(1)})
        if not r.startswith("No app"):
            return r, "Desktop · rules"
    m = re.match(r"^(?:make |save |create |write )?(?:a )?desktop note[:\s]+(.+)$", t, re.I)
    if m:
        return tool_desktop_note({"text": m.group(1)}), "Desktop · rules"
    m = re.match(r"^(?:android|kotlin|compose|firebase|developer|dev) docs?[:\s]+(.+)$", t, re.I)
    if m:
        return tool_dev_docs({"query": m.group(1)}), "Google developer docs"
    m = re.match(r"^pull (?:my |the )?(?:phone )?(backups|documents|photos|scans|recordings|dictation)(?: from (?:my |the )?phone)?$", low)
    if m:
        return pull(m.group(1), phone), "Desktop · phone files"
    return None


TOOLS = [
    {"type": "function", "function": {"name": "phone_command", "description": "Run a command in the BlackLab phone app: tasks, reminders, calendar, notes, journal, recorder, dictation, files search, email, Drive, briefing, education.",
                                      "parameters": {"type": "object", "properties": {"command": {"type": "string", "description": "Plain-English command, e.g. 'add task call vendor tomorrow 9am'"}}, "required": ["command"]}}},
    {"type": "function", "function": {"name": "system_status", "description": "Disk, memory, load, battery and phone connection of this computer.", "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {"name": "find_files", "description": "Find files in the user's home folder by name.", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "read_file", "description": "Read a text file from the user's home folder.", "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {"name": "open_path", "description": "Open a file or folder in the user's home with the default app.", "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {"name": "open_url", "description": "Open a web page in the browser.", "parameters": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]}}},
    {"type": "function", "function": {"name": "launch_app", "description": "Launch a desktop application by name.", "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {"name": "desktop_note", "description": "Save a markdown note on this computer.", "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "text": {"type": "string"}}, "required": ["text"]}}},
    {"type": "function", "function": {"name": "dev_docs", "description": "Search Google's official Android/Firebase/Cloud developer documentation (needs internet).", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
]


def run_tool(name, args, phone):
    fns = {"system_status": tool_system_status, "find_files": tool_find_files, "read_file": tool_read_file, "open_path": tool_open_path,
           "open_url": tool_open_url, "launch_app": tool_launch_app, "desktop_note": tool_desktop_note, "dev_docs": tool_dev_docs}
    try:
        if name == "phone_command":
            return tool_phone(args, phone)
        return fns[name](args or {})
    except Exception as e:
        return f"{name} failed: {e}"


def ollama_chat(text, model, phone):
    system = ("You are BlackLab, the user's laid-back, funny but sharp personal assistant, running offline on their Linux desktop. "
              "The user is a Director of Facility Operations who is also a student. Use tools to act; use phone_command for anything in the BlackLab phone app "
              "(tasks, calendar, notes, journal, recordings, email, Drive). Keep replies short. Today is " + dt.datetime.now().strftime("%A, %B %d %Y, %I:%M %p") + ".")
    msgs = [{"role": "system", "content": system}, {"role": "user", "content": text}]
    used = []
    for _ in range(4):
        r = http_json(f"{OLLAMA}/api/chat", {"model": model, "messages": msgs, "tools": TOOLS, "stream": False, "think": False,
                                            "options": {"temperature": 0.3, "num_ctx": 8192}}, timeout=180)
        msg = r.get("message", {})
        calls = msg.get("tool_calls") or []
        if not calls:
            content = re.sub(r"<think>.*?</think>", "", msg.get("content", ""), flags=re.S).strip()
            return content or "Done.", f"Desktop AI · {model} (offline)" + (f" · used {', '.join(used)}" if used else "")
        msgs.append(msg)
        for c in calls:
            fn = c.get("function", {})
            name, args = fn.get("name"), fn.get("arguments") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {}
            used.append(name)
            msgs.append({"role": "tool", "content": str(run_tool(name, args, phone))[:4000], "tool_name": name})
    return "I went in circles on that one — try rephrasing?", f"Desktop AI · {model}"


def assistant(body):
    text = (body.get("text") or "").strip()
    target = body.get("target", "auto")
    phone = body.get("phone")
    if not text:
        return {"text": ""}
    if target != "phone":
        r = desktop_rules(text, phone)
        if r:
            return {"text": r[0], "source": r[1]}
    looks_phone = bool(PHONE_WORDS.search(text))
    if phone and (target == "phone" or (target == "auto" and looks_phone)):
        r = phone_call(phone, "/api/assistant", {"text": text})
        return {"text": r.get("text", ""), "source": "Phone · " + r.get("source", ""), "links": r.get("links", [])}
    models = ollama_models()
    if models:
        model = body.get("model") if body.get("model") in models else default_model(models)
        text_out, source = ollama_chat(text, model, phone)
        return {"text": text_out, "source": source}
    if phone:
        r = phone_call(phone, "/api/assistant", {"text": text})
        return {"text": r.get("text", ""), "source": "Phone · " + r.get("source", "")}
    return {"text": "I can do desktop basics (system status, find files, open folders/apps, notes, pull phone files). For anything else, start Ollama or link the phone.",
            "source": "Desktop · rules"}


def tts(text: str) -> bytes | None:
    key = SECRETS.get("elevenlabs")
    if not key or not text.strip():
        return None
    cfg = CONFIG.get("voice", {})
    voice = cfg.get("voice_id", "CwhRBWXzGAHq8TQ4Fs17")  # Roger — laid-back, casual, resonant
    model = cfg.get("model", "eleven_flash_v2_5")
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    f = CACHE_DIR / (hashlib.sha1(f"{voice}|{model}|{text}".encode()).hexdigest() + ".mp3")
    if f.exists():
        return f.read_bytes()
    req = urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128",
                                 data=json.dumps({"text": text[:600], "model_id": model,
                                                  "voice_settings": {"stability": .4, "similarity_boost": .75, "style": .35, "use_speaker_boost": True}}).encode(),
                                 headers={"xi-api-key": key, "Content-Type": "application/json", "Accept": "audio/mpeg"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = r.read()
    except Exception:
        return None
    f.write_bytes(data)
    return data


# ---------------------------------------------------------------- HTTP

class Handler(SimpleHTTPRequestHandler):
    server_version = "BlackLabDesktop/" + VERSION

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(WEB_DIR), **kw)

    def log_message(self, fmt, *args):
        if os.environ.get("BLACKLAB_DEBUG"):
            super().log_message(fmt, *args)

    def cors(self):
        origin = self.headers.get("Origin")
        if origin in ALLOWED_ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-BlackLab")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Private-Network", "true")

    def end_headers(self):
        if not self.path.startswith("/local/"):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def reply(self, code, obj=None, raw: bytes | None = None, ctype="application/json"):
        data = raw if raw is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.cors()
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def guard(self) -> bool:
        origin = self.headers.get("Origin")
        if self.headers.get("X-BlackLab") != "1" or (origin and origin not in ALLOWED_ORIGINS):
            self.reply(403, {"error": "forbidden"})
            return False
        return True

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/local/"):
            if not self.guard():
                return
            if self.path == "/local/status":
                models = ollama_models()
                return self.reply(200, {"companion": True, "version": VERSION, "adb": adb_devices(), "ollama": models, "model": default_model(models),
                                        "eleven": bool(SECRETS.get("elevenlabs")), "devdocs": bool(SECRETS.get("developer_knowledge")), "out": str(OUT_DIR)})
            return self.reply(404, {"error": "unknown"})
        return super().do_GET()

    def do_POST(self):
        if not self.path.startswith("/local/"):
            return self.reply(404, {"error": "unknown"})
        if not self.guard():
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except Exception:
            body = {}
        try:
            if self.path == "/local/assistant":
                return self.reply(200, assistant(body))
            if self.path == "/local/adb/forward":
                ok, msg = adb_forward(int(body.get("port", 8765)))
                return self.reply(200 if ok else 409, {"ok": ok, "message": msg, "error": None if ok else msg})
            if self.path == "/local/tts":
                data = tts(body.get("text", ""))
                return self.reply(200, raw=data, ctype="audio/mpeg") if data else self.reply(204, raw=b"", ctype="audio/mpeg")
            if self.path == "/local/pull":
                return self.reply(200, {"message": pull(body.get("kind", ""), body.get("phone"))})
            if self.path == "/local/open":
                return self.reply(200, {"message": tool_open_path({"path": body.get("path", "~")})})
            return self.reply(404, {"error": "unknown"})
        except urllib.error.URLError as e:
            return self.reply(502, {"error": f"Couldn't reach it: {e.reason}"})
        except Exception as e:
            return self.reply(500, {"error": str(e)})


def keep_usb_link():
    """Re-establish `adb forward` whenever the phone is plugged in, so the UI reconnects on its own."""
    while True:
        try:
            if any(d["state"] == "device" and not d["emulator"] for d in adb_devices()["devices"]):
                adb_forward(8765)
        except Exception:
            pass
        time.sleep(15)


def main():
    OUT_DIR.mkdir(exist_ok=True)
    threading.Thread(target=keep_usb_link, daemon=True).start()
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"BlackLab Desktop companion {VERSION} on http://{HOST}:{PORT}  (web: {WEB_DIR})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
