"""Obsidian → BlackLab: what you do in the vault reaches the phone (companion 1.11).

Run every minute by the companion (and right before each phone feed is rendered), so nothing you change is lost or
flipped back:

  * Two-way tasks — check or uncheck a box in `30 Work/Tasks.md` (each line ends with its ^t<id>), or type a new
    `- [ ] …` line under a list's heading: the phone completes, reopens or adds it. A work order note's `status:` set to
    `done` (or back to `open`) does the same. Until the phone confirms, your change wins over the phone's older copy.
  * Trigger tags, in any note you write (not in BlackLab's managed blocks, 70 AI or 90 System):
        #task  · #action  → a task                #remind → a reminder (“remind me to …”)
        #workorder        → a work order          #event  → a calendar event (“schedule …”)
    The tag becomes #task/sent (etc.) once it's queued, so it never runs twice. Checkbox lines work too.
    A note in 00 Inbox with `blacklab: task|workorder|event|remind` in its frontmatter is sent whole.
  * #ask/claude · #ask/claude/opus · #ask/gemini · #ask/local → the line (or, on its own, the note) is asked and the
    answer is written under it as a callout; the tag becomes #ask/answered. The exchange is saved like every other.
  * Meeting notes (`type: meeting`, e.g. from the Meeting template): unchecked lines under "## Action items" become
    tasks on the phone (tagged meeting, with the meeting's name); each line is then marked #action/sent.
  * Flashcards — `front :: back` lines in a note tagged #flashcards become cards on the phone (deck = the note's
    `deck:` or its name). Each card is sent once.

Files you're still typing in (changed in the last 20 s) wait for the next pass.
"""
from __future__ import annotations

import hashlib
import re
import time
from pathlib import Path

from vault import BEGIN, END, FOLDERS

TASKS_REL = f"{FOLDERS['work']}/Tasks.md"
WO_DIR = f"{FOLDERS['work']}/Work Orders"
SKIP_TOP = ("70 AI", "90 System", "85 Atlas", "86 Imported", "87 Entities", "88 Drive", "89 Mobile", "95 Library", "99 Archive", ".obsidian", ".git", ".trash")
SETTLE_S = 20
TASK_LINE = re.compile(r"^(\s*)- \[([ xX])\] (.*?)\s*$")
TASK_ID = re.compile(r"\s\^t(\d+)\s*$")
DUE = re.compile(r"\s*📅\s*(\d{4}-\d{2}-\d{2})")
PRIORITY_MARKS = re.compile(r"\s*[⏫🔼🔽⏬]")
LINKS = re.compile(r"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]")
TRIGGER = re.compile(r"(?<![\w/#])#(task|action|remind|workorder|event)(?![\w/-])", re.I)
ASK = re.compile(r"(?<![\w/#])#ask/(claude|gemini|local)(?:/(opus|sonnet|haiku|fable|pro|flash))?(?![\w/-])", re.I)
CARD = re.compile(r"^\s*(?:[-*]\s+)?(.+?)\s+::\s+(.+?)\s*$")
COMMANDS = {"task": "add task {}", "action": "add task {}", "remind": "remind me to {}", "workorder": "work order: {}", "event": "schedule {}"}


def _profile(heading: str) -> tuple[str, str]:
    """'Professional · Work Orders' → ('PROFESSIONAL', 'Work Orders')."""
    p, _, lst = heading.partition(" · ")
    return (p.strip().upper() or "PROFESSIONAL"), (lst.strip() or "Tasks")


def _plain(text: str) -> str:
    """A task line's title without our markers: dates, priorities, links, tags, ⏳."""
    t = DUE.sub("", text)
    t = PRIORITY_MARKS.sub("", t)
    t = LINKS.sub(lambda m: m.group(2) or m.group(1), t)
    t = re.sub(r"\s#[\w/-]+", "", " " + t).strip()
    return t.replace("⏳", "").strip()


def _block(text: str) -> tuple[int, int] | None:
    i, j = text.find(BEGIN), text.find(END)
    return (i, j) if i != -1 and j != -1 and j > i else None


# ---------------------------------------------------------------- two-way tasks

def overrides(v) -> dict:
    return v._state("sync/task-overrides.json", {})


def pending_adds(v) -> list:
    return v._state("sync/task-adds.json", [])


def harvest_tasks(v, queue) -> dict:
    """Reads your edits in Tasks.md and the work order notes and queues them for the phone."""
    out = {"completed": 0, "reopened": 0, "added": 0}
    if queue is None:
        return out
    with v.lock:
        rendered = v._state("sync/tasks-rendered.json", {})
        known = {t.strip().lower() for t in v._state("sync/tasks-titles.json", [])}
        # New lines only count once Tasks.md has been written with ^t ids (an older render has none: every line would look new)
        adds_ok = bool(rendered) and bool(known)
        ov = overrides(v)
        adds = pending_adds(v)
        p = v.path(TASKS_REL)
        if p.exists():
            text = p.read_text(encoding="utf-8", errors="replace")
            span = _block(text)
            if span:
                lines = text[span[0]:span[1]].split("\n")
                heading, changed = "", False
                for n, ln in enumerate(lines):
                    if ln.startswith("## "):
                        heading = ln[3:].strip()
                        continue
                    m = TASK_LINE.match(ln)
                    if not m:
                        continue
                    checked = m.group(2) != " "
                    idm = TASK_ID.search(ln)
                    if idm:
                        tid = idm.group(1)
                        was = rendered.get(tid)
                        if was is not None and checked != was:
                            queue({"op": "task.complete" if checked else "task.reopen", "task_id": int(tid)}, f"task:{tid}")
                            ov[tid] = checked
                            rendered[tid] = checked
                            out["completed" if checked else "reopened"] += 1
                        continue
                    if "⏳" in ln or not heading or checked or not adds_ok:
                        continue
                    title = _plain(m.group(3))
                    if not title or title.strip().lower() in known:
                        continue
                    prof, lst = _profile(heading)
                    due = DUE.search(m.group(3))
                    queue({"op": "task.add", "title": title[:140], "list": lst, "profile": prof, "due": due.group(1) if due else ""}, None)
                    adds.append({"title": title, "list": lst, "profile": prof, "at": time.time()})
                    lines[n] = ln.rstrip() + " ⏳"
                    changed = True
                    out["added"] += 1
                if changed:
                    v._write(TASKS_REL, text[:span[0]] + "\n".join(lines) + text[span[1]:])
        wo_seen = v._state("sync/wo-rendered.json", {})
        d = v.path(WO_DIR)
        if d.is_dir():
            for f in d.glob("*.md"):
                fm, _ = v.split_frontmatter(f.read_text(encoding="utf-8", errors="replace"))
                props = {k.strip(): val.strip().strip('"') for k, _, val in (ln.partition(":") for ln in fm) if k.strip()}
                tid, status = props.get("task_id", ""), props.get("status", "").lower()
                if not tid.isdigit() or status not in ("open", "done"):
                    continue
                was = wo_seen.get(tid)
                if was and was != status:
                    queue({"op": "task.complete" if status == "done" else "task.reopen", "task_id": int(tid)}, f"task:{tid}")
                    ov[tid] = status == "done"
                    out["completed" if status == "done" else "reopened"] += 1
                wo_seen[tid] = status
        v._save_state("sync/wo-rendered.json", wo_seen)
        v._save_state("sync/tasks-rendered.json", rendered)
        v._save_state("sync/task-overrides.json", ov)
        v._save_state("sync/task-adds.json", adds[-300:])
    return out


def apply_overrides(v, tasks: list) -> list:
    """The phone's tasks with your unconfirmed Obsidian changes laid over them; confirmed ones are forgotten."""
    ov = overrides(v)
    keep = {}
    out = []
    for t in tasks:
        tid = str(t.get("id", ""))
        if tid in ov:
            if bool(t.get("completed")) == ov[tid]:
                pass  # the phone has it now
            else:
                keep[tid] = ov[tid]
                t = dict(t, completed=ov[tid])
        out.append(t)
    v._save_state("sync/task-overrides.json", keep)
    titles = {(t.get("title") or "").strip().lower() for t in tasks}
    adds = [a for a in pending_adds(v) if a["title"].strip().lower() not in titles and time.time() - a.get("at", 0) < 14 * 86400]
    v._save_state("sync/task-adds.json", adds)
    return out


def remember_rendered(v, tasks: list):
    v._save_state("sync/tasks-rendered.json", {str(t.get("id")): bool(t.get("completed")) for t in tasks if t.get("id") is not None})
    v._save_state("sync/tasks-titles.json", sorted({_plain(t.get("title") or "") for t in tasks if t.get("title")}))
    v._save_state("sync/wo-rendered.json", {str(t.get("id")): "done" if t.get("completed") else "open" for t in tasks if t.get("workorder") and t.get("id") is not None})


# ---------------------------------------------------------------- trigger notes

def _candidates(v, since: float) -> list[Path]:
    root = v.root
    out = []
    now = time.time()
    tops = [d for d in root.iterdir() if d.name not in SKIP_TOP and not d.name.startswith(".")] if root.is_dir() else []
    files = [d for d in tops if d.is_file() and d.suffix == ".md"] + [p for d in tops if d.is_dir() for p in d.rglob("*.md")]
    for p in files:
        rel = p.relative_to(root).as_posix()
        if "/." in "/" + rel:
            continue
        try:
            mt = p.stat().st_mtime
        except OSError:
            continue
        if mt > since and now - mt >= SETTLE_S:
            out.append(p)
    return out


def _outside_blocks(text: str) -> list[tuple[int, int]]:
    """Character ranges of the text that are Paul's (not BlackLab's managed blocks)."""
    spans, pos = [], 0
    while True:
        i = text.find(BEGIN, pos)
        if i == -1:
            spans.append((pos, len(text)))
            return spans
        spans.append((pos, i))
        j = text.find(END, i)
        if j == -1:
            return spans
        pos = j + len(END)


def scan_triggers(v, queue, ask=None, since: float | None = None) -> dict:
    """One pass over notes changed since the last pass. [ask](model_id, prompt, note_rel) → (answer, label) or raises."""
    out = {"commands": 0, "asks": 0, "cards": 0, "errors": []}
    st = v._state("sync/triggers.json", {"since": 0, "cards": []})
    if not st.get("since") and since is None:  # the first pass only starts the clock: tags already in old notes don't fire
        st["since"] = time.time()
        v._save_state("sync/triggers.json", st)
        return out
    last = st.get("since", 0) if since is None else since
    started = time.time()
    cards_sent = set(st.get("cards", []))
    for p in _candidates(v, last):
        rel = p.relative_to(v.root).as_posix()
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        new = text
        fm, body = v.split_frontmatter(text)
        props = {k.strip(): val.strip().strip('"\'') for k, _, val in (ln.partition(":") for ln in fm) if k.strip()}
        # a whole Inbox note
        kind = props.get("blacklab", "").lower()
        if rel.startswith(FOLDERS["inbox"] + "/") and kind in COMMANDS and queue:
            ok, words = _safe(Path(rel).stem + ". " + body.strip())
            if ok:
                queue({"op": "command", "text": COMMANDS[kind].format(words[:400]), "source": rel}, None)
                new = new.replace(f"blacklab: {props['blacklab']}", "blacklab: sent", 1)
                out["commands"] += 1
        # lines with tags, outside managed blocks
        pieces, cursor = [], 0
        for a, b in _outside_blocks(new):
            pieces.append(new[cursor:a])
            seg = new[a:b]
            seg, n_cmd = _line_triggers(seg, queue) if queue else (seg, 0)
            out["commands"] += n_cmd
            if queue and props.get("type", "").lower() == "meeting":
                seg, n_act = _meeting_actions(seg, queue, p.stem)
                out["commands"] += n_act
            if ask:
                seg, n_ask, errs = _ask_triggers(seg, ask, rel, body)
                out["asks"] += n_ask
                out["errors"] += errs
            pieces.append(seg)
            cursor = b
        pieces.append(new[cursor:])
        new = "".join(pieces)
        # flashcards
        tags = props.get("tags", "") + " " + " ".join(re.findall(r"#([\w/-]+)", body))
        if queue and re.search(r"(?<![\w-])flashcards(?![\w-])", tags):
            deck = props.get("deck") or p.stem
            for ln in body.splitlines():
                m = CARD.match(ln)
                if not m or ln.lstrip().startswith(("#", ">", "|")):
                    continue
                front, back = _plain(m.group(1)), m.group(2).strip()
                h = hashlib.sha1(f"{deck}|{front}|{back}".encode()).hexdigest()[:16]
                if h in cards_sent or not front or not back:
                    continue
                ok, _ = _safe(front + " " + back)
                if ok:
                    queue({"op": "card.add", "deck": deck[:80], "front": front[:500], "back": back[:1000]}, None)
                    cards_sent.add(h)
                    out["cards"] += 1
        if new != text:
            v._write(rel, new)
    st["since"] = started - SETTLE_S - 1  # files that weren't settled yet get another look
    st["cards"] = sorted(cards_sent)[-5000:]
    v._save_state("sync/triggers.json", st)
    return out


def _safe(text: str) -> tuple[bool, str]:
    import guards
    private, _ = guards.privacy_check(text)
    return (not private), guards.redact(text)


def _line_triggers(seg: str, queue) -> tuple[str, int]:
    lines, n = seg.split("\n"), 0
    for i, ln in enumerate(lines):
        m = TRIGGER.search(ln)
        if not m or ln.lstrip().startswith((">", "%%", "|")):  # answers, comments and tables never fire
            continue
        kind = m.group(1).lower()
        cm = TASK_LINE.match(ln)
        if cm and cm.group(2) != " ":
            continue  # already done: nothing to send
        words = _plain(TRIGGER.sub("", cm.group(3) if cm else ln).strip(" -*\t"))
        if not words:
            continue
        ok, words = _safe(words)
        if not ok:
            lines[i] = TRIGGER.sub(f"#{kind}/private", ln, count=1)
            continue
        op = {"op": "command", "text": COMMANDS[kind].format(words[:300])}
        if kind == "action":
            op["tags"] = "meeting"
        queue(op, None)
        lines[i] = TRIGGER.sub(f"#{kind}/sent", ln, count=1)
        n += 1
    return "\n".join(lines), n


def _meeting_actions(seg: str, queue, meeting: str) -> tuple[str, int]:
    lines, n, inside = seg.split("\n"), 0, False
    for i, ln in enumerate(lines):
        if ln.startswith("#"):
            inside = bool(re.match(r"^#{1,4}\s*(?:✅\s*)?action items?\b", ln, re.I))
            continue
        m = TASK_LINE.match(ln)
        if not inside or not m or m.group(2) != " " or re.search(r"#[\w/-]+", ln):
            continue
        words = _plain(m.group(3))
        if len(words) < 3:
            continue
        ok, words = _safe(words)
        if not ok:
            lines[i] = ln.rstrip() + " #action/private"
            continue
        queue({"op": "task.add", "title": words[:140], "list": "Follow-ups", "profile": "PROFESSIONAL", "due": (DUE.search(ln).group(1) if DUE.search(ln) else ""),
               "tags": "meeting", "notes": f"From the meeting note “{meeting}” in Obsidian"}, None)
        lines[i] = ln.rstrip() + " #action/sent"
        n += 1
    return "\n".join(lines), n


def _ask_triggers(seg: str, ask, rel: str, body: str) -> tuple[str, int, list[str]]:
    lines, n, errs = seg.split("\n"), 0, []
    i = 0
    while i < len(lines):
        ln = lines[i]
        m = ASK.search(ln)
        if not m or ln.lstrip().startswith((">", "%%", "|")):
            i += 1
            continue
        prov, variant = m.group(1).lower(), (m.group(2) or "").lower()
        question = _plain(ASK.sub("", ln).strip(" -*\t"))
        if len(question) < 4:  # the tag on its own: ask about the whole note
            question = "Here is a note from my Obsidian vault. Read it and do what it asks (or, if it asks nothing, give me the most useful response):\n\n" + \
                       ASK.sub("", body).strip()[:30000]
        ok, question = _safe(question)
        if not ok:
            lines[i] = ASK.sub("#ask/private", ln, count=1)
            i += 1
            continue
        model = {"claude": f"claude:{variant or 'sonnet'}", "gemini": f"gemini:{variant}" if variant else "gemini:", "local": "local:"}[prov]
        try:
            answer, label = ask(model, question, rel)
        except Exception as e:  # noqa: BLE001
            errs.append(f"{rel}: {e}")
            lines[i] = ASK.sub("#ask/failed", ln, count=1)
            lines.insert(i + 1, f"> [!warning] Couldn't ask: {str(e)[:200]}")
            i += 2
            continue
        lines[i] = ASK.sub("#ask/answered", ln, count=1)
        callout = [f"> [!{prov if prov != 'local' else 'ai'}] {label} · {time.strftime('%Y-%m-%d %H:%M')}"] + \
                  [("> " + a) if a.strip() else ">" for a in answer.strip().splitlines()]
        lines[i + 1:i + 1] = callout
        i += 1 + len(callout)
        n += 1
    return "\n".join(lines), n, errs
