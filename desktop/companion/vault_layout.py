"""The vault's static layer: Obsidian settings, templates, dashboards (Bases), the system map (Canvas), the README
and the AI Protocol. `files()` are created once and then belong to you; `always()` are BlackLab-owned and refreshed."""
from __future__ import annotations

import json

from vault import FOLDERS, ACCENT


def _rgb(hex_color: str) -> int:
    return int(hex_color.lstrip("#"), 16)


GRAPH_GROUPS = [
    ("10 Facility", "#FF7A59"), ("20 Garage", "#64B5F6"), ("30 Work", "#FFD54F"), ("40 Learning", "#AB7CFF"),
    ("50 Notes", "#81C784"), ("60 Meetings", "#26C6DA"), ("70 AI", "#2BD9A8"), ("02 Projects", "#F06292"),
    ("01 Daily", "#90A4AE"), ("90 System", "#546E7A"),
]


def _obsidian() -> dict[str, str]:
    j = lambda o: json.dumps(o, indent=2) + "\n"  # noqa: E731
    core = {k: True for k in ("file-explorer", "global-search", "switcher", "graph", "backlink", "outgoing-link", "tag-pane", "properties",
                              "page-preview", "daily-notes", "templates", "note-composer", "command-palette", "editor-status", "bookmarks",
                              "outline", "word-count", "file-recovery", "canvas", "bases", "slash-command", "random-note", "workspaces")}
    # every core plugin on, except the two paid Obsidian services that would send the vault off this machine
    core.update({"audio-recorder": True, "markdown-importer": True, "zk-prefixer": True, "slides": True, "footnotes": True, "webviewer": True,
                 "sync": False, "publish": False})
    return {
        ".obsidian/app.json": j({"newFileLocation": "folder", "newFileFolderPath": FOLDERS["inbox"], "attachmentFolderPath": FOLDERS["attachments"],
                                 "useMarkdownLinks": False, "alwaysUpdateLinks": True, "showLineNumber": True, "readableLineLength": True,
                                 "propertiesInDocument": "visible", "showInlineTitle": True, "foldHeading": True, "foldIndent": True,
                                 "userIgnoreFilters": [f"{FOLDERS['archive']}/"], "promptDelete": True, "trashOption": "local"}),
        ".obsidian/appearance.json": j({"theme": "obsidian", "accentColor": ACCENT, "baseFontSize": 16, "enabledCssSnippets": ["blacklab"],
                                        "showViewHeader": True, "nativeMenus": False}),
        ".obsidian/core-plugins.json": j(core),
        ".obsidian/daily-notes.json": j({"folder": FOLDERS["daily"], "format": "YYYY/YYYY-MM-DD", "template": f"{FOLDERS['system']}/Templates/Daily", "autorun": True}),
        ".obsidian/templates.json": j({"folder": f"{FOLDERS['system']}/Templates", "dateFormat": "YYYY-MM-DD", "timeFormat": "HH:mm"}),
        ".obsidian/graph.json": j({"collapse-filter": False, "search": f"-path:\"{FOLDERS['archive']}\"", "showTags": True, "showAttachments": False,
                                   "hideUnresolved": False, "showOrphans": True, "collapse-color-groups": False,
                                   "colorGroups": [{"query": f"path:\"{p}\"", "color": {"a": 1, "rgb": _rgb(c)}} for p, c in GRAPH_GROUPS],
                                   "showArrow": True, "textFadeMultiplier": -1, "nodeSizeMultiplier": 1.1, "lineSizeMultiplier": 1,
                                   "centerStrength": 0.5, "repelStrength": 12, "linkStrength": 1, "linkDistance": 180, "scale": 0.8}),
        ".obsidian/bookmarks.json": j({"items": [
            {"type": "file", "path": "Home.md", "title": "🏠 Home"},
            {"type": "file", "path": "AI Registry.md", "title": "🖋️ AI Registry"},
            {"type": "file", "path": f"{FOLDERS['system']}/BlackLab Map.canvas", "title": "🗺️ System map"},
            {"type": "file", "path": f"{FOLDERS['work']}/Tasks.md", "title": "✅ Tasks"},
            {"type": "file", "path": f"{FOLDERS['system']}/Bases/Facility.base", "title": "🏢 Facility"},
            {"type": "folder", "path": FOLDERS["inbox"], "title": "📥 Inbox"},
        ]}),
        ".obsidian/snippets/blacklab.css": CSS,
    }


CSS = f"""/* BlackLab — a calm, dense, professional look. Settings › Appearance › CSS snippets › blacklab */
body {{
  --bl-accent: {ACCENT};
  --font-text-theme: "Inter", "IBM Plex Sans", system-ui, sans-serif;
  --font-monospace-theme: "JetBrains Mono", "IBM Plex Mono", ui-monospace, monospace;
  --h1-weight: 700; --h2-weight: 650; --h3-weight: 600;
  --callout-radius: 10px;
  --table-header-weight: 600;
}}
.theme-dark {{ --background-primary: #0E1116; --background-secondary: #12161D; --background-modifier-border: #222A35; }}
.theme-light {{ --background-primary: #FBFCFD; --background-secondary: #F2F4F7; }}
.markdown-rendered h1 {{ letter-spacing: -0.02em; border-bottom: 1px solid var(--background-modifier-border); padding-bottom: .3em; }}
.markdown-rendered h2 {{ letter-spacing: -0.01em; margin-top: 1.6em; }}
.markdown-rendered table {{ font-size: .92em; font-variant-numeric: tabular-nums; }}
.markdown-rendered th {{ text-transform: uppercase; font-size: .78em; letter-spacing: .05em; color: var(--text-muted); }}
.markdown-rendered code {{ border-radius: 5px; }}
.blacklab-home .inline-title {{ color: var(--bl-accent); font-size: 2.4em; }}
.blacklab-home .callout[data-callout="abstract"] {{ --callout-color: 43, 217, 168; }}
.blacklab-registry .callout[data-callout="quote"] {{ --callout-color: 171, 124, 255; font-family: var(--font-text-theme); }}
/* Managed blocks (BlackLab writes between the %% blacklab %% markers): a hairline so you can see where your part starts */
.markdown-source-view .cm-comment {{ opacity: .45; }}
.nav-folder-title[data-path^="99 Archive"] {{ opacity: .5; }}
"""


def _templates() -> dict[str, str]:
    t = f"{FOLDERS['system']}/Templates"
    return {
        f"{t}/Daily.md": "---\ntype: daily\ndate: {{date}}\ntags: [daily]\n---\n# {{date:dddd, MMMM D, YYYY}}\n\n## Focus\n\n- \n\n## Notes\n\n",
        f"{t}/Project.md": "---\ntype: project\nstatus: planning\nowner: \nstart: {{date}}\ndue: \ntags: [project]\n---\n# {{title}}\n\n> [!summary] Outcome\n> What does done look like?\n\n## Why\n\n## Milestones\n\n- [ ] \n\n## Decisions\n\n- \n\n## Risks\n\n## Log\n\n- {{date}} — started\n",
        f"{t}/Meeting.md": "---\ntype: meeting\ndate: {{date}}\nattendees: []\ntags: [meeting]\n---\n# {{title}}\n\n[[{{date}}]]\n\n## Agenda\n\n## Notes\n\n## Decisions\n\n## Action items\n\n- [ ] \n",
        f"{t}/Decision Record.md": "---\ntype: decision\nstatus: proposed\ndate: {{date}}\ndeciders: []\ntags: [decision]\n---\n# ADR: {{title}}\n\n## Context\n\n## Options\n\n1. \n2. \n\n## Decision\n\n## Consequences\n\n## Revisit\n\n",
        f"{t}/Asset.md": "---\ntype: asset\nasset_kind: \ntag: \nlocation: \nstatus: ok\ntags: [facility]\n---\n# {{title}}\n\n- **Make / model:** \n- **Serial:** \n- **Installed:** \n\n## Inspections & PM\n\n## History\n\n",
        f"{t}/Inspection.md": "---\ntype: inspection\ndate: {{date}}\nasset: \nresult: \nstandard: \ntags: [facility/inspection]\n---\n# Inspection · {{title}}\n\n[[{{date}}]]\n\n## Checklist\n\n- [ ] \n\n## Findings\n\n## Follow-up work orders\n\n",
        f"{t}/Person.md": "---\ntype: person\norg: \nrole: \nemail: \ntags: [people]\n---\n# {{title}}\n\n## Context\n\n## Conversations\n\n",
        f"{t}/AI Session.md": "---\ntype: ai-session\nagent: \nmodel: \ndate: {{date}}\ntags: [ai/session]\n---\n# {{title}}\n\n> Signed in at [[AI Registry]]? ✅\n\n## Goal\n\n## What changed\n\n## Files\n\n## Next steps\n\n",
        f"{t}/Weekly Review.md": "---\ntype: review\nweek: {{date:GGGG-[W]WW}}\ntags: [review]\n---\n# Week {{date:GGGG-[W]WW}}\n\n## Wins\n\n## Facility\n\n## Learning\n\n## Next week\n\n",
    }


def _bases() -> dict[str, str]:
    b = f"{FOLDERS['system']}/Bases"
    return {
        f"{b}/Open work.base": f"""filters:
  or:
    - and:
        - type == "work-order"
        - status == "open"
    - and:
        - type == "asset"
        - status != "ok"
views:
  - type: table
    name: Open work & attention
    order:
      - file.name
      - type
      - status
      - due
      - location
""",
        f"{b}/Facility.base": """filters:
  and:
    - type == "asset"
views:
  - type: table
    name: Assets
    order:
      - file.name
      - asset_kind
      - tag
      - location
      - status
      - inspections
  - type: cards
    name: Cards
    order:
      - file.name
      - status
      - location
""",
        f"{b}/Projects.base": """filters:
  and:
    - type == "project"
views:
  - type: table
    name: Projects
    order:
      - file.name
      - status
      - release
      - branch
      - repo
""",
        f"{b}/AI sessions.base": """filters:
  or:
    - type == "ai-session"
    - type == "task-log"
    - type == "conversation"
views:
  - type: table
    name: AI activity
    order:
      - file.name
      - type
      - agent
      - date
""",
        f"{b}/Atlas.base": """filters:
  and:
    - file.inFolder("85 Atlas")
views:
  - type: table
    name: Everything on this computer
    order:
      - file.name
      - type
      - format
      - size
      - modified
      - nodes
  - type: table
    name: Documents
    filters:
      and:
        - type == "document"
    order:
      - file.name
      - format
      - pages
      - modified
      - nodes
  - type: table
    name: Projects
    filters:
      and:
        - type == "project"
    order:
      - file.name
      - branch
      - release
      - repo
  - type: table
    name: Media
    filters:
      or:
        - type == "media"
        - type == "media-collection"
    order:
      - file.name
      - media
      - year
      - resolution
      - size
  - type: table
    name: Folders
    filters:
      and:
        - type == "folder"
    order:
      - file.name
      - cards
      - files
      - source
""",
        f"{b}/Entities.base": """filters:
  and:
    - type == "entity"
views:
  - type: table
    name: All people & topics
    order:
      - file.name
      - entity
      - mentions
      - nodes
  - type: table
    name: People
    filters:
      and:
        - entity == "person"
    order:
      - file.name
      - mentions
      - nodes
  - type: table
    name: Organizations
    filters:
      and:
        - entity == "organization"
    order:
      - file.name
      - mentions
  - type: table
    name: Equipment
    filters:
      and:
        - entity == "equipment"
    order:
      - file.name
      - mentions
  - type: table
    name: Credentials
    filters:
      and:
        - entity == "credential"
    order:
      - file.name
      - mentions
  - type: table
    name: Topics
    filters:
      and:
        - entity == "topic"
    order:
      - file.name
      - mentions
      - nodes
  - type: table
    name: Places
    filters:
      and:
        - entity == "place"
    order:
      - file.name
      - mentions
""",
        f"{b}/Guides.base": """filters:
  and:
    - type == "guide"
views:
  - type: table
    name: All guides
    order:
      - file.name
      - guide_kind
      - format
      - pages
      - nodes
      - source
  - type: table
    name: User guides
    filters:
      and:
        - guide_kind == "user"
    order:
      - file.name
      - source
  - type: table
    name: Technical guides
    filters:
      and:
        - guide_kind == "technical"
    order:
      - file.name
      - source
  - type: table
    name: How-to guides
    filters:
      and:
        - guide_kind == "how-to"
    order:
      - file.name
      - source
  - type: table
    name: Training & study
    filters:
      and:
        - guide_kind == "training"
    order:
      - file.name
      - pages
      - source
""",
        f"{b}/Learning.base": """filters:
  or:
    - type == "course"
    - type == "certification"
views:
  - type: table
    name: Courses & certifications
    order:
      - file.name
      - type
      - status
      - expires
""",
    }


def _canvas() -> str:
    def text(id_, x, y, w, h, t, color=None):
        n = {"id": id_, "type": "text", "x": x, "y": y, "width": w, "height": h, "text": t}
        if color:
            n["color"] = color
        return n

    def file(id_, x, y, w, h, path):
        return {"id": id_, "type": "file", "x": x, "y": y, "width": w, "height": h, "file": path}

    def group(id_, x, y, w, h, label, color):
        return {"id": id_, "type": "group", "x": x, "y": y, "width": w, "height": h, "label": label, "color": color}

    def edge(id_, a, b, label, fs="right", ts="left"):
        return {"id": id_, "fromNode": a, "fromSide": fs, "toNode": b, "toSide": ts, "label": label}

    nodes = [
        group("g-phone", -900, -420, 520, 840, "📱 BlackLab (phone)", "4"),
        text("phone-app", -860, -360, 440, 220, "## BlackLab app\nFacility · Garage · Tasks · Calendar · Notes · Notebooks · Learning · Recorder\n\n**Settings › Obsidian vault** — what's mirrored, sync now, open"),
        text("phone-asst", -860, -100, 440, 200, "## Assistant\n“save this to Obsidian: …”\n“log to the vault …”\n“what's new in the vault?”"),
        text("phone-private", -860, 140, 440, 220, "### Stays on the phone\nBehavioral Health · keys · VIN & plate · locked notebook sections · journal (unless you turn it on)", "1"),
        group("g-desk", -260, -420, 560, 840, "🖥️ This computer", "5"),
        text("companion", -220, -360, 480, 240, "## BlackLab Desktop companion\nReceives the phone's feed (`/phone/vault`), mirrors meetings, briefs, task logs, conversations, git projects and new files; commits the vault."),
        text("desktop-ai", -220, -80, 480, 200, "## Desktop AI (Ollama)\nTools: `vault_search`, `vault_write`\nEvery conversation lands on the day's timeline."),
        text("mcp", -220, 160, 480, 220, "## Vault MCP server\n`vault_mcp.py` — `vault_checkin` · `vault_search` · `vault_read` · `vault_write` · `vault_log` · `vault_status`\nUsed by Claude Code and any MCP-capable agent."),
        group("g-vault", 420, -420, 620, 840, "💎 Obsidian vault", "6"),
        file("home", 460, -360, 260, 160, "Home.md"),
        file("registry", 740, -360, 260, 160, "AI Registry.md"),
        file("protocol", 740, -180, 260, 160, "AI Protocol.md"),
        file("readme", 460, -180, 260, 160, "README.md"),
        text("areas", 460, 20, 540, 200, "### Areas\n00 Inbox · 01 Daily · 02 Projects · 10 Facility · 20 Garage · 30 Work · 40 Learning · 50 Notes · 60 Meetings · 70 AI · 80 Knowledge · 90 System · 99 Archive"),
        text("git", 460, 250, 540, 140, "### Versioned\nA git repository: every sync is a commit; add a private remote to back it up off-site and collaborate.", "3"),
    ]
    edges = [
        edge("e1", "phone-app", "companion", "feed every 15 min"), edge("e2", "phone-asst", "companion", "capture / log"),
        edge("e3", "companion", "home", "notes + timeline"), edge("e4", "desktop-ai", "home", "conversations"),
        edge("e5", "mcp", "registry", "sign in"), edge("e6", "mcp", "areas", "read / write"),
    ]
    return json.dumps({"nodes": nodes, "edges": edges}, indent=1) + "\n"


README = f"""---
type: guide
tags: [system/guide]
---
# How this vault works

This is **BlackLab's second brain**: everything you do in BlackLab, on this computer, and with your AIs lands here as
linked notes, so it keeps growing, doubles as a versioned backup, and is a place to work with other people and agents.

## Where things are

| Folder | What | Written by |
|---|---|---|
| `00 Inbox` | Quick captures ("save this to Obsidian: …"), new notes | you, the assistant, AIs |
| `01 Daily` | One note per day: agenda + a **timeline** of everything that happened | BlackLab |
| `02 Projects` | Your git projects: releases, commits, README | this computer |
| `10 Facility` | Assets (one note each), inspections & PM, monthly logs | the phone |
| `20 Garage` | Your vehicle: what's due, service, fuel, recalls | the phone |
| `30 Work` | Tasks, calendar, work orders (linked to assets) | the phone |
| `40 Learning` | Courses, assignments, certifications | the phone |
| `50 Notes` | BlackLab Notes, Notebook pages, (optionally) Journal | the phone |
| `60 Meetings` | Recordings + transcripts, minutes, briefs | phone + desktop AI |
| `70 AI` | Desktop AI conversations, task logs, agent sessions | desktop AI, agents |
| `80 Knowledge` | Your own reference notes, playbooks, how-tos | you |
| `90 System` | Templates, dashboards (Bases), the [[BlackLab Map.canvas|system map]], file activity | BlackLab |
| `99 Archive` | Notes whose source was removed — **nothing is ever deleted** | BlackLab |

## The rules

1. **Your writing is never overwritten.** BlackLab only rewrites what sits between the `%% blacklab:begin %%` and
   `%% blacklab:end %%` markers (hidden in reading view). Write anywhere outside them — usually under `## Notes`.
2. **Edits flow one way for mirrored data.** Change a task or an asset in the app; notes here show it on the next sync.
3. **Private stays private.** Behavioral Health, keys, passwords, the VIN and plate never come here. Clinical text is refused.
4. **Everything is versioned.** The vault is a git repository (each sync is a commit). Obsidian's own *File recovery* is on too.
5. **AIs sign in.** Every agent reads [[AI Protocol]] and signs the [[AI Registry]] before it works here.

## Working with others

- **Teammates:** add a private git remote (`git remote add origin …`) and the companion pushes after every commit;
  people clone the vault and open it in Obsidian. Or share the folder with any sync tool you trust.
- **AIs:** connect them to the vault MCP server (`desktop/companion/vault_mcp.py`). Claude Code is already connected.

## Running it

- Phone: **Settings › Obsidian vault** (on/off, what's mirrored, sync now, open on the computer, status).
- Assistant: “save this to Obsidian: …”, “log to the vault: …”, “what's new in the vault?”, “search the vault for …”.
- Desktop: BlackLab Desktop › Vault. Location: `{{{{vault}}}}` (change `vault_dir` in `~/.config/blacklab-desktop/config.json`).
"""

PROTOCOL = """---
type: guide
tags: [system/guide, ai]
---
# AI Protocol

Welcome. You're an AI working in Paul's BlackLab vault. Please:

1. **Sign in first.** Call `vault_checkin` (MCP) with your name, model, why you came and a friendly note — or, without
   MCP, add a line under *Guest book* in [[AI Registry]]. Every visit, every time.
2. **Read before you write.** Start at [[Home]] and [[README]]. Search before creating a note; extend what exists.
3. **Never edit inside BlackLab's blocks** (`%% blacklab:begin %%` … `%% blacklab:end %%`). Write outside them, under
   `## Notes`, or in your own notes. New, unsorted notes go to `00 Inbox`.
4. **Log what you did.** One `vault_log` line per meaningful change (what, why, which notes) — it lands on today's timeline.
   For a working session, add a note from `90 System/Templates/AI Session` in `70 AI/Agents/<your name>/`.
5. **Link generously.** `[[Note]]` links people, assets, projects and days; use properties (`type`, `status`, `tags`).
6. **Privacy is absolute.** No patient or Behavioral Health information, no keys, passwords, tokens, VINs, plates or
   card numbers — not even redacted. The vault refuses clinical text; don't try to rephrase around it.
7. **Leave it better.** Fix a broken link, add a missing summary, propose (don't impose) structure in `00 Inbox`.
8. **Sign off kindly.** Your last log line can say what you'd do next. Paul reads these.
"""


def files(v) -> dict[str, str]:
    """Created once (yours to change afterwards)."""
    out = {}
    out.update(_obsidian())
    out.update(_templates())
    out.update(_bases())
    out[f"{FOLDERS['system']}/BlackLab Map.canvas"] = _canvas()
    out["README.md"] = README.replace("{{vault}}", str(v.root).replace(str(__import__('pathlib').Path.home()), "~"))
    out["AI Protocol.md"] = PROTOCOL
    out[f"{FOLDERS['knowledge']}/Start here.md"] = ("---\ntype: guide\ntags: [knowledge]\n---\n# Knowledge\n\nYour own reference shelf: playbooks, SOPs, "
                                                   "how-tos, vendor notes, lessons learned. BlackLab never writes here — it's all yours (and your collaborators').\n")
    out[f"{FOLDERS['inbox']}/Welcome.md"] = ("---\ntype: note\ntags: [inbox]\n---\n# Welcome to your vault\n\nCaptures land here. Sort them into an area "
                                            "when you have a minute — or ask an AI to propose where they go.\n\nStart at [[Home]].\n")
    out[".gitignore"] = ".obsidian/workspace*.json\n.obsidian/cache\n.trash/\n.DS_Store\n*.tmp\n"
    return out


def always(v) -> dict[str, str]:
    """BlackLab-owned: refreshed on every start."""
    return guides()


# ---------------------------------------------------------------- BlackLab's own guides (refreshed with the code)

def _guide(title: str, kind: str, body: str) -> str:
    return (f"---\ntype: guide\nguide_kind: {kind}\nsource: BlackLab\nup: [\"[[◆ Knowledge Library]]\", \"[[◆ AI & Automation]]\"]\n"
            f"tags: [guide, guide/{kind}, system/guide]\nmanaged_by: blacklab\n---\n"
            f"%% This guide is refreshed by BlackLab with every companion update — write your own notes in another note and link it here. %%\n"
            f"# {title}\n\n{body.strip()}\n\n---\n**All BlackLab guides:** [[BlackLab User Guide]] · [[BlackLab Technical Guide]] · [[BlackLab How-to Guides]] · "
            f"[[BlackLab Training Guide]] · every other guide on this computer: [[85 Atlas/📘 Guides|📘 Guides]]\n")


USER_GUIDE = """
> [!abstract] Your living graph
> This vault is the second brain behind BlackLab: everything the phone, the desktop AI, your AI agents and **this computer** know, linked into one graph that grows on its own. You read and write it in Obsidian (here and on the phone); BlackLab keeps it current every 10 minutes.

## Where to start
- **[[Home]]** — today so far, areas, dashboards, recent AI visitors.
- **[[◉ Nexus]]** — the brain: every node, how many notes it holds, what's active.
- **[[85 Atlas/🖥️ This Computer|🖥️ This Computer]]** — this machine: hardware, disks, services, toolchains, local AI models, and every folder, document, guide, project and media file, one card each.
- **[[85 Atlas/📘 Guides|📘 Guides]]** — every user, technical, how-to and training guide on the computer.
- **Bookmarks › 🕸️ Graph views** — saved graphs: the brain, everything, each node, *This computer*, *People & topics*, *Guides*, *Imported vaults*.

## The parts of the vault
| Folder | What's in it | Synced to GitHub? |
|---|---|---|
| `00 Inbox` | Quick captures from you, the phone and AIs | yes |
| `01 Daily` | One note per day: agenda + timeline of what happened | yes |
| `02 Projects` … `80 Knowledge` | Facility, garage, work, learning, notes, meetings, AI, your knowledge | yes |
| `Nodes/` | The nodes (◆) and subnodes (◇) — the graph's hubs | yes |
| `85 Atlas` | Cards for everything on this computer | **no — this computer only** |
| `86 Imported` | Your other Obsidian vaults, note for note, kept in sync | **no** |
| `87 Entities` | People, organizations, equipment, credentials, projects, places, topics | **no** |
| `90 System` | Bases (dashboards), templates, graph health, these guides | yes |
| `95 Library` | Offline reference library cards | yes (files no) |
| `99 Archive` | Anything whose source disappeared — nothing is ever deleted | yes |

## Nodes — no fixed number
Seventeen nodes are built in (Facility Operations, Life Safety & Compliance, Peer Support & Milieu Safety, Leadership & Team, Vendors & Contracts, Finance & Budget, Projects & Engineering, AI & Automation, Learning & Credentials, Knowledge Library, Vehicles & Travel, Home Family & Health, Time Goals & Planning, IT Networking & Systems, Media Library, Mining & Crypto, People & Relationships). You or an AI can add as many more as your life needs — see [[BlackLab How-to Guides#Add a new node]]. A note joins a node when it has `up:` pointing at the node (e.g. `up: "[[◆ Facility Operations]]"`), lives in the node's folder, has its type or tag, or mentions its keywords.

## People & topics
Every document, imported note and vault note is read for the people, organizations, equipment, credentials, projects, places and topics it's about. Each gets a note in `87 Entities`, linked to everything that mentions it, so documents that share a vendor, a standard or a person are connected even if you never linked them. Add what you know about a person *below* the managed part of their note (role, last contact, next step) — never health information.

## Talking to it
On the phone or the desktop assistant:
- *“save this to Obsidian: …”* — quick capture into the inbox.
- *“search my vault for chiller”* — full-text search (includes the Atlas and every guide).
- *“show RTU-4 in my graph”* — opens that note's local graph in Obsidian.
- *“look it up in the library: Legionella water management”* — the offline library.

## Privacy, in one paragraph
Nothing clinical, no patient or Behavioral Health information, no keys, passwords, tokens or VINs are ever written — documents that mention them get the passage left out and the card says so. Protected folders (wallets, keys, browser profiles, the Crypto Desk) are never opened. Everything about this computer stays on this computer: the Atlas, imported vaults and people notes are git-ignored, and synced notes only count them.
"""

TECH_GUIDE = """
## Architecture
The BlackLab desktop companion (`desktop/companion/`, systemd user service, port 8770) owns the vault engine:

| Module | Job |
|---|---|
| `vault.py` | The engine: managed blocks (`%% blacklab:begin/end %%`), frontmatter merge, timeline, archive-not-delete, search, git commit + push |
| `vault_nodes.py` | Nodes, subnodes, the Nexus and the Neural Map canvas; the **node registry** (`90 System/Node Registry.json`, unlimited) |
| `vault_atlas.py` | **The Atlas**: walks this computer, cards documents/guides/media/projects, folder notes, 🖥️ This Computer, 📘 Guides, imports other vaults |
| `vault_entities.py` | **People & topics**: known lists + the local model (Ollama) → `87 Entities`, links on cards |
| `vault_graph.py` | Graph colors, forces, saved views, Graph Health, related links (nomic embeddings), pinned plugins |
| `vault_mcp.py` | The MCP server every AI agent uses (`blacklab-vault`) |
| `guards.py` | Every safety rule (protected paths, clinical guard, secret scrubbing, `vault_safe_passages`) |

## Schedule
- **Vault pass** every 10 min (`vault_every_s`): phone feed, meetings, briefs, task logs, conversations, projects, file activity, library cards, nodes, graph, Home, commit + push.
- **Atlas pass** every 30 min (`atlas_every_s`): scan (size + mtime, only changed files are read) → import vaults → cards → people & topics (up to `entities_llm_per_pass` documents through the model; paused while night mining runs).
- **Phone mirror** every 5 min while the phone is linked (adb).

## Configuration (`~/.config/blacklab-desktop/config.json`)
| Key | Default | Meaning |
|---|---|---|
| `atlas` | true | Turn the Atlas on/off |
| `atlas_roots` | `["~"]` | Folders to map |
| `atlas_exclude` | `[]` | Paths to skip (prefixes, `~` allowed) |
| `atlas_exclude_names` | `[]` | Folder names to skip anywhere |
| `atlas_guide_roots` | toolchain docs | Extra folders whose documents are all guides (globs) |
| `atlas_system_guides` | true | Guide-named manuals from installed software (`/usr/share/doc`) |
| `atlas_text_chars` | 12000 | Text shown on a card |
| `vault_import` / `vault_import_skip` | registered vaults | Extra vaults to import / folders to leave out per vault |
| `entities`, `entities_llm`, `entities_model`, `entities_llm_per_pass` | true, true, the chat model, 60 | People & topics |
| `vault_max_nodes` | 500 | Safety cap for agent-created nodes |
| `atlas_path_rules` | `[]` | Your own folder → node rules, e.g. `[["Desktop/Certs", ["learning"], 25]]` |
| `phone_vault_local` | false | Also copy the Atlas, imported vaults and people & topics to the phone (its shared storage) |

## State (`~/.cache/blacklab-desktop/vault/`)
`atlas.json` (one record per file: signature, sha256, kind, nodes, guide kind) · `atlas_text/` (safe extracted text) · `entities.json` · `related.json` + `related_local.json` · `collections.json` (what each writer wrote, for archiving) · `timeline/`.

## Privacy mechanics
1. `.gitignore` holds `85 Atlas/`, `86 Imported/`, `87 Entities/`; `vault_nodes.LOCAL_PREFIXES` makes synced notes (nodes, Nexus, Graph Health) count local notes without naming them; related links never cross from synced to local notes.
2. `guards.is_protected` + `PROTECTED_NAME_RE` (keys, wallets, `client_secret*`, `.env`, keystores) — never opened. Hidden folders are never walked.
3. `guards.vault_safe_passages` drops clinical / off-the-record paragraphs and scrubs keys, tokens, passwords, card numbers, VINs.
4. Entities are verified word-for-word against the text; software manuals don't go through the model.

## MCP tools
`vault_checkin` · `vault_status` · `vault_search` · `vault_read` · `vault_write` · `vault_log` · `vault_capture` · `vault_recent` · `vault_nodes` · `vault_node_add` · **`vault_node_create`** · **`vault_atlas`** · `vault_library_search`.

## Tests
`python3 -m unittest discover -s desktop/companion/tests` (guards, vault, nodes, graph, atlas, entities, library, reverse).

## Troubleshooting
- *A file isn't showing up* — is it hidden, protected, in a skipped folder (`node_modules`, `build`, `Android`, `go`, `snap`), or a format the Atlas lists but doesn't read (code, JSON)? Folder notes list every file they don't card.
- *“N private passages left out”* — the clinical guard matched a paragraph; by design.
- *People & topics are behind* — `vault_atlas` shows `entity_llm_pending`; the model only runs outside night mining.
- *Obsidian feels slow on the full graph* — use a saved view; installed-software manuals are filtered out of the default graph.
"""

HOWTO_GUIDE = """
## Add a new node
- **Ask an AI:** “create a vault node for *Grant Writing* — mission: win funding for facility projects; related to finance and projects.” (MCP `vault_node_create`)
- **By hand:** add an entry to `90 System/Node Registry.json`:
  ```json
  {"id": "grants", "name": "Grant Writing", "emoji": "✍️", "mission": "Win funding for facility projects.",
   "synapses": ["finance", "projects"], "keywords": ["grant", "rfp", "funding", "proposal"], "tags": ["grants"]}
  ```
  The next pass creates `Nodes/◆ Grant Writing.md`, its color group, its saved graph view and its place on the Nexus and the Neural Map.

## Make a note join a node
Add to its properties: `up: "[[◆ Facility Operations]]"` (several are fine). Or give it the node's tag, or put it in the node's folder.

## Add knowledge to a node
Write under the node's **## Knowledge** heading (below the managed part) — or ask an AI: “add to the Life Safety node: …”.

## Find every guide
Open [[85 Atlas/📘 Guides|📘 Guides]] or Bases › *Guides*, or search `tag:#guide/how-to` (also `guide/user`, `guide/technical`, `guide/training`).

## Rescan the computer now
Ask an AI to call `vault_atlas` with `rescan: true`, or run `python3 desktop/companion/vault_atlas.py`.

## Leave a folder out of the Atlas
Add it to `atlas_exclude` in the companion config (e.g. `"~/Downloads/old"`) and restart the companion; its cards move to `99 Archive` on the next pass.

## Add another folder of guides
Add a glob to `atlas_guide_roots` (e.g. `"~/Manuals"`); every document under it becomes a guide card.

## Import another Obsidian vault
Open it once in Obsidian (so it's registered) or add its path to `vault_import`. It appears under `86 Imported/<name>` on the next Atlas pass and stays in sync; the original is never changed.

## Fill in a person
Open the person in `87 Entities/People`, write below the managed part: role, organization, how you know them, last contact, next step.

## See what something is connected to
Open the note → *Open local graph* (or say “show *X* in my graph”). Breadcrumbs' trail at the top shows where it sits (card → folder → … → This Computer → Nexus).

## Get something back
Nothing is deleted: look in `99 Archive/`, or use the git history (*Obsidian Git* or `git log` in the vault).
"""

TRAINING_GUIDE = """
> [!info] Five short sessions (about 30 minutes each). Do them in order; each ends with a check.

## Session 1 — Orientation
1. Open [[Home]], then [[◉ Nexus]]. Read the node table: which nodes are busiest?
2. Open Bookmarks › 🕸️ Graph views › *◉ The brain*. Drag a node; notice the synapses (orange) between nodes.
3. Open today's daily note from Home.
- **Check:** you can name three nodes and say what their mission is.

## Session 2 — Your computer in the graph
1. Open [[85 Atlas/🖥️ This Computer|🖥️ This Computer]]. Read the hardware, disks and services.
2. Follow *Home folder* → *Desktop* → a folder you use. Open a document card: summary, file link, nodes, text.
3. Open the graph view *🖥️ This computer (Atlas)*.
- **Check:** find the card of a PDF you care about and open the original from it.

## Session 3 — Guides and learning
1. Open [[85 Atlas/📘 Guides|📘 Guides]]. Pick one training guide and one how-to guide.
2. Search `tag:#guide/training`. Open [[◆ Learning & Credentials]] and see its *On this computer* count.
3. Link a guide from a course note: type two square brackets and start typing the guide's name.
- **Check:** you can find every guide for a project in under 30 seconds.

## Session 4 — People & topics
1. Open `87 Entities` › *🎓 Credentials* and *💡 Topics*. Open a topic: which documents mention it, what does it keep company with?
2. Open a person; add role and last contact below the managed part.
3. Open the graph view *👥 People & topics*.
- **Check:** pick a standard (e.g. NFPA 101) and list three documents that mention it.

## Session 5 — Growing it with AI
1. Ask your assistant: “save this to Obsidian: idea for the PM program …”.
2. Ask Claude (MCP): “add to the Facility node: AHU filter change every 90 days, MERV 13”.
3. Ask: “create a node for *<something new in your life>*”. Watch it appear on the Nexus.
- **Check:** the new node shows on the Neural Map and has its own graph view.

## Keep it healthy (monthly, 10 minutes)
[[Graph Health]] (orphans, unresolved links) · inbox to zero · skim `99 Archive` · add one primer section to a quiet node.
"""


def guides() -> dict[str, str]:
    g = f"{FOLDERS['system']}/Guides"
    return {f"{g}/BlackLab User Guide.md": _guide("📗 BlackLab User Guide", "user", USER_GUIDE),
            f"{g}/BlackLab Technical Guide.md": _guide("🛠️ BlackLab Technical Guide", "technical", TECH_GUIDE),
            f"{g}/BlackLab How-to Guides.md": _guide("🧭 BlackLab How-to Guides", "how-to", HOWTO_GUIDE),
            f"{g}/BlackLab Training Guide.md": _guide("🎓 BlackLab Training Guide", "training", TRAINING_GUIDE)}
