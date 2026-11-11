"""Facility operations in the vault (companion 1.7): templates and rounds, the panel directory, the SDS binder, shift prep.

Patterns from Paul's facility starter kit and EHS-director agent, rebuilt generic (no site names in this public code —
site specifics live in his private vault):

  * Templates (`10 Facility/Templates/`) — EOC rounds, fire & life-safety audit, daily facility ops report, PM calendar,
    CMS-2567 plan of correction, facility incident report. Written once; after that they're Paul's to edit, and every new
    round is copied from his version. new_round("eoc") → `10 Facility/Rounds/2026-11-10 EOC Rounds.md`.
  * Panel directory (`10 Facility/Panels/`) — one note per panel with a `| Ckt | Load |` table (from the panel-schedule
    photos, corrected by hand). render_panels() builds `⚡ Panel Directory.md` (room → panel/circuit) and panel(q) answers
    "which breaker feeds 428" or "what's on AHU-5". Always verify at the panel: LOTO and test before touch.
  * SDS binder (`10 Facility/SDS Binder/`) — one note per product (frontmatter: hazard classes, location, revision date,
    PPE). render_sds() writes the index with reviews due and storage conflicts (oxidizers with flammables, acids with
    bases, bleach with ammonia or acids…) at the same location; sds(q) answers "SDS for Virex".
  * shift_prep() — EHS director's /shift_prep: what's overdue or due soon, open work orders, rounds done today, SDS and
    credential renewals, then today's timeline so far.

Text in these notes is facility data, never anything about the people served (the guard in guards.py refuses that).
"""
from __future__ import annotations

import datetime as dt
import re

ROOT = "10 Facility"
TEMPLATES = f"{ROOT}/Templates"
ROUNDS = f"{ROOT}/Rounds"
PANELS = f"{ROOT}/Panels"
SDS = f"{ROOT}/SDS Binder"
UP = ["[[◆ Facility Operations]]"]
UP_SAFETY = ["[[◆ Facility Operations]]", "[[◆ Life Safety & Compliance]]"]

# ---------------------------------------------------------------- templates

T_EOC = """# EOC Rounds — {date}

**Unit / area:** ____  **Rounded by:** ____  **Start:** ____  **End:** ____

> Environment of care, not care: no names or identifying details of anyone served. Anything found → a work order (WO #).

## Life safety
- [ ] Exits and corridors clear; exit signs lit
- [ ] Fire doors close and latch on their own; no wedges or hold-opens
- [ ] Extinguishers in place, tagged, gauge in green, monthly check initialed
- [ ] Pull stations and alarm devices unobstructed
- [ ] 18 in. clearance below sprinkler heads; no items hung from piping
- [ ] Smoke-barrier and rated-wall penetrations sealed (above ceiling if a tile is open)
- [ ] Electrical panels: 36 in. clear, doors closed, no daisy-chained power strips

## Ligature-resistant environment
- [ ] Door hardware, hinges and closers ligature-resistant and tight
- [ ] Window tamper seals intact; glazing uncracked; blinds/cords per policy
- [ ] Ceiling tiles secure; vent grilles and sprinkler heads tamper-resistant
- [ ] Fixtures, handrails and anchor points checked; anything new or loose removed
- [ ] Furniture intact (no missing hardware, broken edges)

## Security
- [ ] Duress alarm tested — sounded and answered (time: ____)
- [ ] Locked doors locked; nothing propped; badge readers working
- [ ] Cameras live (spot-check monitor)

## Infection control & waste
- [ ] Biohazard / regulated medical waste labeled, lidded, stored in the locked area
- [ ] Sharps containers below the fill line
- [ ] Eyewash station flushed this week; area clear

## Comfort & environment
- [ ] Sensory / comfort cart stocked; items safe (no cords, glass, small parts)
- [ ] Temperature and lighting comfortable; no odors, leaks or stains
- [ ] Housekeeping: floors, restrooms, trash

## Findings

| # | Location | Finding | Risk (H/M/L) | Action / WO # | Done |
|---|---|---|---|---|---|
| 1 |  |  |  |  |  |
"""

T_FLS = """# Fire & Life Safety Audit — {date}

**Building / area:** ____  **Auditor:** ____  **Period:** monthly / quarterly

| Item | Standard | Frequency | Last done | Result | Notes / WO # |
|---|---|---|---|---|---|
| Fire alarm system test & inspection | NFPA 72 | annual (devices), semiannual/quarterly per device |  |  |  |
| Sprinkler inspection (gauges, valves, FDC) | NFPA 25 | monthly / quarterly |  |  |  |
| Fire extinguishers — visual | NFPA 10 | monthly |  |  |  |
| Fire extinguishers — maintenance | NFPA 10 | annual |  |  |  |
| Emergency lighting — 30 s test | NFPA 101 | monthly |  |  |  |
| Emergency lighting — 90 min test | NFPA 101 | annual |  |  |  |
| Exit signs lit | NFPA 101 | monthly |  |  |  |
| Fire doors inspected | NFPA 80 | annual |  |  |  |
| Smoke / fire dampers | NFPA 80/105 | every 6 years (4 for some) |  |  |  |
| Generator — weekly inspection | NFPA 110 | weekly |  |  |  |
| Generator — load test 30 min | NFPA 110 | monthly |  |  |  |
| Transfer switch exercised | NFPA 110 | monthly |  |  |  |
| Kitchen hood suppression | NFPA 96/17A | semiannual |  |  |  |
| Fire drills (each shift) | NFPA 101 / CMS | quarterly per shift |  |  |  |
| Barrier / penetration walk | NFPA 101 | quarterly |  |  |  |

## Interim life-safety measures (if anything is impaired)
- [ ] Impairment logged, fire watch posted, AHJ / insurer notified as required
- [ ] ILSM assessment done and attached

## Findings & corrections

| # | Deficiency | Standard | Action | Owner | Due | Closed |
|---|---|---|---|---|---|---|
| 1 |  |  |  |  |  |  |
"""

T_DAILY = """# Daily Facility Ops Report — {date}

**Shift:** ____  **Prepared by:** ____

## Critical systems
| System | Status | Notes |
|---|---|---|
| Fire alarm / sprinkler | 🟢 / 🟡 / 🔴 |  |
| Generator & transfer switch | |  |
| Boilers / hot water |  |  |
| HVAC (AHUs, RTUs, through-wall units) |  |  |
| Elevators |  |  |
| Door security / duress system |  |  |

## Work orders
- Opened today: ____   Closed today: ____   Open > 7 days: ____
- Priority items:

## Rounds & inspections done
-

## Incidents & safety observations (no names or identifying details)
-

## Vendors & contractors on site
-

## Tomorrow
-
"""

T_PM = """# PM Calendar

> The yearly plan. The live schedule (last done, next due) lives in BlackLab › Facility; this is the at-a-glance view to
> plan staffing and vendors around. Edit freely.

| System | Task | Standard | Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct | Nov | Dec |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Fire extinguishers | Monthly visual | NFPA 10 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| Emergency lighting | 30-second test | NFPA 101 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| Generator | Load test 30 min | NFPA 110 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| Sprinklers | Quarterly inspection | NFPA 25 | ● |  |  | ● |  |  | ● |  |  | ● |  |  |
| Fire drills | Each shift | NFPA 101 | ● |  |  | ● |  |  | ● |  |  | ● |  |  |
| Kitchen hood | Suppression service | NFPA 96 | ● |  |  |  |  |  | ● |  |  |  |  |  |
| AHUs / RTUs | Filters & belts | mfr | ● |  |  | ● |  |  | ● |  |  | ● |  |  |
| Boilers | Combustion tune-up | mfr / state | | | | | | | | | ● | | | |
| Backflow preventers | Annual test | local code | | | | ● | | | | | | | | |
| Fire alarm | Annual test & inspection | NFPA 72 | | | | | | | | | | ● | | |
| Emergency lighting | 90-minute test | NFPA 101 | | | | | | | | | | | ● | |
| Fire doors | Annual inspection | NFPA 80 | | | | | | ● | | | | | | |
| Elevators | State inspection | ASME A17.1 | | | | | ● | | | | | | | |
"""

T_POC = """# Plan of Correction (CMS-2567) — {date}

> One block per tag. Surveyors look for all four parts; "completed" dates must be realistic and the monitoring must
> show the fix holds. No names or identifying details of anyone served — refer to them as the survey does.

## Tag ____ — (short title)

**Deficiency (as cited):**

1. **Corrective action for the cited instance** — what was done, by whom, and when.
2. **How other areas or people who could be affected were identified** — the sweep and what it found.
3. **Systemic changes** — policy, procedure, training, equipment or schedule changes so it doesn't recur.
4. **Monitoring** — who audits, how often, for how long, and how results go to QAPI / the safety committee.

**Completion date:** ____  **Responsible:** ____

| Audit date | Sample | Compliant | % | Reported to |
|---|---|---|---|---|
|  |  |  |  |  |
"""

T_INCIDENT = """# Facility Incident — {date}

> Environment and equipment only. People involved are referred to by role and by the incident number in the
> organization's reporting system — never by name or anything identifying here.

- **When / where:**
- **What happened:**
- **Hazard / equipment involved:**
- **Immediate actions taken:**
- **Notified (role, time):**
- **Reporting-system incident #:**

## Why (5 whys)
1.
2.
3.

## Corrective actions

| Action | Owner | Due | WO # | Done |
|---|---|---|---|---|
|  |  |  |  |  |
"""

TEMPLATES_SET = {
    "eoc": ("EOC Rounds", T_EOC, UP_SAFETY),
    "fire": ("Fire & Life Safety Audit", T_FLS, UP_SAFETY),
    "daily": ("Daily Facility Ops Report", T_DAILY, UP),
    "pm": ("PM Calendar", T_PM, UP),
    "poc": ("Plan of Correction (CMS-2567)", T_POC, UP_SAFETY),
    "incident": ("Facility Incident", T_INCIDENT, UP_SAFETY),
}
KIND_WORDS = [("eoc", r"\beoc\b|environment of care|safety rounds|ligature"), ("fire", r"fire|life[- ]safety|lsc"),
              ("daily", r"daily|ops report|operations report"), ("poc", r"plan of correction|\bpoc\b|2567|survey"),
              ("incident", r"incident"), ("pm", r"\bpm\b|preventive")]


def kind_of(text: str) -> str | None:
    t = (text or "").lower()
    return next((k for k, rx in KIND_WORDS if re.search(rx, t)), None)


def _template_rel(kind: str) -> str:
    return f"{TEMPLATES}/{TEMPLATES_SET[kind][0]}.md"


def ensure_templates(v) -> int:
    """Write each template once. After that it's Paul's note: never overwritten."""
    n = 0
    for kind, (title, body, up) in TEMPLATES_SET.items():
        rel = _template_rel(kind)
        if v.path(rel).exists():
            continue
        fm = f'---\ntype: template\ntemplate: {kind}\ntags: [facility/template]\nup: [{", ".join(chr(34) + u + chr(34) for u in up)}]\n---\n'
        v._write(rel, fm + body.replace("{date}", "{{date}}"))
        n += 1
    return n


def new_round(v, kind: str, when: dt.date | None = None, by: str = "") -> dict:
    """A dated copy of Paul's template, linked to the day and the template."""
    kind = kind if kind in TEMPLATES_SET else (kind_of(kind) or "")
    if kind not in TEMPLATES_SET or kind == "pm":
        return {"ok": False, "error": "Say which: EOC rounds, fire & life-safety audit, daily ops report, plan of correction or facility incident."}
    ensure_templates(v)
    d = when or v.now().date()
    title = TEMPLATES_SET[kind][0]
    rel = f"{ROUNDS}/{d.isoformat()} {title}.md"
    if v.path(rel).exists():
        return {"ok": True, "path": rel, "existing": True, "message": f"Today's {title.lower()} is already started: {rel}."}
    src = v.path(_template_rel(kind)).read_text(encoding="utf-8", errors="replace")
    _, body = v.split_frontmatter(src)
    body = body.replace("{{date}}", d.strftime("%A, %B %-d, %Y"))
    up = TEMPLATES_SET[kind][2]
    fm = (f"---\ntype: round\nround: {kind}\ndate: {d.isoformat()}\nstatus: open\ntags: [facility/round, facility/{kind}]\n"
          f'up: [{", ".join(chr(34) + u + chr(34) for u in up)}]\nday: "[[{d.isoformat()}]]"\ntemplate: "[[{title}]]"\n---\n')
    v._write(rel, fm + body)
    v.log("facility", f"Started {title.lower()}", "", source=by or "BlackLab", links=[rel.rsplit('/', 1)[-1][:-3]])
    return {"ok": True, "path": rel, "message": f"Started {title.lower()} — it's in your vault at {rel}. Check items off in Obsidian."}


# ---------------------------------------------------------------- panel directory

ROW = re.compile(r"^\|\s*(\d{1,3})\s*\|\s*([^|]*?)\s*\|(.*)$")
ROOMS_AFTER = re.compile(r"(?:\brm\.?|\brooms?|#)\s*#?\s*((?:\d{3}[a-z]?)(?:\s*(?:,|&|and|/)\s*\d{3}[a-z]?)*)", re.I)


def rooms_in(load: str) -> list[str]:
    out = []
    for m in ROOMS_AFTER.finditer(load or ""):
        out += re.findall(r"\d{3}[a-z]?", m.group(1), re.I)
    return list(dict.fromkeys(r.upper() for r in out))


def _props(v, text: str) -> dict:
    fm, _ = v.split_frontmatter(text)
    out = {}
    for ln in fm:
        m = re.match(r"^([A-Za-z_]+):\s*(.*)$", ln)
        if m:
            out[m.group(1)] = m.group(2).strip().strip('"')
    return out


def load_panels(v) -> list[dict]:
    panels = []
    d = v.path(PANELS)
    if not d.is_dir():
        return panels
    for p in sorted(d.glob("*.md")):
        if p.name.startswith("⚡"):
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        props = _props(v, text)
        circuits = []
        for ln in text.splitlines():
            m = ROW.match(ln.strip())
            if m and m.group(2) and not re.fullmatch(r"(spare|space|empty|—|-)?", m.group(2), re.I):
                circuits.append({"ckt": int(m.group(1)), "load": m.group(2), "rooms": rooms_in(m.group(2))})
        panels.append({"name": props.get("panel") or p.stem, "note": p.stem, "voltage": props.get("voltage", ""), "amps": props.get("amps", ""),
                       "fed_from": props.get("fed_from", ""), "location": props.get("location", ""), "circuits": circuits})
    return panels


def render_panels(v) -> dict:
    panels = load_panels(v)
    if not panels:
        return {"panels": 0}
    by_room: dict[str, list[str]] = {}
    for p in panels:
        for c in p["circuits"]:
            for r in c["rooms"]:
                by_room.setdefault(r, []).append(f"[[{p['note']}]] #{c['ckt']} {c['load']}")
    lines = ["# ⚡ Panel Directory", "",
             "> [!warning] Read from photos of the panel schedules — a schedule can be wrong or out of date.",
             "> Always verify at the panel: lock out, tag out, and test before touch.", "",
             "| Panel | Voltage | Amps | Fed from | Location | Circuits listed |", "|---|---|---|---|---|---|"]
    lines += [f"| [[{p['note']}]] | {p['voltage']} | {p['amps']} | {p['fed_from']} | {p['location']} | {len(p['circuits'])} |" for p in panels]
    lines += ["", f"## By room ({len(by_room)})", "", "| Room | Circuits |", "|---|---|"]
    for r in sorted(by_room, key=lambda x: (len(x), x)):
        lines.append(f"| {r} | " + "<br>".join(by_room[r]) + " |")
    v.write_managed(f"{PANELS}/⚡ Panel Directory.md", {"type": "panel-directory", "panels": len(panels), "rooms": len(by_room),
                                                        "tags": ["facility/electrical"], "up": UP_SAFETY}, "\n".join(lines))
    return {"panels": len(panels), "rooms": len(by_room)}


def panel(v, query: str) -> dict:
    """'428', 'room 428', 'AHU-5', 'dishwasher', '4MH1' → the circuits."""
    q = (query or "").strip()
    panels = load_panels(v)
    if not panels:
        return {"ok": False, "error": "No panel schedules in the vault yet (10 Facility/Panels)."}
    hits = []
    room = re.search(r"\b(\d{3}[a-z]?)\b", q, re.I)
    named = [p for p in panels if p["name"].lower() == q.lower() or p["note"].lower() == q.lower()]
    if named:
        p = named[0]
        return {"ok": True, "panel": p, "message": f"{p['name']} ({p['voltage']}, {p['amps']}): " +
                "; ".join(f"#{c['ckt']} {c['load']}" for c in p["circuits"][:24]) + (" …" if len(p["circuits"]) > 24 else "")}
    for p in panels:
        for c in p["circuits"]:
            if room and room.group(1).upper() in c["rooms"]:
                hits.append((p, c))
            elif not room and re.search(re.escape(q.lower().removeprefix("the ")), c["load"].lower()):
                hits.append((p, c))
    if not hits:
        return {"ok": True, "results": [], "message": f"No panel schedule lists “{q}”."}
    msg = "; ".join(f"panel {p['note']} circuit {c['ckt']} ({c['load']})" for p, c in hits[:10])
    return {"ok": True, "results": [{"panel": p["note"], "ckt": c["ckt"], "load": c["load"]} for p, c in hits],
            "message": f"{q}: {msg}. From the panel-schedule photos — verify at the panel and LOTO before work."}


# ---------------------------------------------------------------- SDS binder

HAZARDS = ("flammable", "oxidizer", "acid", "base", "toxic", "water-reactive", "compressed-gas", "irritant", "none")
INCOMPATIBLE = [({"flammable"}, {"oxidizer"}, "flammables with oxidizers (fire)"),
                ({"acid"}, {"base"}, "acids with bases (heat, splatter)"),
                ({"water-reactive"}, {"acid", "base"}, "water-reactives with aqueous acids or bases"),
                ({"oxidizer"}, {"toxic"}, "oxidizers with toxics (keep separated)")]
SPECIAL = [(r"bleach|hypochlorite", r"ammonia|ammonium", "bleach with ammonia (chloramine gas)"),
           (r"bleach|hypochlorite", r"acid|descaler|toilet bowl", "bleach with acids (chlorine gas)")]
REVIEW_YEARS = 3  # an SDS older than this: ask the manufacturer for the current revision

T_SDS = """---
type: sds
product: {name}
manufacturer: ""
sds_revision: ""
location: ""
hazards: []
signal_word: ""
ppe: ""
tags: [facility/sds]
up: ["[[◆ Facility Operations]]", "[[◆ Life Safety & Compliance]]"]
---
# {name}

> Fill in from section 1–2 (identity, hazards), 7 (storage), 8 (PPE) and the revision date. `hazards` is a list of:
> flammable, oxidizer, acid, base, toxic, water-reactive, compressed-gas, irritant, none.

- **Use:**
- **First aid (sec. 4):**
- **Spill (sec. 6):**
- **Storage (sec. 7):**
- **SDS file / link:**
"""


def _list(v: str) -> list[str]:
    return [x.strip().strip('"\'').lower() for x in re.sub(r"^\[|\]$", "", v or "").split(",") if x.strip().strip('"\'')]


def load_sds(v) -> list[dict]:
    d = v.path(SDS)
    out = []
    if not d.is_dir():
        return out
    for p in sorted(d.glob("*.md")):
        if p.name.startswith("🧪"):
            continue
        props = _props(v, p.read_text(encoding="utf-8", errors="replace"))
        rev = None
        try:
            rev = dt.date.fromisoformat(props.get("sds_revision", "")[:10])
        except ValueError:
            pass
        out.append({"note": p.stem, "product": props.get("product") or p.stem, "manufacturer": props.get("manufacturer", ""),
                    "location": props.get("location", ""), "hazards": [h for h in _list(props.get("hazards", "")) if h in HAZARDS],
                    "signal": props.get("signal_word", ""), "ppe": props.get("ppe", ""), "revision": rev})
    return out


def conflicts(items: list[dict]) -> list[str]:
    out = []
    by_loc: dict[str, list[dict]] = {}
    for it in items:
        if it["location"]:
            by_loc.setdefault(it["location"].strip().lower(), []).append(it)
    for loc, group in by_loc.items():
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                ha, hb = set(a["hazards"]), set(b["hazards"])
                for x, y, why in INCOMPATIBLE:
                    if (ha & x and hb & y) or (hb & x and ha & y):
                        out.append(f"{a['location']}: [[{a['note']}]] and [[{b['note']}]] — {why}")
                for ra, rb, why in SPECIAL:
                    na, nb = a["product"].lower(), b["product"].lower()
                    if (re.search(ra, na) and re.search(rb, nb)) or (re.search(ra, nb) and re.search(rb, na)):
                        out.append(f"{a['location']}: [[{a['note']}]] and [[{b['note']}]] — {why}")
    return list(dict.fromkeys(out))


def render_sds(v) -> dict:
    tpl = f"{SDS}/_New product.md"
    if not v.path(tpl).exists():
        v._write(tpl, T_SDS.replace("{name}", "New product"))
    items = [i for i in load_sds(v) if i["note"] != "_New product"]
    today = v.now().date()
    stale = [i for i in items if not i["revision"] or (today - i["revision"]).days > 365 * REVIEW_YEARS]
    clash = conflicts(items)
    lines = ["# 🧪 SDS Binder", "",
             "> One note per product (copy `_New product`). The binder must be on hand for every chemical in use; review it each",
             "> year and when anything new arrives. Conflicts are checked by storage location.", "",
             f"**{len(items)} products** · {len(stale)} need a current SDS · {len(clash)} storage conflicts", ""]
    if clash:
        lines += ["## ⚠️ Storage conflicts", ""] + [f"- {c}" for c in clash] + [""]
    lines += ["| Product | Manufacturer | Hazards | Signal | PPE | Location | SDS revision |", "|---|---|---|---|---|---|---|"]
    for i in sorted(items, key=lambda i: i["product"].lower()):
        rev = i["revision"].isoformat() if i["revision"] else "—"
        flag = " ⚠️" if i in stale else ""
        lines.append(f"| [[{i['note']}]] | {i['manufacturer']} | {', '.join(i['hazards']) or '—'} | {i['signal'] or '—'} | {i['ppe'] or '—'} | {i['location'] or '—'} | {rev}{flag} |")
    v.write_managed(f"{SDS}/🧪 SDS Binder.md", {"type": "sds-binder", "products": len(items), "stale": len(stale), "conflicts": len(clash),
                                               "tags": ["facility/sds"], "up": UP_SAFETY}, "\n".join(lines))
    return {"products": len(items), "stale": len(stale), "conflicts": len(clash)}


def sds(v, query: str) -> dict:
    q = (query or "").strip().lower()
    items = load_sds(v)
    hits = [i for i in items if q and (q in i["product"].lower() or q in i["note"].lower())]
    if not hits:
        return {"ok": True, "results": [], "message": f"No SDS for “{query}” in the binder yet. Add one from 10 Facility/SDS Binder/_New product."}
    i = hits[0]
    rev = i["revision"].isoformat() if i["revision"] else "unknown"
    msg = (f"{i['product']}" + (f" ({i['manufacturer']})" if i["manufacturer"] else "") + f": hazards {', '.join(i['hazards']) or 'not filled in'}"
           + (f", signal word {i['signal']}" if i["signal"] else "") + (f". PPE: {i['ppe']}" if i["ppe"] else "")
           + (f". Stored at {i['location']}" if i["location"] else "") + f". SDS revision {rev}.")
    return {"ok": True, "results": [{k: (str(x) if isinstance(x, dt.date) else x) for k, x in h.items()} for h in hits], "message": msg}


# ---------------------------------------------------------------- shift prep

def _due_rows(v) -> list[str]:
    out = []
    d = v.path(f"{ROOT}/Assets")
    if not d.is_dir():
        return out
    for p in sorted(d.glob("*.md")):
        for ln in p.read_text(encoding="utf-8", errors="replace").splitlines():
            if ln.startswith("|") and ("🔴" in ln or "🟡" in ln):
                cells = [c.strip() for c in ln.strip("|").split("|")]
                if len(cells) >= 6:
                    out.append(("🔴" if "🔴" in ln else "🟡", f"{cells[0]} — {p.stem} (due {cells[4]})"))
    return [f"{badge} {t}" for badge, t in sorted(out, key=lambda x: x[0] != "🔴")]


def _open_work_orders(v) -> list[str]:
    d = v.path("30 Work/Work Orders")
    if not d.is_dir():
        return []
    out = []
    for p in sorted(d.glob("*.md")):
        props = _props(v, p.read_text(encoding="utf-8", errors="replace"))
        if props.get("status") == "open":
            out.append(p.stem + (f" (due {props['due']})" if props.get("due") else ""))
    return out


def shift_prep(v, credentials_due: list[str] | None = None) -> dict:
    today = v.now().date()
    due = _due_rows(v)
    wos = _open_work_orders(v)
    rounds = sorted(p.stem for p in v.path(ROUNDS).glob(f"{today.isoformat()} *.md")) if v.path(ROUNDS).is_dir() else []
    s = render_sds(v)
    timeline = v._state(f"timeline/{today.isoformat()}.json", [])
    lines = [f"Shift prep for {today.strftime('%A, %B %-d')}."]
    over = [d for d in due if d.startswith("🔴")]
    lines.append(f"{len(over)} overdue and {len(due) - len(over)} due soon." if due else "No inspections or PMs overdue or due soon.")
    lines += [f"  {d}" for d in due[:6]]
    if wos:
        lines.append(f"{len(wos)} open work orders: " + "; ".join(wos[:5]) + (" …" if len(wos) > 5 else ""))
    lines.append(("Rounds started today: " + ", ".join(r.split(' ', 1)[1] for r in rounds)) if rounds else "No rounds started yet today — say “start EOC rounds”.")
    if s.get("conflicts"):
        lines.append(f"SDS binder: {s['conflicts']} storage conflict(s) to fix.")
    if s.get("stale"):
        lines.append(f"SDS binder: {s['stale']} product(s) need a current SDS.")
    if credentials_due:
        lines.append("Credentials: " + "; ".join(credentials_due[:4]))
    if timeline:
        lines.append(f"Logged so far today: {len(timeline)} item(s), latest “{sorted(timeline, key=lambda e: e['ts'])[-1]['title']}”.")
    return {"ok": True, "message": "\n".join(lines), "overdue": len(over), "due_soon": len(due) - len(over), "work_orders": len(wos), "rounds": rounds}


def render_all(v) -> dict:
    return {"templates": ensure_templates(v), "panels": render_panels(v), "sds": render_sds(v)}
