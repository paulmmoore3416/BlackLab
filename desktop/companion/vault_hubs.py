"""Hub pages that make Obsidian a place to work, not just to remember (companion 1.11).

    10 Facility/🏢 Command Center.md     today's shift picture: overdue and due PMs, open work orders (two-way), rounds,
                                          panels, SDS, the Bases views, and how to open a work order from any note
    40 Learning/🎓 Study Hub.md          courses, assignments due, exams, credentials and CEUs, flashcard decks
    40 Learning/Flashcards/<Deck>.md     each phone deck as `front :: back` cards; add your own below the block
    the daily note                       a "Today at a glance" block: the morning brief, tasks due, open work orders,
                                          PMs due, and today's Claude and Gemini conversations

Only managed blocks are written; everything you add to these notes is kept.
"""
from __future__ import annotations

import datetime as dt
import re

from vault import FOLDERS

COMMAND_REL = f"{FOLDERS['facility']}/🏢 Command Center.md"
STUDY_REL = f"{FOLDERS['learning']}/🎓 Study Hub.md"
DECKS_DIR = f"{FOLDERS['learning']}/Flashcards"


# ---------------------------------------------------------------- the daily note

def remember_tasks(v, tasks: list):
    """What the dashboard needs from the phone's tasks (rendered into every daily note without re-reading the feed)."""
    today = v.now().date().isoformat()
    due = [t for t in tasks if not t.get("completed") and t.get("due") and t["due"] <= today and not t.get("workorder")]
    wos = [t for t in tasks if not t.get("completed") and t.get("workorder")]
    v._save_state("dash/tasks.json", {"due": [{"title": t.get("title", ""), "due": t.get("due"), "id": t.get("id")} for t in due[:30]],
                                      "workorders": [{"title": t.get("title", ""), "due": t.get("due") or ""} for t in wos[:30]],
                                      "at": today})


def dashboard(v, d: dt.date) -> list[str]:
    """The 'Today at a glance' lines for today's daily note (nothing for other days)."""
    if d != v.now().date():
        return []
    out = ["", "## 🧭 Today at a glance", ""]
    brief = _brief(v, d)
    if brief:
        out.append(f"- 📰 Morning brief: [[{brief}]]")
    st = v._state("dash/tasks.json", {})
    due = st.get("due", [])
    if due:
        out.append(f"- ✅ **{len(due)} task{'s' if len(due) != 1 else ''} due** — " + "; ".join(
            (t["title"] + (" ⚠️" if t.get("due") and t["due"] < d.isoformat() else "")) for t in due[:6]) + (" …" if len(due) > 6 else "") + " → [[Tasks]]")
    wos = st.get("workorders", [])
    if wos:
        out.append(f"- 🛠️ **{len(wos)} open work order{'s' if len(wos) != 1 else ''}** — " + ", ".join(f"[[{v.safe_name(w['title'], 'Work order')}]]" for w in wos[:5])
                   + (" …" if len(wos) > 5 else ""))
    try:
        import facility_ops
        pms = facility_ops._due_rows(v)
    except Exception:
        pms = []
    if pms:
        over = sum(1 for p in pms if p.startswith("🔴"))
        out.append(f"- 🏢 **PMs:** {over} overdue, {len(pms) - over} due soon → [[🏢 Command Center]]")
    try:
        import vault_ai
        ai = vault_ai.today(v, d)
    except Exception:
        ai = []
    if ai:
        n_c = sum(1 for e in ai if e["provider"] == "claude")
        n_g = len(ai) - n_c
        bits = ([f"🟠 [[Claude · {d.isoformat()}|Claude ×{n_c}]]"] if n_c else []) + ([f"🔴 [[Gemini · {d.isoformat()}|Gemini ×{n_g}]]"] if n_g else [])
        out.append("- 🤖 **AI today:** " + " · ".join(bits) + " — latest: " + (ai[-1].get("topic") or "")[:80])
    if len(out) == 3:
        out.append("_A clear day so far._")
    out += ["- 💬 Ask from here: write a question on its own line with #ask/claude (or #ask/gemini, #ask/local) — the answer appears under it. "
            "Tag a line #task, #remind, #workorder or #event to send it to BlackLab."]
    return out


def _brief(v, d: dt.date) -> str:
    root = v.path(f"{FOLDERS['meetings']}/Briefs")
    if not root.is_dir():
        return ""
    hits = sorted(p.stem for p in root.glob(f"{d.isoformat()}*.md"))
    return hits[-1] if hits else ""


# ---------------------------------------------------------------- facility command center

def render_command_center(v) -> bool:
    import facility_ops
    today = v.now().date()
    due = facility_ops._due_rows(v)
    st = v._state("dash/tasks.json", {})
    wos = st.get("workorders", [])
    rounds = sorted(p.stem for p in v.path(facility_ops.ROUNDS).glob(f"{today.isoformat()} *.md")) if v.path(facility_ops.ROUNDS).is_dir() else []
    over = [x for x in due if x.startswith("🔴")]
    lines = ["# 🏢 Command Center", "", f"_{today.strftime('%A, %B')} {today.day} · refreshed every vault pass · edits here sync with BlackLab_", "",
             "> [!facility] Open a work order from anywhere", "> Type a line ending in `#workorder` in any note (\"Roof leak over B-wing #workorder\") —",
             "> it's filed on your phone within a minute. Mark a work order note `status: done` (or check it off in [[Tasks]]) to close it.", "",
             f"## 🔴 Overdue & 🟡 due soon ({len(over)} overdue, {len(due) - len(over)} soon)", ""]
    lines += [f"- {x}" for x in due[:40]] or ["_Nothing overdue or due soon._"]
    lines += ["", f"## 🛠️ Open work orders ({len(wos)})", ""]
    lines += [f"- [[{v.safe_name(w['title'], 'Work order')}]]" + (f" · due {w['due']}" if w.get("due") else "") for w in wos] or ["_None open._"]
    lines += ["", "## 🚶 Rounds today", ""]
    lines += [f"- [[{r}]]" for r in rounds] or ["_None started yet — say “start EOC rounds” to BlackLab, or copy a template from [[10 Facility/Templates|Templates]]._"]
    lines += ["", "## 📊 Views", "", "![[Open work.base]]", "", "![[Facility.base]]", "",
              "## 📚 References", "", "- [[⚡ Panel Directory]] — which breaker feeds what (verify at the panel: LOTO and test before touch)",
              "- [[🧪 SDS Binder]] — safety data sheets, reviews due, storage conflicts", "- [[Calendar]] · [[Tasks]]"]
    return v.write_managed(COMMAND_REL, {"type": "dashboard", "overdue": len(over), "due_soon": len(due) - len(over), "open_work_orders": len(wos),
                                         "tags": ["facility", "facility/dashboard"], "up": "[[◆ Facility Operations]]"}, "\n".join(lines))


# ---------------------------------------------------------------- study hub + flashcards

def render_decks(v, decks: list) -> int:
    """Each phone deck as a note: the phone's cards in the managed block; cards you add below go to the phone."""
    n = 0
    for dk in decks or []:
        name = v.safe_name(dk.get("name"), "Deck")
        cards = dk.get("cards") or []
        today = (v.now().date() - dt.date(1970, 1, 1)).days
        due = sum(1 for c in cards if (c.get("dueDay") or 0) <= today)
        lines = [f"# 🗂️ {name}", "", f"_{len(cards)} cards · {due} due today · reviewed on the phone (Learning › Flashcards)._", "",
                 "Add a card below this block as `front :: back` — it goes to this deck on your phone.", ""]
        for c in cards[:2000]:
            front = re.sub(r"\s+", " ", str(c.get("front", ""))).strip().replace("::", ":")
            back = re.sub(r"\s+", " ", str(c.get("back", ""))).strip()
            if front:
                lines.append(f"- {front} :: {back}")
        v.write_managed(f"{DECKS_DIR}/{name}.md", {"type": "deck", "deck": dk.get("name", name), "cards": len(cards), "due": due,
                                                   "tags": ["flashcards", "learning/flashcards"], "up": "[[🎓 Study Hub]]"}, "\n".join(lines),
                        user_tail="\n## My new cards\n\n")
        n += 1
    epoch_today = (v.now().date() - dt.date(1970, 1, 1)).days
    v._save_state("dash/decks.json", [{"name": v.safe_name(d.get("name"), "Deck"), "cards": len(d.get("cards") or []),
                                       "due": sum(1 for c in (d.get("cards") or []) if (c.get("dueDay") or 0) <= epoch_today)} for d in decks or []])
    return n


def render_study_hub(v, learning: dict) -> bool:
    today = v.now().date()
    courses = learning.get("courses", [])
    by_id = {c.get("id"): " ".join(x for x in (c.get("code"), c.get("title")) if x) for c in courses}
    asg = sorted([a for a in learning.get("assignments", []) if a.get("status") not in ("SUBMITTED", "GRADED") and a.get("due")], key=lambda a: a["due"])
    certs = learning.get("certs", [])
    decks = v._state("dash/decks.json", [])
    lines = ["# 🎓 Study Hub", "", f"_{today.strftime('%A, %B')} {today.day} · from BlackLab's Learning section · flashcards sync both ways_", "",
             f"## 📝 Due next ({len(asg)})", ""]
    for a in asg[:25]:
        late = " ⚠️ late" if a["due"] < today.isoformat() else ""
        lines.append(f"- [ ] {a.get('title')} ({a.get('kind', '')}) · [[{v.safe_name(by_id.get(a.get('courseId'), ''), 'Course')}]] 📅 {a['due']}{late}")
    if not asg:
        lines.append("_Nothing due._")
    lines += ["", f"## 📚 Courses ({len(courses)})", ""] + ([f"- [[{v.safe_name(by_id[c.get('id')], 'Course')}]]" + (f" · {c['instructor']}" if c.get("instructor") else "")
                                                         for c in courses] or ["_No courses yet._"])
    lines += ["", f"## 🏅 Credentials ({len(certs)})", ""] + ([f"- [[{v.safe_name(c.get('name'), 'Certification')}]] · {c.get('status', '')}"
                                                             + (f" · expires {c['expires']}" if c.get("expires") else "") + (f" · exam {c['exam']}" if c.get("exam") else "")
                                                             for c in certs] or ["_None yet._"])
    lines += ["", "- CEU log and renewals: [[🏅 Credentials]]", "", f"## 🗂️ Flashcards ({sum(d['cards'] for d in decks)} cards, {sum(d['due'] for d in decks)} due)", ""]
    lines += [f"- [[{d['name']}]] — {d['cards']} cards, {d['due']} due" for d in decks] or ["_No decks yet._"]
    lines += ["", "> [!learning] Make cards anywhere", "> In any note tagged #flashcards, write `front :: back` lines — they become cards on your phone "
              "(the deck is the note's `deck:` or its name). Ask Claude to draft them: “#ask/claude make 15 flashcards from this note as front :: back”."]
    return v.write_managed(STUDY_REL, {"type": "dashboard", "assignments_due": len(asg), "courses": len(courses), "tags": ["learning", "learning/dashboard"],
                                       "up": "[[◆ Learning & Credentials]]"}, "\n".join(lines))
