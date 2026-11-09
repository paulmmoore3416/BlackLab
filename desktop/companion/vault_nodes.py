"""The vault's neural network: one Nexus and 13 main nodes that grow as BlackLab, you and your AIs add information.

Every note BlackLab writes carries `up: [[◆ Node]]` (so the graph wires itself), every node links the Nexus and its
related nodes ("synapses"), and on each sync every node rebuilds its live part — members, vital signs, recent
signals from the daily timeline, and mentions found across the whole vault. Each node also starts with a detailed
primer (frameworks, standards, KPIs, checklists) in the part that belongs to you and your AIs, under `## Knowledge`.

The one function every node serves (the Nexus): turn what happens into better decisions and actions for Paul —
at the facility, in school and at home.
"""
from __future__ import annotations

import datetime as dt
import json
import re
from pathlib import Path

NODES_DIR = "Nodes"
NEXUS = "◉ Nexus"
FUNCTION = ("Every node serves one function: **turn what happens into better decisions and actions for Paul** — at the facility, "
            "in school and at home. Information flows in from BlackLab, the desktop AI and every agent; it is linked here, and it flows back "
            "out as answers, briefs, reminders and plans.")


def N(id, name, emoji, color, mission, synapses, folders=(), types=(), tags=(), asset_kinds=(), kinds=(), keywords=(), primer=""):
    return {"id": id, "name": name, "emoji": emoji, "color": color, "mission": mission, "synapses": list(synapses), "folders": list(folders),
            "types": list(types), "tags": list(tags), "asset_kinds": list(asset_kinds), "kinds": list(kinds), "keywords": list(keywords), "primer": primer}


NODES = [
    N("facility", "Facility Operations", "🏢", "#FF7A59",
      "Keep every building system safe, reliable and efficient — know every asset, its condition, its history and what it needs next.",
      ["safety", "vendors", "finance", "leadership"],
      folders=["10 Facility"], types=["asset", "facility-log", "work-order"], tags=["facility"], kinds=["facility"],
      keywords=["hvac", "chiller", "boiler", "ahu", "rtu", "vav", "pump", "generator", "roof", "plumbing", "electrical", "elevator", "work order",
                "preventive maintenance", "pm ", "filter", "belt", "bas", "controls", "lighting", "ballast", "water heater", "cmms"],
      primer="""## Knowledge

### Operating model
- **Maintenance mix:** reactive (run-to-failure, only for non-critical, cheap assets) · preventive (time/usage-based PM) · predictive (condition: vibration, thermography, oil analysis, ΔP across filters) · reliability-centered (RCM: pick the strategy per failure mode and consequence).
- **Asset hierarchy:** site → building → floor/area → system (e.g. HVAC) → asset (AHU-2) → component (supply fan motor). Tag every asset (`FAC-0042`) and keep make/model/serial/install date — BlackLab's Facility module and the desktop equipment registry both do.
- **Criticality ranking (1–5):** life safety · patient/occupant impact · regulatory exposure · redundancy · cost and lead time to repair. Criticality drives PM frequency, spare parts and response priority.
- **Work-order life cycle:** request → triage (priority P1 emergency < 1 h, P2 urgent < 24 h, P3 routine < 7 d, P4 planned) → assign → work → close with cause, action and parts → review repeat failures monthly.

### KPIs worth tracking
| KPI | How | Healthy range (benchmark, adjust to your site) |
|---|---|---|
| PM completion | PMs done on time ÷ PMs due | ≥ 90–95 % |
| Planned : reactive | planned WO hours ÷ all WO hours | ~80 : 20 |
| Backlog | open WO hours ÷ weekly crew hours | 2–4 weeks |
| Mean time to repair | Σ repair time ÷ repairs | trend down |
| Repeat failures | same asset, same failure, 90 days | trend to zero |
| Facility Condition Index | deferred maintenance ÷ current replacement value | < 5 % good · 5–10 % fair · > 10 % poor |
| Energy Use Intensity | kBtu ÷ ft² ÷ year | compare with ENERGY STAR Portfolio Manager peers |

### Standards & references
- **ASHRAE 180** (HVAC inspection & maintenance) · **ASHRAE 170** (healthcare ventilation) · **ASHRAE 62.1** (ventilation for IAQ) · **ASHRAE 188** + CMS water management expectations (Legionella risk plan) · **ASHRAE 52.2** (filter MERV ratings).
- Manufacturer O&M manuals override generic intervals; keep them linked from each asset note.

### Checklists
- **Monthly review:** overdue PMs · failed inspections still open · repeat failures · parts on order · vendor callbacks · energy anomalies.
- **New asset:** tag · photo of nameplate (BlackLab › nameplate) · warranty start/end · PM schedule · criticality · location · link to vendor.

### Questions an AI should ask here
- Which assets fail most often, and what do the failures have in common?
- Which PMs are overdue, and which of those are on critical assets?
- What should go into next year's capital plan, based on age, condition and repair spend?
""".strip()),

    N("safety", "Life Safety & Compliance", "🧯", "#EF5350",
      "Be survey-ready every day: life-safety systems inspected, deficiencies tracked to closure, and evidence one click away.",
      ["facility", "milieu", "vendors", "leadership"],
      types=["inspection"], tags=["facility/inspection"], asset_kinds=["Life safety", "Fire protection", "Generator", "Elevator"],
      keywords=["nfpa", "life safety", "fire", "sprinkler", "alarm", "extinguisher", "egress", "exit", "joint commission", "tjc", "cms", "eoc",
                "environment of care", "ilsm", "survey", "osha", "ligature", "eyewash", "emergency lighting", "fire door", "drill", "k-tag", "statement of conditions",
                "hot work", "icra", "lockout", "hazcom", "sds"],
      primer="""## Knowledge

> Intervals below are the common ones; your AHJ, accreditor and the code edition your state/CMS adopts decide. CMS currently enforces the **2012 editions of NFPA 101 and NFPA 99** for most healthcare settings — confirm for your occupancy.

### The core inspection calendar
| System | Standard | Typical frequency |
|---|---|---|
| Portable extinguishers | NFPA 10 | monthly visual · annual maintenance · 6-year maintenance & 12-year hydrostatic (stored-pressure dry chemical) |
| Emergency generator | NFPA 110 | weekly inspection · monthly load run (≥ 30 min, ≥ 30 % nameplate or manufacturer exhaust temp) · annual · 4-hour run every 36 months (TJC) |
| Emergency & exit lighting | NFPA 101 §7.9 | monthly 30-second test · annual 90-minute test |
| Fire alarm | NFPA 72 | devices tested per table (most annually) · batteries semiannual |
| Sprinklers | NFPA 25 | valves/gauges weekly–monthly · main drain & flow tests quarterly–annual · 5-year internal |
| Fire & smoke doors | NFPA 80 / 105 | annual inspection with documentation |
| Kitchen hood suppression | NFPA 96 / 17A | semiannual |
| Eyewash / showers | ANSI Z358.1 | weekly activation · annual full inspection |
| Fire drills | NFPA 101 / TJC EC | healthcare: once per shift per quarter |

### Programs and terms
- **TJC chapters:** EC (Environment of Care), LS (Life Safety), EM (Emergency Management). **CMS:** K-tags (Life Safety Code), E-tags (Emergency Preparedness).
- **ILSM** — interim life safety measures whenever a life-safety deficiency can't be fixed right away or during construction; document the assessment and the measures.
- **Statement of Conditions / building maintenance program** — keep deficiencies, the plan and the closure date current.
- **ICRA** (infection control risk assessment) and **hot work permits** (NFPA 51B) before construction or open flame.
- **OSHA general industry:** lockout/tagout (1910.147), HazCom & SDS (1910.1200), walking-working surfaces, electrical safety (NFPA 70E).
- **Ligature risk:** an environmental risk assessment of every space a person at risk could use alone; ligature-resistant fixtures where required; written mitigation (observation, locked spaces) for what can't be fixed yet; reassess after any change. See [[◆ Peer Support & Milieu Safety]].

### Survey binder (what to have ready)
Inspection logs for every system above · open deficiencies with ILSM · fire drill reports · generator logs · utility management plan · emergency operations plan · hazard vulnerability analysis · water management plan · BlackLab › Facility › Survey binder (PDF).

### Questions an AI should ask here
- What is overdue or failed right now, and is an ILSM needed?
- What would a surveyor ask for first this week, and do we have it?
""".strip()),

    N("milieu", "Peer Support & Milieu Safety", "🤝", "#AB7CFF",
      "Practice recovery-oriented, trauma-informed peer support and keep the physical environment safe — knowledge and practice only, never anyone's information.",
      ["safety", "leadership", "learning", "home"],
      keywords=["peer support", "peer specialist", "recovery", "trauma-informed", "de-escalation", "milieu", "rounds", "boundaries", "self-care",
                "grounding", "wellness", "988", "crisis line", "safety rounds", "contraband", "sharps"],
      primer="""## Knowledge

> 🔒 **No one's information goes here** — no names, initials, rooms, diagnoses or events about a person. The vault refuses clinical text. This node is for *how* you practice: principles, skills, checklists, lessons learned, training notes.

### Peer support foundations
- **Values:** recovery-oriented · person-driven · voluntary · relationship-focused · trauma-informed · strengths-based · hope as a practice.
- **SAMHSA's six principles of a trauma-informed approach:** safety · trustworthiness & transparency · peer support · collaboration & mutuality · empowerment, voice & choice · cultural, historical & gender issues.
- **Core competencies (SAMHSA peer workers):** engage in recovery-oriented, person-centered, voluntary, relationship-focused, trauma-informed practice; share lived experience purposefully; support recovery planning; link to resources and natural supports; communicate effectively; support collaboration and teamwork; promote leadership and advocacy; grow professionally.
- **Purposeful self-disclosure:** brief, relevant, focused on hope and what helped — then return the focus to the person.

### De-escalation (general practice)
The widely taught ten domains of verbal de-escalation: respect personal space · don't be provocative · establish verbal contact (one person leads) · be concise · identify wants and feelings · listen closely · agree or agree to disagree · set clear limits · offer choices and optimism · debrief everyone afterwards. Follow your facility's own model and policy (e.g. CPI, Mandt) — it governs.

### Milieu & environmental safety
- Rounds and environmental checks per facility policy and frequency; document per policy (not here).
- Ligature-resistant environment and the ligature risk assessment — see [[◆ Life Safety & Compliance]].
- Contraband and sharps procedures, visitor and delivery checks, safe furniture and fixtures, blind spots and sightlines, door hardware.
- After any incident: review the *environment* (what physical change would prevent recurrence?) and log a facility work order if needed.

### Boundaries & sustaining yourself
Dual relationships · gifts · social media · scope of practice (peer, not clinician) · supervision and debriefs · your own wellness plan · compassion-fatigue warning signs.

### Resources
988 Suicide & Crisis Lifeline (call/text 988) · SAMHSA National Helpline 1-800-662-4357 · your state's peer certification board (CEUs and renewal).
""".strip()),

    N("leadership", "Leadership & Team", "🧭", "#4F8CFF",
      "Lead the facilities team: clear priorities, fair staffing, strong handoffs, growth for every person, and decisions on record.",
      ["facility", "vendors", "finance", "time"],
      folders=["60 Meetings/Minutes"], types=["meeting", "decision", "person", "review"], tags=["meeting", "decision", "people"],
      keywords=["staff", "team", "hiring", "interview", "one-on-one", "1:1", "training", "leadership", "policy", "sop", "handoff", "handover",
                "schedule", "overtime", "coverage", "performance", "recognition", "meeting"],
      primer="""## Knowledge

### Rhythm of leadership
- **Daily:** 10-minute huddle (safety first, today's priorities, blockers) · shift handoff (open WOs, hazards, ILSMs, vendor visits).
- **Weekly:** 1:1s (their agenda first, then yours, then growth) · backlog and PM review · [[Weekly Review]].
- **Monthly:** KPIs to leadership · safety committee/EOC rounds · recognition.
- **Quarterly:** goals check · training matrix · budget variance · succession and cross-training gaps.

### Tools
- **SBI feedback:** Situation → Behavior → Impact, then ask and listen.
- **RACI** for every project: Responsible · Accountable (one person) · Consulted · Informed.
- **Delegation levels:** do as told → research and report → recommend → decide and tell me → decide.
- **Decision records:** use `90 System/Templates/Decision Record` — context, options, decision, consequences, revisit date.

### Staffing math
- FTE = 2,080 paid hours/year; productive hours are usually ~1,800–1,900 after PTO, training and holidays.
- Required FTEs ≈ (annual WO + PM labor hours) ÷ productive hours per FTE; add coverage for 24/7 posts (≈ 4.2–5 FTE per round-the-clock post).
- Track: turnover · overtime % · training compliance · open positions and time to fill.

### Questions an AI should ask here
- Who is overloaded, and what can be delegated or deferred?
- What decisions are waiting on me, and which meetings produced no actions?
""".strip()),

    N("vendors", "Vendors & Contracts", "📑", "#26C6DA",
      "Know every contractor and agreement: who does what, how well, at what cost, under which terms — and when it renews.",
      ["facility", "finance", "safety"],
      keywords=["vendor", "contractor", "quote", "bid", "rfp", "contract", "warranty", "coi", "certificate of insurance", "invoice", "purchase order",
                "service agreement", "sla", "subcontractor", "renewal", "change order", "lien waiver"],
      primer="""## Knowledge

### Onboarding a vendor
W-9 · certificate of insurance (general liability, auto, workers' comp; your organization as additional insured; track expiry) · state license · safety orientation (hazards, ILSM, hot work, ICRA, badging, escort rules) · emergency contact · billing terms.

### Agreements
- **Scope** (what, where, frequency, exclusions) · **SLA** (response: emergency ≤ 2–4 h, urgent ≤ 24 h, routine ≤ 5 d) · **documentation** they must return (inspection reports, deficiency lists) · **pricing** (rates, after-hours, parts markup, escalation cap) · **term & renewal** (auto-renew notice window!) · **termination**.
- Warranties: start date = substantial completion; track per asset; call the installer first.

### Buying
- Quote thresholds per your policy (commonly 3 quotes above a set amount; formal bid/RFP above a higher one).
- Compare total cost of ownership, not price alone — see [[◆ Finance & Budget]].
- Change orders in writing before work; lien waivers with payment on construction.

### Vendor scorecard (1–5 each)
Quality · timeliness · communication · safety & compliance · documentation · cost. Review twice a year; keep the score in the vendor's note.

### Questions an AI should ask here
- Which COIs or contracts expire in the next 60 days?
- Which vendor has the most callbacks or the slowest response?
""".strip()),

    N("finance", "Finance & Budget", "💵", "#81C784",
      "Spend well and plan ahead: operating budget, capital plan, life-cycle costs and the business case for every request.",
      ["facility", "vendors", "leadership", "vehicles"],
      keywords=["budget", "cost", "capex", "opex", "capital", "invoice", "spend", "price", "purchase", "reimbursement", "payback", "roi", "variance",
                "deferred maintenance", "fci", "estimate", "forecast", "$"],
      primer="""## Knowledge

### Concepts
- **OPEX** (operating: labor, contracts, utilities, supplies) vs **CAPEX** (capital: replacements and projects above your capitalization threshold, multi-year life).
- **Life-cycle / total cost of ownership** = purchase + installation + energy + maintenance + downtime + disposal over the useful life.
- **Simple payback** = cost ÷ annual savings · **ROI** = (savings − cost) ÷ cost · for big projects use NPV with your organization's discount rate.
- **FCI** = deferred maintenance ÷ current replacement value (see [[◆ Facility Operations]]).

### The 5-year capital plan
List each candidate with age vs expected life (ASHRAE/BOMA service-life tables), condition, repair spend last 3 years, criticality and risk. Rank by risk × consequence; show what happens if it's deferred.

### A budget request that gets approved
1. The need (data: failures, age, code citations, photos) · 2. Options (do nothing / repair / replace / alternative) · 3. Cost of each, incl. TCO · 4. Risk of not doing it · 5. Savings and payback · 6. Timeline · 7. The ask. BlackLab template: *budget request*.

### Watch monthly
Budget vs actual (variance > 10 % needs a note) · overtime · contract spend · utilities per ft² · emergency repairs (unplanned spend is a reliability signal).
""".strip()),

    N("projects", "Projects & Engineering", "🧩", "#F06292",
      "Build and ship: BlackLab and every project — architecture, releases, decisions and what's next.",
      ["ai", "knowledge", "time"],
      folders=["02 Projects"], types=["project"], tags=["project"], kinds=["project"],
      keywords=["blacklab", "release", "commit", "code", "app", "github", "build", "bug", "feature", "kotlin", "python", "android", "deploy", "version"],
      primer="""## Knowledge

### BlackLab at a glance
- **Phone app** (Kotlin + Jetpack Compose, Room, WorkManager; Pixel 10 Pro XL): Facility, Garage, To-Do, Schedule, Notes & Notebooks, Documents, Journal, Recorder & Dictation, Camera, Education, Assistant, Behavioral Health workspace (encrypted, phone-only).
- **BlackLab Desktop** (Python standard library, `desktop/companion`): web UI on 127.0.0.1:8770, USB/Wi-Fi link to the phone, local AI (Ollama), jobs (Whisper, briefs, equipment registry), this vault.
- **Web client** (GitHub Pages) and **Android Auto** screens.
- **Release:** `scripts/release.sh X.Y.Z [--install] [--publish]` — secret scan, privacy scan, tests, lint, signed APK + `.sha256` for the in-app updater.
- **Rules:** the repo is public — no keys, VIN, personal data; schema changes need a Room migration; editor CSP hash test.

### Engineering practice
Small, reviewed changes · tests next to the logic (pure functions where possible) · decision records for anything you'd have to explain twice · a changelog entry per user-visible change · back up the signing key (losing it means no more updates).

### Questions an AI should ask here
- What changed this week, what's half-done, and what's the next release about?
""".strip()),

    N("ai", "AI & Automation", "🤖", "#2BD9A8",
      "Make the AIs work as one team: who can do what, what they remember, how they sign in, and what they did.",
      ["projects", "knowledge", "time"],
      folders=["70 AI"], types=["ai-session", "task-log", "conversation", "registry"], tags=["ai", "desktop/conversation", "desktop/task-log"],
      kinds=["ai", "agent", "registry"],
      keywords=["assistant", "desktop ai", "claude", "ollama", "gemini", "nano", "mcp", "agent", "prompt", "model", "automation", "whisper"],
      primer="""## Knowledge

### The AI team
| AI | Where | Can | Can't |
|---|---|---|---|
| Phone assistant ("Todd") | BlackLab on the phone | commands (tasks, calendar, notes, Facility, Garage), Ask my files, Gemini Nano on-device, hands off to the desktop | read Behavioral Health aloud; send anything clinical off the phone |
| Desktop AI | BlackLab Desktop (Ollama: qwen3:8b…) | files, documents, equipment registry, briefs, meeting minutes (Whisper), vault search/write | open links found inside documents; touch protected folders (keys, wallets) |
| Claude Code | terminal, this repo | builds BlackLab, works in the vault through the MCP server | push or publish without Paul's go-ahead |
| Any MCP agent | blacklab-vault MCP | checkin, search, read, write, log, nodes | edit BlackLab's managed blocks, write clinical text or secrets |

### Protocol
Sign the [[AI Registry]] · read [[AI Protocol]] · search before writing · log every meaningful change on the day's timeline · add knowledge to the right node with `vault_node_add` · link generously.

### Memory layers
Phone: Ask-my-files index (on-device) · Desktop: `Chat History/memory.md` + document embeddings · Vault: everything, linked — the long-term memory every AI can read.

### Good prompts
Say the outcome, the audience, the constraints and the format ("one-page budget request for the chiller, for the CFO, with payback").
""".strip()),

    N("learning", "Learning & Credentials", "🎓", "#FFB74D",
      "Keep growing: degrees, certifications, CEUs and study that compound into expertise.",
      ["knowledge", "facility", "milieu", "time"],
      folders=["40 Learning"], types=["course", "certification"], tags=["learning"], kinds=["learning"],
      keywords=["cfm", "fmp", "sfp", "chfm", "ceu", "exam", "class", "course", "assignment", "flashcard", "study", "certification", "degree", "credit",
                "lecture", "quiz", "homework"],
      primer="""## Knowledge

### Facility management credentials (confirm current requirements with each body)
- **IFMA FMP** (Facility Management Professional): four courses/exams — Operations & Maintenance · Project Management · Finance & Business · Leadership & Strategy.
- **IFMA SFP** (Sustainability Facility Professional): Strategy & Alignment · Data Analysis & Reporting · Operations & Maintenance.
- **IFMA CFM** (Certified Facility Manager): eligibility by education + FM experience; one comprehensive exam across IFMA's core competencies (communication; emergency preparedness & business continuity; environmental stewardship & sustainability; finance & business; human factors; leadership & strategy; operations & maintenance; project management; quality; real estate; technology); recertify every 3 years.
- **AHA/ASHE CHFM** (Certified Healthcare Facility Manager): healthcare-specific — compliance (TJC, CMS, NFPA), planning, design & construction, maintenance & operations, finance.
- **Peer Support Specialist:** your state board's CEU and renewal rules — see [[◆ Peer Support & Milieu Safety]].

### How to study (evidence-based)
Retrieval practice (flashcards in BlackLab, practice exams) · spaced repetition · interleaving topics · elaboration (explain it to someone) · worked examples · sleep. Use Cornell notes for lectures; turn every mistake on a practice test into a flashcard.

### Track
CEUs per credential and renewal date · exam dates · assignments due in 14 days · GPA trend.
""".strip()),

    N("knowledge", "Knowledge Library", "📚", "#90CAF9",
      "Your reference shelf: SOPs, playbooks, how-tos, lessons learned and notes worth keeping — written once, found instantly.",
      ["learning", "projects", "ai", "facility"],
      folders=["80 Knowledge", "50 Notes/Notebooks", "00 Inbox"], types=["guide", "page", "notebook"], tags=["knowledge", "notebook"],
      keywords=["sop", "how to", "how-to", "playbook", "reference", "checklist", "lesson learned", "procedure", "guide", "template"],
      primer="""## Knowledge

### How this library works
- **Atomic notes:** one idea, procedure or answer per note, with a clear title ("Resetting the BAS after a power failure").
- **Maps of content:** the 13 nodes *are* the maps; link a new note to its node and it joins the network.
- **Properties:** `type` (guide, sop, reference, lesson), `status` (draft/current/retired), `owner`, `reviewed` (date).
- **Inbox zero, weekly:** move each `00 Inbox` note to its home, link it to a node, or delete it.
- **Write for the 2 a.m. reader:** steps numbered, warnings first, photos where they help.

### SOP skeleton
Purpose · scope · safety (PPE, LOTO) · tools & parts · steps · verification · who to call · revision history.

### Lessons learned
What happened · what we expected · why it differed · what we'll do next time · who needs to know.
""".strip()),

    N("vehicles", "Vehicles & Travel", "🚙", "#64B5F6",
      "Keep the car safe, maintained and paid for: service on time, recalls closed, fuel and trips understood.",
      ["finance", "time", "home"],
      folders=["20 Garage"], types=["vehicle"], tags=["garage"], kinds=["garage"],
      keywords=["santa fe", "hyundai", "oil change", "tire", "fuel", "gas", "mileage", "odometer", "recall", "trip", "drive", "registration", "inspection sticker"],
      primer="""## Knowledge

### Ownership rhythm
- **Every fill-up:** log it in BlackLab (MPG and cost per mile follow).
- **Monthly:** tire pressure (door-jamb placard, cold tires) · lights · wipers · fluid levels.
- **Per schedule:** oil and filter (severe-service if mostly short trips, towing, extreme heat/cold) · tire rotation · cabin and engine air filters · brake inspection · coolant, transmission and transfer-case service per the owner's manual.
- **Recalls:** BlackLab checks NHTSA; close each at the dealer and mark it resolved.

### Business mileage
Log trip purpose for work drives (BlackLab trip rules); the IRS standard mileage rate changes every year — use the current one.

### Road kit & seasons
Jumper pack · tire inflator · first aid · flashlight · water · blanket; winter: scraper, washer fluid rated for cold, battery test before the first freeze.
""".strip()),

    N("home", "Home, Family & Health", "🏡", "#FF8A80",
      "Run a calm household: home systems maintained, family plans in sync, important documents ready, health habits on track.",
      ["time", "finance", "vehicles", "milieu"],
      tags=["label/home", "label/family", "label/personal", "journal"], types=["journal"], folders=["50 Notes/Journal"],
      keywords=["family", "home", "kids", "errand", "groceries", "birthday", "school", "doctor", "dentist", "gym", "workout", "health", "vacation",
                "house", "yard", "dinner", "church", "appointment"],
      primer="""## Knowledge

### Home systems calendar (the facility mindset at home)
| When | What |
|---|---|
| Monthly | test smoke & CO alarms · check HVAC filter (replace every 1–3 months) · run water in unused fixtures |
| Spring | gutters · AC service · outdoor faucets and hoses · grade and drainage check |
| Fall | furnace service · gutters · seal drafts · winterize irrigation and hose bibs |
| Yearly | water heater flush · dryer vent cleaning · replace alarm batteries (10-year sealed units: replace at expiry) · roof and attic look · fire extinguisher gauge |

### Family operating system
Shared calendar (BlackLab Schedule › Family) · Sunday 15-minute plan for the week · chores and allowances · emergency plan (meeting place, contacts, go-bag) · important documents in one safe place (IDs, insurance, titles, wills, medical directives).

### Health habits
Sleep · movement · water · meals · preventive appointments on the calendar. Keep personal health details in your own notes only if you want them here — the journal is off unless you turn it on.
""".strip()),

    N("time", "Time, Goals & Planning", "⏳", "#FFD54F",
      "Turn intentions into calendars: daily notes, weekly reviews, quarterly goals — the clock that every other node runs on.",
      ["leadership", "learning", "home", "projects"],
      folders=["01 Daily", "30 Work"], types=["daily", "calendar", "tasks", "review"], tags=["daily", "work/tasks", "work/calendar", "review"],
      kinds=["event", "task", "sync"],
      keywords=["goal", "plan", "deadline", "due", "today", "tomorrow", "this week", "review", "priority", "focus", "okr", "quarter"],
      primer="""## Knowledge

### The loop
- **Capture** everything (BlackLab assistant, quick capture, inbox) → **clarify** (is it actionable? next action?) → **organize** (task, calendar, reference, someday) → **reflect** (daily & weekly) → **engage** (do the right thing now). *(GTD)*
- **Eisenhower:** urgent & important → do · important, not urgent → schedule · urgent, not important → delegate · neither → drop.
- **Time blocking:** protect two focus blocks a day; batch email and calls; leave buffer for the emergencies a facility always brings.

### Weekly review (30 minutes, Friday)
Inbox to zero · review last week's daily notes · open work orders and overdue PMs · calendar next 2 weeks · goals progress · one thing to stop doing · wins. Template: `90 System/Templates/Weekly Review`.

### Quarterly goals
3 goals max, each with a measurable result and the first next action. Review monthly; carry learnings into the next quarter.

### The daily note
Every day in `01 Daily` gets an agenda and a timeline automatically. Add your focus at the top and your notes at the bottom — that's your log.
""".strip()),
]

BY_ID = {n["id"]: n for n in NODES}

from vault_subnodes import GROUP as SUB_GROUP, SUBNODES, SUB_BY_ID  # noqa: E402


def subtitle(s) -> str:
    return f"◇ {s['name']}"


def subrel(s) -> str:
    return f"{NODES_DIR}/{SUB_GROUP}/{subtitle(s)}.md"


def sublink(s) -> str:
    return f"[[{subtitle(s)}]]"


def title(n) -> str:
    return f"◆ {n['name']}"


def rel(n) -> str:
    return f"{NODES_DIR}/{title(n)}.md"


def link(n) -> str:
    return f"[[{title(n)}]]"


def _tags(props) -> list[str]:
    t = props.get("tags") or []
    if isinstance(t, str):
        t = [x.strip() for x in t.strip("[]").split(",")]
    return [str(x).strip().strip('"') for x in t if str(x).strip()]


def primary(rel_path: str, props: dict) -> list[dict]:
    """The nodes a note belongs to by where it lives and what it is (no content scan)."""
    if rel_path.startswith(NODES_DIR + "/") or rel_path.startswith(NEXUS):
        return []
    typ = str(props.get("type", "")).strip('"')
    tags = _tags(props)
    kind = str(props.get("asset_kind", "")).strip('"')
    out = []
    for n in NODES:
        if (any(rel_path.startswith(f + "/") or rel_path == f for f in n["folders"]) or typ in n["types"] or kind in n["asset_kinds"]
                or any(t == nt or t.startswith(nt + "/") for t in tags for nt in n["tags"])):
            out.append(n)
    return out


def up_links(rel_path: str, props: dict) -> list[str]:
    if rel_path in ("Home.md", "AI Registry.md", "AI Protocol.md", "README.md"):
        return [f"[[{NEXUS}]]"]
    return [link(n) for n in primary(rel_path, props)]


def _parse_props(lines: list[str]) -> dict:
    out = {}
    for l in lines:
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", l)
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def _keyword_hits(n, text: str) -> int:
    return sum(text.count(k) for k in n["keywords"])


def render(v) -> dict:
    """Rebuilds every node, the Nexus and the Neural Map canvas. Returns {node id: members}."""
    root = v.root
    notes = []
    for p in root.rglob("*.md"):
        parts = p.relative_to(root).parts
        if parts[0] in (".obsidian", ".git", NODES_DIR, "99 Archive") or p.name.startswith(".") or parts[:2] == ("90 System", "Templates"):
            continue
        if p.name == f"{NEXUS}.md":
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        fm, body = v.split_frontmatter(text)
        props = _parse_props(fm)
        r = str(p.relative_to(root))
        notes.append({"rel": r, "title": p.stem, "props": props, "low": (p.stem + "\n" + body[:6000]).lower(),
                      "mtime": p.stat().st_mtime, "folder": parts[0]})
    today = v.now().date()
    signals_by_day = [(today - dt.timedelta(days=i), v._state(f"timeline/{(today - dt.timedelta(days=i)).isoformat()}.json", [])) for i in range(14)]
    summary = {}
    for n in NODES:
        members = [x for x in notes if n in primary(x["rel"], x["props"])]
        member_set = {x["rel"] for x in members}
        mentions = sorted(((x, _keyword_hits(n, x["low"])) for x in notes if x["rel"] not in member_set), key=lambda t: -t[1])
        mentions = [(x, h) for x, h in mentions if h >= 2][:40]
        signals = []
        for d, items in signals_by_day:
            for e in items:
                txt = (e.get("title", "") + " " + e.get("detail", "")).lower()
                if e.get("kind") in n["kinds"] or _keyword_hits(n, txt) > 0:
                    signals.append((d, e))
        by_type: dict[str, list] = {}
        for x in members:
            by_type.setdefault(str(x["props"].get("type", "note")).strip('"') or "note", []).append(x)
        week_ago = (dt.datetime.now() - dt.timedelta(days=7)).timestamp()
        growth = sum(1 for x in members if x["mtime"] >= week_ago)
        lines = [f"# {n['emoji']} {n['name']}", "", f"> [!abstract] Mission", f"> {n['mission']}", "",
                 f"**Part of** [[{NEXUS}]] · **Synapses:** " + " · ".join(link(BY_ID[s]) for s in n["synapses"]), "",
                 "## Vital signs", "", "| Members | Mentions elsewhere | Signals (14 days) | Changed this week | Updated |", "|---|---|---|---|---|",
                 f"| {len(members)} | {len(mentions)} | {len(signals)} | {growth} | {v.now().strftime('%Y-%m-%d %H:%M')} |", ""]
        if signals:
            lines += ["## Recent signals", ""]
            for d, e in sorted(signals, key=lambda t: -t[1].get("ts", 0))[:25]:
                lines.append(f"- [[{d.isoformat()}]] `{e.get('t', '')}` {v.KIND_ICON.get(e.get('kind'), '•')} {e.get('title', '')} <small>({e.get('source', '')})</small>")
            lines.append("")
        if members:
            lines += ["## Members", ""]
            for typ, xs in sorted(by_type.items(), key=lambda kv: (-len(kv[1]), kv[0])):
                xs = sorted(xs, key=lambda x: -x["mtime"])
                lines.append(f"### {typ.replace('-', ' ').title()} ({len(xs)})")
                lines.append("")
                for x in xs[:60]:
                    status = str(x["props"].get("status", "")).strip('"')
                    lines.append(f"- [[{x['title']}]]" + (f" · {status}" if status and status not in ("ok", "active") else ""))
                if len(xs) > 60:
                    lines.append(f"- _…and {len(xs) - 60} more — search `up: \"{title(n)}\"`_")
                lines.append("")
        subs = [sn for sn in SUBNODES if sn["parent"] == n["id"]]
        related_subs = [sn for sn in SUBNODES if n["id"] in sn["related"] and sn["parent"] != n["id"]]
        if subs or related_subs:
            lines += [f"## Subnodes" + (f" · {SUB_GROUP}" if subs else ""), ""]
            lines += [f"- {sn['emoji']} {sublink(sn)} — {sn['mission']}" for sn in subs]
            lines += [f"- {sn['emoji']} {sublink(sn)} <small>(related)</small>" for sn in related_subs]
            lines.append("")
        sources = [x for x in notes if str(x["props"].get("type", "")).strip('"') == "source" and str(x["props"].get("node", "")).strip('"') == n["id"]]
        if sources:
            gb = sum(float(str(x["props"].get("size_gb", 0) or 0).strip('"') or 0) for x in sources)
            lines += [f"## Library ({len(sources)} sources · {gb:.1f} GB offline)", ""]
            lines += [f"- [[{x['title']}]]" + ("" if str(x["props"].get("status", "")).strip('"') == "ok" else " · ⏳ downloading") for x in sorted(sources, key=lambda x: x["title"])] + [""]
        if mentions:
            lines += ["## Mentioned across the vault", ""] + [f"- [[{x['title']}]] <small>×{h}</small>" for x, h in mentions[:25]] + [""]
        lines += ["## Grow this node", "",
                  "Add knowledge under **## Knowledge** below (it's yours and your AIs' — BlackLab never rewrites it). AIs: `vault_node_add` with "
                  f"node `{n['id']}`. Link new notes here with `up: \"{link(n)}\"`."]
        v.write_managed(rel(n), {"type": "node", "node": n["id"], "aliases": [n["name"]], "members": len(members), "mentions": len(mentions),
                                 "signals": len(signals), "up": [f"[[{NEXUS}]]"], "tags": ["node", f"node/{n['id']}"], "cssclasses": ["blacklab-node"]},
                        "\n".join(lines), user_tail="\n" + n["primer"] + "\n\n## Growth log\n\n")
        summary[n["id"]] = {"members": len(members), "mentions": len(mentions), "signals": len(signals), "growth": growth}
    for sn in SUBNODES:
        parent = BY_ID[sn["parent"]]
        mine = [x for x in notes if subtitle(sn) in str(x["props"].get("up", "")) or f"{sn['id']}" in str(x["props"].get("subnodes", "")).replace('"', "").replace(" ", "").split(",")
                or sn["id"] in re.findall(r"[a-z]+", str(x["props"].get("subnodes", "")))]
        mine_set = {x["rel"] for x in mine}
        ment = sorted(((x, sum(x["low"].count(k) for k in sn["keywords"])) for x in notes if x["rel"] not in mine_set), key=lambda t: -t[1])
        ment = [(x, h) for x, h in ment if h >= 2][:25]
        lines = [f"# {sn['emoji']} {sn['name']}", "", "> [!abstract] Mission", f"> {sn['mission']}", "",
                 f"**Part of** {link(parent)} · {SUB_GROUP} · **Related:** " + " · ".join(
                     link(BY_ID[r]) if r in BY_ID else sublink(SUB_BY_ID[r]) for r in sn["related"] if r in BY_ID or r in SUB_BY_ID), ""]
        srcs = [x for x in mine if str(x["props"].get("type", "")).strip('"') == "source"]
        if srcs:
            lines += [f"## Library ({len(srcs)} sources)", ""] + [f"- [[{x['title']}]]" + ("" if str(x["props"].get("status", "")).strip('"') == "ok" else " · ⏳") for x in sorted(srcs, key=lambda x: x["title"])] + [""]
        others = [x for x in mine if x not in srcs]
        if others:
            lines += ["## Notes", ""] + [f"- [[{x['title']}]]" for x in others[:60]] + [""]
        if ment:
            lines += ["## Mentioned across the vault", ""] + [f"- [[{x['title']}]] <small>×{h}</small>" for x, h in ment] + [""]
        lines += ["## Grow this subnode", "", f"Add knowledge under **## Knowledge** below; AIs: `vault_node_add` with node `{sn['id']}`. "
                  "Ask the library: *“look it up in the library: …”*."]
        v.write_managed(subrel(sn), {"type": "subnode", "node": sn["parent"], "subnode": sn["id"], "aliases": [sn["name"]], "members": len(mine),
                                     "up": [link(parent)], "tags": ["subnode", f"node/{sn['parent']}", f"subnode/{sn['id']}"]},
                        "\n".join(lines), user_tail="\n" + sn["primer"] + "\n\n## Growth log\n\n")
    _nexus(v, summary, len(notes))
    _canvas(v, summary)
    _graph_colors(v)
    return summary


def _nexus(v, summary, total):
    lines = [f"# ◉ Nexus", "", f"> [!tip] The one function", f"> {FUNCTION}", "",
             "## The 13 nodes", "", "| | Node | Mission | Members | Signals (14 d) | This week |", "|---|---|---|---|---|---|"]
    for n in NODES:
        s = summary.get(n["id"], {})
        lines.append(f"| {n['emoji']} | {link(n)} | {n['mission'].split(':')[0][:90]} | {s.get('members', 0)} | {s.get('signals', 0)} | +{s.get('growth', 0)} |")
    strongest = sorted(NODES, key=lambda n: -summary.get(n["id"], {}).get("signals", 0))[:3]
    quiet = [n for n in NODES if summary.get(n["id"], {}).get("members", 0) == 0]
    lines += ["", "## Network health", "", f"- **Notes in the network:** {total}",
              "- **Most active:** " + ", ".join(link(n) for n in strongest),
              ("- **Waiting for their first notes:** " + ", ".join(link(n) for n in quiet)) if quiet else "- Every node has members.",
              "- **Map:** [[Neural Map.canvas|Neural Map]] · **Start:** [[Home]] · **AIs:** [[AI Registry]] · [[AI Protocol]]", "",
              "## How information flows", "",
              "1. **In:** the phone's feed, the desktop AI, meeting minutes, git projects, files, and every agent through the vault MCP server.",
              "2. **Linked:** each note gets `up:` links to its node(s); nodes link their members, mentions, synapses and this Nexus.",
              "3. **Out:** your assistant, the desktop AI and Claude search the network (`vault_search`, `vault_nodes`) to answer, brief and plan.",
              "4. **Grows:** add knowledge to a node (`vault_node_add`, or write under its *Knowledge*), and it's there for every AI, every time."]
    v.write_managed(f"{NEXUS}.md", {"type": "nexus", "aliases": ["Nexus", "Brain", "Neural network"], "nodes": len(NODES), "notes": total,
                                   "tags": ["node", "nexus"], "cssclasses": ["blacklab-nexus"]}, "\n".join(lines), user_tail="\n## Notes\n\n")


def _canvas(v, summary):
    import math
    nodes, edges = [], []
    nodes.append({"id": "nexus", "type": "file", "file": f"{NEXUS}.md", "x": -200, "y": -120, "width": 400, "height": 240, "color": "6"})
    R = 900
    pos = {}
    for i, n in enumerate(NODES):
        a = 2 * math.pi * i / len(NODES) - math.pi / 2
        x, y = int(R * math.cos(a)) - 170, int(R * math.sin(a)) - 100
        pos[n["id"]] = (x, y)
        nodes.append({"id": n["id"], "type": "file", "file": rel(n), "x": x, "y": y, "width": 340, "height": 200, "color": n["color"]})
        edges.append({"id": f"e-{n['id']}", "fromNode": "nexus", "toNode": n["id"], "color": n["color"]})
    seen = set()
    for n in NODES:
        for s in n["synapses"]:
            k = tuple(sorted((n["id"], s)))
            if k not in seen:
                seen.add(k)
                edges.append({"id": f"s-{k[0]}-{k[1]}", "fromNode": k[0], "toNode": k[1]})
    subs = [sn for sn in SUBNODES]
    for j, sn in enumerate(subs):
        px, py = pos[sn["parent"]]
        a = 2 * math.pi * j / max(1, len(subs))
        x, y = px + int(520 * math.cos(a)) + 60, py + int(420 * math.sin(a)) + 40
        nodes.append({"id": f"sub-{sn['id']}", "type": "file", "file": subrel(sn), "x": x, "y": y, "width": 260, "height": 140, "color": BY_ID[sn["parent"]]["color"]})
        edges.append({"id": f"es-{sn['id']}", "fromNode": sn["parent"], "toNode": f"sub-{sn['id']}"})
    v._write("90 System/Neural Map.canvas", json.dumps({"nodes": nodes, "edges": edges}, indent=1) + "\n")


def _graph_colors(v):
    """Adds the nodes and the Nexus to the graph's color groups once (keeps everything else you changed)."""
    p = v.path(".obsidian/graph.json")
    try:
        g = json.loads(p.read_text())
    except Exception:
        return
    groups = g.get("colorGroups", [])
    if any("Nexus" in str(x.get("query")) for x in groups):
        return
    groups = [{"query": f"file:\"{NEXUS}\"", "color": {"a": 1, "rgb": 0xFFFFFF}},
              {"query": f"path:\"{NODES_DIR}\"", "color": {"a": 1, "rgb": 0x2BD9A8}}] + groups
    g["colorGroups"] = groups
    v._write(".obsidian/graph.json", json.dumps(g, indent=2) + "\n")


def add(v, node: str, content: str, author: str = "AI") -> dict:
    """Adds knowledge to a node (its user part, under the Knowledge it already has). [node] is an id or a name."""
    key = (node or "").strip().lower().lstrip("◆ ").strip()
    n = BY_ID.get(key) or next((x for x in NODES if x["name"].lower() == key or key in x["name"].lower()), None)
    sn = None if n else (SUB_BY_ID.get(key) or next((x for x in SUBNODES if x["name"].lower() == key or key in x["name"].lower()), None))
    if sn:
        if not v.path(subrel(sn)).exists():
            render(v)
        r = v.write_note(subrel(sn), content, mode="append", author=author)
        if r.get("ok"):
            r["node"] = sn["name"]
        return r
    if not n:
        return {"ok": False, "error": "Unknown node. Nodes: " + ", ".join(f"{x['id']} ({x['name']})" for x in NODES) +
                ". Subnodes: " + ", ".join(f"{x['id']} ({x['name']})" for x in SUBNODES)}
    if not v.path(rel(n)).exists():
        render(v)
    r = v.write_note(rel(n), content, mode="append", author=author)
    if r.get("ok"):
        r["node"] = n["name"]
    return r


def overview(v) -> list[dict]:
    out = []
    for n in NODES:
        p = v.path(rel(n))
        props = _parse_props(v.split_frontmatter(p.read_text(encoding="utf-8", errors="replace"))[0]) if p.exists() else {}
        out.append({"id": n["id"], "name": n["name"], "note": rel(n), "mission": n["mission"], "synapses": n["synapses"],
                    "members": int(props.get("members", 0) or 0), "signals": int(props.get("signals", 0) or 0),
                    "subnodes": [{"id": s["id"], "name": s["name"], "note": subrel(s)} for s in SUBNODES if s["parent"] == n["id"]]})
    return out
