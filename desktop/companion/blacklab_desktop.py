#!/usr/bin/env python3
"""BlackLab Desktop companion.

A small local server (Python standard library only) that
  * serves the BlackLab Desktop web UI at http://127.0.0.1:8770,
  * links to the phone over USB (`adb forward`) and relays to the phone's link API,
  * runs an offline desktop assistant: deterministic rules for common desktop tasks plus a local
    Ollama model with tool calling for everything else (no cloud needed),
  * speaks with ElevenLabs and searches Google developer docs when keys are configured,
  * keeps everything the desktop AI produces in one Desktop folder, ~/Desktop/BlackLab AI:
      Downloads/     files it pulls from the phone
      Documents/     notes and documents it writes
      Chat History/  every conversation, as daily transcripts plus a searchable memory file
      Task Logs/     one log per job: the request, each step and tool, and every file it
                     creates, edits, downloads, reads or opens.
  * keeps clinical and "off the record" requests out of every record, remembers recent turns so
    follow-ups work, keeps a long-term memory.md, and searches your documents with local embeddings.

Listens on 127.0.0.1 only. Secrets live in ~/.config/blacklab-desktop/secrets.json (chmod 600),
never in the repository.
"""
from __future__ import annotations

import contextlib
import csv
import datetime as dt
import functools
import hashlib
import html as htmllib
import hmac
import secrets as pysecrets
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
from collections import deque
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agents  # noqa: E402  (background jobs, recordings, equipment, briefs)
import guards  # noqa: E402  (every safety rule: HTTP, paths, privacy, tool calls — unit tested)
import reverse  # noqa: E402  (reverse engineering with REA + Ghidra, read-only — unit tested)
import vault as vaultlib  # noqa: E402  (the Obsidian vault: notes, timeline, AI registry, git)
import audit  # noqa: E402  (the hash-chained activity ledger — unit tested)

VERSION = "1.7.0"
HOST, PORT = "127.0.0.1", int(os.environ.get("BLACKLAB_DESKTOP_PORT", "8770"))
HOME = Path.home()
WEB_DIR = Path(__file__).resolve().parent.parent / "web"
CONFIG_DIR = HOME / ".config" / "blacklab-desktop"
CACHE_DIR = HOME / ".cache" / "blacklab-desktop"
OLLAMA = os.environ.get("OLLAMA_HOST", "127.0.0.1:11434").rstrip("/")
if not OLLAMA.startswith("http"):  # Ollama's own convention allows "host:port"
    OLLAMA = "http://" + OLLAMA.replace("0.0.0.0", "127.0.0.1")
ALLOWED_ORIGINS = guards.allowed_origins(HOST, PORT)
ALLOWED_HOSTS = guards.allowed_hosts(HOST, PORT)
PREFERRED_MODELS = ["qwen3:8b", "granite4:tiny-h", "paul-assistant:latest", "qwen2.5:14b"]


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default


SECRETS = load_json(CONFIG_DIR / "secrets.json", {})
CONFIG = load_json(CONFIG_DIR / "config.json", {})


def _cfg_dir(key: str, default) -> Path:
    return Path(os.path.expanduser(str(CONFIG.get(key) or default)))


# Everything the desktop AI produces lives under one Desktop folder. config.json: "ai_folder" moves it all;
# "downloads_dir", "documents_dir", "chat_history_dir" or "task_log_dir" moves one; "chat_history": false or
# "task_logs": false turns that record off.
AI_DIR = _cfg_dir("ai_folder", "~/Desktop/BlackLab AI")
DOWNLOADS_DIR = _cfg_dir("downloads_dir", AI_DIR / "Downloads")
DOCS_DIR = _cfg_dir("documents_dir", AI_DIR / "Documents")
CHAT_DIR = _cfg_dir("chat_history_dir", AI_DIR / "Chat History")
TASK_LOG_DIR = _cfg_dir("task_log_dir", AI_DIR / "Task Logs")
OUT_DIR = DOWNLOADS_DIR  # the name companion 1.2 and earlier used
VAULT_ON = CONFIG.get("vault", True) is not False
VAULT = vaultlib.Vault(_cfg_dir("vault_dir", "~/Obsidian/BlackLab"), CACHE_DIR / "vault", CONFIG.get("timezone", "America/Chicago"))
LEDGER = audit.Ledger(CACHE_DIR / "vault" / "ledger.jsonl")
VAULT.ledger = LEDGER


def ensure_phone_token() -> str:
    """The shared key the phone uses for /phone/* (provisioned onto the phone by ./daddyshome)."""
    tok = SECRETS.get("phone_ai_token")
    if not tok:
        tok = pysecrets.token_urlsafe(32)
        SECRETS["phone_ai_token"] = tok
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        p = CONFIG_DIR / "secrets.json"
        p.write_text(json.dumps(SECRETS, indent=2))
        os.chmod(p, 0o600)
    return tok


def keep_alive():
    """How long Ollama keeps the model loaded after each request. ./daddyshome sets -1 (always loaded)."""
    v = CONFIG.get("keep_alive", "30m")
    return int(v) if isinstance(v, (int, float)) or (isinstance(v, str) and v.lstrip("-").isdigit()) else v


def num_ctx() -> int:
    """One context size everywhere — Ollama reloads the whole model when it changes."""
    return int(CONFIG.get("num_ctx", 8192))


PHONE = {"last_seen": 0, "model": ""}  # the phone's last authenticated contact


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
    adb_reverse_all()
    return True, f"USB link ready: 127.0.0.1:{port} → {devs[0]['model'] or serial}"


def adb_reverse_all():
    """Lets every connected phone (USB or Wireless debugging) reach this companion at its own 127.0.0.1:PORT."""
    adb = adb_path()
    if not adb:
        return []
    done = []
    for d in adb_devices()["devices"]:
        if d["state"] == "device" and not d["emulator"]:
            r = subprocess.run([adb, "-s", d["serial"], "reverse", f"tcp:{PORT}", f"tcp:{PORT}"], capture_output=True, text=True, timeout=8)
            if r.returncode == 0:
                done.append(d["model"] or d["serial"])
    return done


def ollama_models(include_embed=False):
    try:
        tags = http_json(f"{OLLAMA}/api/tags", timeout=3)
        return [m["name"] for m in tags.get("models", []) if include_embed or "embed" not in m["name"]]
    except Exception:
        return []


def default_model(models):
    want = CONFIG.get("model")
    if want in models:
        return want
    return next((m for m in PREFERRED_MODELS if m in models), models[0] if models else "")


def xdg_open(target: str):
    subprocess.Popen(["xdg-open", target], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


# Never readable, openable or searchable by the AI, whatever a request (or a document it reads) asks:
# wallet keys and recovery phrases, SSH/GPG keys, browser profiles, and the keys of BlackLab itself.
PROTECTED = guards.protected_paths(HOME, CONFIG.get("protected_paths", []))
PROTECTED_NAME_RE = guards.PROTECTED_NAME_RE


def is_protected(path: Path) -> bool:
    return guards.is_protected(path, PROTECTED)


def safe_home_path(p: str) -> Path | None:
    return guards.safe_home_path(p, HOME, PROTECTED)


# ---------------------------------------------------------------- privacy
#
# Anything that looks clinical (the Behavioral Health workspace, patients, meds…) or that the user marks
# "privately: …" / "off the record …" is answered normally but never written to the chat history,
# the memory or the content of a task log. Identifiers are scrubbed from everything that is saved.

OFF_RECORD_RE = guards.OFF_RECORD_RE
CLINICAL_RE = guards.CLINICAL_RE


def privacy_check(text: str, flagged=False) -> tuple[bool, str]:
    """(private?, text without an "off the record" prefix)."""
    return guards.privacy_check(text, flagged, CONFIG)


redact = guards.redact


# ---------------------------------------------------------------- task logs
#
# One Markdown file per task in TASK_LOG_DIR/<YYYY-MM>/, plus a task-index.csv for a quick overview.
# A task is one request to the desktop AI (from the phone or the desktop UI). Tools record their
# steps through @logged_tool, and anything that touches a file calls task_file(action, path):
# "created", "edited", "downloaded", "deleted", "read", "opened". New tools must do the same.

_TASK = threading.local()
_INDEX_LOCK = threading.Lock()
LOG_EXCERPT = 2000  # characters of each tool result kept in the log


def _short(v, n=LOG_EXCERPT) -> str:
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, default=str)
    return s if len(s) <= n else s[:n] + f"… ({len(s) - n:,} more characters)"


def _home_rel(path) -> str:
    try:
        return "~/" + str(Path(path).resolve().relative_to(HOME))
    except (ValueError, OSError):
        return str(path)


class TaskLog:
    def __init__(self, request: str, origin: str, private=False):
        self.request, self.origin, self.private = redact(request.strip()), origin, private
        self.started = dt.datetime.now()
        self.steps: list[dict] = []
        self.files: list[tuple[str, str]] = []
        self.reply, self.source, self.error = "", "", None

    def step(self, kind: str, title: str, detail="", seconds: float | None = None):
        self.steps.append({"at": dt.datetime.now(), "kind": kind, "title": title, "detail": _short(detail) if detail else "", "seconds": seconds})

    def file(self, action: str, path):
        entry = (action, _home_rel(path) if not str(path).startswith("http") else str(path))
        if entry not in self.files:
            self.files.append(entry)

    def status(self) -> str:
        if self.error:
            return "Failed"
        return "Unfinished" if self.reply.startswith("I went in circles") else "Done"

    def markdown(self, ended: dt.datetime) -> str:
        secs = (ended - self.started).total_seconds()
        title = "(private request)" if self.private else " ".join(self.request.split())[:80] or "(empty request)"
        out = [f"# Desktop AI task: {title}", "",
               "| | |", "|---|---|",
               f"| When | {self.started:%Y-%m-%d %H:%M:%S} → {ended:%H:%M:%S} ({secs:.1f} s) |",
               f"| From | {self.origin} |",
               f"| Answered by | {self.source or '—'} |",
               f"| Status | {self.status()} |",
               f"| Files touched | {len(self.files)} |", "",
               "## Request", "", *(["_Private: the request, tool details and reply were not recorded._"] if self.private else
                                   ("> " + line for line in (self.request or "(empty)").splitlines())), "",
               "## Steps", ""]
        if not self.steps:
            out.append("_No tools were used._")
        for i, st in enumerate(self.steps, 1):
            took = f" · {st['seconds']:.1f} s" if st["seconds"] is not None else ""
            out.append(f"{i}. **{st['title']}** ({st['kind']}) · {st['at']:%H:%M:%S}{took}")
            if st["detail"] and not self.private:
                out += ["", "    ```", *("    " + line for line in st["detail"].splitlines()), "    ```", ""]
        out += ["", "## Files", ""]
        if self.files:
            out += ["| Action | Path |", "|---|---|", *(f"| {a} | `{p}` |" for a, p in self.files[:500])]
            if len(self.files) > 500:
                out += ["", f"_…and {len(self.files) - 500:,} more._"]
        else:
            out.append("_No files were created, edited, downloaded, read or opened._")
        out += ["", "## Reply", "", "_Not recorded (private)._" if self.private else redact(self.reply) or "_(no reply)_"]
        if self.error:
            out += ["", "## Error", "", "```", self.error, "```"]
        out += ["", f"_BlackLab Desktop companion {VERSION}_", ""]
        return "\n".join(out)

    def save(self) -> Path:
        ended = dt.datetime.now()
        folder = TASK_LOG_DIR / f"{self.started:%Y-%m}"
        folder.mkdir(parents=True, exist_ok=True)
        for d in (TASK_LOG_DIR, folder):
            os.chmod(d, 0o700)  # logs can quote file contents: keep them to this user
        slug = "private-task" if self.private else re.sub(r"[^a-z0-9]+", "-", self.request.lower()).strip("-")[:48] or "task"
        path = folder / f"{self.started:%Y-%m-%d_%H-%M-%S}_{slug}.md"
        n = 2
        while path.exists():
            path = folder / f"{self.started:%Y-%m-%d_%H-%M-%S}_{slug}-{n}.md"
            n += 1
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(self.markdown(ended))
        index = TASK_LOG_DIR / "task-index.csv"
        with _INDEX_LOCK:
            new = not index.exists()
            with open(index, "a", newline="") as f:
                w = csv.writer(f)
                if new:
                    w.writerow(["started", "seconds", "from", "request", "answered_by", "status", "tools", "files", "log"])
                w.writerow([f"{self.started:%Y-%m-%d %H:%M:%S}", f"{(ended - self.started).total_seconds():.1f}", self.origin,
                            "(private)" if self.private else " ".join(self.request.split())[:200], self.source, self.status(),
                            ", ".join(st["title"] for st in self.steps if st["kind"] == "tool"),
                            "; ".join(f"{a} {p}" for a, p in self.files[:20]) + (f"; +{len(self.files) - 20} more" if len(self.files) > 20 else ""), str(path.relative_to(TASK_LOG_DIR))])
            os.chmod(index, 0o600)
        return path


@contextlib.contextmanager
def task_log(request: str, origin: str, private=False):
    """Logs everything done inside the block as one task. Nested calls join the outer task."""
    if getattr(_TASK, "log", None) is not None or CONFIG.get("task_logs", True) is False or not (request or "").strip():
        yield getattr(_TASK, "log", None)
        return
    log = TaskLog(request, origin, private)
    _TASK.log = log
    try:
        yield log
    except Exception as e:
        log.error = f"{type(e).__name__}: {e}"
        raise
    finally:
        _TASK.log = None
        try:
            log.save()
        except Exception as e:  # a full disk or a bad folder must never break the assistant
            print(f"task log not written: {e}", file=sys.stderr, flush=True)


def current_task() -> TaskLog | None:
    return getattr(_TASK, "log", None)


def task_step(kind: str, title: str, detail="", seconds: float | None = None):
    if (log := current_task()) is not None:
        log.step(kind, title, detail, seconds)


def task_file(action: str, path):
    if (log := current_task()) is not None:
        log.file(action, path)


def logged_tool(name: str):
    """Records a tool's arguments, result and duration in the current task log."""
    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*a, **kw):
            args = a[0] if a else None
            t0 = time.time()
            try:
                out = fn(*a, **kw)
            except Exception as e:
                task_step("tool", name, f"args: {_short(args, 400)}\nfailed: {e}", time.time() - t0)
                raise
            task_step("tool", name, f"args: {_short(args, 400)}\nresult: {_short(out)}" if args else f"result: {_short(out)}", time.time() - t0)
            LEDGER.record("Desktop AI", f"tool:{name}", source="desktop assistant", duration_ms=int((time.time() - t0) * 1000))
            return out
        return wrapper
    return deco



# ---------------------------------------------------------------- chat history
#
# Every exchange with the desktop AI is archived twice in CHAT_DIR: a readable daily transcript
# (<YYYY-MM>/<YYYY-MM-DD>.md) and one JSON line per exchange (chat-history.jsonl), which the
# recall_chats tool searches so the assistant can remember earlier conversations.

_CHAT_LOCK = threading.Lock()
CHAT_STOPWORDS = set("the and what did does about you your for that this with was were when have has said say ask asked tell "
                     "talk talked chat chats chatted history conversation remember recall last time earlier before we us our me my "
                     "today yesterday week any".split())


def _private_dir(*dirs: Path):
    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)
        os.chmod(d, 0o700)


def _append_private(path: Path, text: str):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a") as f:
        f.write(text)


def archive_chat(request: str, reply: str, origin: str, source: str, when: dt.datetime, tools: list[str]):
    if CONFIG.get("chat_history", True) is False or not (request or "").strip():
        return
    folder = CHAT_DIR / f"{when:%Y-%m}"
    day = folder / f"{when:%Y-%m-%d}.md"
    quote = lambda t: "\n".join("> " + line for line in (t or "").strip().splitlines()) or "> _(no reply)_"
    with _CHAT_LOCK:
        _private_dir(CHAT_DIR, folder)
        head = "" if day.exists() else f"# Desktop AI chats · {when:%A, %B %d %Y}\n\n"
        request, reply = redact(request), redact(reply)
        _append_private(day, head + f"## {when:%H:%M:%S} · {origin}\n\n**You**\n\n{quote(request)}\n\n"
                                    f"**BlackLab** · {source or '—'}\n\n{quote(reply)}\n\n---\n\n")
        try:
            if agents.C and agents.ISSUE_RE.search(request):  # "RTU-4 is tripping again" becomes part of its history
                agents.link_mentions(request, "chat", "Chat", when.timestamp())
        except Exception:
            pass
        _append_private(CHAT_DIR / "chat-history.jsonl", json.dumps(
            {"at": when.isoformat(timespec="seconds"), "from": origin, "you": request.strip(), "blacklab": reply, "by": source, "tools": tools},
            ensure_ascii=False) + "\n")
    if VAULT_ON:  # private turns never reach archive_chat, so they never reach the vault either
        try:
            VAULT.log("ai", request.strip().splitlines()[0][:140], reply.strip()[:600], source=f"Desktop AI · {origin.split(' (')[0]}",
                      links=[f"{when:%Y-%m-%d}"], at=when.astimezone(VAULT.tz) if VAULT.tz else when)
        except Exception as e:
            print(f"vault log skipped: {e}", file=sys.stderr, flush=True)


@logged_tool("recall_chats")
def tool_recall_chats(args):
    q = ((args.get("query") if isinstance(args, dict) else str(args or "")) or "").lower()
    path = CHAT_DIR / "chat-history.jsonl"
    if not path.exists():
        return "There's no chat history yet."
    today = dt.date.today()
    day = today if "today" in q else today - dt.timedelta(days=1) if "yesterday" in q else None
    words = [w for w in re.findall(r"[a-z0-9$.,-]+", q) if len(w) > 2 and w.strip(".,") not in CHAT_STOPWORDS]
    hits = []
    for line in path.read_text(errors="ignore").splitlines()[-20000:]:
        try:
            c = json.loads(line)
        except ValueError:
            continue
        if "recall_chats" in (c.get("tools") or []) or (day and not c.get("at", "").startswith(day.isoformat())):
            continue
        hay = f"{c.get('you', '')} {c.get('blacklab', '')}".lower()
        score = sum(w.strip(".,") in hay for w in words) if words else 1
        if score:
            hits.append((score, c.get("at", ""), c))
    task_file("read", path)
    if not hits:
        return f"Nothing in the chat history matches “{q}”."
    hits.sort(key=lambda h: (h[0], h[1]), reverse=True)
    return f"{len(hits)} earlier chat(s) match; the best {min(5, len(hits))}, oldest first:\n\n" + "\n\n".join(
        f"{c['at'][:16].replace('T', ' ')} · you: {_short(c.get('you', ''), 240)}\nBlackLab: {_short(c.get('blacklab', ''), 500)}"
        for _, _, c in sorted(hits[:5], key=lambda h: h[1]))

# ---------------------------------------------------------------- memory
#
# Short term: the last 3 exchanges per conversation (phone or desktop UI) from the past 15 minutes go to the
# model with each request, so "open the first one" works. Long term: Chat History/memory.md, a plain list the
# model reads before every answer. "remember that …" / "forget …" edit it; once a day the model adds up to five
# durable facts it learned from the day's chats (config.json "memory_auto": false stops that). Edit it freely.

MEMORY_FILE = CHAT_DIR / "memory.md"
MEMORY_HEAD = ("# What BlackLab remembers about you\n\nThe desktop AI reads this before every answer. Edit or delete lines freely; "
               "\"forget …\" removes one.\n\n")
MEMORY_MAX = 80
_RECENT: dict[str, deque] = {}
_MEMORY_LOCK = threading.Lock()


def recent_turns(origin: str) -> list[tuple[str, str]]:
    return [(u, a) for t, u, a in _RECENT.get(origin, ()) if time.time() - t < 900]


def remember_turn(origin: str, user: str, reply: str):
    _RECENT.setdefault(origin, deque(maxlen=3)).append((time.time(), user[:1500], reply[:1500]))


def memory_facts() -> list[str]:
    try:
        return [ln for ln in MEMORY_FILE.read_text().splitlines() if ln.startswith("- ")]
    except OSError:
        return []


def _write_memory(facts: list[str]):
    _private_dir(CHAT_DIR)
    tmp = MEMORY_FILE.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(MEMORY_HEAD + "\n".join(facts) + ("\n" if facts else ""))
    os.replace(tmp, MEMORY_FILE)
    task_file("edited", MEMORY_FILE)


def memory_add(facts: list[str], how="added") -> list[str]:
    with _MEMORY_LOCK:
        have = memory_facts()
        known = {re.sub(r"\s*_\(.*?\)_$", "", f).lower() for f in have}
        new = [f.strip().rstrip(".") for f in facts if f.strip() and "- " + f.strip().rstrip(".").lower() not in known]
        if not new:
            return []
        have += [f"- {f} _({how} {dt.date.today():%Y-%m-%d})_" for f in new]
        while len(have) > MEMORY_MAX:  # drop the oldest learned fact first, then the oldest of any kind
            i = next((i for i, f in enumerate(have) if "_(learned" in f), 0)
            have.pop(i)
        _write_memory(have)
        return new


def memory_forget(query: str) -> list[str]:
    words = [w for w in re.findall(r"[a-z0-9]+", query.lower()) if len(w) > 2 and w not in CHAT_STOPWORDS]
    if not words:
        return []
    with _MEMORY_LOCK:
        have = memory_facts()
        gone = [f for f in have if all(w in f.lower() for w in words)]
        if gone:
            _write_memory([f for f in have if f not in gone])
        return gone


def memory_prompt() -> str:
    facts = [re.sub(r"\s*_\(.*?\)_$", "", f) for f in memory_facts()]
    text = "\n".join(facts)[-2500:]
    return f"\n\nWhat you know about the user (their memory file; may be out of date):\n{text}" if text else ""


def consolidate_memory(since: str | None, until: str) -> int:
    """Once a night: ask the model for up to five durable facts from the finished days' chats."""
    path = CHAT_DIR / "chat-history.jsonl"
    models = ollama_models()
    if not path.exists() or not models:
        return 0
    since = since or (dt.datetime.fromisoformat(until) - dt.timedelta(days=1)).isoformat(timespec="seconds")
    chats = []
    for line in path.read_text(errors="ignore").splitlines():
        try:
            c = json.loads(line)
        except ValueError:
            continue
        if since < c.get("at", "") < until and "recall_chats" not in (c.get("tools") or []):
            chats.append(f"User: {c.get('you', '')[:400]}\nAssistant: {c.get('blacklab', '')[:400]}")
    if not chats:
        return 0
    with task_log("Daily memory update from today's chats", "BlackLab Desktop (scheduled)") as log:
        model = default_model(models)
        existing = "\n".join(memory_facts()) or "(empty)"
        prompt = ("You maintain a short memory file about a user. You already know they are a Director of Facility Operations "
                  "and a student: don't write that down.\n"
                  f"Current memory:\n{existing}\n\nToday's conversations:\n" + "\n\n".join(chats)[-12000:] +
                  "\n\nList up to 5 NEW facts that will still matter in future conversations: people and vendors, equipment and "
                  "sites, preferences, recurring duties, courses. Skip one-off requests, anything already in the memory, and "
                  "file names, guesses, and anything about patients, clients or anyone's health. Only write facts the user "
                  "stated. One short fact per line, each starting with '- '. "
                  "If there is nothing worth keeping, answer NONE.")
        task_step("model", f"Asked {model} for durable facts", f"{len(chats)} exchange(s) since {since}")
        r = http_json(f"{OLLAMA}/api/generate", {"model": model, "prompt": prompt, "stream": False, "think": False, "keep_alive": keep_alive(),
                                                 "options": {"temperature": 0.2, "num_predict": 300, "num_ctx": num_ctx()}}, timeout=170)
        text = re.sub(r"<think>.*?</think>", "", r.get("response", ""), flags=re.S)
        facts = [ln[2:].strip()[:200] for ln in text.splitlines() if ln.startswith("- ")][:5]
        facts = [redact(f) for f in facts if not CLINICAL_RE.search(f) and
                 not re.search(r"\b(suggest\w*|indicat\w*|potential(?:ly)?|likely|possibly|appears?|seems?|implies)\b|\.md\b", f, re.I)]
        added = memory_add(facts, "learned")
        if log is not None:
            log.reply = ("Added to memory.md:\n" + "\n".join("- " + f for f in added)) if added else "Nothing new worth remembering."
            log.source = f"Desktop AI · {model} (scheduled)"
        return len(added)


def memory_keeper():
    state_path = CACHE_DIR / "memory-state.json"
    time.sleep(120)
    while True:
        try:
            now = dt.datetime.now()
            st = load_json(state_path, {})
            midnight = dt.datetime.combine(now.date(), dt.time()).isoformat(timespec="seconds")
            if CONFIG.get("memory_auto", True) is not False and CONFIG.get("chat_history", True) is not False \
                    and now.hour >= 2 and st.get("last") != now.date().isoformat() and not agents.mining_active():
                consolidate_memory(st.get("since"), midnight)  # whole days only, up to last midnight
                CACHE_DIR.mkdir(parents=True, exist_ok=True)
                state_path.write_text(json.dumps({"last": now.date().isoformat(), "since": midnight}))
        except Exception as e:  # Ollama busy or offline: try again later
            print(f"memory update skipped: {e}", file=sys.stderr, flush=True)
        time.sleep(1800)


# ---------------------------------------------------------------- document search
#
# Documents/ and Downloads/ are indexed with a local embedding model (nomic-embed-text through Ollama) in
# ~/.cache/blacklab-desktop/doc-index.json, refreshed incrementally on every search. Nothing leaves the computer.

DOC_INDEX = CACHE_DIR / "doc-index.json"
DOC_ROOTS: list[Path] = []  # extra folders to index (agents.py adds Equipment/Cards)
DOC_EXT = {".md", ".txt", ".csv", ".html", ".htm", ".json", ".log", ".pdf", ".tsv", ".xml"}
_DOC_LOCK = threading.Lock()


def embed_model() -> str:
    return CONFIG.get("embed_model", "nomic-embed-text")


def _doc_text(p: Path) -> str:
    if p.suffix.lower() == ".pdf":
        if not shutil.which("pdftotext"):
            return ""
        return subprocess.run(["pdftotext", "-layout", str(p), "-"], capture_output=True, text=True, timeout=60).stdout
    t = p.read_text(errors="ignore")
    if p.suffix.lower() in (".html", ".htm"):
        t = htmllib.unescape(re.sub(r"<[^>]+>", " ", re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", t)))
    return t


def _chunks(text: str, size=1200, overlap=200) -> list[str]:
    text = re.sub(r"[ \t]+", " ", re.sub(r"\n{3,}", "\n\n", text)).strip()
    out, i = [], 0
    while i < len(text) and len(out) < 300:
        end = min(len(text), i + size)
        if end < len(text):  # end on a paragraph or sentence when one is near
            cut = max(text.rfind("\n\n", i + size // 2, end), text.rfind(". ", i + size // 2, end))
            end = cut + 1 if cut > 0 else end
        out.append(text[i:end].strip())
        if end >= len(text):
            break
        i = max(end - overlap, i + 1)
    return [c for c in out if len(c) > 40]


def embed(texts: list[str]) -> list[list[float]]:
    vecs = []
    for i in range(0, len(texts), 32):
        r = http_json(f"{OLLAMA}/api/embed", {"model": embed_model(), "input": texts[i:i + 32], "keep_alive": "10m"}, timeout=180)
        vecs += r.get("embeddings", [])
    return vecs


def update_doc_index() -> dict:
    with _DOC_LOCK:
        idx = load_json(DOC_INDEX, {})
        if idx.get("model") != embed_model():
            idx = {"model": embed_model(), "files": {}}
        seen, changed = set(), False
        for root in dict.fromkeys((DOCS_DIR, DOWNLOADS_DIR, *DOC_ROOTS)):
            if not root.is_dir():
                continue
            for f in root.rglob("*"):
                try:
                    st = f.stat()
                except OSError:
                    continue
                if not f.is_file() or f.suffix.lower() not in DOC_EXT or st.st_size > 5_000_000 or len(seen) >= 3000:
                    continue
                key, sig = str(f), f"{st.st_mtime_ns}:{st.st_size}"
                seen.add(key)
                if idx["files"].get(key, {}).get("sig") == sig:
                    continue
                try:
                    chunks = _chunks(_doc_text(f))
                    vecs = embed(["search_document: " + c for c in chunks]) if chunks else []
                except (OSError, subprocess.SubprocessError):
                    chunks, vecs = [], []
                idx["files"][key] = {"sig": sig, "chunks": chunks, "vecs": [[round(x, 5) for x in v] for v in vecs]}
                changed = True
        for key in [k for k in idx["files"] if k not in seen]:
            del idx["files"][key]
            changed = True
        if changed:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            fd = os.open(DOC_INDEX.with_suffix(".tmp"), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as out:
                json.dump(idx, out)
            os.replace(DOC_INDEX.with_suffix(".tmp"), DOC_INDEX)
        return idx


def doc_search(query: str, k=4) -> list[tuple[float, str, str]]:
    """Best (score, path, passage) matches from Documents/ and Downloads/."""
    if not any(m.split(":")[0] == embed_model().split(":")[0] for m in ollama_models(include_embed=True)):
        raise RuntimeError(f"the embedding model isn't installed: run `ollama pull {embed_model()}`")
    idx = update_doc_index()
    qv = embed(["search_query: " + query])[0]
    hits = []
    for path, f in idx["files"].items():
        for c, v in zip(f["chunks"], f["vecs"]):
            hits.append((sum(a * b for a, b in zip(qv, v)), path, c))
    hits.sort(key=lambda h: h[0], reverse=True)
    best, files = [], set()
    for h in hits:  # at most two passages per file
        if sum(1 for b in best if b[1] == h[1]) < 2:
            best.append(h)
            files.add(h[1])
        if len(best) >= k:
            break
    for path in files:
        task_file("read", path)
    return best


@logged_tool("equipment")
def tool_equipment(args):
    q = (args.get("query") if isinstance(args, dict) else str(args or "")) or ""
    return agents.equipment_lookup(q) if agents.C else "The equipment registry isn't loaded."


@logged_tool("search_documents")
def tool_search_documents(args):
    q = (args.get("query") if isinstance(args, dict) else str(args or "")) or ""
    try:
        hits = doc_search(q)
    except Exception as e:
        return f"Document search isn't available: {e}"
    if not hits or hits[0][0] < 0.45:
        return f"Nothing in {_home_rel(DOCS_DIR)} or {_home_rel(DOWNLOADS_DIR)} closely matches “{q}”."
    return "\n\n".join(f"[{_home_rel(p)}] (match {s:.2f})\n{_short(c, 900)}" for s, p, c in hits if s >= 0.4)


def answer_from_docs(question: str) -> tuple[str, str]:
    try:
        hits = [h for h in doc_search(question, 5) if h[0] >= 0.4]
    except Exception as e:
        return f"Document search isn't available: {e}", "Desktop · documents"
    if not hits:
        return f"I couldn't find anything about that in {_home_rel(DOCS_DIR)} or {_home_rel(DOWNLOADS_DIR)}.", "Desktop · documents"
    hits = [h for h in hits if h[0] >= hits[0][0] - 0.1]  # only passages close to the best match
    sources = list(dict.fromkeys(_home_rel(p) for _, p, _ in hits))
    models = ollama_models()
    if not models:
        return "\n\n".join(f"[{_home_rel(p)}]\n{_short(c, 600)}" for _, p, c in hits), "Desktop · documents"
    model = default_model(models)
    passages = "\n\n".join(f"[{_home_rel(p)}]\n{c}" for _, p, c in hits)
    task_step("model", f"Asked {model} to answer from {len(sources)} document(s)", ", ".join(sources))
    r = http_json(f"{OLLAMA}/api/generate", {
        "model": model, "stream": False, "think": False, "keep_alive": keep_alive(), "options": {"temperature": 0.2, "num_predict": 400, "num_ctx": num_ctx()},
        "prompt": f"Answer the question using only these passages from the user's own files. Be brief. If they don't answer it, say so.\n\n"
                  f"{passages}\n\nQuestion: {question}"}, timeout=170)
    text = re.sub(r"<think>.*?</think>", "", r.get("response", ""), flags=re.S).strip()
    return text + "\n\nSources: " + "; ".join(sources), f"Desktop AI · {model} · your documents"


# ---------------------------------------------------------------- desktop tools

@logged_tool("system_status")
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


@logged_tool("disk_hogs")
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


@logged_tool("find_files")
def tool_find_files(args):
    q = (args.get("query") if isinstance(args, dict) else str(args)).lower().strip()
    if not q:
        return "What should I look for?"
    hits, scanned, start = [], 0, time.time()
    records = {str(TASK_LOG_DIR), str(CHAT_DIR)} | {str(p) for p in PROTECTED}  # own records match every request; secrets never show
    for root, dirs, files in os.walk(HOME):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".") and os.path.join(root, d) not in records]
        for f in files:
            scanned += 1
            if q in f.lower() and not PROTECTED_NAME_RE.search(f):
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
           "videos": "Videos", "home": "", "blacklab": "Desktop/BlackLab"}
for _dir, _names in ((AI_DIR, ("blacklab ai", "ai folder")),
                     (DOWNLOADS_DIR, ("ai downloads", "blacklab downloads", "phone downloads", "blacklab-desktop")),
                     (DOCS_DIR, ("ai documents", "ai docs", "blacklab documents", "notes", "ai notes")),
                     (CHAT_DIR, ("chat history", "chats", "ai chats", "chat archive")),
                     (CHAT_DIR / "memory.md", ("memory", "my memory", "ai memory", "memory file")),
                     (TASK_LOG_DIR, ("task logs", "task log", "ai logs", "ai task logs"))):
    if str(_dir.resolve()).startswith(str(HOME) + "/"):
        for _k in _names:
            FOLDERS[_k] = str(_dir.resolve().relative_to(HOME))
def build_open_folder_re():
    return re.compile(r"^open (?:my |the )?(" + "|".join(sorted((re.escape(k) for k in FOLDERS if k), key=len, reverse=True)) + r")(?: folder)?$")


OPEN_FOLDER_RE = build_open_folder_re()


@logged_tool("open_path")
def tool_open_path(args):
    p = args.get("path") if isinstance(args, dict) else str(args)
    key = p.lower().strip().strip("~/").replace(" folder", "")
    target = safe_home_path("~/" + FOLDERS[key]) if key in FOLDERS else safe_home_path(p)
    if not target or not target.exists():
        return f"Couldn't find {p} in your home folder."
    xdg_open(str(target))
    task_file("opened", target)
    return f"Opened {target.relative_to(HOME) if target != HOME else '~'}."


@logged_tool("open_url")
def tool_open_url(args):
    url = args.get("url") if isinstance(args, dict) else str(args)
    if not re.match(r"^https?://", url or ""):
        url = "https://" + url
    xdg_open(url)
    task_file("opened link", url)
    return f"Opened {url}."


@logged_tool("launch_app")
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
            if len(name) >= 3 and name in label and best is None:
                best = f
        if best is not None and (best.stem.lower() == name):
            break
    if not best:
        return f"No app called “{name}” found."
    subprocess.Popen(["gtk-launch", best.stem], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return f"Launching {best.stem}."


@logged_tool("desktop_note")
def tool_desktop_note(args):
    text = args.get("text") if isinstance(args, dict) else str(args)
    title = (args.get("title") if isinstance(args, dict) else "") or " ".join(text.split()[:6])
    folder = DOCS_DIR
    _private_dir(folder)
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:50] or "note"
    path = folder / f"{dt.date.today():%Y-%m-%d}-{slug}.md"
    existed = path.exists()
    path.write_text(f"# {title}\n\n{text}\n\n_{dt.datetime.now():%Y-%m-%d %H:%M} · BlackLab Desktop_\n")
    task_file("edited" if existed else "created", path)
    return f"Saved to {_home_rel(path)}."


@logged_tool("read_file")
def tool_read_file(args):
    p = safe_home_path(args.get("path") if isinstance(args, dict) else str(args))
    if not p or not p.is_file():
        return "File not found in your home folder."
    if p.stat().st_size > 400_000:
        return "That file is too big to read here."
    task_file("read", p)
    return p.read_text(errors="ignore")[:6000]


@logged_tool("dev_docs")
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


@logged_tool("phone_command")
def tool_phone(args, phone):
    text = args.get("command") if isinstance(args, dict) else str(args)
    r = phone_call(phone, "/api/assistant", {"text": text})
    return r.get("text", "")


@logged_tool("pull_from_phone")
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
            dest = DOWNLOADS_DIR / f["path"]
            if dest.exists() and dest.stat().st_size == f["size"]:
                skipped += 1
                continue
            if not dest.resolve().is_relative_to(DOWNLOADS_DIR.resolve()):
                continue  # never let a phone-supplied path escape the Downloads folder
            dest.parent.mkdir(parents=True, exist_ok=True)
            url = phone["url"].rstrip("/") + "/api/file?" + urllib.parse.urlencode({"path": f["path"]})
            req = urllib.request.Request(url, headers={"Authorization": "Bearer " + phone.get("token", "")})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as out:
                shutil.copyfileobj(r, out)
            task_file("downloaded", dest)
            got += 1
            total += f["size"]

    for rel in roots[kind]:
        walk(rel)
    return f"Pulled {got} new {kind} file(s) ({total / 1e6:.1f} MB) into {_home_rel(DOWNLOADS_DIR)}; {skipped} already up to date."


# ---------------------------------------------------------------- assistant

PRONOUNS = {"it", "that", "this", "them", "those", "these", "one", "the first one", "the last one", "that one", "the file", "the folder"}
PHONE_WORDS = guards.PHONE_WORDS


@logged_tool("vault_search")
def tool_vault_search(args):
    import vault_recall
    r = vault_recall.ask(VAULT, args.get("query", "") if isinstance(args, dict) else str(args), 6, local=True, config=CONFIG)
    hits = r.get("results") or []
    if not hits:
        return "Nothing in the vault matches that."
    return f"(search: {r.get('engine')})\n\n" + "\n\n".join(f"• {h['path']}" + (f" (relevance {h['score']})" if h.get("score") is not None else "")
                                                     + f"\n  {h['text'][:400]}" for h in hits)


@logged_tool("skills_search")
def tool_skills_search(args):
    import agent_skills
    hits = agent_skills.search(args.get("query", "") if isinstance(args, dict) else str(args), 6)
    return "\n".join(f"• {h['name']} ({h['library']}): {h['description'][:220]}" for h in hits) or "No agent skill matches that."


@logged_tool("skill_use")
def tool_skill_use(args):
    import agent_skills
    return agent_skills.load(args.get("name", "") if isinstance(args, dict) else str(args))


@logged_tool("vault_write")
def tool_vault_write(args):
    a = args if isinstance(args, dict) else {"text": str(args)}
    folder = (a.get("folder") or vaultlib.FOLDERS["inbox"]).strip("/")
    title = VAULT.safe_name(a.get("title") or a.get("text", "").split("\n")[0][:60], "Note")
    r = VAULT.write_note(f"{folder}/{title}.md", a.get("text", ""), "append", author="Desktop AI")
    if r.get("ok"):
        task_file("created", VAULT.path(r["path"]))
        VAULT.commit(min_gap=0)
        return f"Saved to the vault: {r['path']}"
    return r.get("error", "Couldn't save that.")


RE_HOWTO_RE = re.compile(r"^(?:how (?:do|can|would|should) (?:i|we|you)|how to|what(?:'s| is) the (?:command|way)|can (?:rea|ghidra)|does rea|which rea|"
                         r"what rea command)\b.*\b(?:rea|ghidra|reverse[- ]engineer\w*|decompil\w*)\b", re.I)
RE_CODE_FOR_RE = re.compile(r"^(?:please )?(?:decompile|show(?: me)?|find|where(?:'s| is))\s+(?:the |all )?(?:function|code|routine|logic)s?\s+(?:that|which|where it)?\s*"
                            r"(?:prints?|shows?|uses?|handles?|checks?|reads?|writes?|sends?|opens?|parses?|builds?|validates?|for)\s+(?:the |a |an )?(.+?)\s+(?:in|of|inside)\s+(.+)$", re.I)
RE_REPORT_RE = re.compile(r"^(?:please )?(?:reverse[- ]?engineer|triage|(?:do|run|write) (?:a |an )?(?:full )?(?:reverse[- ]engineering|re) (?:report|pass|triage) (?:on|of|for))\s+(?:the |this |my )?(?:file |binary |program |app |apk |executable |firmware )?(.+)$", re.I | re.S)
VAULT_SHOW_RE = re.compile(r"^(?:show|open|pull up|find)\s+(?:me\s+)?(?:(.+?)\s+(?:in|on)\s+(?:my |the )?(?:obsidian |vault |knowledge )?graph|(?:my |the )?(?:obsidian |vault |knowledge )?graph)\s*$", re.I)


def show_in_graph(query: str) -> str:
    """Opens Obsidian on this computer at a note's local graph (or the whole graph) through Advanced URI."""
    import vault_graph
    if not query.strip():
        xdg_open(vault_graph.graph_uri(VAULT.name))
        return "Opening your whole graph in Obsidian."
    rel = vault_graph.find_note(VAULT, query)
    if not rel:
        return f"Nothing in the vault matches “{query}”."
    xdg_open(vault_graph.local_graph_uri(VAULT.name, rel))
    return f"Opening {Path(rel).stem} in Obsidian with its local graph."


VAULT_SAVE_RE = re.compile(r"^(?:save|add|put|capture|log|note)(?: this| that)? (?:to|in|into|on) (?:my |the )?(?:obsidian|vault)(?: vault)?[:,\s-]+(.{3,})$", re.I | re.S)
VAULT_FIND_RE = re.compile(r"^(?:search|find|look (?:up|for)|what(?:'s| is| do i have)) (?:in )?(?:my |the )?(?:obsidian|vault)(?: vault)?(?: for| about)?[:\s]+(.+)$", re.I)


def private_drive():
    import drive_private
    return drive_private.PrivateDrive(VAULT if VAULT_ON else None, CONFIG)


def desktop_rules(text: str, phone):
    t = text.strip()
    low = t.lower()
    import drive_private
    parsed = drive_private.parse(t)
    if parsed:
        r = private_drive().save(parsed[1], parsed[0], source="BlackLab · " + ("phone" if phone else "desktop"))
        return (r.get("message") or r.get("error")), "Desktop · private drive"
    if VAULT_ON:
        m = VAULT_SAVE_RE.match(t)
        if m:
            return tool_vault_write({"text": m.group(1)}), "Desktop · vault"
        m = VAULT_FIND_RE.match(t)
        if m:
            return tool_vault_search({"query": m.group(1)}), "Desktop · vault"
        m = VAULT_SHOW_RE.match(t)
        if m:
            return show_in_graph(m.group(1) or ""), "Desktop · vault graph"
        if re.search(r"what'?s new in (?:my |the )?(?:obsidian|vault)|vault status", low):
            st = VAULT.status()
            return (f"Your vault has {st['notes']} notes and {st['commits']} saved versions; {st['today']} things logged today. "
                    + (f"Last AI visitor: {st['last_visitor']['agent']} ({st['last_visitor']['when']})." if st.get("last_visitor") else "")), "Desktop · vault"
    if re.search(r"^(?:rea|reverse[- ]engineering|ghidra|decompiler) (?:status|doctor|check)\??$|^is (?:rea|ghidra|reverse engineering) (?:installed|ready|working)\??$", low):
        return reverse.status_text(), "Desktop · reverse engineering"
    if RE_HOWTO_RE.search(low) and not reverse.path_in(t):
        return reverse.answer_howto(t), "Desktop · REA docs"
    m = RE_CODE_FOR_RE.match(t)
    if m and reverse.path_in(m.group(2)):
        target, why = reverse.resolve_target(reverse.path_in(m.group(2)))
        if not target:
            return why, "Desktop · reverse engineering"
        return reverse.find_code(target, m.group(1).strip(" \"'")), "Desktop · reverse engineering (REA + Ghidra)"
    m = RE_REPORT_RE.match(t)
    if m:
        path = reverse.path_in(m.group(1))
        if path:
            target, why = reverse.resolve_target(path)
            if not target:
                return why, "Desktop · reverse engineering"
            goal = re.sub(r"^\s*(?:and|to|then)\s+", "", m.group(1).replace(path, "").strip(" \"',.;:"), flags=re.I)
            j = reverse.submit_job(target, goal)
            return (f"On it — a full reverse-engineering pass on {target.name} is running in the background (job {j['id']})"
                    + (f", aimed at: {goal}" if goal else "") + f". The report lands in {_home_rel(reverse.RE_DIR)}."), "Desktop · reverse engineering"
    if re.search(r"\b(system|computer|pc|desktop) (status|health|info)\b|^status$|how('s| is) (my )?(computer|pc|system)", low):
        return tool_system_status(), "Desktop · rules"
    if re.search(r"(disk|storage) (space|usage|hogs?)|using (my )?(disk|storage)|what('s| is) taking (up )?space", low):
        return tool_disk_hogs(), "Desktop · rules"
    m = re.match(r"^(?:find|search for|look for|locate) (?:files? )?(?:named |called |like )?(.+?)(?: on (?:my )?(?:computer|desktop|pc))?$", low)
    if m and not PHONE_WORDS.search(low):
        return tool_find_files({"query": m.group(1)}), "Desktop · rules"
    m = OPEN_FOLDER_RE.match(low)
    if m:
        return tool_open_path({"path": m.group(1)}), "Desktop · rules"
    m = re.match(r"^(?:please )?remember(?: that|:)\s+(.+)$", t, re.I | re.S)
    if m:
        if CLINICAL_RE.search(m.group(1)):
            return "I don't keep patient or health details in memory. Nothing was saved.", "Desktop · memory"
        added = memory_add([redact(m.group(1).strip())])
        return (f"Got it, I'll remember that. ({_home_rel(MEMORY_FILE)})" if added else "I already had that one."), "Desktop · memory"
    m = re.match(r"^(?:please )?forget(?: that| about)?:?\s+(.+?)\.?$", t, re.I)
    if m:
        gone = memory_forget(m.group(1))
        return ("Forgot:\n" + "\n".join(gone) if gone else f"Nothing in memory matches “{m.group(1)}”. It's all in {_home_rel(MEMORY_FILE)}."), "Desktop · memory"
    if re.match(r"^(?:what do you (?:remember|know) about me|show (?:me )?(?:my |your )?memory)\??$", low):
        facts = memory_facts()
        return ("Here's what I remember:\n" + "\n".join(facts) if facts else "My memory's empty. Say “remember that …” to add something."), "Desktop · memory"
    m = re.match(r"^(?:search|ask|check) (?:my |the )?(?:ai |blacklab )?(?:documents|docs|downloads)\b(?: for| about)?[:,\s]+(.+)$", t, re.I | re.S)
    if m:
        return answer_from_docs(m.group(1).strip())
    m = re.match(r"^(?:(?:what'?s|show(?: me)?|give me) )?(?:the )?(?:history|record|info|details|nameplate) (?:on|for|of) (.+?)\??$|^(?:show |list )?(?:my |all |the )?equipment(?: list)?(?: for| matching)?\s*(.*?)\??$", low)
    if m and (m.group(1) or m.group(2) is not None):
        q = (m.group(1) or m.group(2) or "all").strip()
        if m.group(2) is not None or agents.EQUIP_DB.exists():
            r = tool_equipment({"query": q})
            if m.group(2) is not None or not r.startswith("No equipment matches"):
                return r, "Desktop · equipment"
    if re.match(r"^(?:crypto|crypto desk|trading|desk) (?:status|summary|positions?)\??$|^how(?:'s| is) (?:my )?crypto(?: desk)? doing\??$", low):
        desk = Path(os.path.expanduser(CONFIG.get("crypto_desk_cmd", "~/.local/bin/desk")))
        if not desk.exists():
            return "Crypto Desk isn't installed on this computer.", "Desktop · crypto"
        try:
            out = subprocess.run([str(desk), "status"], capture_output=True, text=True, timeout=60).stdout.strip()
        except (OSError, subprocess.SubprocessError) as e:
            out = f"Crypto Desk didn't answer: {e}"
        task_step("tool", "Crypto Desk status (read-only)")
        return out or "Crypto Desk returned nothing.", "Desktop · Crypto Desk"
    m = re.match(r"^which (?:units|equipment|machines)\b(.*?)\??$", low)
    if m:
        return tool_equipment({"query": m.group(1)}), "Desktop · equipment"
    m = re.match(r"^(?:show|search|check) (?:my |the )?chat history(?: for| about)?\s*(.*)$", low)
    if m:
        return tool_recall_chats({"query": m.group(1)}), "Desktop · chat history"
    m = re.match(r"^(?:open|go to|browse to) (https?://\S+|[\w-]+\.(?:com|org|net|io|dev|edu|gov)\S*)$", low)
    if m:
        return tool_open_url({"url": m.group(1)}), "Desktop · rules"
    m = re.match(r"^(?:launch|start|open) (?:the )?(?:app )?([\w .+-]+?)(?: app)?$", low)
    if m and not PHONE_WORDS.search(low) and m.group(1) not in FOLDERS and m.group(1) not in PRONOUNS:
        r = tool_launch_app({"name": m.group(1)})
        if not r.startswith("No app"):
            return r, "Desktop · rules"
    m = re.match(r"^(?:make |save |create |write )?(?:a )?desktop (?:note|document|doc)[:\s]+(.+)$", t, re.I | re.S)
    if m:
        named = re.match(r"^(?:titled|called|named)\s+(.+?)\s*[:\n]\s*(.+)$", m.group(1), re.I | re.S)
        args = {"title": named.group(1).strip(" \"'"), "text": named.group(2)} if named else {"text": m.group(1)}
        return tool_desktop_note(args), "Desktop · rules"
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
    {"type": "function", "function": {"name": "desktop_note", "description": "Write a note or document (Markdown) into the user's BlackLab AI Documents folder on this computer.", "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "text": {"type": "string"}}, "required": ["text"]}}},
    {"type": "function", "function": {"name": "recall_chats", "description": "Search earlier conversations with the user (the chat history) when they refer to something discussed before.",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string", "description": "Key words, plus 'today' or 'yesterday' to limit the day"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "search_documents", "description": "Search inside the user's own documents and downloads (notes, reports, PDFs, phone files) for a topic.",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "equipment", "description": "Look up the user's equipment registry: a unit's nameplate data and history (work orders, meetings, photos, repeat issues), or list units matching words like a type, make, location or refrigerant.",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string", "description": "A tag like RTU-4, a serial, or words like 'R-22' or 'chiller'; 'all' lists everything"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "vault_search", "description": "Search the user's Obsidian vault (their second brain: facility assets and logs, projects, meetings, daily timelines, notes).",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "vault_write", "description": "Save a note into the user's Obsidian vault (00 Inbox unless a folder is named). Only when the user asks to save, note or log something there.",
                                      "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "text": {"type": "string"}, "folder": {"type": "string"}}, "required": ["text"]}}},
    {"type": "function", "function": {"name": "library_search", "description": "Search the offline reference library (~50 GB: Wikipedia, Stack Exchange, LibreTexts, Wikibooks, iFixit, MedlinePlus, Ready.gov, USDA canning, survival and field manuals) for facts and how-tos.",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "reverse_engineer", "description": (
        "Reverse-engineer a program or file on this computer with REA and Ghidra (read-only: it never runs the target). "
        "To see the code behind a message, option or feature, use 'find_code' with that text as query (it finds the string, follows the "
        "references and decompiles them in one step). 'overview' summarizes a file; 'strings' / 'functions' list them; 'decompile' / 'xrefs' / "
        "'disassemble' need an address you got from an earlier result (never invent one). "
        "'inspect' runs the format parsers (APK, .NET, Python .pyc, WASM, Go/Rust build info, kernel modules, model files). "
        "'report' writes a full report in the background. 'help' answers how-to questions about REA from its docs; 'status' says what's installed."),
        "parameters": {"type": "object", "properties": {
            "action": {"type": "string", "enum": list(reverse.ACTIONS)},
            "path": {"type": "string", "description": "The file or app folder, e.g. ~/Downloads/tool.bin (not needed for help/status)"},
            "query": {"type": "string", "description": "Address (0x401000) or function name for decompile/xrefs/disassemble; text for search; the question for report/help"},
            "kind": {"type": "string", "enum": ["strings", "procedures"], "description": "What 'search' looks through"}},
            "required": ["action"]}}},
    {"type": "function", "function": {"name": "skills_search", "description": "Find agent skills (expert playbooks from Anthropic, Vercel, OpenAI, Google and Hugging Face) for a task: documents (PDF, Word, Excel, PowerPoint), web/app design, React/Next.js, Google Cloud, Vercel, Hugging Face models and datasets, MCP servers, writing.",
                                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "skill_use", "description": "Load one agent skill's instructions (by name from skills_search) and follow them for the user's request. Never run a skill's scripts; show the commands instead.",
                                      "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {"name": "dev_docs", "description": "Search Google's official Android/Firebase/Cloud developer documentation (needs internet).", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
]


TOOL_NAMES = {t["function"]["name"] for t in TOOLS}


SIDE_EFFECT_ASKS = guards.SIDE_EFFECT_ASKS
READS_CONTENT = guards.READS_CONTENT
_url_host = guards.url_host
tool_guard = guards.tool_guard


def run_tool(name, args, phone):
    fns = {"system_status": tool_system_status, "find_files": tool_find_files, "read_file": tool_read_file, "open_path": tool_open_path,
           "open_url": tool_open_url, "launch_app": tool_launch_app, "desktop_note": tool_desktop_note, "dev_docs": tool_dev_docs,
           "recall_chats": tool_recall_chats, "search_documents": tool_search_documents, "equipment": tool_equipment,
           "vault_search": tool_vault_search, "vault_write": tool_vault_write, "library_search": tool_library_search,
           "reverse_engineer": tool_reverse_engineer, "skills_search": tool_skills_search, "skill_use": tool_skill_use}
    try:
        if name == "phone_command":
            return tool_phone(args, phone)
        return fns[name](args or {})
    except Exception as e:
        return f"{name} failed: {e}"


def _skill_hint(text: str) -> str:
    try:
        import agent_skills
        return agent_skills.hint(text)
    except Exception:
        return ""


def _skill_seed(text: str) -> tuple:
    """'use the pdf skill to …' — the model starts with that skill loaded, as if it had called skill_use."""
    try:
        import agent_skills
        s = agent_skills.named_in(text)
        return (("skill_use", {"name": s["name"]}, agent_skills.load(s["name"])),) if s else ()
    except Exception:
        return ()


def ollama_chat(text, model, phone, history=(), use_memory=True, rounds=4, seed=()):
    seed = tuple(seed) + _skill_seed(text)
    tools = [t for t in TOOLS if phone or t["function"]["name"] != "phone_command"]
    system = ("You are BlackLab, the user's laid-back, funny but sharp personal assistant, running offline on their Linux desktop. "
              "The user is a Director of Facility Operations who is also a student. "
              f"Their home folder is {HOME}; your own files are in {_home_rel(AI_DIR)} (Downloads, Documents, Chat History, Task Logs). "
              "Use a tool only when the request needs it, and only the kind of action the user asked for. "
              "Answer arithmetic, unit conversions and general questions directly, with no tools. "
              + ("Use phone_command only for things in the BlackLab phone app the user asked about (tasks, calendar, notes, journal, recordings, email, Drive). " if phone else "")
              + "Use search_documents for questions about the user's own documents or files, and recall_chats when they refer to an earlier conversation. "
              "For reverse engineering (binaries, apps, APKs, firmware, decompiling, 'what does this program do') use reverse_engineer: one step at a time, "
              "reusing addresses from earlier results, and say which evidence each claim comes from. Never write, rewrite or 'simplify' code yourself: "
              "quote only code a tool returned; if you haven't seen the code, call find_code or say you haven't. Only analyze software the user owns or may examine. "
              "When a request matches an agent skill, load it with skill_use and follow it. "
              "Keep replies short. Today is " + dt.datetime.now().strftime("%A, %B %d %Y, %I:%M %p") + "." + _skill_hint(text)
              + (memory_prompt() if use_memory else ""))
    msgs = [{"role": "system", "content": system}]
    for u, a in history or ():
        msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": a}]
    msgs.append({"role": "user", "content": text})
    used = []
    tainted = False
    outputs: list[str] = []
    for name, args, result in seed:  # results the model starts with (as if it had called the tool)
        msgs += [{"role": "assistant", "content": "", "tool_calls": [{"function": {"name": name, "arguments": args}}]},
                 {"role": "tool", "content": result[:4000], "tool_name": name}]
        used.append(name)
        outputs.append(result)
        tainted = tainted or name in READS_CONTENT
    task_step("model", f"Asked {model}", f"system prompt + {len(history or ())} earlier turn(s) + request; tools offered: {', '.join(t['function']['name'] for t in tools)}")
    for _ in range(rounds):
        r = http_json(f"{OLLAMA}/api/chat", {"model": model, "messages": msgs, "tools": tools, "stream": False, "think": False, "keep_alive": keep_alive(),
                                            "options": {"temperature": 0.3, "num_ctx": num_ctx()}}, timeout=180)
        msg = r.get("message", {})
        calls = msg.get("tool_calls") or []
        if not calls:
            content = re.sub(r"<think>.*?</think>", "", msg.get("content", ""), flags=re.S).strip()
            if "reverse_engineer" in used or guards.RE_WORDS.search(text):
                content = reverse.check_code(content, outputs)  # no invented "decompiled" code
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
            if name not in TOOL_NAMES:
                task_step("model", f"{model} asked for an unknown tool: {name}", args)
            blocked = tool_guard(name, text + " " + " ".join(u for u, _ in (history or ())[-1:]), args, tainted)
            if blocked:
                task_step("guard", f"Blocked {name}: {blocked}", args)
            elif name in READS_CONTENT:
                tainted = True
            result = blocked or str(run_tool(name, args, phone))
            outputs.append(result)
            msgs.append({"role": "tool", "content": result[:4000], "tool_name": name})
    return "I went in circles on that one — try rephrasing?", f"Desktop AI · {model}"


def assistant(body):
    """Every request is one task in the task log, whichever path answers it."""
    origin = body.get("origin") or "BlackLab Desktop (this computer)"
    private, text = privacy_check(body.get("text") or "", bool(body.get("private")))
    body = {**body, "text": text, "history": [] if private else recent_turns(origin), "private": private}
    with task_log(text, origin, private) as log:
        started = dt.datetime.now()
        out = _assistant(body)
        if log is not None:
            log.reply, log.source = out.get("text", ""), out.get("source", "")
        if private:
            out = {**out, "source": (out.get("source") or "Desktop") + " · private, not saved"}
            return out
        remember_turn(origin, text, out.get("text", ""))
        try:
            archive_chat(text, out.get("text", ""), origin, out.get("source", ""), started,
                         [st["title"] for st in (log.steps if log else []) if st["kind"] == "tool"])
        except Exception as e:  # never let the archive break a reply
            print(f"chat not archived: {e}", file=sys.stderr, flush=True)
        return out


def _assistant(body):
    text = (body.get("text") or "").strip()
    target = body.get("target", "auto")
    phone = body.get("phone")
    if not text:
        return {"text": ""}
    if target != "phone":
        r = desktop_rules(text, phone)
        if r:
            task_step("rules", "Handled by the desktop rules (no AI needed)")
            return {"text": r[0], "source": r[1]}
    looks_phone = bool(PHONE_WORDS.search(text))
    if phone and (target == "phone" or (target == "auto" and looks_phone)):
        task_step("relay", "Sent to the phone's assistant")
        r = phone_call(phone, "/api/assistant", {"text": text})
        return {"text": r.get("text", ""), "source": "Phone · " + r.get("source", ""), "links": r.get("links", [])}
    models = ollama_models()
    if models:
        model = body.get("model") if body.get("model") in models else default_model(models)
        rounds, seed = int(body.get("rounds") or 4), []
        if guards.RE_WORDS.search(text):
            rounds = max(rounds, 7)  # overview → strings → xrefs → decompile → answer
            if reverse.path_in(text):  # start from facts about the file, not from what the model imagines it is
                args = {"action": "overview", "path": reverse.path_in(text)}
                seed = [("reverse_engineer", args, str(tool_reverse_engineer(args)))]
        text_out, source = ollama_chat(text, model, phone, body.get("history") or (), use_memory=not body.get("private"),
                                       rounds=min(rounds, 10), seed=seed)
        return {"text": text_out, "source": source}
    if phone:
        task_step("relay", "Ollama is offline: sent to the phone's assistant")
        r = phone_call(phone, "/api/assistant", {"text": text})
        return {"text": r.get("text", ""), "source": "Phone · " + r.get("source", "")}
    task_step("model", "Ollama is offline: asked Gemini (cloud)")
    g = gemini_text("You are BlackLab, a helpful, concise desktop assistant. Answer in 1-4 sentences.\n\nUser: " + text)
    if g:
        return {"text": g, "source": "Gemini (cloud) — Ollama is offline"}
    return {"text": "I can do desktop basics (system status, find files, open folders/apps, notes, pull phone files). For anything else, start Ollama or link the phone.",
            "source": "Desktop · rules"}


def gemini_text(prompt: str, temperature=0.3, max_tokens=400) -> str | None:
    """Cloud fallback when Ollama is down: the Gemini API with the key in secrets.json ("gemini")."""
    key = SECRETS.get("gemini")
    if not key:
        return None
    model = CONFIG.get("gemini_model", "gemini-2.5-flash")
    cfg = {"temperature": temperature, "maxOutputTokens": max_tokens}
    if "2.5" in model and "flash" in model:
        cfg["thinkingConfig"] = {"thinkingBudget": 0}
    try:
        r = http_json(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                      {"contents": [{"role": "user", "parts": [{"text": prompt[:30000]}]}], "generationConfig": cfg},
                      headers={"x-goog-api-key": key}, timeout=60)
        parts = r.get("candidates", [{}])[0].get("content", {}).get("parts", [])
        return "".join(p.get("text", "") for p in parts).strip() or None
    except Exception:
        return None


def phone_generate(body):
    """Raw generation for the phone's AI features (assistant router, summaries, Talk, Ask my files)."""
    models = ollama_models()
    if not models:
        text = gemini_text(str(body.get("prompt", "")), float(body.get("temperature", 0.3)), int(body.get("max_tokens", 320)))
        if text:
            return {"text": text, "model": "gemini (cloud)"}
        raise RuntimeError("Ollama isn't running")
    model = default_model(models)
    r = http_json(f"{OLLAMA}/api/generate", {
        "model": model, "prompt": str(body.get("prompt", ""))[:24000], "stream": False, "think": False, "keep_alive": keep_alive(),
        "options": {"temperature": float(body.get("temperature", 0.3)), "num_predict": int(body.get("max_tokens", 320)), "num_ctx": num_ctx()},
    }, timeout=170)
    text = re.sub(r"<think>.*?</think>", "", r.get("response", ""), flags=re.S).strip()
    return {"text": text, "model": model}


def phone_ask(body):
    """The phone hands the desktop assistant a request for the computer. Phone tools are off here so it can't loop back."""
    who = PHONE.get("model") or "phone"
    r = assistant({"text": body.get("text", ""), "target": "desktop", "phone": None, "origin": f"Phone assistant ({who})",
                   "private": bool(body.get("private"))})
    return {"text": r.get("text", ""), "source": "Desktop · " + r.get("source", "")}


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

    def host_ok(self) -> bool:
        return (self.headers.get("Host") or "").lower() in ALLOWED_HOSTS

    def guard(self) -> bool:
        if not guards.ui_request_ok(self.headers.get("Host"), self.headers.get("Origin"), self.headers.get("X-BlackLab"), ALLOWED_HOSTS, ALLOWED_ORIGINS):
            self.reply(403, {"error": "forbidden"})
            return False
        return True

    def phone_guard(self) -> bool:
        """/phone/* is for the BlackLab app only: our Host, no browser Origin, and the shared key."""
        if not guards.phone_request_ok(self.headers.get("Host"), self.headers.get("Origin"), self.headers.get("Authorization"),
                                       SECRETS.get("phone_ai_token") or "", ALLOWED_HOSTS):
            self.reply(401, {"error": "unauthorized"})
            return False
        if self.headers.get("X-BlackLab-Phone"):  # the app identifies itself; health checks from this computer don't count
            PHONE["last_seen"] = time.time()
            PHONE["model"] = self.headers.get("X-BlackLab-Phone", "")[:60]
        return True

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.end_headers()

    def do_GET(self):
        if not self.host_ok():
            return self.reply(403, {"error": "forbidden"})
        route = urllib.parse.urlsplit(self.path).path
        if route == "/phone/jobs":
            if not self.phone_guard():
                return
            return self.reply(200, {"jobs": agents.pending_for_phone(), "whisper": agents.whisper_ready()})
        if route in ("/local/jobs", "/local/equipment"):
            if not self.guard():
                return
            if route == "/local/jobs":
                jobs = sorted(agents.JOBS.values(), key=lambda j: j["created"], reverse=True)[:60]
                return self.reply(200, {"jobs": [agents.public(j) for j in jobs], "whisper": agents.whisper_ready()})
            return self.reply(200, {"text": agents.equipment_lookup("all")})
        if self.path == "/phone/status":
            if not self.phone_guard():
                return
            models = ollama_models()
            return self.reply(200, {"ok": True, "ollama": bool(models), "model": default_model(models), "models": models, "version": VERSION,
                                    "host": os.uname().nodename, "keep_alive": str(CONFIG.get("keep_alive", "30m"))})
        if self.path.startswith("/local/"):
            if not self.guard():
                return
            if self.path == "/local/status":
                models = ollama_models()
                return self.reply(200, {"companion": True, "version": VERSION, "adb": adb_devices(), "ollama": models, "model": default_model(models),
                                        "phone_ai": {"paired_recently": time.time() - PHONE["last_seen"] < 600, "last_seen": PHONE["last_seen"], "phone": PHONE["model"]},
                                        "eleven": bool(SECRETS.get("elevenlabs")), "devdocs": bool(SECRETS.get("developer_knowledge")), "out": str(DOWNLOADS_DIR),
                                        "folders": {"ai": str(AI_DIR), "downloads": str(DOWNLOADS_DIR), "documents": str(DOCS_DIR),
                                                    "chat_history": str(CHAT_DIR) if CONFIG.get("chat_history", True) is not False else None},
                                        "task_logs": str(TASK_LOG_DIR) if CONFIG.get("task_logs", True) is not False else None,
                                        "agents": agents.status(), "vault": VAULT.status() if VAULT_ON else None, "reverse": reverse.status()})
            return self.reply(404, {"error": "unknown"})
        return super().do_GET()

    def do_POST(self):
        route = urllib.parse.urlsplit(self.path).path
        if route.startswith("/phone/vault"):
            if not self.phone_guard():
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
                if length > 12_000_000:
                    return self.reply(413, {"error": "too large"})
                body = json.loads(self.rfile.read(length) or b"{}")
                return self.reply(200, vault_op(route.removeprefix("/phone/vault").strip("/") or "feed", body, "Phone"))
            except ValueError as e:
                return self.reply(400, {"error": str(e)})
            except Exception as e:
                return self.reply(500, {"error": str(e)})
        if route in ("/phone/upload", "/phone/jobs", "/phone/jobs/ack", "/phone/context"):
            if not self.phone_guard():
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
                if route == "/phone/upload":
                    name = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("name", ["upload"])[0]
                    return self.reply(200, {"upload": agents.save_upload(name, length, self.rfile)})
                if length > 4_000_000:
                    return self.reply(413, {"error": "too large"})
                body = json.loads(self.rfile.read(length) or b"{}")
                if route == "/phone/jobs":
                    who = PHONE.get("model") or "phone"
                    j = agents.submit(body.get("kind", ""), body.get("title", ""), body.get("params") or {}, body.get("upload"),
                                      origin=f"Phone ({who})", private=bool(body.get("private")))
                    LEDGER.record("Phone", f"job:{body.get('kind', '')}", source=who, metadata={"private": bool(body.get("private"))})
                    return self.reply(200, agents.public(j))
                if route == "/phone/jobs/ack":
                    agents.ack([str(i) for i in body.get("ids", [])][:200])
                    return self.reply(200, {"ok": True})
                agents.save_context(body)
                return self.reply(200, {"ok": True})
            except ValueError as e:
                return self.reply(400, {"error": str(e)})
            except Exception as e:
                return self.reply(500, {"error": str(e)})
        if self.path in ("/phone/generate", "/phone/ask"):
            if not self.phone_guard():
                return
            try:
                body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 200_000)) or b"{}")
                return self.reply(200, phone_generate(body) if self.path == "/phone/generate" else phone_ask(body))
            except Exception as e:
                return self.reply(503, {"error": str(e)})
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
                with task_log(f"pull {body.get('kind', '')} from the phone", "BlackLab Desktop (this computer)") as log:
                    msg = pull(body.get("kind", ""), body.get("phone"))
                    if log is not None:
                        log.reply, log.source = msg, "Desktop · phone files"
                return self.reply(200, {"message": msg})
            if self.path == "/local/notify":
                return self.reply(200, agents.public(agents.notice(str(body.get("title", "BlackLab")), str(body.get("text", "")))))
            if self.path == "/local/jobs":
                upload = None
                if body.get("path"):
                    f = safe_home_path(body["path"])
                    if not f or not f.is_file():
                        return self.reply(400, {"error": "file not found in your home folder"})
                    upload = "local-" + hashlib.sha1(str(f).encode()).hexdigest()[:12]
                    agents.UPLOADS[upload] = str(f)
                j = agents.submit(body.get("kind", ""), body.get("title", "") or (Path(body["path"]).name if body.get("path") else ""),
                                  body.get("params") or {}, upload, private=bool(body.get("private")))
                LEDGER.record("This computer", f"job:{body.get('kind', '')}", source="desktop UI", metadata={"private": bool(body.get("private"))})
                return self.reply(200, agents.public(j))
            if self.path.startswith("/local/vault/"):
                return self.reply(200, vault_op(self.path.removeprefix("/local/vault/"), body, "This computer"))
            if self.path == "/local/open":
                return self.reply(200, {"message": tool_open_path({"path": body.get("path", "~")})})
            return self.reply(404, {"error": "unknown"})
        except urllib.error.URLError as e:
            return self.reply(502, {"error": f"Couldn't reach it: {e.reason}"})
        except Exception as e:
            return self.reply(500, {"error": str(e)})


# Vault actions that only read: recorded in the ledger too, but they never commit
READ_OPS = {"skills", "status", "search", "read", "recent", "graph", "nodes", "atlas", "library", "library_status", "history", "drive_status", "ask",
            "panel", "sds", "shift_prep", "credentials", "ledger", "recall_status"}


def vault_op(op: str, body: dict, who: str) -> dict:
    """Every vault action from the phone, this computer's UI and the MCP server — each one a line in the activity ledger."""
    t0 = time.time()
    actor = str(body.get("actor") or body.get("author") or body.get("source") or who)[:60] if isinstance(body, dict) else who
    ok = None
    try:
        r = _vault_op(op, body, who)
        ok = not (isinstance(r, dict) and r.get("ok") is False)
        return r
    finally:
        if op != "checkin" and (op != "feed" or ok):  # check-ins record themselves; the phone's feed only when it worked
            target = str(body.get("path") or body.get("node") or "")[:200] if isinstance(body, dict) else ""
            LEDGER.record(actor, f"vault:{op}", target=target, source=who, duration_ms=int((time.time() - t0) * 1000),
                          metadata={"ok": ok, "read_only": op in READ_OPS})


def local_generate(prompt: str, max_tokens: int = 360) -> str:
    """Local model only (answers from the vault never go to a cloud model)."""
    models = ollama_models()
    if not models:
        raise RuntimeError("the local model (Ollama) isn't running")
    r = http_json(f"{OLLAMA}/api/generate", {"model": default_model(models), "prompt": prompt[:24000], "stream": False, "think": False,
                                             "keep_alive": keep_alive(), "options": {"temperature": 0.2, "num_predict": max_tokens, "num_ctx": num_ctx()}}, timeout=170)
    return re.sub(r"<think>.*?</think>", "", r.get("response", ""), flags=re.S).strip()


def _vault_op(op: str, body: dict, who: str) -> dict:
    if not VAULT_ON:
        return {"ok": False, "error": "The vault is turned off (config.json \"vault\": false)."}
    v = VAULT
    if op == "feed":
        counts = v.apply_feed(body)
        v.commit(f"Phone sync · " + ", ".join(f"{k} {n}" for k, n in counts.items()), min_gap=0)
        return {"ok": True, "counts": counts, "status": v.status()}
    if op == "status":
        return dict(v.status(), ok=True, phone_mirror=PHONE_VAULT)
    if op == "checkin":
        r = v.checkin(body.get("agent", ""), body.get("model", ""), body.get("purpose", ""), body.get("note", ""), where=body.get("where") or who)
    elif op == "search":
        return {"ok": True, "results": v.search(body.get("query", ""), int(body.get("limit") or 8))}
    elif op == "read":
        return {"ok": True, "text": v.read(body.get("path", ""))}
    elif op == "recent":
        import vault_mcp
        return {"ok": True, "text": vault_mcp.recent_text(v, int(body.get("days") or 2))}
    elif op == "write":
        r = v.write_note(body.get("path", ""), body.get("content", ""), body.get("mode") or "append", body.get("author") or who)
    elif op == "log":
        r = {"ok": v.log(body.get("kind") or "note", body.get("title", ""), body.get("detail", ""), body.get("source") or who, body.get("links") or [])}
    elif op in ("capture", "note"):
        r = v.capture(body.get("text", ""), body.get("title", ""), body.get("source") or who)
    elif op == "sync":
        threading.Thread(target=vault_pass, kwargs={"force": True}, daemon=True).start()
        return {"ok": True, "message": "Syncing the vault now."}
    elif op == "open":
        xdg_open(v.status()["open_url"])
        return {"ok": True, "message": "Opening the vault in Obsidian."}
    elif op == "show":
        return {"ok": True, "message": show_in_graph(body.get("query", ""))}
    elif op == "graph":
        import vault_graph
        st = v._state("graph.json", {})
        h = vault_graph.graph_health(vault_graph.scan(v))
        plugins = {pid: (v.path(f".obsidian/plugins/{pid}/main.js").exists(), tag) for pid, (_, tag, _f) in vault_graph.PLUGINS.items()}
        return {"ok": True, "notes": h["notes"], "edges": h["edges"], "orphans": len(h["orphans"]), "unresolved": len(h["unresolved"]),
                "with_up": h["with_up"], "hubs": [t for _, t in h["hubs"][:6]], "related": st.get("related"), "profile": st.get("profile"),
                "plugins": [{"id": k, "installed": ok, "version": tag} for k, (ok, tag) in plugins.items()]}
    elif op == "nodes":
        import vault_nodes
        return {"ok": True, "nodes": vault_nodes.overview(v), "nexus": vault_nodes.NEXUS, "function": vault_nodes.FUNCTION}
    elif op == "node_add":
        import vault_nodes
        r = vault_nodes.add(v, body.get("node", ""), body.get("content", ""), body.get("author") or who)
        if r.get("ok"):
            v.log("note", f"{body.get('author') or who} added to ◆ {r['node']}", "", source=body.get("author") or who, links=[f"◆ {r['node']}"])
    elif op == "drive_save":
        import drive_private
        text, folder = body.get("text", ""), body.get("folder") or None
        if body.get("say"):  # the phone sends the sentence as spoken: "save to my private drive under taxes: …"
            parsed = drive_private.parse(body["say"])
            if not parsed:
                return {"ok": False, "error": "Say it like “save to my private drive: …” (optionally “… under taxes: …”)."}
            folder, text = parsed
        return private_drive().save(text, folder, source=body.get("source") or who)
    elif op == "drive_status":
        return dict(private_drive().status(), ok=True)
    elif op == "node_create":
        import vault_nodes
        r = vault_nodes.create(v, body.get("name", ""), body.get("mission", ""), body.get("emoji") or "◆", body.get("color") or "", body.get("synapses") or [],
                               body.get("keywords") or [], body.get("tags") or [], author=body.get("author") or who, primer=body.get("primer") or "")
        if r.get("ok"):
            v.render_nodes()
    elif op == "atlas":
        import vault_atlas
        if body.get("rescan"):
            threading.Thread(target=atlas_pass, kwargs={"force": True}, daemon=True).start()
        return dict(vault_atlas.summary(v), ok=True, running=ATLAS_RUN["running"], last=ATLAS_RUN["last"], error=ATLAS_RUN["error"])
    elif op == "library":
        import library
        return {"ok": True, "results": library.search(body.get("query", ""), library.books_for(body.get("node"), body.get("subnode")) or None, int(body.get("limit") or 8))}
    elif op == "library_status":
        import library
        return dict(library.status(), ok=True)
    elif op == "phone_mirror":
        devs = [d for d in adb_devices()["devices"] if d["state"] == "device" and not d["emulator"]]
        if not devs:
            return {"ok": False, "error": "The phone isn't linked (plug it in or turn on Wireless debugging)."}
        threading.Thread(target=phone_vault_pass, args=(devs[0]["serial"], True), daemon=True).start()
        return {"ok": True, "message": "Copying the vault to the phone now."}
    elif op == "history":
        return {"ok": True, "commits": v.history(int(body.get("n") or 20))}
    elif op == "ask":  # "what do my notes say about …": semantic recall through kb-core, an answer from the local model
        import vault_recall
        local = who != "Phone" or CONFIG.get("phone_recall_local", False) is True
        if body.get("answer"):
            return vault_recall.answer(v, body.get("query", ""), local_generate, local=local, config=CONFIG, limit=int(body.get("limit") or 5))
        return vault_recall.ask(v, body.get("query", ""), int(body.get("limit") or 6), local=local, config=CONFIG)
    elif op == "recall_status":
        import vault_recall
        return dict(vault_recall.status(v, CONFIG), ok=True)
    elif op == "round":  # "start EOC rounds" — a dated checklist from Paul's template
        import facility_ops
        r = facility_ops.new_round(v, body.get("kind") or body.get("say") or "", by=body.get("source") or who)
    elif op == "panel":
        import facility_ops
        return facility_ops.panel(v, body.get("query", ""))
    elif op == "sds":
        import facility_ops
        return facility_ops.sds(v, body.get("query", ""))
    elif op == "shift_prep":
        import facility_ops
        import vault_credentials
        return facility_ops.shift_prep(v, vault_credentials.due_soon(v))
    elif op == "credentials":
        import vault_credentials
        rows = vault_credentials.merged(v)
        today = v.now().date()
        soon = vault_credentials.due_soon(v, today)
        q = (body.get("query") or "").lower().strip()
        if q:
            hit = [r for r in rows if q in r["name"].lower()]
            if hit:
                r = hit[0]
                return {"ok": True, "message": f"{r['name']}: {vault_credentials.state(r, today)[1].split(' ', 1)[1]}" +
                        (f" (renew by {r['renew'].isoformat()})." if r.get("renew") else ".")}
        return {"ok": True, "count": len(rows), "due_soon": soon,
                "message": (f"{len(rows)} credentials on file. " + ("Needs attention: " + "; ".join(soon) + "." if soon else "Nothing expires in the next 90 days."))}
    elif op == "skills":  # "what skills do you have" / "find a skill for …" / MCP vault_skills
        import agent_skills
        if body.get("action") == "read":
            return {"ok": True, "text": agent_skills.load(body.get("name") or body.get("query") or "", max_chars=40000)}
        return agent_skills.describe(body.get("query") or "")
    elif op == "ledger":
        return dict(LEDGER.verify(), summary={k: x for k, x in LEDGER.summary(int(body.get("days") or 7)).items() if k != "recent"})
    else:
        raise ValueError(f"unknown vault action: {op}")
    v.commit(min_gap=0)
    return r


VAULT_PROJECTS = [Path(os.path.expanduser(p)) for p in CONFIG.get("vault_projects", ["~/Desktop/BlackLab"])]
VAULT_WATCH = [Path(os.path.expanduser(p)) for p in CONFIG.get("vault_watch", ["~/Desktop", "~/Documents", "~/Downloads"])]


def vault_pass(force=False):
    """One full desktop pass: meetings, briefs, task logs, conversations, projects, new files, Home, a commit."""
    v = VAULT
    with v.lock:
        v.ensure()
        f = vaultlib.FOLDERS
        n = v.mirror_folder(DOCS_DIR / "Meetings", f"{f['meetings']}/Minutes", "meeting")
        n += v.mirror_folder(DOCS_DIR / "Briefs", f"{f['meetings']}/Briefs", "brief")
        n += v.mirror_folder(TASK_LOG_DIR, f"{f['ai']}/Task Logs", "task-log", since_days=60)
        if CONFIG.get("chat_history", True) is not False:
            n += v.mirror_folder(CHAT_DIR, f"{f['ai']}/Conversations", "conversation", since_days=60)
        n += v.projects([p for p in VAULT_PROJECTS if p.is_dir()])
        if CONFIG.get("vault_file_activity", True) is not False:
            v.file_activity([p for p in VAULT_WATCH if p.is_dir()], PROTECTED + [AI_DIR / "Crypto Desk", HOME / "Desktop" / "mining"])
        if CONFIG.get("guestbook_repo"):
            try:
                import guestbook
                guestbook.render(v, guestbook.fetch(CONFIG["guestbook_repo"]), CONFIG["guestbook_repo"])
            except Exception as e:
                v.errors = (v.errors + [f"guest book: {e}"])[-20:]
        try:
            import library
            library.write_cards(v)
        except Exception as e:
            v.errors = (v.errors + [f"library cards: {e}"])[-20:]
        v.render_nodes()
        try:
            import vault_graph
            r = vault_graph.apply(v, embed=embed if embed_model() else None, mining=agents.mining_active)
            for k in ("plugins_error", "related_error"):
                if r.get(k):
                    v.errors = (v.errors + [f"graph: {r[k]}"])[-20:]
        except Exception as e:
            v.errors = (v.errors + [f"graph: {e}"])[-20:]
        try:
            import drive_private
            m = drive_private.load_map()
            if m.get("private"):
                v.write_managed("90 System/Private Drive.md", {"type": "system", "tags": ["system/drive"], "up": ["[[◉ Nexus]]"]},
                                drive_private.layout_markdown(m), user_tail="\n## Notes\n\n")
                threading.Thread(target=private_drive().flush, daemon=True).start()  # retry anything that waited
        except Exception as e:
            v.errors = (v.errors + [f"private drive: {e}"])[-20:]
        for label, fn in (("facility", lambda: __import__("facility_ops").render_all(v)),
                          ("skills", lambda: __import__("agent_skills").render(v)),
                          ("credentials", lambda: __import__("vault_credentials").render(v)),
                          ("ledger", lambda: audit.render(v, LEDGER))):
            try:
                fn()
            except Exception as e:
                v.errors = (v.errors + [f"{label}: {e}"])[-20:]
        v.render_registry()
        v.render_home()
        v.last_sync["desktop"] = time.time()
        v.commit(min_gap=0 if force else 300)
    if CONFIG.get("skills", True) is not False:  # weekly: pull the skill libraries (git, read-only; nothing in them runs)
        threading.Thread(target=lambda: __import__("agent_skills").sync(), daemon=True, name="skills").start()
    if CONFIG.get("recall", True) is not False and not RECALL_RUN["running"]:
        threading.Thread(target=recall_pass, daemon=True, name="recall").start()
    return n


RECALL_RUN = {"running": False, "last": None}


def recall_pass():
    """Keep kb-core's copy of the vault current (outside the vault lock: it only reads notes). Paused while mining."""
    if agents.mining_active():
        return
    RECALL_RUN["running"] = True
    try:
        import vault_recall
        t0 = time.time()
        RECALL_RUN["last"] = r = vault_recall.index(VAULT, CONFIG, budget_s=float(CONFIG.get("recall_budget_s", 240)))
        if r.get("sent") or r.get("removed"):
            LEDGER.record("BlackLab", "recall:index", target="kb-core", source="companion", duration_ms=int((time.time() - t0) * 1000),
                          metadata={"sent": r.get("sent", 0), "removed": r.get("removed", 0), "pending": r.get("pending", 0)})
    except Exception as e:
        RECALL_RUN["last"] = {"ok": False, "error": str(e)[:200]}
    finally:
        RECALL_RUN["running"] = False


def vault_keeper():
    """Keeps the vault current: a pass at start, then every 10 minutes (and a new day note at midnight)."""
    time.sleep(5)
    first = True
    while True:
        try:
            vault_pass(force=first)
            first = False
            v = VAULT
            today = v.now().date()
            if not v.path(v.day_rel(today)).exists():
                v.render_day(today)
        except Exception as e:
            print(f"vault pass failed: {e}", file=sys.stderr, flush=True)
            VAULT.errors = (VAULT.errors + [str(e)[:200]])[-20:]
        time.sleep(int(CONFIG.get("vault_every_s", 600)))


ATLAS_RUN = {"running": False, "last": None, "error": None, "at": 0.0}


def atlas_pass(force=False):
    """The Atlas (vault_atlas): new/changed files on this computer → cards, guides, imported vaults; then people & topics
    (vault_entities, local model, paused while mining). Reads files outside the vault lock; writes inside it."""
    if not VAULT_ON or CONFIG.get("atlas", True) is False or ATLAS_RUN["running"]:
        return
    if not force and agents.mining_active():
        return
    ATLAS_RUN["running"] = True
    try:
        import vault_atlas
        import vault_entities
        a = vault_atlas.Atlas(VAULT, CONFIG, PROTECTED)
        r = {"scan": a.scan()}
        with VAULT.lock:
            r["import"] = a.import_vaults()
            r["write"] = a.write()
        if CONFIG.get("entities", True) is not False:
            r["entities"] = vault_entities.locked_run(VAULT, CONFIG, a, llm=True, host=OLLAMA, mining=agents.mining_active, lock=VAULT.lock)
            if r["entities"]:
                with VAULT.lock:
                    a.write()  # cards show their people & topics
        # the linked phone (◆ Mobile Device): at most every mobile_every_s (6 h), only while it's plugged in / paired
        if CONFIG.get("mobile", True) is not False and time.time() - ATLAS_RUN.get("mobile_at", 0) > int(CONFIG.get("mobile_every_s", 21600)):
            devs = [d for d in adb_devices()["devices"] if d["state"] == "device" and not d["emulator"]]
            if devs:
                import vault_mobile
                m = vault_mobile.Mobile(VAULT, adb_path(), devs[0]["serial"], CONFIG)
                with VAULT.lock:
                    r["mobile"] = m.run()
                ATLAS_RUN["mobile_at"] = time.time()
        try:
            import vault_drive
            pages = Path(CONFIG.get("drive_pages_dir", str(CACHE_DIR / "drive-pages")))
            if pages.is_dir():
                with VAULT.lock:
                    r["drive"] = vault_drive.DriveImport(VAULT, CONFIG).run(str(pages), _drive_folder_names(pages))
        except Exception as e:
            VAULT.errors = (VAULT.errors + [f"drive: {e}"])[-20:]
        ATLAS_RUN.update(last=r, error=None, at=time.time())
    except Exception as e:
        ATLAS_RUN["error"] = str(e)[:300]
        VAULT.errors = (VAULT.errors + [f"atlas: {e}"])[-20:]
        print(f"atlas pass failed: {e}", file=sys.stderr, flush=True)
    finally:
        ATLAS_RUN["running"] = False


def _drive_folder_names(pages: Path) -> dict:
    names = {}
    for f in pages.glob("folders*.json"):
        try:
            names.update({k: v for k, v in json.loads(f.read_text()).items() if v})
        except Exception:
            pass
    return names


def atlas_keeper():
    time.sleep(60)  # after the first vault pass
    while True:
        atlas_pass()
        time.sleep(int(CONFIG.get("atlas_every_s", 1800)))


_KIWIX = {"proc": None}


def library_keeper():
    """Runs kiwix-serve (127.0.0.1 only) once the reader is installed and books are registered; restarts it if it stops."""
    import library
    while True:
        try:
            cmd = library.serve_cmd()
            p = _KIWIX["proc"]
            import socket
            with socket.socket() as sk:
                sk.settimeout(1)
                up = sk.connect_ex(("127.0.0.1", library.PORT)) == 0  # one that survived a companion restart still serves
            if cmd and not up and (p is None or p.poll() is not None):
                _KIWIX["proc"] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        except Exception as e:
            print(f"library server: {e}", file=sys.stderr, flush=True)
        time.sleep(60)


@logged_tool("reverse_engineer")
def tool_reverse_engineer(args):
    return reverse.tool(args if isinstance(args, dict) else {"action": "help", "query": str(args)})


@logged_tool("library_search")
def tool_library_search(args):
    import library
    q = args.get("query", "") if isinstance(args, dict) else str(args)
    hits = library.search(q, None, 6)
    if hits and "error" in hits[0]:
        return hits[0]["error"]
    return "\n\n".join(f"• {h['title']} — {h['book']}\n  {h['snippet'][:300]}\n  {h['url']}" for h in hits) or "Nothing in the offline library matches that."


PHONE_VAULT = {"at": 0.0, "last": None, "error": None}


def phone_vault_pass(serial: str, force=False):
    """Mirror the Obsidian vault to the phone (Documents/BlackLab) and bring back notes edited there."""
    if not VAULT_ON or CONFIG.get("phone_vault", True) is False:
        return
    if not force and time.time() - PHONE_VAULT["at"] < int(CONFIG.get("phone_vault_every_s", 300)):
        return
    import phone_vault
    PHONE_VAULT["at"] = time.time()

    def pulled(paths):
        VAULT.dirty.update(paths)
        VAULT.log("note", f"{len(paths)} note{'s' if len(paths) != 1 else ''} edited in Obsidian on the phone", ", ".join(paths[:5]), source="Phone · Obsidian",
                  links=[Path(p).stem for p in paths[:5]])
    try:
        with VAULT.lock:
            PHONE_VAULT["last"] = phone_vault.sync(adb_path(), serial, VAULT.root, CONFIG.get("phone_vault_dir", "/sdcard/Documents/BlackLab"),
                                                   CACHE_DIR / "phone-vault.json", on_pull=pulled,
                                                   include_local=CONFIG.get("phone_vault_local", False) is True)
            VAULT.commit("Phone sync (Obsidian on the phone)" if PHONE_VAULT["last"]["pulled"] else "", min_gap=0 if PHONE_VAULT["last"]["pulled"] else 300)
        PHONE_VAULT["error"] = None
    except Exception as e:
        PHONE_VAULT["error"] = str(e)[:200]
        print(f"phone vault mirror: {e}", file=sys.stderr, flush=True)


def keep_usb_link():
    """Re-establish `adb forward` whenever the phone is plugged in, so the UI reconnects on its own — and keep
    the phone's copy of the Obsidian vault current while it's linked."""
    while True:
        try:
            phones = [d for d in adb_devices()["devices"] if d["state"] == "device" and not d["emulator"]]
            if phones:
                adb_forward(8765)  # desktop → phone link, and adb reverse so the phone can reach this companion's AI
                threading.Thread(target=phone_vault_pass, args=(phones[0]["serial"],), daemon=True).start()
        except Exception:
            pass
        time.sleep(15)


def migrate_old_folders():
    """Companion 1.2 and earlier used ~/BlackLab-Desktop and ~/Desktop/BlackLab AI Task Logs: move them in."""
    for old, new, key in ((HOME / "BlackLab-Desktop", DOWNLOADS_DIR, "downloads_dir"),
                          (HOME / "Desktop" / "BlackLab AI Task Logs", TASK_LOG_DIR, "task_log_dir")):
        if key in CONFIG or not old.is_dir() or old.is_symlink() or (new.exists() and any(new.iterdir())):
            continue
        new.parent.mkdir(parents=True, exist_ok=True)
        if new.exists():
            new.rmdir()
        shutil.move(str(old), str(new))
        print(f"moved {_home_rel(old)} → {_home_rel(new)}", flush=True)


def main():
    migrate_old_folders()
    _private_dir(AI_DIR, DOWNLOADS_DIR, DOCS_DIR, CHAT_DIR, TASK_LOG_DIR)
    ensure_phone_token()
    threading.Thread(target=keep_usb_link, daemon=True).start()
    threading.Thread(target=memory_keeper, daemon=True).start()
    if VAULT_ON:
        threading.Thread(target=vault_keeper, daemon=True, name="vault").start()
        if CONFIG.get("atlas", True) is not False:
            threading.Thread(target=atlas_keeper, daemon=True, name="atlas").start()
    if CONFIG.get("library", True) is not False:
        threading.Thread(target=library_keeper, daemon=True, name="library").start()
    agents.setup(sys.modules[__name__])
    reverse.setup(sys.modules[__name__])
    global OPEN_FOLDER_RE
    OPEN_FOLDER_RE = build_open_folder_re()  # agents.setup adds Equipment, Business Intelligence, Crypto Desk
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"BlackLab Desktop companion {VERSION} on http://{HOST}:{PORT}  (web: {WEB_DIR})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
