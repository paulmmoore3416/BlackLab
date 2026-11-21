"""Changes made in Obsidian that the phone carries out (companion 1.11): the desktop → phone half of the sync.

The phone already checks in every minute while it's linked (GET /phone/jobs). That reply now also carries `ops`, and
the phone acks the ones it applied (POST /phone/ops/ack). Ops wait here, on disk, until they're acked — so an edit made
while the phone is away is applied the next time it's linked, exactly once (each op has an id the phone remembers).

    {"op": "task.complete", "task_id": 12}            a box checked in 30 Work/Tasks.md or a work order marked done
    {"op": "task.reopen",   "task_id": 12}            unchecked
    {"op": "task.add", "title": "…", "list": "…", "profile": "PROFESSIONAL", "due": "2026-11-23", "tags": "meeting"}
    {"op": "command", "text": "schedule …"}           any assistant command (#event, #remind, #workorder trigger notes)
    {"op": "card.add", "deck": "…", "front": "…", "back": "…"}   a flashcard written in Obsidian
    {"op": "project.status", "name": "…", "status": "active"}   informational (the Home card refreshes from /phone/projects)
"""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from pathlib import Path

KINDS = {"task.complete", "task.reopen", "task.add", "command", "card.add", "project.status"}
_LOCK = threading.Lock()


class Ops:
    def __init__(self, path: Path):
        self.path = Path(path)

    def _load(self) -> list[dict]:
        try:
            return json.loads(self.path.read_text())
        except Exception:
            return []

    def _save(self, items: list[dict]):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps(items, ensure_ascii=False, indent=1))
        os.replace(tmp, self.path)

    def queue(self, op: dict, key: str | None = None) -> dict:
        """Adds an op. With [key], an op with the same key that's still waiting is replaced (a box checked, then unchecked)."""
        if op.get("op") not in KINDS:
            raise ValueError(f"unknown op: {op.get('op')}")
        item = dict(op, id=uuid.uuid4().hex[:12], at=time.time())
        if key:
            item["key"] = key
        with _LOCK:
            items = [x for x in self._load() if not (key and x.get("key") == key)]
            items.append(item)
            self._save(items[-2000:])
        return item

    def pending(self, limit: int = 100) -> list[dict]:
        with _LOCK:
            return [{k: v for k, v in x.items() if k != "key"} for x in self._load()[:limit]]

    def ack(self, ids: list[str]) -> int:
        ids = set(str(i) for i in ids)
        with _LOCK:
            items = self._load()
            keep = [x for x in items if x["id"] not in ids]
            self._save(keep)
        return len(items) - len(keep)

    def count(self) -> int:
        with _LOCK:
            return len(self._load())
