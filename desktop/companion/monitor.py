"""Remote check-ups for when Paul isn't home (companion 1.13). Unit tested in tests/test_monitor.py.

    snapshot(...)  everything worth knowing about this computer in one dict: services, GPUs, disk, memory, the AI
                   (Ollama answering, the chat model loaded), vault recall (kb-core), the library, the vault, the phone
                   link and last night's system check
    problems(s)    what's wrong, each with a stable key, a severity and one plain sentence
    report(s, p)   the short text the phone assistant shows ("on my computer, status report")
    changes(...)   which problems are new and which cleared since the last check — the companion turns those into
                   phone notifications (every 5 minutes, only when something changes, never a repeat)

No AI is involved anywhere here on purpose: the report still works when Ollama is down, which is exactly when you want it.
"""
from __future__ import annotations

import datetime as dt
import json
import time
import urllib.request
from pathlib import Path

STATUS_WORDS = (r"^(?:(?:give me |run |do )?(?:a )?(?:full |quick )?(?:system|health|status) (?:check|report|update)"
                r"|how(?:'s| is) (?:everything|it all|the (?:computer|desktop|server|pc|system|home ?lab)|my (?:computer|desktop|server|pc|home ?lab))(?: doing| going| holding up)?"
                r"|is everything (?:ok|okay|alright|working|running|good|up)(?: at home| on the computer| on my computer)?"
                r"|(?:computer|desktop|server|system|home ?lab) (?:status|health|check|report)"
                r"|any(?:thing)? (?:problems|issues|alerts|wrong)(?: at home| on the computer| with the computer)?"
                r"|what(?:'s| is) wrong(?: at home| with the computer)?)\??$")


def _get(url: str, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read() or b"{}")
    except Exception:
        return None


def snapshot(system: dict, health: dict, *, ollama_url="http://127.0.0.1:11434", chat_model="", recall: dict | None = None,
             vault: dict | None = None, phone_last_seen: float = 0, library: dict | None = None, syncthing: dict | None = None,
             now: float | None = None, get=_get) -> dict:
    now = now or time.time()
    ps = get(ollama_url + "/api/ps")
    loaded = [m.get("name") for m in (ps or {}).get("models") or []]
    return {"at": now, "system": system, "health": health or {}, "ollama": ps is not None, "loaded": loaded, "chat_model": chat_model,
            "recall": recall or {}, "vault": vault or {}, "phone_last_seen": phone_last_seen, "library": library or {}, "syncthing": syncthing}


def problems(s: dict) -> list[dict]:
    out = []

    def add(key, sev, text):
        out.append({"key": key, "severity": sev, "text": text})
    sysd = s.get("system") or {}
    for x in sysd.get("services", []):
        if x["state"] != "active":
            add(f"service:{x['name']}", "bad" if x["scope"] != "port" else "warn", f"{x['name']} is {x['state']}")
    if not s.get("ollama"):
        add("ollama", "bad", "Ollama isn't answering — the desktop AI is down")
    elif s.get("chat_model") and not any(m == s["chat_model"] or m == s["chat_model"] + ":latest" for m in s.get("loaded", [])):
        add("model", "warn", f"{s['chat_model']} isn't loaded (the first answer will take ~15 s)")
    for d in sysd.get("disks", []):
        p = 100.0 * d["used"] / max(1, d["total"])
        if p >= 90:
            add(f"disk:{d['label']}", "bad", f"{d['label']} disk {p:.0f}% full")
        elif p >= 80:
            add(f"disk:{d['label']}", "warn", f"{d['label']} disk {p:.0f}% full")
    if sysd.get("mem_total") and sysd.get("mem_avail", 0) < 2 * 2**30:
        add("memory", "warn", f"only {sysd.get('mem_avail', 0) / 2**30:.1f} GB memory free")
    for g in sysd.get("gpus", []):
        if (g.get("temp") or 0) >= 85:
            add(f"gpu:{g['name']}", "warn", f"{g['name']} at {g['temp']}°C")
    rc = s.get("recall") or {}
    if rc and not rc.get("kb_core"):
        add("kb-core", "warn", "vault recall (kb-core) is down — answers fall back to keyword search")
    h = s.get("health") or {}
    if h.get("status") == "fail":
        add("nightly", "bad", f"last system check failed: {h.get('summary', '')}"[:160])
    gen = h.get("generated") or 0
    if gen and s["at"] - gen > 30 * 3600:
        add("nightly-stale", "warn", f"no system check since {dt.datetime.fromtimestamp(gen).strftime('%a %H:%M')}")
    errs = (s.get("vault") or {}).get("errors") or []
    if errs:
        add("vault-errors", "warn", f"vault: {errs[-1]}"[:160])
    st = s.get("syncthing")
    if st and st.get("phone_configured") and not st.get("phone_connected") and s["at"] - (st.get("phone_last_seen") or 0) > 6 * 3600:
        add("syncthing", "warn", "the phone's vault copy hasn't synced in over 6 hours")
    return out


def report(s: dict, probs: list[dict] | None = None) -> str:
    probs = problems(s) if probs is None else probs
    sysd = s.get("system") or {}
    svc = sysd.get("services", [])
    up = sum(1 for x in svc if x["state"] == "active")
    bad = [p for p in probs if p["severity"] == "bad"]
    head = ("🟢 All good" if not probs else "🔴 Needs attention" if bad else "🟡 Mostly fine") + f" — {sysd.get('host', 'your computer')}"
    lines = [head]
    for p in probs[:8]:
        lines.append(("🔴 " if p["severity"] == "bad" else "🟡 ") + p["text"])
    upt = sysd.get("uptime", 0)
    lines.append(f"Services {up}/{len(svc)} up · up {int(upt // 86400)} d {int(upt % 86400 // 3600)} h · load {sysd.get('load1', 0):.1f} on {sysd.get('cpus', 0)} threads")
    mem_used = max(0, sysd.get("mem_total", 0) - sysd.get("mem_avail", 0))
    disks = ", ".join(f"{d['label'].lower()} {100 * d['used'] / max(1, d['total']):.0f}%" for d in sysd.get("disks", []))
    lines.append(f"Memory {mem_used / 2**30:.0f}/{sysd.get('mem_total', 0) / 2**30:.0f} GB · disk {disks or '?'}")
    for g in sysd.get("gpus", []):
        lines.append(f"{g['name'].replace('NVIDIA GeForce ', '')}: {g['used'] / 2**30:.1f}/{g['total'] / 2**30:.0f} GB VRAM, {g.get('busy') or 0}% busy" + (f", {g['temp']}°C" if g.get("temp") else ""))
    lines.append("AI: " + (("loaded " + ", ".join(s.get("loaded") or [])) if s.get("ollama") and s.get("loaded") else "Ollama up, nothing loaded" if s.get("ollama") else "Ollama DOWN"))
    rc = s.get("recall") or {}
    if rc:
        ref = rc.get("reference") or {}
        lines.append(f"Recall: {'up' if rc.get('kb_core') else 'DOWN'} · {rc.get('indexed', 0)} notes" + (f" + {ref.get('indexed', 0)} reference docs" if ref else ""))
    h = s.get("health") or {}
    if h:
        gen = h.get("generated")
        lines.append(f"Last system check: {str(h.get('status', '?')).upper()} — {h.get('summary', '')}" + (f" ({dt.datetime.fromtimestamp(gen).strftime('%a %H:%M')})" if gen else ""))
    st = s.get("syncthing")
    if st and st.get("phone_configured"):
        lines.append("Phone vault sync: " + ("connected" if st.get("phone_connected") else "not connected right now"))
    return "\n".join(lines)


def changes(prev: dict, probs: list[dict]) -> tuple[list[dict], list[str]]:
    """(new problems, keys that cleared) against the last check's {key: text}. Warnings must persist two checks in a row
    before they're new (a 5-minute blip isn't worth a buzz); bad ones alert right away."""
    seen = prev.get("open", {})
    pending = prev.get("pending", {})
    new, now_open, now_pending = [], {}, {}
    for p in probs:
        if p["key"] in seen:
            now_open[p["key"]] = p["text"]
        elif p["severity"] == "bad" or p["key"] in pending:
            new.append(p)
            now_open[p["key"]] = p["text"]
        else:
            now_pending[p["key"]] = p["text"]
    cleared = [k for k in seen if k not in now_open]
    prev.clear()
    prev.update(open=now_open, pending=now_pending)
    return new, cleared


def syncthing_status(st_home: Path = Path.home() / ".local/state/blacklab-syncthing", folder="blacklab-vault", get=None) -> dict | None:
    """Is the phone connected to this computer's Syncthing? None when Syncthing isn't set up."""
    cfg = st_home / "config.xml"
    if not cfg.exists():
        return None
    import re
    import xml.etree.ElementTree as ET
    try:
        root = ET.parse(cfg).getroot()
    except ET.ParseError:
        return None
    key = root.findtext("gui/apikey") or ""
    addr = root.findtext("gui/address") or "127.0.0.1:8384"
    me = None
    devices = [d.get("id") for d in root.findall("device")]
    shared = [d.get("id") for f in root.findall("folder") if f.get("id") == folder for d in f.findall("device")]

    def api(path):
        if get:
            return get(path)
        req = urllib.request.Request(f"http://{addr}{path}", headers={"X-API-Key": key})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return json.loads(r.read() or b"{}")
        except Exception:
            return None
    sysst = api("/rest/system/status") or {}
    me = sysst.get("myID")
    phones = [d for d in shared if d and d != me]
    conns = (api("/rest/system/connections") or {}).get("connections", {})
    stats = api("/rest/stats/device") or {}
    last = 0.0
    for d in phones:
        ls = (stats.get(d) or {}).get("lastSeen") or ""
        m = re.match(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})", ls)
        if m and not ls.startswith("1970"):
            try:
                last = max(last, dt.datetime.fromisoformat(m.group(1)).replace(tzinfo=dt.timezone.utc).timestamp())
            except ValueError:
                pass
    return {"running": bool(sysst), "phone_configured": bool(phones), "phone_connected": any((conns.get(d) or {}).get("connected") for d in phones),
            "phone_last_seen": last, "devices": len(devices)}
