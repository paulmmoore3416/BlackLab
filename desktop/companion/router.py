"""The fork layer (companion 1.15): the small decisions in a tool loop that need no model.

Every tool loop keeps hitting the same forks: which tool fits this request, should this call run again, retry or stop.
Most of them are obvious and can be settled in code in microseconds ("sharp"); only the ones that genuinely split go to a
model: the main model picks the tool itself, and a repeated error goes to the advisor (advisor.py).

  * which_tool(text)      a read-only tool + arguments when the request plainly names one (find files named X, the
                          history of RTU-4, what did we say about X). Sharp ones are run up front and handed to the model
                          as if it had called them, which spares a round on an 8k-context local model. Never a side effect.
  * Forks.before(...)     the same tool with the same arguments again: stop, and point the model at the result it has.
  * Forks.after(...)      the same failure twice: "split", the advisor's cue.

STATS counts each fork (sharp vs split) for the Agent Ops view. Pure code; unit tested in tests/test_router.py.
"""
from __future__ import annotations

import json
import re
import threading
import time

import agentops

SHARP = 0.9  # at or above: decided in code; below: left to a model
SEEDABLE = frozenset({"find_files", "recall_chats", "equipment", "search_documents"})  # read-only tools only

TAG = r"(?:RTU|AHU|MAU|ERV|DOAS|FCU|VAV|EF|CT|HWP|CHWP|CWP|UH|WSHP|Chiller|Boiler|Pump)\s?-?\s?\d{1,3}[A-Z]?"


def norm_tag(tag: str) -> str:
    """'rtu 4', 'RTU4', 'Rtu - 4' → 'RTU-4' (how the equipment registry writes tags)."""
    m = re.match(r"([A-Za-z]+)\s*-?\s*(\d+[A-Za-z]?)$", tag.strip())
    if not m:
        return tag.strip().upper()
    if len(m.group(1)) > 4:  # Chiller 2, Boiler 1, Pump 3: the registry's lookup matches these word by word
        return f"{m.group(1).title()} {m.group(2).upper()}"
    return f"{m.group(1).upper()}-{m.group(2).upper()}"


RULES = [  # (tool, pattern, argument builder, confidence)
    ("find_files", re.compile(r"^(?:please\s+)?(?:find|locate|where(?:'s| is| are))\s+(?:my\s+|the\s+|all\s+)?(?:files?|documents?|docs|pdfs?|spreadsheets?)\s+"
                              r"(?:named|called|titled|with\s+(?:the\s+)?name)\s+[\"']?(.+?)[\"']?\s*[?.!]*$", re.I),
     lambda m: {"query": m.group(1).strip()}, 0.95),
    ("equipment", re.compile(r"\b(?:history|nameplate|serial|model number|refrigerant|specs?|work orders?|repeat issues?|info)\b.{0,30}?\b(" + TAG + r")\b", re.I),
     lambda m: {"query": norm_tag(m.group(1))}, 0.95),
    ("equipment", re.compile(r"\b(" + TAG + r")\b.{0,12}\b(?:history|nameplate|serial|model number|refrigerant|specs?|work orders?)\b", re.I),
     lambda m: {"query": norm_tag(m.group(1))}, 0.93),
    ("recall_chats", re.compile(r"^(?:what|when) did (?:we|i|you) (?:talk|say|discuss|decide|chat)(?: about)?(?: on| regarding)?\s+(.{3,}?)[?.!]*$", re.I),
     lambda m: {"query": m.group(1).strip()}, 0.92),
    ("search_documents", re.compile(r"^(?:search|look through|check)\s+(?:my\s+|the\s+)?documents?\s+(?:for|about)\s+(.{3,}?)[?.!]*$", re.I),
     lambda m: {"query": m.group(1).strip()}, 0.94),
    # weaker cues: counted as splits, the model decides
    ("recall_chats", re.compile(r"\b(?:earlier|last|previous) (?:chat|conversation)\b|\blast time we (?:talked|spoke)\b", re.I),
     lambda m: {"query": ""}, 0.6),
    ("find_files", re.compile(r"\bwhere did i (?:put|save)\b", re.I), lambda m: {"query": ""}, 0.6),
]

ERROR_RE = re.compile(r"^(?:[\w_]+ failed:|error\b|traceback\b|not run:|no such file|permission denied|timed out|couldn'?t\b|can'?t\b|unknown\b)", re.I)
_NOISE = re.compile(r"(?:/[\w.\-~]+)+|0x[0-9a-f]+|\d+(?:\.\d+)?", re.I)


class Stats:
    """Counts of sharp vs split decisions per fork, per day, plus the last few, for the Agent Ops view.
    attach(path) loads the saved counts and keeps saving there, so they outlive the 04:00 restart."""

    KEYS = ("sharp", "split")

    def __init__(self):
        self._lock = threading.Lock()
        self.counts: dict[str, dict[str, int]] = {}
        self.days: dict[str, dict[str, int]] = {}
        self.recent: list[dict] = []
        self.path = None
        self.clock = time.time

    def attach(self, path):
        saved = agentops.load(path, {})
        with self._lock:
            for fork, c in (saved.get("counts") if isinstance(saved.get("counts"), dict) else {}).items():
                mine, old = self.counts.setdefault(str(fork), {"sharp": 0, "split": 0}), agentops.counts(c, self.KEYS)
                for k in self.KEYS:
                    mine[k] += old[k]
            for d, c in (saved.get("days") if isinstance(saved.get("days"), dict) else {}).items():
                mine, old = self.days.setdefault(str(d), {"sharp": 0, "split": 0}), agentops.counts(c, self.KEYS)
                for k in self.KEYS:
                    mine[k] += old[k]
            self.days = agentops.keep_days(self.days)
            old_recent = [x for x in saved.get("recent") or [] if isinstance(x, dict)] if isinstance(saved.get("recent"), list) else []
            self.recent = (old_recent + self.recent)[-20:]
            self.path = path
            data = self._data()
        agentops.save(path, data)

    def _data(self) -> dict:
        # On disk, never the detail: which_tool's details carry the tool arguments (a search query can come from a private
        # request). Memory keeps them for the live view; the file keeps only when, which fork and how it went.
        return {"counts": self.counts, "days": self.days,
                "recent": [{k: x.get(k) for k in ("at", "fork", "sharp", "p")} for x in self.recent]}

    def note(self, fork: str, sharp: bool, detail: str = "", p: float | None = None):
        key = "sharp" if sharp else "split"
        with self._lock:
            now = self.clock()
            self.counts.setdefault(fork, {"sharp": 0, "split": 0})[key] += 1
            self.days.setdefault(agentops.day(now), {"sharp": 0, "split": 0})[key] += 1
            self.days = agentops.keep_days(self.days)
            self.recent = (self.recent + [{"at": now, "fork": fork, "sharp": sharp, "p": p, "detail": detail[:120]}])[-20:]
            if self.path is not None:
                agentops.save(self.path, self._data())  # a few hundred bytes, under the lock so writes stay in order

    def snapshot(self) -> dict:
        with self._lock:
            total = sum(c["sharp"] + c["split"] for c in self.counts.values())
            today = self.days.get(agentops.day(self.clock()), {"sharp": 0, "split": 0})
            return {"total": total, "forks": {k: {**v, "sharp_rate": round(v["sharp"] / max(1, v["sharp"] + v["split"]), 2)} for k, v in self.counts.items()},
                    "today": today["sharp"] + today["split"],
                    "days": agentops.series(self.days, self.KEYS, self.clock()),
                    "recent": list(self.recent[-8:])}


STATS = Stats()


def which_tool(text: str, stats: Stats | None = STATS) -> dict | None:
    """The tool the request plainly names: {'tool', 'args', 'p', 'sharp'}; None when no rule matches."""
    t = " ".join((text or "").split())
    if not t or len(t) > 300:
        return None
    for tool, rx, build, p in RULES:
        m = rx.search(t)
        if not m:
            continue
        args = build(m)
        sharp = p >= SHARP and tool in SEEDABLE and all(v for v in args.values())
        if stats is not None:
            stats.note("which_tool", sharp, f"{tool} {json.dumps(args)}", p)
        return {"tool": tool, "args": args, "p": p, "sharp": sharp}
    return None


def is_error(result: str) -> bool:
    return bool(ERROR_RE.match((result or "").strip()))


def signature(name: str, result: str) -> str:
    """The shape of a failure, without the numbers and paths that differ between otherwise identical errors."""
    first = (result or "").strip().splitlines()[0] if (result or "").strip() else ""
    return f"{name}:{_NOISE.sub('#', first.lower())[:160]}"


def _key(name: str, args) -> str:
    try:
        return name + ":" + json.dumps(args or {}, sort_keys=True, default=str)
    except (TypeError, ValueError):
        return name + ":" + str(args)


class Forks:
    """One task's retry-or-stop decisions."""

    def __init__(self, stats: Stats | None = STATS):
        self.stats = stats
        self.seen: dict[str, str] = {}
        self.errors: dict[str, int] = {}

    def before(self, name: str, args) -> str | None:
        """None: run it. Otherwise the message to hand the model instead (an identical call already ran)."""
        k = _key(name, args)
        if k in self.seen:
            if self.stats is not None:
                self.stats.note("retry_or_stop", True, f"stopped a repeat of {name}")
            prev = self.seen[k]
            return (f"Not run again: {name} already ran with these exact arguments"
                    + (" and failed the same way. Try different arguments or a different tool, or answer from what you have."
                       if is_error(prev) else ". Its result is above: answer from it."))
        return None

    def after(self, name: str, args, result: str) -> str:
        """'ok', 'error' (a first failure: retrying is reasonable) or 'split' (the same failure again: ask the advisor)."""
        self.seen[_key(name, args)] = result or ""
        if not is_error(result):
            return "ok"
        sig = signature(name, result)
        self.errors[sig] = self.errors.get(sig, 0) + 1
        if self.errors[sig] >= 2:
            if self.stats is not None:
                self.stats.note("retry_or_stop", False, f"{name} failed the same way {self.errors[sig]}×")
            return "split"
        if self.stats is not None:
            self.stats.note("retry_or_stop", True, f"{name} failed once: retry allowed")
        return "error"
