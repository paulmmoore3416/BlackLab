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


def save(path, data) -> bool:
    """Write JSON atomically with owner-only permissions. Never raises (losing a count must not break a request);
    returns whether the write happened."""
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
        return False
    return True


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


# ---------------------------------------------------------------- the phone (companion 1.15.2: /phone/agentops)

ADVISOR_CHOICES = ("auto", "off", "claude:opus")
REPORT_CAP = 12_000
PHONE_PRIVATE_KEYS = ("request", "origin", "started", "job", "private")
JOB_KEYS = ("id", "kind", "title", "status", "progress", "created", "started", "finished", "origin", "private", "error")


def cap_report(md: str) -> str:
    md = md or ""
    return md if len(md) <= REPORT_CAP else md[:REPORT_CAP] + "\n\n…(cut)"


def phone_payload(ops: dict, report_md: str, now_report_md: str = "") -> dict:
    """The Agent Ops payload for the phone: a copy, with private live tasks reduced to who/when/how many steps (step
    titles can carry tool reasons or clinical-content notes, and the phone shows them on the lock screen), plus the
    latest review report so the phone can render it without another call."""
    out = json.loads(json.dumps(ops, default=str))
    live = []
    for t in out.get("live") or []:
        steps = t.get("steps") or []
        if t.get("private"):
            t = {**{k: t.get(k) for k in PHONE_PRIVATE_KEYS}, "request": "(private)", "private": True}
        live.append({**t, "step_count": len(steps)})
    out["live"] = live
    # jobs: what the phone shows and matches on, never the result (its own results already come via /phone/jobs)
    out["jobs"] = [{k: j.get(k) for k in JOB_KEYS} | ({"title": "(private)", "progress": "", "error": "failed" if j.get("error") else None} if j.get("private") else {})
                   for j in out.get("jobs") or [] if isinstance(j, dict)]
    nr = out.get("nightly_review") if isinstance(out.get("nightly_review"), dict) else {}
    out["nightly_review"] = {**nr, "report_md": cap_report(report_md)}
    rn = out.get("review_now") if isinstance(out.get("review_now"), dict) else {}
    out["review_now"] = {**rn, "report_md": cap_report(now_report_md)} if rn else {}
    return out


def advisor_label(setting: str) -> str:
    s = str(setting or "auto")
    return s if s in ADVISOR_CHOICES else f"custom: {s}"


def write_config_key(path, key: str, value) -> dict:
    """Set one key in config.json, re-reading the file first (./daddyshome edits it too) and keeping every other key.
    A file that exists but doesn't parse as an object (damaged, or caught mid-write) raises ValueError and is left
    alone — writing {key: value} over it would lose every other setting. A failed write raises OSError."""
    p = Path(path)
    if p.exists():
        cfg = json.loads(p.read_text())  # ValueError on a damaged or half-written file
        if not isinstance(cfg, dict):
            raise ValueError("config.json is not a JSON object")
    else:
        cfg = {}
    cfg[key] = value
    if not save(p, cfg):
        raise OSError(f"couldn't write {p}")
    return cfg


def report_path(state: dict, vault_root) -> Path | None:
    """Where the review's report lives: a vault note (relative, '.md' added) or the fallback file the script wrote."""
    rel = str((state or {}).get("report") or "")
    if not rel:
        return None
    p = Path(rel)
    if p.is_absolute():
        return p if p.suffix == ".md" and p.is_file() else None
    root = Path(vault_root).resolve()
    note = (root / (rel if rel.endswith(".md") else rel + ".md")).resolve()
    return note if note.is_relative_to(root) and note.is_file() else None


def review_start(running: bool, enabled: bool) -> dict:
    if running:
        return {"started": False, "note": "A review is already running"}
    if not enabled:
        return {"started": False, "note": "The nightly review is off in autopilot.json"}
    return {"started": True, "note": "Review started"}


def brief_item(forks: dict, advisor: dict, review: dict, now: float) -> dict:
    """The morning brief's 'Desktop AI' data: yesterday's fork/advisor counts and the last review's verdict."""
    y = day(now - 86400)
    fy = next((d for d in (forks or {}).get("days") or [] if isinstance(d, dict) and d.get("day") == y), {})
    ay = next((d for d in (advisor or {}).get("days") or [] if isinstance(d, dict) and d.get("day") == y), {})
    at = (review or {}).get("at")
    last = None
    if isinstance(at, (int, float)):
        last = {"status": str(review.get("status") or ""), "verdict": str(review.get("summary") or "")[:200],
                "when": time.strftime("%Y-%m-%d %H:%M", time.localtime(at))}
    return {"yesterday_fork_decisions": sum(counts(fy, ("sharp", "split")).values()),
            "yesterday_advisor_consults": sum(counts(ay, ("plan", "repeat", "done")).values()), "last_review": last}
