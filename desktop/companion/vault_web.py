"""Website-style dashboards inside the vault (companion 1.12). Unit tested in tests/test_vault_web.py.

    05 Dashboards/☀️ Today.md            the homepage: weather, the morning brief, today's schedule, tasks (now / next /
                                          later), last night's system check, quick capture and "ask your vault"
    05 Dashboards/🏢 Facility Command.md  PM and compliance countdowns, work orders, rounds, credentials due, references
    05 Dashboards/🎓 Learning Hub.md      credentials with time-left bars, coursework, flashcards, a study streak,
                                          the offline library shelf
    05 Dashboards/🖥️ Home Lab.md          services, CPU / memory / disk / both GPUs (live on this computer), AI models,
                                          the last system check and action buttons

Each page is one HTML block inside the managed block, styled by `.obsidian/snippets/blacklab-web.css` (written here and
enabled once), so it reads like a web page in Obsidian on the computer and on the phone. Obsidian strips scripts and
inline styles are avoided on purpose: every bar and ring uses a class (`blw-p-0` … `blw-p-100`).

Interactive parts are placeholders the BlackLab Assistant plugin fills in on the computer (desktop/obsidian/
blacklab-assistant): `data-bl-widget="capture|ask|frame"`, `data-bl-action="…"` buttons and `data-bl-live="homelab"`
(refreshed from GET /local/dashboard/live while the page is open). Without the plugin, on the phone, they say so.

Nothing volatile (clock time, CPU load) goes into the files, so they change only when something real changes; that
keeps git history and the phone sync quiet. Every title from the phone goes through guards.vault_safe.
"""
from __future__ import annotations

import datetime as dt
import html
import json
import os
import re
import shutil
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

import guards
from vault import FOLDERS

DASH_DIR = "05 Dashboards"
PAGES = {  # id: (file name, nav label, icon, node it belongs to)
    "today": ("☀️ Today", "Today", "☀️", "◆ Time, Goals & Planning"),
    "facility": ("🏢 Facility Command", "Facility", "🏢", "◆ Facility Operations"),
    "learning": ("🎓 Learning Hub", "Learning", "🎓", "◆ Learning & Credentials"),
    "homelab": ("🖥️ Home Lab", "Home Lab", "🖥️", "◆ IT, Networking & Systems"),
}
SNIPPET = "blacklab-web"
PAGE_CLASS = "bl-web-page"
ACTIONS = ("refresh", "vault_pass", "health_scan", "atlas")  # what a dashboard button may ask the companion to do
HEALTH_LATEST = Path.home() / ".local/state/blacklab-desktop/health/latest.json"
PHONE_CONTEXT = Path.home() / ".cache/blacklab-desktop/phone-context.json"
KIWIX = "http://127.0.0.1:8780"


def rel(page: str) -> str:
    return f"{DASH_DIR}/{PAGES[page][0]}.md"


# ---------------------------------------------------------------- tiny HTML kit (everything escaped)

def esc(s) -> str:
    return html.escape(str(s if s is not None else ""), quote=True)


def link(target: str, label: str | None = None, cls: str = "") -> str:
    """An Obsidian internal link that works inside raw HTML."""
    t = esc(target)
    c = f"internal-link {cls}".strip()
    return f'<a class="{c}" data-href="{t}" href="{t}">{esc(label if label is not None else target)}</a>'


def ext(url: str, label: str, cls: str = "") -> str:
    return f'<a class="external-link {cls}" href="{esc(url)}" target="_blank" rel="noopener">{esc(label)}</a>'


def pct_class(p: float) -> str:
    """0–100 → blw-p-0, blw-p-5, … (bars and rings are classes, never inline styles)."""
    p = max(0.0, min(100.0, float(p or 0)))
    return f"blw-p-{int(round(p / 5.0)) * 5}"


def pill(text: str, tone: str = "info") -> str:
    return f'<span class="blw-pill blw-{tone}">{esc(text)}</span>'


def bar(p: float, tone: str = "accent", label: str = "") -> str:
    lab = f'<span class="blw-bar-label">{esc(label)}</span>' if label else ""
    return f'<div class="blw-bar blw-{tone}">{lab}<div class="blw-bar-track"><div class="blw-bar-fill {pct_class(p)}"></div></div></div>'


def ring(p: float, value: str, label: str, tone: str = "accent") -> str:
    return (f'<div class="blw-ring blw-{tone} {pct_class(p)}"><div class="blw-ring-in"><b>{esc(value)}</b>'
            f'<small>{esc(label)}</small></div></div>')


def tile(label: str, value, sub: str = "", tone: str = "info", icon: str = "") -> str:
    ic = f'<span class="blw-tile-icon">{esc(icon)}</span>' if icon else ""
    return (f'<div class="blw-tile blw-{tone}">{ic}<div class="blw-tile-value">{esc(value)}</div>'
            f'<div class="blw-tile-label">{esc(label)}</div>' + (f'<div class="blw-tile-sub">{esc(sub)}</div>' if sub else "") + "</div>")


def card(title: str, body: str, icon: str = "", cls: str = "", more: str = "") -> str:
    head = f'<div class="blw-card-head"><span class="blw-card-title">{esc(icon) + " " if icon else ""}{esc(title)}</span>{more}</div>'
    return f'<section class="blw-card {cls}">{head}<div class="blw-card-body">{body}</div></section>'


def empty(text: str) -> str:
    return f'<p class="blw-empty">{esc(text)}</p>'


def rows(items: list[str]) -> str:
    return '<ul class="blw-list">' + "".join(f"<li>{i}</li>" for i in items) + "</ul>" if items else ""


def button(action: str, label: str, tone: str = "accent") -> str:
    return f'<button class="blw-btn blw-{tone}" data-bl-action="{esc(action)}">{esc(label)}</button>'


def widget(kind: str, hint: str, **attrs) -> str:
    extra = "".join(f' data-{esc(k)}="{esc(v)}"' for k, v in attrs.items())
    return f'<div class="blw-widget" data-bl-widget="{esc(kind)}"{extra}><p class="blw-widget-hint">{esc(hint)}</p></div>'


def nav(active: str) -> str:
    items = []
    for pid, (name, label, icon, _) in PAGES.items():
        cls = "blw-nav-link" + (" is-active" if pid == active else "")
        items.append(link(f"{DASH_DIR}/{name}", f"{icon} {label}", cls))
    extra = [link("◉ Nexus", "◉ Nexus", "blw-nav-link blw-nav-quiet"), link("Home", "⌂ Home", "blw-nav-link blw-nav-quiet")]
    return ('<nav class="blw-nav"><span class="blw-brand"><span class="blw-brand-dot"></span>BlackLab</span>'
            f'<span class="blw-nav-links">{"".join(items)}</span><span class="blw-nav-end">{"".join(extra)}</span></nav>')


def hero(eyebrow: str, title: str, sub: str = "", pills: list[str] | None = None, actions: str = "") -> str:
    p = f'<div class="blw-hero-pills">{"".join(pills)}</div>' if pills else ""
    a = f'<div class="blw-hero-actions">{actions}</div>' if actions else ""
    return (f'<header class="blw-hero"><div class="blw-eyebrow">{esc(eyebrow)}</div><div class="blw-title">{esc(title)}</div>'
            + (f'<div class="blw-sub">{esc(sub)}</div>' if sub else "") + p + a + "</header>")


def page(pid: str, head: str, tiles: list[str], sections: list[str], foot: str = "") -> str:
    """One HTML block with no blank lines (a blank line would end Obsidian's HTML block)."""
    out = (f'<div class="bl-web" data-page="{esc(pid)}">' + nav(pid) + head
           + (f'<div class="blw-tiles">{"".join(tiles)}</div>' if tiles else "")
           + f'<div class="blw-grid">{"".join(sections)}</div>'
           + f'<footer class="blw-foot">{foot or "BlackLab Desktop writes this page · add your own notes below it, they are kept"}</footer></div>')
    return re.sub(r"\n\s*\n", "\n", out)


# ---------------------------------------------------------------- data helpers (pure; tests feed them directly)

def safe(text) -> str:
    ok, t = guards.vault_safe(str(text or ""))
    return re.sub(r"\s+", " ", t).strip() if ok else ""


def load_json(path: Path, default):
    try:
        return json.loads(Path(path).read_text())
    except Exception:
        return default


TASK = re.compile(r"^\s*- \[([ xX])\] (.*?)\s*$")
DUE = re.compile(r"📅\s*(\d{4}-\d{2}-\d{2})")
MARKS = re.compile(r"\s*(?:[⏫🔼🔽⏬]|📅\s*\d{4}-\d{2}-\d{2}|\^t\d+|⌛)")


def parse_tasks(text: str) -> list[dict]:
    """Open tasks from Tasks.md: title, due, high priority, list (its heading)."""
    out, section = [], ""
    for ln in (text or "").splitlines():
        if ln.startswith("## "):
            section = ln[3:].strip()
            continue
        m = TASK.match(ln)
        if not m or m.group(1) != " ":
            continue
        body = m.group(2)
        d = DUE.search(body)
        title = safe(MARKS.sub("", body))
        if title:
            out.append({"title": title, "due": d.group(1) if d else "", "high": "⏫" in body, "list": section})
    return out


def bucket_tasks(tasks: list[dict], today: dt.date) -> dict:
    t, week = today.isoformat(), (today + dt.timedelta(days=7)).isoformat()
    now = sorted([x for x in tasks if x["due"] and x["due"] <= t], key=lambda x: (x["due"], not x["high"]))
    nxt = sorted([x for x in tasks if x["due"] and t < x["due"] <= week], key=lambda x: x["due"])
    later = [x for x in tasks if not x["due"] or x["due"] > week]
    return {"now": now, "next": nxt, "later": later, "overdue": sum(1 for x in now if x["due"] < t)}


def events_on(ctx: dict, d: dt.date) -> list[dict]:
    out = []
    for e in ctx.get("events") or []:
        start = str(e.get("start") or "")
        if start[:10] == d.isoformat():
            title = safe(e.get("title"))
            if title:
                out.append({"time": start[11:16], "end": str(e.get("end") or "")[11:16], "title": title, "where": safe(e.get("location"))})
    return sorted(out, key=lambda e: e["time"])


def brief_top(v, today: dt.date) -> tuple[str, list[str]]:
    """The newest morning brief from the last two days: its name and its "Top 3 today" lines (plain text)."""
    root = v.path(f"{FOLDERS['meetings']}/Briefs")
    if not root.is_dir():
        return "", []
    names = sorted(p.stem for p in root.glob("*.md") if p.stem[:10] >= (today - dt.timedelta(days=1)).isoformat())
    if not names:
        return "", []
    text = (root / f"{names[-1]}.md").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^## Top 3 today\s*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    lines = []
    for ln in (m.group(1) if m else "").splitlines():
        ln = re.sub(r"^\s*(?:\d+\.|[-*])\s*", "", ln).strip()
        ln = re.sub(r"[*_`]+", "", ln).strip()
        if ln:
            lines.append(safe(ln)[:220])
    return names[-1], [l for l in lines if l][:3]


def part_of_day(h: int) -> str:
    return "Good morning" if h < 12 else "Good afternoon" if h < 17 else "Good evening"


def health_tone(status: str) -> str:
    return {"ok": "ok", "warn": "warn", "fail": "bad"}.get(status or "", "info")


def due_items(v) -> list[dict]:
    """PMs and inspections from the asset notes: overdue (red) or due soon (amber), with days left."""
    import facility_ops
    today = v.now().date()
    out = []
    for r in facility_ops._due_rows(v):
        m = re.match(r"^(🔴|🟡) (.*) \(due (\d{4}-\d{2}-\d{2})\)$", r)
        if not m:
            continue
        due = dt.date.fromisoformat(m.group(3))
        what, _, asset = m.group(2).partition(" — ")
        out.append({"what": safe(what), "asset": safe(asset), "due": due, "days": (due - today).days, "over": m.group(1) == "🔴"})
    return sorted(out, key=lambda x: x["days"])


def human_days(n: int) -> str:
    if n < 0:
        return f"{-n} day{'s' if n != -1 else ''} overdue"
    if n == 0:
        return "due today"
    return f"in {n} day{'s' if n != 1 else ''}"


def gib(n: float) -> str:
    return f"{n / 2**30:.1f} GB" if n >= 2**30 else f"{n / 2**20:.0f} MB"


# ---------------------------------------------------------------- 1: Today

def render_today(v, ctx: dict, health: dict) -> str:
    now = v.now()
    today = now.date()
    tasks = parse_tasks(v.user_text(f"{FOLDERS['work']}/Tasks.md") + "\n" + _managed(v, f"{FOLDERS['work']}/Tasks.md"))
    b = bucket_tasks(tasks, today)
    evs, tomorrow = events_on(ctx, today), events_on(ctx, today + dt.timedelta(days=1))
    wos = v._state("dash/tasks.json", {}).get("workorders", [])
    cards_due = int(ctx.get("flashcards_due") or 0) if str(ctx.get("flashcards_due") or "0").isdigit() else 0
    w = ctx.get("weather") or {}
    weather = safe(w.get("summary")) or "No weather from the phone yet."
    alerts = [safe(a if isinstance(a, str) else a.get("title") or a.get("event") or "") for a in (w.get("alerts") or [])]
    phone_at = ctx.get("received")
    pills = [pill(f"System · {health.get('status', 'no report').upper()}", health_tone(health.get("status")))]
    if phone_at:
        pills.append(pill("Phone update " + dt.datetime.fromtimestamp(phone_at, v.tz).strftime("%b %d %H:%M"), "info"))
    pills += [pill(f"⚠️ {a}", "bad") for a in alerts if a]
    head = hero(today.strftime("%A · %B ") + str(today.day), part_of_day(now.hour), weather, pills)

    tiles = [tile("due today & overdue", len(b["now"]), f"{b['overdue']} overdue" if b["overdue"] else "on track", "bad" if b["overdue"] else "accent", "✅"),
             tile("events today", len(evs), (evs[0]["time"] + " " + evs[0]["title"]) if evs else "a clear calendar", "info", "📅"),
             tile("open work orders", len(wos), "from the phone", "warn" if wos else "ok", "🛠️"),
             tile("flashcards due", cards_due, "review on the phone", "accent", "🗂️")]

    name, top = brief_top(v, today)
    brief = (rows([esc(t) for t in top]) if top else empty("No brief yet today — it runs at 07:10.")) + (
        f'<div class="blw-more">{link(name, "Read the full brief →")}</div>' if name else "")

    def ev_html(es):
        return '<ol class="blw-timeline">' + "".join(
            f'<li><span class="blw-time">{esc(e["time"])}</span><span class="blw-ev">{esc(e["title"])}'
            + (f'<small>{esc(e["where"])}</small>' if e["where"] else "") + "</span></li>" for e in es) + "</ol>"
    sched = (ev_html(evs) if evs else empty("Nothing on the calendar today.")) + (
        f'<div class="blw-subhead">Tomorrow</div>{ev_html(tomorrow[:4])}' if tomorrow else "")

    def task_rows(ts, limit):
        return rows([f'{"<b>⏫</b> " if t["high"] else ""}{esc(t["title"])}' + (f' {pill(human_days((dt.date.fromisoformat(t["due"]) - today).days), "bad" if t["due"] < today.isoformat() else "warn")}' if t["due"] else "")
                     + f' <small class="blw-muted">{esc(t["list"])}</small>' for t in ts[:limit]])
    task_body = ('<div class="blw-cols3">'
                 f'<div><div class="blw-subhead">Now · {len(b["now"])}</div>{task_rows(b["now"], 8) or empty("Nothing due.")}</div>'
                 f'<div><div class="blw-subhead">Next 7 days · {len(b["next"])}</div>{task_rows(b["next"], 8) or empty("Nothing scheduled.")}</div>'
                 f'<div><div class="blw-subhead">Later · {len(b["later"])}</div>{task_rows(b["later"], 6) or empty("All clear.")}</div></div>')

    issues = health.get("issues") or []
    sys_body = (f'<div class="blw-banner blw-{health_tone(health.get("status"))}"><b>{esc(health.get("title") or "No system check yet")}</b>'
                f'<span>{esc(health.get("summary") or "The nightly check runs at 03:00.")}</span></div>'
                + rows([pill(i.get("area", ""), health_tone(i.get("status"))) + " " + esc(i.get("title", "")) for i in issues[:5]]))
    ai = _ai_today(v, today)

    sections = [
        card("Morning brief", brief, "📰", "blw-span-2"),
        card("Schedule", sched, "📅", "", link(f"{FOLDERS['work']}/Calendar", "Calendar →", "blw-card-link")),
        card("Tasks", task_body, "✅", "blw-span-3", link(f"{FOLDERS['work']}/Tasks", "Open Tasks →", "blw-card-link")),
        card("Quick capture", widget("capture", "Open this page in Obsidian on the computer to capture from here — or tag any line #task."), "⚡"),
        card("Ask your vault", widget("ask", "On the computer, ask a question here and the local AI answers from your notes."), "🔎"),
        card("System", sys_body, "🖥️", "", link(f"{DASH_DIR}/{PAGES['homelab'][0]}", "Home Lab →", "blw-card-link")),
        card("AI today", ai, "🤖", "blw-span-2"),
        card("Jump to", '<div class="blw-chips">' + "".join(link(t, l, "blw-chip") for t, l in (
            ("10 Facility/🏢 Command Center", "🏢 Command Center"), ("02 Projects/🗂️ Project Board", "🗂️ Project Board"),
            (v.day_rel(today).removesuffix(".md"), "📓 Today's note"), ("40 Learning/🎓 Study Hub", "🎓 Study Hub"),
            ("◉ Nexus", "◉ Nexus"), ("00 Inbox", "📥 Inbox"))) + "</div>", "🧭"),
    ]
    return page("today", head, tiles, sections)


def _managed(v, rel_: str) -> str:
    p = v.path(rel_)
    if not p.exists():
        return ""
    t = p.read_text(encoding="utf-8", errors="replace")
    import vault as vaultlib
    if vaultlib.BEGIN in t and vaultlib.END in t:
        return t[t.index(vaultlib.BEGIN):t.index(vaultlib.END)]
    return ""


def _ai_today(v, today: dt.date) -> str:
    try:
        import vault_ai
        ai = vault_ai.today(v, today)
    except Exception:
        ai = []
    if not ai:
        return empty("No Claude or Gemini conversations yet today — open the assistant from the 🤖 ribbon button.")
    items = []
    for e in ai[-6:]:
        tone = "claude" if e["provider"] == "claude" else "gemini"
        items.append(pill(e["provider"].title(), tone) + " " + esc(safe(e.get("topic"))[:110]))
    return rows(items)


# ---------------------------------------------------------------- 2: Facility Command

def render_facility(v, ctx: dict) -> str:
    import facility_ops
    today = v.now().date()
    due = due_items(v)
    over = [d for d in due if d["over"]]
    wos = v._state("dash/tasks.json", {}).get("workorders", [])
    rounds_dir = v.path(facility_ops.ROUNDS)
    rounds = sorted(p.stem for p in rounds_dir.glob(f"{today.isoformat()} *.md")) if rounds_dir.is_dir() else []
    try:
        import vault_credentials
        creds = vault_credentials.due_soon(v, today)
    except Exception:
        creds = []
    panels = len(list(v.path("10 Facility/Panels").glob("*.md"))) - 1 if v.path("10 Facility/Panels").is_dir() else 0
    sds = len(list(v.path("10 Facility/SDS Binder").glob("*.md"))) if v.path("10 Facility/SDS Binder").is_dir() else 0
    head = hero(today.strftime("%A · %B ") + str(today.day), "Facility Command",
                f"{len(over)} overdue · {len(due) - len(over)} due soon · {len(wos)} open work orders",
                [pill("LOTO + test before touch", "warn"), pill(f"{max(panels, 0)} panels mapped", "info"), pill(f"{sds} SDS on file", "info")])
    tiles = [tile("overdue PMs", len(over), "act first" if over else "none", "bad" if over else "ok", "🔴"),
             tile("due soon", len(due) - len(over), "next 30 days", "warn" if len(due) > len(over) else "ok", "🟡"),
             tile("open work orders", len(wos), "two-way with the phone", "warn" if wos else "ok", "🛠️"),
             tile("rounds today", len(rounds), "EOC · safety · life safety", "accent", "🚶")]

    def countdown(d):
        tone = "bad" if d["over"] else "warn"
        p = 100 if d["days"] <= 0 else max(5, 100 - d["days"] * 100 / 30)
        return (f'<div class="blw-count"><div class="blw-count-head"><b>{esc(d["what"])}</b> <span class="blw-muted">{esc(d["asset"])}</span>'
                f'{pill(human_days(d["days"]), tone)}</div>{bar(p, tone)}<small class="blw-muted">due {d["due"].isoformat()}</small></div>')
    pm = "".join(countdown(d) for d in due[:12]) or empty("Nothing overdue or due in the next 30 days.")

    def wo_card(w):
        d = w.get("due") or ""
        late = d and d < today.isoformat()
        return (f'<div class="blw-kanban-item {"is-late" if late else ""}">{link(v.safe_name(w["title"], "Work order"), safe(w["title"]) or "Work order")}'
                + (pill(("⚠️ " if late else "") + "due " + d, "bad" if late else "info") if d else pill("no due date", "info")) + "</div>")
    urgent = [w for w in wos if w.get("due") and w["due"] <= (today + dt.timedelta(days=2)).isoformat()]
    rest = [w for w in wos if w not in urgent]
    board = ('<div class="blw-kanban">'
             f'<div class="blw-kanban-col"><div class="blw-subhead">Urgent · {len(urgent)}</div>{"".join(wo_card(w) for w in urgent) or empty("None")}</div>'
             f'<div class="blw-kanban-col"><div class="blw-subhead">Open · {len(rest)}</div>{"".join(wo_card(w) for w in rest[:12]) or empty("None")}</div></div>'
             '<p class="blw-hint">Type a line ending in <code>#workorder</code> in any note and it is filed on your phone within a minute.</p>')
    shift = [e for e in events_on(ctx, today) if e["where"] or re.search(r"maint|admin|day start|round", e["title"], re.I)]
    week = []
    for i in range(1, 6):
        d = today + dt.timedelta(days=i)
        es = [e for e in events_on(ctx, d) if re.search(r"maint|inspect|round|vendor|fire|drill|generator|elevator", e["title"], re.I)]
        week += [f'<span class="blw-time">{d.strftime("%a")} {esc(e["time"])}</span> {esc(e["title"])}' for e in es]
    rounds_body = (rows([link(r, r) for r in rounds]) if rounds else empty("No rounds started yet today.")) + (
        '<div class="blw-chips">' + link("10 Facility/Templates", "📋 Round templates", "blw-chip") + "</div>")
    refs = '<div class="blw-chips">' + "".join(link(t, l, "blw-chip") for t, l in (
        ("⚡ Panel Directory", "⚡ Panel Directory"), ("🧪 SDS Binder", "🧪 SDS Binder"), ("10 Facility/🏢 Command Center", "🏢 Command Center (list view)"),
        ("◆ Vendors & Contracts", "🤝 Vendors & Contracts"), ("◆ Life Safety & Compliance", "🧯 Life Safety & Compliance"), ("30 Work/Calendar", "📅 Calendar"))) + "</div>"
    sections = [
        card("PM & compliance countdown", pm, "⏱️", "blw-span-2"),
        card("Shift today", (rows([f'<span class="blw-time">{esc(e["time"])}</span> {esc(e["title"])}' for e in shift]) or empty("No shift blocks on the calendar."))
             + ('<div class="blw-subhead">Coming up this week</div>' + rows(week[:8]) if week else ""), "🗓️"),
        card("Work orders", board, "🛠️", "blw-span-2", link("30 Work/Tasks", "Tasks →", "blw-card-link")),
        card("Rounds today", rounds_body, "🚶"),
        card("Credentials due", rows([esc(c) for c in creds[:8]]) or empty("Everything current."), "🏅", "", link("40 Learning/🏅 Credentials", "All →", "blw-card-link")),
        card("References", refs, "📚", "blw-span-2"),
    ]
    return page("facility", head, tiles, sections)


# ---------------------------------------------------------------- 3: Learning Hub

def credential_view(r: dict, today: dt.date) -> dict:
    """Time left for one credential: percent of its renewal period remaining, a tone and a label."""
    if r.get("never"):
        return {"p": 100, "tone": "ok", "label": "No expiry", "value": "∞"}
    renew, issued = r.get("renew"), r.get("issued")
    if not renew:
        return {"p": 0, "tone": "info", "label": "Renewal unknown", "value": "?"}
    days = (renew - today).days
    if days < 0:
        return {"p": 0, "tone": "bad", "label": f"Expired {-days} days ago", "value": "0"}
    span = max(1, (renew - issued).days) if issued else 730
    tone = "warn" if days <= 60 else "ok"
    value = f"{days // 30}mo" if days >= 60 else f"{days}d"
    return {"p": min(100, days * 100 / span), "tone": tone, "label": f"Renew by {renew.isoformat()}", "value": value}


def study_days(v, today: dt.date, weeks: int = 12) -> list[tuple[dt.date, int]]:
    """Days with learning activity: timeline entries of kind learning / flashcards, and notes edited under 40 Learning."""
    start = today - dt.timedelta(days=today.weekday() + 7 * (weeks - 1))
    counts: dict[dt.date, int] = {}
    for i in range((today - start).days + 1):
        d = start + dt.timedelta(days=i)
        for e in v._state(f"timeline/{d.isoformat()}.json", []):
            if e.get("kind") == "learning" or re.search(r"flashcard|study|course|class|exam|ceu", str(e.get("title", "")), re.I):
                counts[d] = counts.get(d, 0) + 1
    root = v.path(FOLDERS["learning"])
    if root.is_dir():
        for p in root.rglob("*.md"):
            d = dt.date.fromtimestamp(p.stat().st_mtime)
            if start <= d <= today and "Flashcards" not in p.parts:
                counts[d] = counts.get(d, 0) + 1
    return [(start + dt.timedelta(days=i), counts.get(start + dt.timedelta(days=i), 0)) for i in range((today - start).days + 1)]


def heatmap(days: list[tuple[dt.date, int]]) -> str:
    cells = []
    for d, n in days:
        lvl = 0 if n == 0 else 1 if n == 1 else 2 if n <= 3 else 3
        cells.append(f'<span class="blw-heat blw-heat-{lvl}" aria-label="{d.isoformat()}: {n}"></span>')
    return f'<div class="blw-heatmap">{"".join(cells)}</div>'


def render_learning(v, ctx: dict) -> str:
    today = v.now().date()
    try:
        import vault_credentials
        creds = vault_credentials.merged(v)
    except Exception:
        creds = []
    views = sorted(((r, credential_view(r, today)) for r in creds), key=lambda rv: ({"bad": 0, "warn": 1, "info": 2, "ok": 3}[rv[1]["tone"]], rv[1]["p"]))
    learning = v._state("dash/learning.json", {})
    asg = [a for a in (ctx.get("assignments") or learning.get("assignments") or []) if a.get("status") not in ("SUBMITTED", "GRADED")]
    exams = ctx.get("exams") or []
    decks = v._state("dash/decks.json", [])
    cards_due = sum(d.get("due", 0) for d in decks)
    days = study_days(v, today)
    streak = 0
    for d, n in reversed(days):
        if n == 0 and d != today:
            break
        streak += 1 if n else 0
    active = sum(1 for _, n in days[-14:] if n)
    current = sum(1 for _, vw in views if vw["tone"] == "ok")
    soon = sum(1 for _, vw in views if vw["tone"] in ("warn", "bad"))
    head = hero(today.strftime("%A · %B ") + str(today.day), "Learning Hub",
                "Credentials, coursework, flashcards and your offline library in one place.",
                [pill(f"{current} credentials current", "ok")] + ([pill(f"{soon} need attention", "warn")] if soon else []))
    tiles = [tile("credentials current", current, f"of {len(views)}", "ok", "🏅"),
             tile("renew or expired", soon, "check HR / issuer", "bad" if soon else "ok", "⏳"),
             tile("assignments open", len(asg), f"{len(exams)} exams scheduled", "accent", "📝"),
             tile("study days (14 d)", active, f"{streak}-day streak", "info", "🔥")]
    grid = '<div class="blw-cards">' + "".join(
        f'<div class="blw-mini blw-{vw["tone"]}">{ring(vw["p"], vw["value"], "left", vw["tone"])}<div class="blw-mini-text">'
        f'<b>{esc(safe(r["name"]))}</b><small>{esc(safe(r.get("issuer")))}</small>{pill(vw["label"], vw["tone"])}</div></div>'
        for r, vw in views) + "</div>" if views else empty("No credentials yet — add rows to My credentials in 🏅 Credentials.")

    def asg_row(a):
        d = str(a.get("due") or "")[:10]
        late = d and d < today.isoformat()
        return f'{esc(safe(a.get("title")))} <small class="blw-muted">{esc(safe(a.get("course") or a.get("kind") or ""))}</small>' + (
            pill(("late · " if late else "") + d, "bad" if late else "warn") if d else "")
    course = (rows([asg_row(a) for a in sorted(asg, key=lambda a: str(a.get("due") or "9"))[:10]]) or empty("No open assignments."))
    if exams:
        course += '<div class="blw-subhead">Exams</div>' + rows([f'{esc(safe(e.get("title") or e.get("name")))} {pill(str(e.get("date") or e.get("when") or "")[:10], "accent")}' for e in exams[:6]])
    deck_body = rows([link(f"40 Learning/Flashcards/{d['name']}", d["name"]) + f' {pill(str(d.get("due", 0)) + " due", "accent" if d.get("due") else "info")} <small class="blw-muted">{d.get("cards", 0)} cards</small>'
                      for d in decks[:10]]) or empty("No decks yet — write front :: back lines in any note tagged #flashcards.")
    try:
        import library
        books = [b for b in library.load_state().get("books", {}).values() if b.get("ok")]
    except Exception:
        books = []
    shelf = ('<div class="blw-chips">' + "".join(ext(f"{KIWIX}/content/{Path(b['file']).stem}", b.get("title") or Path(b["file"]).stem, "blw-chip")
                                               for b in sorted(books, key=lambda b: b.get("title", ""))) + "</div>") if books else empty("The offline library is empty.")
    shelf += widget("frame", "On the computer the library opens right here.", src=KIWIX + "/")
    sections = [
        card("Credentials", grid, "🏅", "blw-span-3", link("40 Learning/🏅 Credentials", "Edit →", "blw-card-link")),
        card("Coursework", course, "📝", "blw-span-2", link("40 Learning/🎓 Study Hub", "Study Hub →", "blw-card-link")),
        card("Flashcards", f'<div class="blw-big">{cards_due}<small> due today</small></div>' + deck_body, "🗂️"),
        card("Study streak", heatmap(days) + f'<p class="blw-hint">{active} active day{"s" if active != 1 else ""} in the last two weeks · darker = more activity (notes edited in 40 Learning, flashcard and study entries in the timeline).</p>', "🔥", "blw-span-2"),
        card("Ask about your notes", widget("ask", "On the computer, ask a study question and the local AI answers from your notes."), "🔎"),
        card(f"Library shelf · {len(books)} books", shelf, "📚", "blw-span-3"),
    ]
    return page("learning", head, tiles, sections)


# ---------------------------------------------------------------- 4: Home Lab

def _run(cmd: list[str], timeout=5) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout.strip()
    except Exception:
        return ""


def _port(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def _get_json(url: str, timeout=2):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read())
    except Exception:
        return None


def collect_system(profile: dict | None = None, run=_run, port=_port, get_json=_get_json, ollama="http://127.0.0.1:11434") -> dict:
    """A snapshot of this computer for the Home Lab page. Every probe is cheap and local; a failed probe is just missing."""
    profile = profile or load_json(Path.home() / ".config/blacklab-desktop/autopilot.json", {})
    user_units = list(dict.fromkeys(list(profile.get("keep_user_units") or ["blacklab-desktop.service"]) + ["familycraft.service"]))
    sys_units = list(profile.get("keep_system_services") or ["ollama.service", "tailscaled.service"])
    services = []
    out = run(["systemctl", "--user", "is-active", *user_units]).splitlines()
    for u, s in zip(user_units, out + [""] * len(user_units)):
        services.append({"name": u.removesuffix(".service"), "state": s or "unknown", "scope": "user"})
    out = run(["systemctl", "is-active", *sys_units]).splitlines()
    for u, s in zip(sys_units, out + [""] * len(sys_units)):
        services.append({"name": u.removesuffix(".service"), "state": s or "unknown", "scope": "system"})
    for name, p in (("kb-core (vault recall)", 8100), ("kiwix library", 8780), ("companion UI", 8770)):
        services.append({"name": name, "state": "active" if port(p) else "inactive", "scope": "port"})
    mem = {}
    try:
        for ln in Path("/proc/meminfo").read_text().splitlines():
            k, v = ln.split(":", 1)
            mem[k] = int(v.split()[0]) * 1024
    except Exception:
        pass
    try:
        load1 = os.getloadavg()[0]
    except OSError:
        load1 = 0.0
    try:
        uptime = float(Path("/proc/uptime").read_text().split()[0])
    except Exception:
        uptime = 0.0
    disks = []
    for label, path in (("System", "/"), ("Home", str(Path.home()))):
        try:
            u = shutil.disk_usage(path)
            if not any(d["total"] == u.total and d["used"] == u.used for d in disks):
                disks.append({"label": label, "total": u.total, "used": u.used})
        except OSError:
            pass
    rota = run(["lsblk", "-dno", "NAME,ROTA,MODEL"])
    gpus = []
    for ln in run(["nvidia-smi", "--query-gpu=name,memory.used,memory.total,utilization.gpu,temperature.gpu", "--format=csv,noheader,nounits"]).splitlines():
        c = [x.strip() for x in ln.split(",")]
        if len(c) >= 5 and c[1].isdigit():
            gpus.append({"name": c[0], "used": int(c[1]) * 2**20, "total": int(c[2]) * 2**20, "busy": int(c[3]) if c[3].isdigit() else 0,
                         "temp": int(c[4]) if c[4].isdigit() else None})
    for dev in sorted(Path("/sys/class/drm").glob("card[0-9]/device")):
        try:
            used, total = int((dev / "mem_info_vram_used").read_text()), int((dev / "mem_info_vram_total").read_text())
            busy = int((dev / "gpu_busy_percent").read_text())
        except Exception:
            continue
        gpus.append({"name": f"AMD Radeon {total / 2**30:.0f} GB", "used": used, "total": total, "busy": busy, "temp": None})
    tags = (get_json(ollama + "/api/tags") or {}).get("models") or []
    ps = {m.get("name"): m for m in (get_json(ollama + "/api/ps") or {}).get("models") or []}
    models = [{"name": m.get("name"), "size": m.get("size", 0), "loaded": m.get("name") in ps,
               "vram": (ps.get(m.get("name")) or {}).get("size_vram", 0)} for m in tags]
    return {"host": os.uname().nodename, "cpus": os.cpu_count() or 1, "load1": load1, "uptime": uptime,
            "mem_total": mem.get("MemTotal", 0), "mem_avail": mem.get("MemAvailable", 0), "disks": disks,
            "spinning": any(l.split()[1] == "1" for l in rota.splitlines() if len(l.split()) >= 2 and not l.startswith("loop")),
            "gpus": gpus, "services": services, "models": sorted(models, key=lambda m: (not m["loaded"], m["name"] or ""))}


def homelab_live(s: dict, coarse: bool = False) -> str:
    """The live part of Home Lab (services + gauges). coarse=True for the file on disk: rounded so it rarely changes."""
    def r(p):
        return int(p / 10 + 0.5) * 10 if coarse else p
    up = [x for x in s.get("services", []) if x["state"] == "active"]
    svc = '<div class="blw-services">' + "".join(
        f'<div class="blw-svc blw-{"ok" if x["state"] == "active" else "bad" if x["state"] in ("failed", "inactive") else "info"}">'
        f'<span class="blw-dot"></span><b>{esc(x["name"])}</b><small>{esc(x["state"])} · {esc(x["scope"])}</small></div>' for x in s.get("services", [])) + "</div>"
    cpu = min(100.0, 100.0 * s.get("load1", 0) / max(1, s.get("cpus", 1)))
    mem_used = max(0, s.get("mem_total", 0) - s.get("mem_avail", 0))
    mem_p = 100.0 * mem_used / max(1, s.get("mem_total", 1))
    gauges = [ring(r(cpu), f"{r(cpu):.0f}%", "CPU", "bad" if cpu > 85 else "warn" if cpu > 60 else "accent"),
              ring(r(mem_p), f"{r(mem_p):.0f}%", "Memory", "bad" if mem_p > 90 else "warn" if mem_p > 75 else "accent")]
    for d in s.get("disks", []):
        p = 100.0 * d["used"] / max(1, d["total"])
        gauges.append(ring(round(p), f"{p:.0f}%", d["label"] + " disk", "bad" if p > 90 else "warn" if p > 80 else "accent"))
    gpu_rows = []
    for g in s.get("gpus", []):
        p = 100.0 * g["used"] / max(1, g["total"])
        gpu_rows.append(f'<div class="blw-gpu"><b>{esc(g["name"])}</b>' + bar(r(p), "accent", f"VRAM {gib(g['used'])} / {gib(g['total'])}")
                        + bar(r(g.get("busy") or 0), "info", f"busy {r(g.get('busy') or 0):.0f}%" + (f" · {g['temp']}°C" if g.get("temp") and not coarse else "")) + "</div>")
    return (f'<div class="blw-live-head">{pill(f"{len(up)}/{len(s.get("services", []))} services up", "ok" if len(up) == len(s.get("services", [])) else "warn")}'
            + (pill("live", "accent") if not coarse else pill("snapshot · live on the computer", "info")) + "</div>"
            + svc + f'<div class="blw-gauges">{"".join(gauges)}</div>' + "".join(gpu_rows))


def render_homelab(v, s: dict, health: dict) -> str:
    up = sum(1 for x in s.get("services", []) if x["state"] == "active")
    loaded = [m for m in s.get("models", []) if m["loaded"]]
    days = int(s.get("uptime", 0) // 86400)
    head = hero(s.get("host", "this computer"), "Home Lab Console", f"{s.get('cpus', 0)} CPU threads · {gib(s.get('mem_total', 0))} memory · "
                f"{len(s.get('gpus', []))} GPUs · up {days} day{'s' if days != 1 else ''}" if days else
                f"{s.get('cpus', 0)} CPU threads · {gib(s.get('mem_total', 0))} memory · {len(s.get('gpus', []))} GPUs · restarted today",
                [pill(f"System check · {str(health.get('status', 'none')).upper()}", health_tone(health.get("status"))),
                 pill(f"{up}/{len(s.get('services', []))} services up", "ok" if up == len(s.get("services", [])) else "warn")],
                button("refresh", "↻ Refresh dashboards") + button("health_scan", "🩺 Run system check", "info"))
    vram = sum(g["total"] for g in s.get("gpus", []))
    tiles = [tile("services up", f"{up}/{len(s.get('services', []))}", "BlackLab-only mode", "ok" if up == len(s.get("services", [])) else "warn", "🟢"),
             tile("AI models", len(s.get("models", [])), f"{len(loaded)} loaded now", "accent", "🧠"),
             tile("GPU memory", gib(vram), " + ".join(g["name"].replace("NVIDIA GeForce ", "").replace("AMD ", "") for g in s.get("gpus", [])) or "none", "info", "🎛️"),
             tile("system disk", "HDD" if s.get("spinning") else "SSD", "spinning drive — SSD on the way" if s.get("spinning") else "solid state", "warn" if s.get("spinning") else "ok", "💽")]
    live = f'<div class="blw-live" data-bl-live="homelab">{homelab_live(s, coarse=True)}</div>'
    model_rows = rows([f'<b>{esc(m["name"])}</b> <small class="blw-muted">{gib(m["size"])}</small> '
                       + (pill("loaded · " + gib(m["vram"]) + " on GPU", "ok") if m["loaded"] else
                          pill("fits one GPU" if m["size"] <= 5.5 * 2**30 else "splits across GPUs", "info" if m["size"] <= 5.5 * 2**30 else "warn"))
                       for m in s.get("models", [])[:16]]) or empty("Ollama isn't answering.")
    issues = health.get("issues") or []
    check = (f'<div class="blw-banner blw-{health_tone(health.get("status"))}"><b>{esc(health.get("title") or "No report yet")}</b>'
             f'<span>{esc(health.get("summary") or "")}</span></div>'
             + rows([pill(i.get("area", ""), health_tone(i.get("status"))) + " " + esc(i.get("title", "")) + (f'<br><small class="blw-muted">{esc(i.get("fix"))}</small>' if i.get("fix") else "") for i in issues[:8]]))
    actions = ('<div class="blw-actions">' + button("refresh", "↻ Refresh dashboards") + button("vault_pass", "🔄 Full vault pass", "info")
               + button("health_scan", "🩺 Run system check", "info") + button("atlas", "🗺️ Rebuild the Atlas", "info") + "</div>"
               + '<p class="blw-hint">Buttons work in Obsidian on this computer (the BlackLab Assistant plugin passes them to the companion).</p>')
    sections = [
        card("Live system", live, "📈", "blw-span-3"),
        card("AI models", model_rows, "🧠", "blw-span-2"),
        card("Actions", actions, "🕹️"),
        card("Last system check", check, "🩺", "blw-span-2", link("◆ IT, Networking & Systems", "Node →", "blw-card-link")),
        card("Links", '<div class="blw-chips">' + ext("http://127.0.0.1:8770/", "BlackLab Desktop", "blw-chip") + ext(KIWIX + "/", "Offline library", "blw-chip")
             + link("85 Atlas/🖥️ This Computer", "🖥️ This Computer", "blw-chip") + link("90 System/Graph Health", "🩺 Graph Health", "blw-chip") + "</div>", "🔗"),
    ]
    return page("homelab", head, tiles, sections)


# ---------------------------------------------------------------- write everything

def write_page(v, pid: str, body: str) -> bool:
    name, _, _, node = PAGES[pid]
    return v.write_managed(rel(pid), {"type": "dashboard", "dashboard": pid, "tags": ["dashboard", f"dashboard/{pid}"], "up": f"[[{node}]]",
                                      "cssclasses": [PAGE_CLASS]}, body, user_tail="\n## My notes\n\n")


def apply(v, ctx: dict | None = None, health: dict | None = None, system: dict | None = None, pages=tuple(PAGES)) -> dict:
    """Writes the CSS snippet (enabled once) and the dashboards. Returns which pages changed."""
    ctx = ctx if ctx is not None else load_json(PHONE_CONTEXT, {})
    health = health if health is not None else load_json(HEALTH_LATEST, {})
    install_css(v)
    changed = {}
    for pid in pages:
        try:
            if pid == "today":
                body = render_today(v, ctx, health)
            elif pid == "facility":
                body = render_facility(v, ctx)
            elif pid == "learning":
                body = render_learning(v, ctx)
            else:
                body = render_homelab(v, system if system is not None else collect_system(), health)
            changed[pid] = write_page(v, pid, body)
        except Exception as e:  # one broken page never stops the others
            changed[pid] = f"error: {e}"
    return changed


def install_css(v) -> None:
    v._write(f".obsidian/snippets/{SNIPPET}.css", css())
    st = v._state("web.json", {})
    if st.get("css_enabled_once"):
        return
    ap = v.path(".obsidian/appearance.json")
    try:
        app = json.loads(ap.read_text()) if ap.exists() else {}
    except ValueError:
        app = {}
    snips = app.get("enabledCssSnippets") or []
    if SNIPPET not in snips:
        app["enabledCssSnippets"] = snips + [SNIPPET]
        v._write(".obsidian/appearance.json", json.dumps(app, indent=2) + "\n")
    v._save_state("web.json", dict(st, css_enabled_once=True))


# ---------------------------------------------------------------- the design system

def css() -> str:
    steps = "\n".join(f".bl-web .blw-p-{p} {{ --p: {p}; }}" for p in range(0, 101, 5))
    return CSS.replace("/*STEPS*/", steps)


CSS = r"""/* BlackLab Web — website-style dashboards (05 Dashboards). Written by BlackLab Desktop (vault_web.py); edits are replaced.
   Make your own look: copy this file to another snippet and override the --blw-* tokens there. */
.bl-web-page { --file-line-width: 1240px; }
.bl-web-page .inline-title, .bl-web-page .metadata-container, .bl-web-page .embedded-backlinks { display: none; }
.bl-web-page .markdown-preview-view.is-readable-line-width .markdown-preview-sizer { max-width: var(--file-line-width); }
.bl-web {
  --blw-accent: #2BD9A8; --blw-accent-2: #64B5F6; --blw-ok: #3DDC84; --blw-warn: #FFB020; --blw-bad: #FF5A5F; --blw-info: #8AB4F8;
  --blw-claude: #FF8C1A; --blw-gemini: #FF2D3D;
  --blw-bg: #0B0E13; --blw-surface: #121821; --blw-surface-2: #18202B; --blw-line: #232D3B; --blw-text: #E7ECF3; --blw-muted: #8D99AB;
  --blw-radius: 16px; --blw-gap: 14px; --blw-shadow: 0 1px 0 rgba(255,255,255,.04) inset, 0 10px 30px -12px rgba(0,0,0,.55);
  font-family: "Inter", "IBM Plex Sans", system-ui, sans-serif; color: var(--blw-text); font-size: 14.5px; line-height: 1.45;
  display: flex; flex-direction: column; gap: var(--blw-gap); margin: 0 0 1.5em; font-variant-numeric: tabular-nums;
}
.theme-light .bl-web { --blw-bg: #F4F6FA; --blw-surface: #FFFFFF; --blw-surface-2: #F1F4F8; --blw-line: #E1E6EE; --blw-text: #111827; --blw-muted: #5B6676;
  --blw-shadow: 0 1px 2px rgba(16,24,40,.06), 0 8px 24px -14px rgba(16,24,40,.18); --blw-accent: #0FA57F; --blw-warn: #C77700; --blw-ok: #13915A; --blw-bad: #D93B40; --blw-info: #2F6FD6; }
.bl-web a { text-decoration: none; }
.bl-web p { margin: 0; }
.bl-web code { font-size: .9em; }
/* nav */
.bl-web .blw-nav { position: sticky; top: 0; z-index: 3; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 10px 14px;
  background: color-mix(in srgb, var(--blw-surface) 82%, transparent); backdrop-filter: blur(12px); border: 1px solid var(--blw-line); border-radius: 999px; box-shadow: var(--blw-shadow); }
.bl-web .blw-brand { display: inline-flex; align-items: center; gap: 8px; font-weight: 800; letter-spacing: -.02em; }
.bl-web .blw-brand-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--blw-accent); box-shadow: 0 0 12px var(--blw-accent); }
.bl-web .blw-nav-links, .bl-web .blw-nav-end { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.bl-web .blw-nav-end { margin-left: auto; }
.bl-web .blw-nav-link { padding: 6px 12px; border-radius: 999px; color: var(--blw-muted) !important; font-weight: 600; font-size: .92em; transition: background .15s, color .15s; }
.bl-web .blw-nav-link:hover { background: var(--blw-surface-2); color: var(--blw-text) !important; }
.bl-web .blw-nav-link.is-active { background: color-mix(in srgb, var(--blw-accent) 16%, transparent); color: var(--blw-accent) !important; }
.bl-web .blw-nav-quiet { font-weight: 500; }
/* hero */
.bl-web .blw-hero { position: relative; overflow: hidden; padding: 30px 30px 26px; border-radius: calc(var(--blw-radius) + 6px); border: 1px solid var(--blw-line);
  background: radial-gradient(1200px 300px at 0% 0%, color-mix(in srgb, var(--blw-accent) 22%, transparent), transparent 60%),
              radial-gradient(700px 260px at 100% 0%, color-mix(in srgb, var(--blw-accent-2) 18%, transparent), transparent 60%), var(--blw-surface);
  box-shadow: var(--blw-shadow); }
.bl-web .blw-hero::after { content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .35;
  background-image: linear-gradient(var(--blw-line) 1px, transparent 1px), linear-gradient(90deg, var(--blw-line) 1px, transparent 1px); background-size: 28px 28px;
  mask-image: linear-gradient(to bottom, black, transparent 85%); -webkit-mask-image: linear-gradient(to bottom, black, transparent 85%); }
.bl-web .blw-hero > * { position: relative; z-index: 1; }
.bl-web .blw-eyebrow { text-transform: uppercase; letter-spacing: .14em; font-size: .74em; font-weight: 700; color: var(--blw-accent); }
.bl-web .blw-title { font-size: 2.35em; font-weight: 800; letter-spacing: -.035em; line-height: 1.1; margin: 6px 0 6px; }
.bl-web .blw-sub { color: var(--blw-muted); font-size: 1.05em; max-width: 70ch; }
.bl-web .blw-hero-pills, .bl-web .blw-hero-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 14px; }
/* tiles */
.bl-web .blw-tiles { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--blw-gap); }
.bl-web .blw-tile { position: relative; padding: 16px 18px; border-radius: var(--blw-radius); background: var(--blw-surface); border: 1px solid var(--blw-line); box-shadow: var(--blw-shadow); overflow: hidden; }
.bl-web .blw-tile::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; background: var(--tone); }
.bl-web .blw-tile-icon { position: absolute; right: 14px; top: 12px; font-size: 1.3em; opacity: .85; }
.bl-web .blw-tile-value { font-size: 2em; font-weight: 800; letter-spacing: -.03em; color: var(--tone); line-height: 1.1; }
.bl-web .blw-tile-label { font-weight: 600; margin-top: 2px; }
.bl-web .blw-tile-sub { color: var(--blw-muted); font-size: .86em; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* tones */
.bl-web .blw-ok { --tone: var(--blw-ok); } .bl-web .blw-warn { --tone: var(--blw-warn); } .bl-web .blw-bad { --tone: var(--blw-bad); }
.bl-web .blw-info { --tone: var(--blw-info); } .bl-web .blw-accent { --tone: var(--blw-accent); }
.bl-web .blw-claude { --tone: var(--blw-claude); } .bl-web .blw-gemini { --tone: var(--blw-gemini); }
/* grid + cards */
.bl-web .blw-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--blw-gap); align-items: stretch; grid-auto-flow: row dense; }
.bl-web .blw-span-2 { grid-column: span 2; } .bl-web .blw-span-3 { grid-column: 1 / -1; }
.bl-web .blw-card { border-radius: var(--blw-radius); background: var(--blw-surface); border: 1px solid var(--blw-line); box-shadow: var(--blw-shadow); overflow: hidden; }
.bl-web .blw-card-head { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--blw-line);
  background: linear-gradient(to bottom, var(--blw-surface-2), var(--blw-surface)); }
.bl-web .blw-card-title { font-weight: 700; letter-spacing: -.01em; }
.bl-web .blw-card-link { margin-left: auto; font-size: .86em; font-weight: 600; color: var(--blw-accent) !important; }
.bl-web .blw-card-body { padding: 14px 16px 16px; display: flex; flex-direction: column; gap: 10px; }
.bl-web .blw-subhead { text-transform: uppercase; letter-spacing: .1em; font-size: .72em; font-weight: 700; color: var(--blw-muted); margin-top: 4px; }
.bl-web .blw-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.bl-web .blw-list li { padding: 8px 0; border-bottom: 1px dashed var(--blw-line); display: block; }
.bl-web .blw-list li:last-child { border-bottom: 0; }
.bl-web .blw-list li::before { display: none; }
.bl-web .blw-muted { color: var(--blw-muted); }
.bl-web .blw-empty { color: var(--blw-muted); font-style: italic; }
.bl-web .blw-hint { color: var(--blw-muted); font-size: .86em; }
.bl-web .blw-more a { font-weight: 600; color: var(--blw-accent) !important; }
.bl-web .blw-big { font-size: 2.2em; font-weight: 800; letter-spacing: -.03em; color: var(--blw-accent); line-height: 1; }
.bl-web .blw-big small { font-size: .38em; color: var(--blw-muted); font-weight: 600; letter-spacing: 0; }
.bl-web .blw-cols3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
/* pills, chips, buttons */
.bl-web .blw-pill { display: inline-flex; align-items: center; gap: 4px; padding: 2px 9px; border-radius: 999px; font-size: .78em; font-weight: 700; white-space: nowrap;
  color: var(--tone); background: color-mix(in srgb, var(--tone) 14%, transparent); border: 1px solid color-mix(in srgb, var(--tone) 35%, transparent); vertical-align: 1px; }
.bl-web .blw-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.bl-web .blw-chip { padding: 7px 12px; border-radius: 10px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); color: var(--blw-text) !important; font-weight: 600; font-size: .92em; transition: border-color .15s, transform .15s; }
.bl-web .blw-chip:hover { border-color: var(--blw-accent); transform: translateY(-1px); }
.bl-web .blw-btn { cursor: pointer; padding: 8px 14px; border-radius: 10px; font-weight: 700; font-size: .9em; border: 1px solid color-mix(in srgb, var(--tone) 45%, transparent);
  color: var(--tone); background: color-mix(in srgb, var(--tone) 12%, transparent); box-shadow: none; height: auto; }
.bl-web .blw-btn:hover { background: color-mix(in srgb, var(--tone) 22%, transparent); }
.bl-web .blw-btn.is-busy { opacity: .6; pointer-events: none; }
.bl-web .blw-actions { display: flex; flex-direction: column; gap: 8px; }
.bl-web .blw-actions .blw-btn { text-align: left; }
/* bars, rings, gauges */
.bl-web .blw-bar { display: flex; flex-direction: column; gap: 4px; }
.bl-web .blw-bar-label { font-size: .82em; color: var(--blw-muted); }
.bl-web .blw-bar-track { height: 8px; border-radius: 999px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); overflow: hidden; }
.bl-web .blw-bar-fill { height: 100%; width: calc(var(--p) * 1%); border-radius: inherit; background: linear-gradient(90deg, color-mix(in srgb, var(--tone) 70%, transparent), var(--tone)); box-shadow: 0 0 10px -2px var(--tone); }
.bl-web .blw-ring { --size: 78px; width: var(--size); height: var(--size); flex: 0 0 var(--size); border-radius: 50%; display: grid; place-items: center;
  background: conic-gradient(var(--tone) calc(var(--p) * 1%), var(--blw-surface-2) 0); }
.bl-web .blw-ring-in { width: calc(var(--size) - 14px); height: calc(var(--size) - 14px); border-radius: 50%; background: var(--blw-surface); display: flex; flex-direction: column; align-items: center; justify-content: center; line-height: 1.05; }
.bl-web .blw-ring-in b { font-size: 1.05em; } .bl-web .blw-ring-in small { font-size: .7em; color: var(--blw-muted); }
.bl-web .blw-gauges { display: flex; flex-wrap: wrap; gap: 18px; }
.bl-web .blw-gauges .blw-ring { --size: 96px; }
.bl-web .blw-gpu { display: flex; flex-direction: column; gap: 6px; padding: 10px 12px; border-radius: 12px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); }
/* timeline, kanban, countdowns, services, mini cards */
.bl-web .blw-timeline { list-style: none; margin: 0; padding: 0 0 0 4px; border-left: 2px solid var(--blw-line); }
.bl-web .blw-timeline li { position: relative; display: flex; gap: 12px; padding: 6px 0 6px 14px; }
.bl-web .blw-timeline li::before { content: ""; position: absolute; left: -7px; top: 13px; width: 10px; height: 10px; border-radius: 50%; background: var(--blw-accent); box-shadow: 0 0 0 3px var(--blw-surface); }
.bl-web .blw-time { font-weight: 700; color: var(--blw-accent); min-width: 3.2em; font-size: .92em; }
.bl-web .blw-ev { display: flex; flex-direction: column; } .bl-web .blw-ev small { color: var(--blw-muted); }
.bl-web .blw-kanban { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.bl-web .blw-kanban-col { display: flex; flex-direction: column; gap: 8px; padding: 10px; border-radius: 12px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); }
.bl-web .blw-kanban-item { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; padding: 10px 12px; border-radius: 10px; background: var(--blw-surface); border: 1px solid var(--blw-line); border-left: 3px solid var(--blw-info); }
.bl-web .blw-kanban-item.is-late { border-left-color: var(--blw-bad); }
.bl-web .blw-count { display: flex; flex-direction: column; gap: 6px; padding: 10px 0; border-bottom: 1px dashed var(--blw-line); }
.bl-web .blw-count:last-child { border-bottom: 0; }
.bl-web .blw-count-head { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; } .bl-web .blw-count-head .blw-pill { margin-left: auto; }
.bl-web .blw-services { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 8px; }
.bl-web .blw-svc { display: grid; grid-template-columns: auto 1fr; column-gap: 8px; align-items: center; padding: 9px 12px; border-radius: 12px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); }
.bl-web .blw-svc small { grid-column: 2; color: var(--blw-muted); font-size: .8em; }
.bl-web .blw-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--tone); box-shadow: 0 0 10px var(--tone); grid-row: span 2; }
.bl-web .blw-live-head { display: flex; gap: 8px; flex-wrap: wrap; }
.bl-web .blw-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 10px; }
.bl-web .blw-mini { display: flex; gap: 12px; align-items: center; padding: 12px; border-radius: 14px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); }
.bl-web .blw-mini-text { display: flex; flex-direction: column; gap: 3px; min-width: 0; align-items: flex-start; }
.bl-web .blw-mini-text b { line-height: 1.2; } .bl-web .blw-mini-text small { color: var(--blw-muted); }
.bl-web .blw-banner { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border-radius: 12px; border: 1px solid color-mix(in srgb, var(--tone) 40%, transparent);
  background: color-mix(in srgb, var(--tone) 10%, transparent); }
.bl-web .blw-banner b { color: var(--tone); } .bl-web .blw-banner span { color: var(--blw-muted); font-size: .92em; }
.bl-web .blw-heatmap { display: grid; grid-auto-flow: column; grid-template-rows: repeat(7, 14px); gap: 4px; justify-content: start; }
.bl-web .blw-heat { width: 14px; height: 14px; border-radius: 4px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); }
.bl-web .blw-heat-0 { opacity: .9; } .bl-web .blw-heat-1 { background: color-mix(in srgb, var(--blw-accent) 35%, transparent); } .bl-web .blw-heat-2 { background: color-mix(in srgb, var(--blw-accent) 65%, transparent); }
.bl-web .blw-heat-3 { background: var(--blw-accent); box-shadow: 0 0 8px -2px var(--blw-accent); }
/* widgets filled in by the BlackLab Assistant plugin */
.bl-web .blw-widget-hint { color: var(--blw-muted); font-size: .88em; }
.bl-web .blw-widget.is-ready .blw-widget-hint { display: none; }
.bl-web .blw-form { display: flex; gap: 8px; } .bl-web .blw-form input, .bl-web .blw-form textarea { flex: 1; min-width: 0; border-radius: 10px; }
.bl-web .blw-answer { padding: 10px 12px; border-radius: 12px; background: var(--blw-surface-2); border: 1px solid var(--blw-line); font-size: .95em; }
.bl-web .blw-answer:empty { display: none; }
.bl-web .blw-frame { width: 100%; height: 460px; border: 1px solid var(--blw-line); border-radius: 12px; background: #fff; }
.bl-web .blw-foot { color: var(--blw-muted); font-size: .8em; text-align: center; padding: 4px 0 8px; }
/* phone and narrow panes */
@media (max-width: 900px) {
  .bl-web .blw-tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bl-web .blw-grid, .bl-web .blw-cols3, .bl-web .blw-kanban { grid-template-columns: 1fr; }
  .bl-web .blw-span-2, .bl-web .blw-span-3 { grid-column: auto; }
  .bl-web .blw-title { font-size: 1.8em; } .bl-web .blw-hero { padding: 22px 18px; }
  .bl-web .blw-nav { border-radius: 16px; } .bl-web .blw-nav-end { margin-left: 0; }
}
.is-mobile .bl-web .blw-tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.is-mobile .bl-web .blw-grid, .is-mobile .bl-web .blw-cols3, .is-mobile .bl-web .blw-kanban { grid-template-columns: 1fr; }
.is-mobile .bl-web .blw-span-2, .is-mobile .bl-web .blw-span-3 { grid-column: auto; }
/*STEPS*/
"""
