"""One memory for every BlackLab AI (companion 1.16).

Before this, each AI remembered on its own: the phone kept its conversation in assistant/history.json, the desktop
kept Chat History/ and memory.md, and the phone's Gemini / Gemini Nano turns reached only the vault. Now:

  Long term   memory.md (Chat History/memory.md) — the facts every model reads before answering. The desktop's
              models read it directly; the phone fetches it from GET /phone/memory and puts it in its own prompts
              (Gemini Nano, Gemini, Claude), so "remember that …" said anywhere is known everywhere.
  Short term  a cross-device thread of the last few exchanges. Each surface (phone, desktop UI, Obsidian) keeps its
              own last 3 turns for follow-ups; when a surface has nothing recent, it picks up the other devices'
              last turns from the past 10 minutes — start on the computer, carry on from the phone.

Pure and dependency-free (no Ollama, no vault) so tests/test_chat_memory.py can exercise it directly.
Private ("off the record") turns never get here: assistant() returns before remembering them.
"""
from __future__ import annotations

import datetime as dt
import os
import re
import threading
import time
from collections import deque
from pathlib import Path
from typing import Callable

STOPWORDS = set("the and what did does about you your for that this with was were when have has said say ask asked tell "
                "talk talked chat chats chatted history conversation remember recall last time earlier before we us our me my "
                "today yesterday week any".split())

HEAD = ("# What BlackLab remembers about you\n\nEvery BlackLab AI (the computer, the phone assistant, Gemini on the phone) reads "
        "this before answering. Edit or delete lines freely; \"forget …\" removes one.\n\n")
MAX_FACTS = 80
_SUFFIX = re.compile(r"\s*_\(.*?\)_$")


def _private_dir(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    os.chmod(d, 0o700)


class Memory:
    """memory.md: one "- fact _(added 2026-11-28)_" line per fact, oldest first, capped at [max_facts]."""

    def __init__(self, path: Path, on_write: Callable[[Path], None] | None = None, max_facts: int = MAX_FACTS,
                 today: Callable[[], dt.date] = dt.date.today):
        self.path, self.on_write, self.max_facts, self.today = Path(path), on_write, max_facts, today
        self.lock = threading.Lock()

    def facts(self) -> list[str]:
        try:
            return [ln for ln in self.path.read_text().splitlines() if ln.startswith("- ")]
        except OSError:
            return []

    def clean(self) -> list[str]:
        """The facts without their "_(added …)_" stamps — what a model should read."""
        return [_SUFFIX.sub("", f)[2:].strip() for f in self.facts()]

    def rev(self) -> int:
        """Changes whenever memory.md does (its mtime in ms), so the phone only re-reads it when needed."""
        try:
            return int(self.path.stat().st_mtime * 1000)
        except OSError:
            return 0

    def _write(self, facts: list[str]):
        _private_dir(self.path.parent)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(HEAD + "\n".join(facts) + ("\n" if facts else ""))
        os.replace(tmp, self.path)
        if self.on_write:
            self.on_write(self.path)

    def add(self, facts: list[str], how: str = "added") -> list[str]:
        with self.lock:
            have = self.facts()
            known = {_SUFFIX.sub("", f).lower() for f in have}
            new = []
            for f in facts:
                f = (f or "").strip().rstrip(".")
                if f and "- " + f.lower() not in known:
                    known.add("- " + f.lower())
                    new.append(f)
            if not new:
                return []
            have += [f"- {f} _({how} {self.today():%Y-%m-%d})_" for f in new]
            while len(have) > self.max_facts:  # drop the oldest learned fact first, then the oldest of any kind
                have.pop(next((i for i, f in enumerate(have) if "_(learned" in f), 0))
            self._write(have)
            return new

    def forget(self, query: str) -> list[str]:
        words = [w for w in re.findall(r"[a-z0-9]+", (query or "").lower()) if len(w) > 2 and w not in STOPWORDS]
        if not words:
            return []
        with self.lock:
            have = self.facts()
            gone = [f for f in have if all(w in f.lower() for w in words)]
            if gone:
                self._write([f for f in have if f not in gone])
            return gone

    def prompt(self, limit: int = 2500) -> str:
        text = "\n".join("- " + f for f in self.clean())[-limit:]
        return f"\n\nWhat you know about the user (their memory file; may be out of date):\n{text}" if text else ""


class Thread:
    """Short-term memory: each surface's own last turns, plus one thread across every device."""

    def __init__(self, per_origin: int = 3, shared: int = 8, window: float = 900, handoff: float = 600,
                 clock: Callable[[], float] = time.time):
        self.per_origin, self.window, self.handoff, self.clock = per_origin, window, handoff, clock
        self._own: dict[str, deque] = {}
        self._all: deque = deque(maxlen=shared)
        self.lock = threading.Lock()

    def remember(self, origin: str, user: str, reply: str, surface: str = ""):
        t = self.clock()
        with self.lock:
            self._own.setdefault(origin, deque(maxlen=self.per_origin)).append((t, user[:1500], reply[:1500]))
            self._all.append({"at": t, "origin": origin, "surface": surface or origin, "you": user[:1500], "ai": reply[:1500]})

    def recent(self, origin: str) -> list[tuple[str, str]]:
        """This surface's turns from the last 15 minutes; if it has none, the other devices' last 10 minutes."""
        now = self.clock()
        with self.lock:
            own = [(u, a) for t, u, a in self._own.get(origin, ()) if now - t < self.window]
            if own:
                return own
            return [(c["you"], c["ai"]) for c in self._all if c["origin"] != origin and now - c["at"] < self.handoff][-self.per_origin:]

    def shared(self, limit: int = 6, window: float | None = None) -> list[dict]:
        now, w = self.clock(), self.window if window is None else window
        with self.lock:
            return [dict(c) for c in self._all if now - c["at"] < w][-limit:]


def payload(memory: Memory, thread: Thread, safe: Callable[[str], bool] = lambda s: True, limit: int = 6) -> dict:
    """GET /phone/memory: the facts plus the cross-device thread. [safe] drops anything that must stay off the phone's models."""
    turns = [{"at": int(c["at"] * 1000), "surface": c["surface"], "you": c["you"], "ai": c["ai"]}
             for c in thread.shared(limit) if safe(c["you"]) and safe(c["ai"])]
    return {"ok": True, "rev": memory.rev(), "facts": [f for f in memory.clean() if safe(f)], "recent": turns}
