"""The activity ledger (companion 1.7): one tamper-evident line for everything that changes the vault or runs a job.

Two ideas from Paul's own projects, put together:

  * MemResort's AI observation schema — every line says who did what, where, with which model:
      {ts, actor, action, source, target, model, provider, duration_ms, metadata}
  * OMNIX's audit log — each line also carries the SHA-256 of the line before it ("prev") and of itself ("hash"), so
    editing, deleting or re-ordering any past line breaks the chain from that point on. verify() finds the first break.

The ledger lives in the companion's state folder (~/.cache/blacklab-desktop/vault/ledger.jsonl), not in the vault:
it is evidence, so the thing it audits doesn't get to rewrite it. Only metadata is recorded — paths, op names, counts,
durations — never note text, queries or answers (those can be private). render() writes a summary to
`90 System/Activity Ledger.md` and the AI Registry shows what each agent did.

This is tamper-*evident*, not tamper-proof: someone with write access to the file can rewrite the whole chain. The
head hash is also written into the vault note on every render, so the vault's git history pins it.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import threading
from collections import Counter
from pathlib import Path

FIELDS = ("ts", "actor", "action", "source", "target", "model", "provider", "duration_ms", "metadata")
GENESIS = "0" * 64
_LOCK = threading.Lock()
SAFE_META = re.compile(r"^[\w .:/@#()+,-]{0,160}$")  # metadata values: short and plain (no free text)


def _canon(entry: dict) -> str:
    return json.dumps({k: entry.get(k) for k in (*FIELDS, "prev")}, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def _digest(entry: dict) -> str:
    return hashlib.sha256(_canon(entry).encode()).hexdigest()


def _clean_meta(meta: dict | None) -> dict:
    out = {}
    for k, val in (meta or {}).items():
        if not re.fullmatch(r"[a-z_]{1,32}", str(k)):
            continue
        if isinstance(val, bool) or isinstance(val, (int, float)) or val is None:
            out[k] = val
        elif isinstance(val, str) and SAFE_META.match(val):
            out[k] = val
        elif isinstance(val, str):
            out[k] = f"<{len(val)} chars>"
    return out


class Ledger:
    def __init__(self, path: Path):
        self.path = Path(path)
        self._head: str | None = None

    def head(self) -> str:
        if self._head is None:
            self._head = GENESIS
            last = self._last_line()
            if last:
                try:
                    self._head = json.loads(last)["hash"]
                except (ValueError, KeyError):
                    self._head = GENESIS
        return self._head

    def _last_line(self) -> str:
        if not self.path.exists():
            return ""
        with self.path.open("rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 65536))
            lines = f.read().decode("utf-8", errors="replace").strip().splitlines()
        return lines[-1] if lines else ""

    def record(self, actor: str, action: str, target: str = "", source: str = "", model: str = "", provider: str = "",
               duration_ms: int | None = None, metadata: dict | None = None, ts: float | None = None) -> dict:
        """Append one observation. Never raises: auditing must not break the thing being audited."""
        try:
            with _LOCK:
                e = {"ts": round(ts if ts is not None else dt.datetime.now().timestamp(), 3), "actor": str(actor or "unknown")[:60],
                     "action": str(action or "")[:60], "source": str(source or "")[:60], "target": str(target or "")[:200],
                     "model": str(model or "")[:80], "provider": str(provider or "")[:40],
                     "duration_ms": int(duration_ms) if duration_ms is not None else None, "metadata": _clean_meta(metadata)}
                e["prev"] = self.head()
                e["hash"] = _digest(e)
                self.path.parent.mkdir(parents=True, exist_ok=True)
                fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                with os.fdopen(fd, "a", encoding="utf-8") as f:
                    f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
                self._head = e["hash"]
                return e
        except Exception:
            return {}

    def entries(self, last: int | None = None) -> list[dict]:
        if not self.path.exists():
            return []
        lines = self.path.read_text(encoding="utf-8", errors="replace").splitlines()
        out = []
        for ln in lines[-last:] if last else lines:
            try:
                out.append(json.loads(ln))
            except ValueError:
                out.append({"_bad": ln[:80]})
        return out

    def verify(self) -> dict:
        """Walk the chain. ok=False names the first line (1-based) whose link or hash doesn't match."""
        prev, n = GENESIS, 0
        for n, e in enumerate(self.entries(), 1):
            if "_bad" in e:
                return {"ok": False, "entries": n, "broken_at": n, "reason": "not JSON"}
            if e.get("prev") != prev:
                return {"ok": False, "entries": n, "broken_at": n, "reason": "link to the previous line doesn't match (a line was removed, added or re-ordered)"}
            if _digest(e) != e.get("hash"):
                return {"ok": False, "entries": n, "broken_at": n, "reason": "line was edited after it was written"}
            prev = e["hash"]
        return {"ok": True, "entries": n, "head": prev}

    def summary(self, days: int = 7) -> dict:
        since = dt.datetime.now().timestamp() - days * 86400
        recent = [e for e in self.entries(20000) if (e.get("ts") or 0) >= since]
        return {"days": days, "count": len(recent), "by_actor": Counter(e.get("actor") for e in recent).most_common(12),
                "by_action": Counter(e.get("action") for e in recent).most_common(16), "recent": recent[-25:]}


def render(v, ledger: Ledger) -> dict:
    ver = ledger.verify()
    s = ledger.summary(7)
    fmt = lambda ts: dt.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M") if ts else "—"
    status = (f"✅ **Chain intact** — {ver['entries']} entries, head `{ver['head'][:16]}`" if ver["ok"] else
              f"⛔ **Chain broken at entry {ver['broken_at']}** — {ver['reason']}. Entries after it can't be trusted.")
    lines = ["# 🧾 Activity Ledger", "",
             "> Every change to the vault, every job and every AI tool call, one line each, hash-chained so a past line can't be",
             "> edited or removed without it showing here. Metadata only (who, what, where, how long) — never note text.",
             "> The ledger file is `~/.cache/blacklab-desktop/vault/ledger.jsonl`; schema from MemResort, chain from OMNIX.", "",
             status, "", f"## Last 7 days — {s['count']} actions", "", "| Who | Actions |", "|---|---|"]
    lines += [f"| {a} | {n} |" for a, n in s["by_actor"]] or ["| — | 0 |"]
    lines += ["", "| Action | Count |", "|---|---|"] + [f"| `{a}` | {n} |" for a, n in s["by_action"]]
    lines += ["", "## Most recent", "", "| When | Who | Action | Target | ms |", "|---|---|---|---|---|"]
    for e in reversed(s["recent"]):
        tgt = (e.get("target") or "").replace("|", "/")[:70]
        lines.append(f"| {fmt(e.get('ts'))} | {e.get('actor')} | `{e.get('action')}` | {tgt} | {e.get('duration_ms') if e.get('duration_ms') is not None else ''} |")
    v.write_managed("90 System/Activity Ledger.md", {"type": "system", "ledger_ok": ver["ok"], "ledger_entries": ver["entries"],
                                                     "ledger_head": ver.get("head", "")[:16], "tags": ["system/ledger"], "up": ["[[◆ AI & Automation]]"]},
                    "\n".join(lines))
    return ver


def by_agent(ledger: Ledger, days: int = 30) -> dict[str, Counter]:
    """For the AI Registry: what each actor did recently."""
    since = dt.datetime.now().timestamp() - days * 86400
    out: dict[str, Counter] = {}
    for e in ledger.entries(20000):
        if (e.get("ts") or 0) >= since:
            out.setdefault(e.get("actor") or "unknown", Counter())[e.get("action") or "?"] += 1
    return out
