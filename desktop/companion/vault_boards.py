"""The Project Board: every project as a card, its lane is its status — both ways (companion 1.11).

    02 Projects/🗂️ Project Board.md    a Kanban board (the pinned, verified obsidian-kanban plugin), one lane per status:
                                        💡 Ideas · 📐 Planning · 🚀 Active · ⛔ Blocked · ✅ Done

  * A project note is any note with `type: project` (the Project template, the git projects the companion writes, the
    Veteran Initiative hub…). Its `status:` decides the lane.
  * Drag a card to another lane in Obsidian: on the next pass the note's `status:` follows (the board wins when you
    changed it; the note wins when you edited the note).
  * Add a card (just a title) in a lane: a new project note is created from the Project template with that status.
  * Active projects also show on BlackLab's Home screen and in the desktop app (GET /phone/projects, /local/projects).
"""
from __future__ import annotations

import datetime as dt
import json
import re

from vault import FOLDERS

BOARD_REL = f"{FOLDERS['projects']}/🗂️ Project Board.md"
LANES = [("idea", "💡 Ideas"), ("planning", "📐 Planning"), ("active", "🚀 Active"), ("blocked", "⛔ Blocked"), ("done", "✅ Done")]
ALIASES = {"ideas": "idea", "backlog": "idea", "proposed": "planning", "plan": "planning", "planned": "planning", "in progress": "active",
           "in-progress": "active", "doing": "active", "started": "active", "on hold": "blocked", "on-hold": "blocked", "paused": "blocked",
           "waiting": "blocked", "complete": "done", "completed": "done", "shipped": "done", "closed": "done", "archived": "done"}
CARD = re.compile(r"^- \[([ xX])\] (.+?)\s*$")
LINK = re.compile(r"\[\[([^\]|#]+)(?:[|#][^\]]*)?\]\]")


def norm(status: str) -> str:
    s = (status or "").strip().strip("\"'").lower()
    s = ALIASES.get(s, s)
    return s if s in dict(LANES) else "planning"


def _props(v, text: str) -> dict:
    fm, _ = v.split_frontmatter(text)
    out = {}
    for ln in fm:
        k, _, val = ln.partition(":")
        if k.strip() and not k.startswith((" ", "-")):
            out[k.strip()] = val.strip().strip("\"'")
    return out


def projects(v) -> list[dict]:
    root = v.path(FOLDERS["projects"])
    out = []
    if not root.is_dir():
        return out
    for p in sorted(root.rglob("*.md")):
        if p.name == "🗂️ Project Board.md":
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        pr = _props(v, text)
        if pr.get("type") != "project":
            continue
        out.append({"name": p.stem, "rel": p.relative_to(v.root).as_posix(), "status": norm(pr.get("status", "")), "raw_status": pr.get("status", ""),
                    "due": pr.get("due", ""), "owner": pr.get("owner", ""), "release": pr.get("release", ""), "mtime": p.stat().st_mtime})
    return out


def read_board(v) -> dict[str, str]:
    """{project name: status} from the board file as it is now (Paul may have dragged cards)."""
    p = v.path(BOARD_REL)
    if not p.exists():
        return {}
    lane_of = {label: k for k, label in LANES}
    lane, out = None, {}
    for ln in p.read_text(encoding="utf-8", errors="replace").splitlines():
        if ln.startswith("## "):
            lane = lane_of.get(ln[3:].strip())
            continue
        m = CARD.match(ln)
        if lane and m:
            link = LINK.search(m.group(2))
            name = link.group(1).strip() if link else m.group(2).strip()
            out[name] = lane
    return out


def _set_status(v, rel: str, status: str):
    p = v.path(rel)
    text = p.read_text(encoding="utf-8", errors="replace")
    fm, body = v.split_frontmatter(text)
    v._write(rel, v.frontmatter({"status": status}, fm) + body)


def sync(v, template: str | None = None) -> dict:
    """Board ⇄ notes, then the board is written fresh. Returns what changed."""
    report = {"moved": [], "created": [], "projects": 0}
    with v.lock:
        st = v._state("sync/board.json", {"board": {}, "notes": {}})
        board_now = read_board(v)
        items = {x["name"]: x for x in projects(v)}
        for name, lane in board_now.items():
            was_board = st["board"].get(name)
            if name in items:
                note_changed = st["notes"].get(name) not in (None, items[name]["status"])
                if was_board is not None and lane != was_board and not note_changed and items[name]["status"] != lane:
                    _set_status(v, items[name]["rel"], lane)
                    items[name]["status"] = lane
                    report["moved"].append(f"{name} → {lane}")
            elif was_board is None and name and not name.startswith("%%"):
                safe = v.safe_name(name, "Project")
                rel = f"{FOLDERS['projects']}/{safe}.md"
                if not v.path(rel).exists():
                    today = v.now().date().isoformat()
                    body = (template or "---\ntype: project\nstatus: planning\nstart: {{date}}\ntags: [project]\n---\n# {{title}}\n\n## Why\n\n## Milestones\n\n- [ ] \n")
                    body = body.replace("{{date}}", today).replace("{{title}}", safe)
                    v._write(rel, body)
                    _set_status(v, rel, lane)
                    items[safe] = {"name": safe, "rel": rel, "status": lane, "due": "", "owner": "", "release": "", "mtime": 0}
                    report["created"].append(safe)
        lines = ["---", "kanban-plugin: board", "type: board", "tags: [project/board]", 'up: "[[◆ Projects & Engineering]]"', "---", ""]
        for key, label in LANES:
            lines += [f"## {label}", ""]
            for x in sorted((i for i in items.values() if i["status"] == key), key=lambda i: i["name"].lower()):
                extra = " ".join(e for e in (f"@{{{x['due']}}}" if re.fullmatch(r"\d{4}-\d{2}-\d{2}", x.get("due") or "") else "",
                                             f"`{x['release']}`" if x.get("release") else "") if e)
                lines.append(f"- [{'x' if key == 'done' else ' '}] [[{x['name']}]]" + (f" {extra}" if extra else ""))
            lines.append("")
            if key == "done":
                lines += ["**Complete**", ""]
        settings = {"kanban-plugin": "board", "lane-width": 300, "show-checkboxes": False, "date-trigger": "@", "new-note-folder": FOLDERS["projects"],
                    "new-note-template": f"{FOLDERS['system']}/Templates/Project.md", "list-collapse": [False, False, False, False, True]}
        lines += ["", "%% kanban:settings", "```", json.dumps(settings), "```", "%%", ""]
        desired = {x["name"]: x["status"] for x in items.values()}
        if not (v.path(BOARD_REL).exists() and board_now == desired):  # Kanban saves the file its own way: only rewrite when cards differ
            v._write(BOARD_REL, "\n".join(lines))
        st["board"] = {x["name"]: x["status"] for x in items.values()}
        st["notes"] = dict(st["board"])
        v._save_state("sync/board.json", st)
        report["projects"] = len(items)
    return report


def summary(v) -> dict:
    """For the phone's Home card and the desktop app: active and blocked first, then the rest."""
    order = {k: i for i, (k, _l) in enumerate([LANES[2], LANES[3], LANES[1], LANES[0], LANES[4]])}
    items = sorted(projects(v), key=lambda x: (order.get(x["status"], 9), -x["mtime"]))
    counts = {k: sum(1 for x in items if x["status"] == k) for k, _ in LANES}
    return {"projects": [{"name": x["name"], "status": x["status"], "lane": dict(LANES)[x["status"]], "due": x["due"], "release": x["release"]}
                         for x in items[:40]],
            "counts": counts, "board": BOARD_REL, "updated": dt.datetime.now().isoformat(timespec="seconds")}
