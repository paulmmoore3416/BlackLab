"""Every conversation with Claude and Gemini, saved in the vault and easy to find (companion 1.11).

    70 AI/Claude/🟠 Claude.md                          the hub: what's here, counts, the latest exchanges (managed block)
    70 AI/Claude/Conversations/2026-11/Claude · 2026-11-21.md   one note per day, append-only
    70 AI/Gemini/🔴 Gemini.md  ·  70 AI/Gemini/Conversations/…    the same for Gemini (Gemini Nano on the phone included)
    70 AI/Local AI/…                                    the phone's local answers (desktop chats are mirrored from Chat History)

Every note carries `ai_provider`, `#ai/<provider>` and `cssclasses: [bl-ai-<provider>]`, so the graph colors and glows them
(vault_colors + Graph FX) and the local AI finds them with one query (`recall_ai`, kb-core recall, vault search).
Text passes guards.vault_safe first: clinical or off-the-record turns are never written, and keys/identifiers are scrubbed.
"""
from __future__ import annotations

import datetime as dt
import json
import re

import guards

PROVIDERS = {
    "claude": {"name": "Claude", "emoji": "🟠", "color": "#FF8C1A", "folder": "70 AI/Claude", "speaker": "Claude",
               "about": "Claude (Anthropic) — through Paul's Claude subscription: the BlackLab phone app, the desktop app, the Obsidian "
                        "sidebar, trigger notes and Claude Code sessions."},
    "gemini": {"name": "Gemini", "emoji": "🔴", "color": "#FF2D3D", "folder": "70 AI/Gemini", "speaker": "Gemini",
               "about": "Gemini (Google) — the Gemini API from the phone and the desktop, and Gemini Nano on the phone."},
    "local": {"name": "Local AI", "emoji": "🟢", "color": "#2BD9A8", "folder": "70 AI/Local AI", "speaker": "Local AI",
              "about": "The offline models (Ollama on this computer) answering from the phone."},
}
AI_NODE = "[[◆ AI & Automation]]"
RECENT_KEEP = 400


def provider_for(provider: str, model: str = "") -> str:
    p = (provider or "").lower()
    if p == "nano" or "gemini" in (model or "").lower():
        return "gemini"
    return p if p in PROVIDERS else "local"


def hub_rel(p: str) -> str:
    c = PROVIDERS[p]
    return f"{c['folder']}/{c['emoji']} {c['name']}.md"


def hub_link(p: str) -> str:
    c = PROVIDERS[p]
    return f"[[{c['emoji']} {c['name']}]]"


def day_stem(p: str, d: dt.date) -> str:
    return f"{PROVIDERS[p]['name']} · {d.isoformat()}"


def day_rel(p: str, d: dt.date) -> str:
    return f"{PROVIDERS[p]['folder']}/Conversations/{d:%Y-%m}/{day_stem(p, d)}.md"


def topic(text: str) -> str:
    first = re.sub(r"\s+", " ", (text or "").strip().splitlines()[0] if (text or "").strip() else "").strip()
    return (first[:90] + "…") if len(first) > 90 else first


def _quote(t: str) -> str:
    return "\n".join("> " + line if line.strip() else ">" for line in (t or "").strip().splitlines()) or "> _(no reply)_"


def props(p: str, d: dt.date, models: list[str], exchanges: int, surfaces: list[str]) -> dict:
    return {"type": "ai-conversation", "ai_provider": p, "ai_models": models, "date": d.isoformat(), "exchanges": exchanges,
            "surfaces": surfaces, "tags": [f"ai/{p}", "ai/conversation"], "cssclasses": [f"bl-ai-{p}"], "up": hub_link(p),
            "daily": f"[[{d.isoformat()}]]"}


def record(v, provider: str, model: str, surface: str, request: str, reply: str, when: dt.datetime | None = None,
           label: str = "") -> dict:
    """Appends one exchange to the provider's day note, refreshes the hub and puts a line on the daily timeline."""
    p = provider_for(provider, model)
    ok1, request = guards.vault_safe(request or "")
    ok2, reply = guards.vault_safe(reply or "")
    if not ok1 or not ok2:
        return {"ok": False, "error": "Not saved: that looks clinical or off the record, and the vault never stores it."}
    if not request.strip():
        return {"ok": False, "error": "nothing to save"}
    when = when or v.now()
    if when.tzinfo is None and getattr(v, "tz", None):
        when = when.replace(tzinfo=v.tz)
    d = when.date()
    rel = day_rel(p, d)
    c = PROVIDERS[p]
    model = (model or "").strip()[:60]
    label = (label or model or c["name"]).strip()[:60]
    surface = re.sub(r"\s+", " ", surface or "BlackLab").strip()[:60]
    with v.lock:
        path = v.path(rel)
        old = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
        fm, body = v.split_frontmatter(old)
        if not body.strip():
            body = (f"# {c['emoji']} {c['name']} · {d.strftime('%A, %B')} {d.day}, {d.year}\n\n"
                    f"{hub_link(p)} · [[{d.isoformat()}]] · {AI_NODE}\n\n")
        entry = (f"## {when:%H:%M} · {surface} · {label}\n\n**Paul**\n\n{_quote(request)}\n\n"
                 f"**{c['speaker']}**\n\n{_quote(reply)}\n\n---\n\n")
        body = body.rstrip("\n") + "\n\n" + entry
        st = v._state(f"ai/{p}-days.json", {})
        day = st.get(d.isoformat(), {"models": [], "surfaces": [], "n": 0})
        day["n"] += 1
        if model and model not in day["models"]:
            day["models"].append(model)
        if surface not in day["surfaces"]:
            day["surfaces"].append(surface)
        st[d.isoformat()] = day
        v._save_state(f"ai/{p}-days.json", st)
        v._write(rel, v.frontmatter(props(p, d, day["models"], day["n"], day["surfaces"]), fm) + body)
        recent = v._state(f"ai/{p}-recent.json", [])
        recent.append({"at": when.isoformat(timespec="seconds"), "surface": surface, "model": model, "label": label,
                       "topic": topic(request), "note": day_stem(p, d), "anchor": f"{when:%H:%M} · {surface} · {label}"})
        v._save_state(f"ai/{p}-recent.json", recent[-RECENT_KEEP:])
        render_hub(v, p)
    v.log("ai", f"{c['emoji']} {c['name']}: {topic(request)}", reply.strip()[:400], source=f"{label} · {surface}", links=[day_stem(p, d)], at=when)
    return {"ok": True, "path": rel, "provider": p}


def render_hub(v, p: str):
    c = PROVIDERS[p]
    recent = v._state(f"ai/{p}-recent.json", [])
    days = v._state(f"ai/{p}-days.json", {})
    total = sum(x.get("n", 0) for x in days.values())
    models = sorted({m for x in days.values() for m in x.get("models", [])})
    lines = [f"# {c['emoji']} {c['name']}", "", f"_{c['about']}_", "",
             f"**{total} exchanges** over **{len(days)} days**" + (f" · models: {', '.join(models)}" if models else ""), "",
             f"Every conversation is saved here so the local AI learns from it. On the graph these notes glow "
             f"{'orange' if p == 'claude' else 'red' if p == 'gemini' else 'mint'}.", "",
             "## Latest", ""]
    if not recent:
        lines.append("_Nothing yet._")
    for e in reversed(recent[-30:]):
        lines.append(f"- `{e['at'][:16].replace('T', ' ')}` [[{e['note']}|{(e['topic'] or '(no text)').replace('|', '/').replace(']', ')').replace('[', '(')}]] · {e['surface']} · {e['label']}")
    lines += ["", "## By day", ""]
    for k in sorted(days, reverse=True)[:60]:
        lines.append(f"- [[{day_stem(p, dt.date.fromisoformat(k))}]] — {days[k]['n']} · {', '.join(days[k].get('surfaces', []))}")
    lines += ["", "## For the local AI", "",
              f"Search with `recall_ai` (provider `{p}`), the vault search (`ai_provider: {p}`) or the tag #ai/{p}."]
    v.write_managed(hub_rel(p), {"type": "ai-hub", "ai_provider": p, "exchanges": total, "tags": [f"ai/{p}", "ai/hub"],
                                 "cssclasses": [f"bl-ai-{p}"], "up": AI_NODE}, "\n".join(lines))


def ensure_hubs(v):
    for p in ("claude", "gemini"):
        if not v.path(hub_rel(p)).exists():
            render_hub(v, p)


def search(v, query: str, provider: str = "", limit: int = 8) -> list[dict]:
    """The local AI's quick lookup: exchanges whose topic or text matches, newest first."""
    words = [w for w in re.findall(r"[a-z0-9]{3,}", (query or "").lower())]
    provs = [provider_for(provider)] if provider else list(PROVIDERS)
    hits = []
    for p in provs:
        for e in v._state(f"ai/{p}-recent.json", []):
            hay = (e.get("topic", "") + " " + e.get("label", "")).lower()
            score = sum(w in hay for w in words) if words else 1
            if score:
                hits.append((score, e["at"], p, e))
    if words and len(hits) < limit:  # fall back to the full day notes
        for p in provs:
            root = v.path(f"{PROVIDERS[p]['folder']}/Conversations")
            if not root.is_dir():
                continue
            for f in sorted(root.rglob("*.md"), reverse=True)[:120]:
                text = f.read_text(encoding="utf-8", errors="ignore")
                for sec in text.split("\n## ")[1:]:
                    s = sum(w in sec.lower() for w in words)
                    if s >= max(1, len(words) // 2):
                        head = sec.splitlines()[0]
                        hits.append((s - 0.5, f.stem[-10:] + "T" + head[:5], p, {"note": f.stem, "anchor": head.strip(), "topic": sec[:300].split("**Paul**")[-1].strip("\n> ")[:160],
                                                                                 "label": head.split(" · ")[-1] if " · " in head else "", "surface": ""}))
    out, seen = [], set()
    for _s, at, p, e in sorted(hits, key=lambda h: (h[0], h[1]), reverse=True):
        key = (e.get("note"), e.get("anchor"))
        if key in seen:
            continue
        seen.add(key)
        out.append({"provider": p, "at": at, "note": e.get("note"), "anchor": e.get("anchor"), "topic": e.get("topic"), "model": e.get("label")})
        if len(out) >= limit:
            break
    return out


def read_exchange(v, note: str, anchor: str, max_chars: int = 6000) -> str:
    for p in PROVIDERS:
        m = re.search(r"(\d{4}-\d{2})-\d{2}$", note or "")
        if not m:
            continue
        rel = f"{PROVIDERS[p]['folder']}/Conversations/{m.group(1)}/{note}.md"
        try:
            text = v.path(rel).read_text(encoding="utf-8", errors="ignore")
        except (OSError, ValueError):
            continue
        i = text.find("## " + anchor)
        if i != -1:
            j = text.find("\n## ", i + 3)
            return text[i:j if j != -1 else None][:max_chars]
    return ""


def json_summary(v) -> dict:
    out = {}
    for p in ("claude", "gemini"):
        days = v._state(f"ai/{p}-days.json", {})
        recent = v._state(f"ai/{p}-recent.json", [])
        out[p] = {"exchanges": sum(x.get("n", 0) for x in days.values()), "days": len(days), "last": recent[-1] if recent else None}
    return out


def today(v, d: dt.date | None = None) -> list[dict]:
    """Today's Claude and Gemini exchanges for the daily dashboard."""
    d = d or v.now().date()
    out = []
    for p in ("claude", "gemini"):
        for e in v._state(f"ai/{p}-recent.json", []):
            if e.get("at", "").startswith(d.isoformat()):
                out.append(dict(e, provider=p))
    return sorted(out, key=lambda e: e["at"])


if __name__ == "__main__":  # quick look: python3 vault_ai.py
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import vault as vaultlib
    cfg = json.loads((Path.home() / ".config/blacklab-desktop/config.json").read_text())
    vv = vaultlib.Vault(Path(cfg.get("vault_dir", "~/Obsidian/BlackLab")).expanduser(), Path.home() / ".cache/blacklab-desktop/vault")
    print(json.dumps(json_summary(vv), indent=2))
