"""Agent Ops state (companion 1.15.1): what has to outlive a restart, and the nightly review's own panel.

  * save(path, data) / load(path, default)   small private JSON files (0600, written atomically) under
                                             ~/.cache/blacklab-desktop/, so the fork-layer and advisor counts survive the
                                             04:00 restart instead of starting from zero every morning.
  * day(ts) / keep_days(...)                 per-day totals for the trend, KEEP_DAYS of them.
  * review_setup(cfg)                        the nightly review's own model, advisor and time from autopilot.json (not the
                                             interactive Claude Code settings, which are a different thing).
  * review_health(setup, state, now)         ok / waiting / missed / failed / off, so a silent 02:30 failure shows up.

Pure code; unit tested in tests/test_agentops.py.
"""
from __future__ import annotations

import json
import os
import tempfile
import time
from pathlib import Path

import ai_models

KEEP_DAYS = 30
SHOW_DAYS = 14
LATE = 26 * 3600  # the review runs every night (it records "Skipped" on days without commits), so >26 h means it didn't


def load(path, default):
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default
    return data if isinstance(data, type(default)) else default


def save(path, data) -> None:
    """Write JSON atomically with owner-only permissions. Never raises: losing a count must not break a request."""
    p = Path(path)
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=f".{p.name}.")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(data, f)
            os.chmod(tmp, 0o600)
            os.replace(tmp, p)
        except BaseException:
            os.unlink(tmp)
            raise
    except OSError:
        pass


def day(ts: float) -> str:
    return time.strftime("%Y-%m-%d", time.localtime(ts))


def keep_days(days: dict, keep: int = KEEP_DAYS) -> dict:
    """The newest `keep` days (ISO dates sort as strings)."""
    return dict(sorted(days.items())[-keep:])


def series(days: dict, keys, now: float, n: int = SHOW_DAYS) -> list[dict]:
    """The last n calendar days, oldest first, with quiet days as zeros (so a trend line has no gaps)."""
    out = []
    for i in range(n - 1, -1, -1):
        d = day(now - i * 86400)
        out.append({"day": d, **{k: (days.get(d) or {}).get(k, 0) for k in keys}})
    return out


def counts(obj, keys) -> dict[str, int]:
    """A {key: int} dict from untrusted JSON; anything malformed counts as 0."""
    obj = obj if isinstance(obj, dict) else {}
    return {k: v if isinstance(v := obj.get(k), int) and v >= 0 else 0 for k in keys}


def review_setup(cfg: dict) -> dict:
    model = str(cfg.get("review_model") or "sonnet")
    return {"enabled": cfg.get("nightly_review", True) is not False, "model": model,
            "advisor": ai_models.advisor_for(model, cfg.get("review_advisor", "opus")) or "",
            "time": str(cfg.get("review_time") or "02:30"), "repos": [str(r) for r in cfg.get("review_repos") or []]}


def review_health(setup: dict, state: dict, now: float, installed_at: float | None = None) -> dict:
    if not setup.get("enabled"):
        return {"state": "off", "note": 'turned off in autopilot.json ("nightly_review": false)'}
    at = state.get("at") if isinstance(state.get("at"), (int, float)) else None
    if at is None:
        if installed_at and now - installed_at > LATE:
            return {"state": "missed", "note": f"installed but it has never run (timer set for {setup['time']})"}
        return {"state": "waiting", "note": f"first run tonight at {setup['time']}"}
    if now - at > LATE:
        n = int((now - at) // 86400)
        return {"state": "missed", "note": f"last ran {n} day{'' if n == 1 else 's'} ago; check blacklab-nightly-review.timer"}
    if state.get("status") == "Failed":
        return {"state": "failed", "note": str(state.get("summary") or "failed")[:200]}
    return {"state": "ok", "note": ""}
