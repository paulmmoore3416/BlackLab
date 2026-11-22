"""Public data feeds that keep the vault — and so the desktop AI's memory — current (companion 1.13).
Unit tested in tests/test_feeds.py. Every source is a keyless, public U.S. government API; nothing about Paul is sent
(weather alerts are fetched for the whole state and filtered here, so no location leaves this computer).

    weather     NWS active alerts for the state, kept if they name a watched county   → 🌩️ Weather Alerts      (home, safety)
    regulatory  Federal Register rules / proposed rules from OSHA, CMS, EPA, VA, DOE, SAMHSA, filtered to facility,
                life-safety and behavioral-health topics; one note per document      → 80 Knowledge/Regulatory (safety, …)
    ecfr        last amendment date of the CFR parts in the library; a newer one re-downloads that document
    security    CISA Known Exploited Vulnerabilities added in the last 45 days, flagged when they name software on
                this computer or the phone                                             → 🛡️ Security Advisories (systems)
    disasters   FEMA disaster declarations for the state (last 2 years)               → 🚨 Disaster Declarations (safety, home)

Each feed runs at most every `FEEDS[name]["every_h"]` hours; results also go to state feeds/<name>.json for the dashboards.
"""
from __future__ import annotations

import datetime as dt
import json
import re
import time
import urllib.parse
import urllib.request

import guards

UA = "BlackLab-Desktop/1.13 (personal facility assistant)"  # nothing that identifies Paul
FEED_DIR = "80 Knowledge/Feeds"
REG_DIR = "80 Knowledge/Regulatory"

FEEDS = {"weather": {"every_h": 0.5}, "regulatory": {"every_h": 12}, "ecfr": {"every_h": 24}, "security": {"every_h": 12}, "disasters": {"every_h": 24}}

# Federal Register: agency slug → (node id, title/abstract must match this to be kept; None keeps every rule)
AGENCIES = {
    "occupational-safety-and-health-administration": ("safety", None),
    "centers-for-medicare-medicaid-services": ("safety", r"conditions? of participation|psychiatric|behavioral|mental health|life safety|emergency preparedness|"
                                                         r"fire safety|physical environment|health care facilit|survey|accreditation|patient safety|restraint|seclusion"),
    "environmental-protection-agency": ("facility", r"refrigerant|hydrofluorocarbon|\bHFC|asbestos|lead[- ]based paint|legionella|boiler|stationary "
                                                    r"(?:engine|combustion)|emergency generator|\bRICE\b|cooling tower|trichloroethylene|methylene chloride|"
                                                    r"perchloroethylene|\bPFAS\b|risk management program|indoor air"),
    "veterans-affairs-department": ("milieu", r"mental health|residential|domiciliary|suicide|homeless|construction|facilit|peer|caregiver"),
    "energy-department": ("facility", r"energy conservation (?:program|standards).*(?:commercial|industrial|boiler|furnace|air[- ]condition|heat pump|"
                                       r"chiller|motor|pump|fan|lighting|transformer|water heater)"),
    "substance-abuse-and-mental-health-services-administration": ("milieu", None),
}
TYPES = ("RULE", "PRORULE")
# Rules that match a topic word but never matter here: pesticide tolerances, other states' program approvals, air plans
NOISE = re.compile(r"pesticide|tolerances?\b|primacy|approval and promulgation|implementation plan|incorporation by reference of approved|"
                   r"air quality designations|significant new use rules? on certain chemical", re.I)

# Software on this computer and the phone worth an alert when CISA says it's being exploited
WATCH_SOFTWARE = ["linux kernel", "ubuntu", "openssh", "sudo", "glibc", "systemd", "polkit", "firefox", "chrome", "chromium", "android", "pixel",
                  "tailscale", "syncthing", "obsidian", "ollama", "python", "node.js", "nodejs", "docker", "nginx", "git", "openssl", "nvidia", "amd",
                  "samba", "cups", "libwebp", "electron"]
ECFR_PARTS = {"osha_1910": (29, 1910), "osha_1926": (29, 1926), "cfr29_1904": (29, 1904), "cfr42_482": (42, 482)}


def _get(url: str, timeout=30, accept="application/json"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def safe(text) -> str:
    """Public government text: no clinical-keyword guard (a rule about psychiatric hospitals is program material, like the
    Veteran Initiative notes), but identifiers are still dropped and secrets redacted (guards.reference_safe_passages)."""
    t, _ = guards.reference_safe_passages(str(text or ""))
    return re.sub(r"\s+", " ", t).strip()


def due(v, name: str, now: float | None = None) -> bool:
    last = v._state(f"feeds/{name}.json", {}).get("at", 0)
    return (now or time.time()) - last >= FEEDS[name]["every_h"] * 3600


# ---------------------------------------------------------------- weather (NWS)

def weather(v, state: str, counties: list[str], get=_get) -> dict:
    d = get(f"https://api.weather.gov/alerts/active?area={urllib.parse.quote(state)}", accept="application/geo+json")
    keep = []
    for f in d.get("features") or []:
        p = f.get("properties") or {}
        area = p.get("areaDesc") or ""
        if counties and not any(c.lower() in area.lower() for c in counties):
            continue
        keep.append({"event": safe(p.get("event")), "severity": p.get("severity") or "", "urgency": p.get("urgency") or "", "headline": safe(p.get("headline")),
                     "area": safe(area)[:200], "onset": p.get("onset") or p.get("effective") or "", "ends": p.get("ends") or p.get("expires") or "",
                     "instruction": safe(p.get("instruction"))[:600], "id": p.get("id") or ""})
    sev = {"Extreme": 0, "Severe": 1, "Moderate": 2, "Minor": 3}
    keep.sort(key=lambda a: (sev.get(a["severity"], 4), a["onset"]))
    lines = ["# 🌩️ Weather Alerts", "", f"_National Weather Service alerts for {', '.join(counties) or state} · checked every 30 minutes._", ""]
    if not keep:
        lines.append("✅ No active alerts.")
    for a in keep:
        lines += [f"## {a['event']} — {a['severity'] or 'n/a'}", "", f"- **When:** {a['onset'][:16].replace('T', ' ')} → {a['ends'][:16].replace('T', ' ')}",
                  f"- **Where:** {a['area']}", "", a["headline"]] + ([f"", f"> [!warning] What to do", f"> {a['instruction']}"] if a["instruction"] else []) + [""]
    lines += ["", "Facility prep checklists: [[◆ Life Safety & Compliance]] · freeze and storm prep in [[Tasks]]."]
    v.write_managed(f"{FEED_DIR}/🌩️ Weather Alerts.md", {"type": "feed", "blacklab_reference": "program", "feed": "weather", "alerts": len(keep), "tags": ["feed", "feed/weather"],
                                                          "up": ["[[◆ Home, Family & Health]]", "[[◆ Life Safety & Compliance]]"]}, "\n".join(lines))
    return {"alerts": keep}


# ---------------------------------------------------------------- regulatory (Federal Register)

def fr_url(agency: str, since: str) -> str:
    q = [("per_page", "50"), ("order", "newest"), ("conditions[agencies][]", agency), ("conditions[publication_date][gte]", since)]
    q += [("conditions[type][]", t) for t in TYPES]
    q += [("fields[]", f) for f in ("title", "abstract", "publication_date", "type", "html_url", "document_number", "agencies", "effective_on",
                                     "comments_close_on", "significant")]
    return "https://www.federalregister.gov/api/v1/documents.json?" + urllib.parse.urlencode(q)


def relevant(doc: dict, pattern: str | None) -> bool:
    if NOISE.search(doc.get("title", "")):
        return False
    if not pattern:
        return True
    return bool(re.search(pattern, f"{doc.get('title', '')} {doc.get('abstract') or ''}", re.I))


def regulatory(v, today: dt.date, get=_get, days: int = 45) -> dict:
    import vault_nodes as VN
    since = (today - dt.timedelta(days=days)).isoformat()
    kept = []
    for agency, (node, pattern) in AGENCIES.items():
        try:
            d = get(fr_url(agency, since))
        except Exception as e:
            kept.append({"error": f"{agency}: {e}"[:160]})
            continue
        for doc in d.get("results") or []:
            if relevant(doc, pattern):
                kept.append(dict(doc, node=node, agency=agency))
    docs = [k for k in kept if "error" not in k]
    seen, written = set(), []
    for doc in sorted(docs, key=lambda x: x.get("publication_date", ""), reverse=True):
        num = doc.get("document_number") or ""
        if not num or num in seen:
            continue
        seen.add(num)
        title = safe(doc.get("title"))[:150] or num
        name = v.safe_name(f"{doc.get('publication_date', '')} {title}", "Federal Register document")[:110]
        agencies = ", ".join(safe(a.get("name")) for a in doc.get("agencies") or [] if a.get("name"))
        kind = {"RULE": "Final rule", "PRORULE": "Proposed rule"}.get(doc.get("type", ""), doc.get("type", ""))
        kind = {"Rule": "Final rule", "Proposed Rule": "Proposed rule"}.get(kind, kind)
        body = [f"# 📜 {title}", "", f"- **What:** {kind}" + (" · significant" if doc.get("significant") else ""), f"- **From:** {agencies}",
                f"- **Published:** {doc.get('publication_date', '')}" + (f" · effective {doc['effective_on']}" if doc.get("effective_on") else "")
                + (f" · comments close {doc['comments_close_on']}" if doc.get("comments_close_on") else ""),
                f"- **Source:** [Federal Register {num}]({doc.get('html_url', '')})", "", "## Summary", "", safe(doc.get("abstract")) or "_No abstract._", "",
                "> [!question] Does this affect us?", "> Ask the assistant: “#ask/claude what does this rule change for a behavioral health facility?”"]
        rel = f"{REG_DIR}/{name}.md"
        v.write_managed(rel, {"type": "regulation", "blacklab_reference": "program", "source": "Federal Register", "document_number": num, "published": doc.get("publication_date", ""),
                              "kind": kind, "tags": ["regulatory", f"regulatory/{doc['agency'].split('-')[0]}"],
                              "up": [f"[[◆ {VN.BY_ID[doc['node']]['name']}]]" if doc["node"] in VN.BY_ID else "[[◆ Life Safety & Compliance]]"]},
                        "\n".join(body))
        written.append({"title": title, "date": doc.get("publication_date", ""), "kind": kind, "agency": agencies, "note": name, "url": doc.get("html_url", "")})
    v._collection("regulatory", {f"{REG_DIR}/{w['note']}.md" for w in written})  # aged out of the window → 99 Archive (never deleted)
    lines = ["# 📜 Regulatory Watch", "", f"_Federal Register rules and proposed rules from the last {days} days that touch facilities, life safety and "
             "behavioral health (OSHA, CMS, EPA, VA, DOE, SAMHSA) · checked twice a day._", ""]
    lines += [f"- {w['date']} · **{w['kind']}** · [[{w['note']}|{w['title']}]] — {w['agency']}" for w in written] or ["_Nothing new in this window._"]
    errs = [k["error"] for k in kept if "error" in k]
    if errs:
        lines += ["", "_Couldn't check: " + "; ".join(errs) + "_"]
    v.write_managed(f"{FEED_DIR}/📜 Regulatory Watch.md", {"type": "feed", "blacklab_reference": "program", "feed": "regulatory", "documents": len(written), "tags": ["feed", "feed/regulatory"],
                                                          "up": ["[[◆ Life Safety & Compliance]]", "[[◆ Facility Operations]]"]}, "\n".join(lines))
    return {"documents": written, "errors": errs}


# ---------------------------------------------------------------- eCFR: re-download amended parts

def ecfr(v, get=_get) -> dict:
    """The latest amendment date of each CFR part in the library; a part amended since its download is marked stale
    (library.download_docs fetches it again on its next run)."""
    import library
    st = library.load_state()
    stale = []
    dates = {}
    for doc_id, (title, part) in ECFR_PARTS.items():
        try:
            d = get(f"https://www.ecfr.gov/api/versioner/v1/versions/title-{title}.json?part={part}")
        except Exception:
            continue
        latest = max((x.get("amendment_date") or "" for x in d.get("content_versions") or []), default="")
        dates[doc_id] = latest
        have = st["docs"].get(doc_id) or {}
        if latest and have.get("ok") and (have.get("when") or "")[:10] < latest:
            have["ok"] = False  # amended since we saved it: fetch the current text again
            stale.append(f"{title} CFR {part} (amended {latest})")
    if stale:
        library.save_state(st)
    return {"latest": dates, "stale": stale}


# ---------------------------------------------------------------- security (CISA KEV)

def matches_software(vuln: dict, watch=WATCH_SOFTWARE) -> str:
    hay = f"{vuln.get('vendorProject', '')} {vuln.get('product', '')}".lower()
    for w in watch:
        if re.search(r"(?<![a-z])" + re.escape(w) + r"(?![a-z])", hay):
            return w
    return ""


def security(v, today: dt.date, get=_get, days: int = 45) -> dict:
    d = get("https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json", timeout=60)
    since = (today - dt.timedelta(days=days)).isoformat()
    recent = sorted([x for x in d.get("vulnerabilities") or [] if (x.get("dateAdded") or "") >= since], key=lambda x: x["dateAdded"], reverse=True)
    ours = [dict(x, hit=matches_software(x)) for x in recent if matches_software(x)]
    lines = ["# 🛡️ Security Advisories", "", f"_CISA Known Exploited Vulnerabilities (catalog {d.get('catalogVersion', '?')}): {len(recent)} added in the last {days} days, "
             f"{len(ours)} naming software on this computer or the phone · checked twice a day._", ""]
    if ours:
        lines += ["## ⚠️ Software you run", ""]
        lines += [f"- **{safe(x['vendorProject'])} {safe(x['product'])}** — {safe(x['vulnerabilityName'])} ({x['cveID']}, added {x['dateAdded']}, fix by {x.get('dueDate', '?')})"
                  f"  \n  {safe(x.get('requiredAction'))[:240]}" for x in ours[:25]]
        lines += ["", "Update with `sudo apt update && sudo apt upgrade` (this computer) and Settings › System › Software updates (the phone).", ""]
    lines += ["## Everything else added recently", ""] + [f"- {x['dateAdded']} · {safe(x['vendorProject'])} {safe(x['product'])} — {x['cveID']}"
                                                          for x in recent if not matches_software(x)][:40]
    lines += ["", "Framework: [[NIST Cybersecurity Framework 2.0]] in the library · node [[◆ IT, Networking & Systems]]"]
    v.write_managed(f"{FEED_DIR}/🛡️ Security Advisories.md", {"type": "feed", "blacklab_reference": "program", "feed": "security", "relevant": len(ours), "tags": ["feed", "feed/security"],
                                                             "up": ["[[◆ IT, Networking & Systems]]"]}, "\n".join(lines))
    return {"recent": len(recent), "ours": [{"cve": x["cveID"], "what": f"{x['vendorProject']} {x['product']}", "added": x["dateAdded"], "hit": x["hit"]} for x in ours]}


# ---------------------------------------------------------------- disasters (OpenFEMA)

def disasters(v, state: str, today: dt.date, get=_get) -> dict:
    since = (today - dt.timedelta(days=730)).isoformat()
    q = urllib.parse.urlencode({"$filter": f"state eq '{state}' and declarationDate ge '{since}T00:00:00.000z'", "$orderby": "declarationDate desc", "$top": "200"})
    d = get(f"https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries?{q}")
    by = {}
    for r in d.get("DisasterDeclarationsSummaries") or []:
        k = r.get("disasterNumber")
        e = by.setdefault(k, {"number": k, "title": safe(r.get("declarationTitle")), "type": r.get("incidentType") or "", "date": (r.get("declarationDate") or "")[:10],
                              "kind": r.get("declarationType") or "", "areas": []})
        a = safe(r.get("designatedArea"))
        if a and a not in e["areas"]:
            e["areas"].append(a)
    items = sorted(by.values(), key=lambda e: e["date"], reverse=True)
    lines = ["# 🚨 Disaster Declarations", "", f"_FEMA declarations for {state} in the last two years · checked daily._", ""]
    lines += [f"- {e['date']} · **{e['title']}** ({e['type']}, {e['kind']} {e['number']}) — {', '.join(e['areas'][:8])}{' …' if len(e['areas']) > 8 else ''}" for e in items] or ["_None._"]
    lines += ["", "Plans: [[CMS SOM Appendix Z — Emergency Preparedness (Rev. 204)]] · [[◆ Life Safety & Compliance]]"]
    v.write_managed(f"{FEED_DIR}/🚨 Disaster Declarations.md", {"type": "feed", "blacklab_reference": "program", "feed": "disasters", "declarations": len(items), "tags": ["feed", "feed/disasters"],
                                                               "up": ["[[◆ Life Safety & Compliance]]", "[[◆ Home, Family & Health]]"]}, "\n".join(lines))
    return {"declarations": items[:20]}


# ---------------------------------------------------------------- run what's due

def run(v, config: dict, now: float | None = None, get=_get, only: tuple[str, ...] | None = None) -> dict:
    now = now or time.time()
    today = dt.date.fromtimestamp(now)
    state = str(config.get("feeds_state") or "MO")
    counties = list(config.get("feeds_counties") or ["St. Louis"])
    report = {}
    for name in only or tuple(FEEDS):
        if only is None and not due(v, name, now):
            continue
        try:
            if name == "weather":
                r = weather(v, state, counties, get)
            elif name == "regulatory":
                r = regulatory(v, today, get)
            elif name == "ecfr":
                r = ecfr(v, get)
            elif name == "security":
                r = security(v, today, get)
            else:
                r = disasters(v, state, today, get)
            v._save_state(f"feeds/{name}.json", dict(r, at=now, ok=True))
            report[name] = "ok"
        except Exception as e:
            prev = v._state(f"feeds/{name}.json", {})
            v._save_state(f"feeds/{name}.json", dict(prev, at=now, ok=False, error=str(e)[:200]))
            report[name] = f"error: {e}"[:200]
    return report
