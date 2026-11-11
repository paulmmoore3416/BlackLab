"""Color everywhere (companion 1.7): every node — and everything that belongs to one — wears its node's color.

  * Graph        — one color group per main node, a shade of its parent for every subnode, and colors for the folders
                   that have no node (Atlas, imports, entities, Drive, phone, library, system). Re-applied on every pass,
                   because Obsidian on the phone writes graph.json back with its own (empty) groups. Groups you add
                   yourself are kept after ours.
  * Notes        — every note BlackLab manages gets `cssclasses: [bl-node-<id>]` for its node, so its title, headings,
                   checkboxes, quotes, tables and links take the node's color (your own cssclasses are kept).
  * Explorer     — folders and node notes in the file explorer carry their node's color (a dot and tinted name).
  * Tags         — tag pills colored by the node they belong to (#facility/…, #learning/…, #ai/…).
  * Callouts     — `> [!facility]`, `> [!safety]`, `> [!ai]` … one callout type per node in its color.
  * Extended Graph — folder hulls (top-level folders, in node colors) and tag arcs.

Everything is generated from the node registry, so a node created later gets its color everywhere on the next pass.
The CSS lives in `.obsidian/snippets/blacklab-colors.css` (synced to the phone with the rest of .obsidian).
"""
from __future__ import annotations

import colorsys
import json
import re
from pathlib import Path

import vault_nodes as VN

SNIPPET = "blacklab-colors"
EXTRA_FOLDERS = [  # folders without a node of their own
    ("85 Atlas", "#D4E157"), ("86 Imported", "#B39DDB"), ("87 Entities", "#FFF59D"), ("88 Drive", "#4285F4"),
    ("89 Mobile", "#5C6BC0"), ("90 System", "#546E7A"), ("95 Library", "#A1887F"), ("99 Archive", "#616161"), ("Attachments", "#9E9E9E"),
    ("50 Notes", "#B0BEC5"), ("60 Meetings", "#4F8CFF"),
]
TAG_NODES = {"facility": "facility", "safety": "safety", "milieu": "milieu", "leadership": "leadership", "meeting": "leadership",
             "vendor": "vendors", "vendors": "vendors", "finance": "finance", "budget": "finance", "project": "projects", "projects": "projects",
             "ai": "ai", "agent": "ai", "learning": "learning", "course": "learning", "cert": "learning", "knowledge": "knowledge", "library": "knowledge",
             "vehicle": "vehicles", "garage": "vehicles", "home": "home", "journal": "home", "daily": "time", "log": "time", "work": "time",
             "review": "time", "it": "systems", "system": "systems", "network": "systems", "media": "media", "mining": "mining",
             "people": "people", "person": "people", "mobile": "mobile", "phone": "mobile"}


def _hex(rgb: tuple[float, float, float]) -> str:
    return "#" + "".join(f"{max(0, min(255, round(c * 255))):02X}" for c in rgb)


def _parse(hex_color: str) -> tuple[float, float, float]:
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def shade(hex_color: str, i: int, n: int) -> str:
    """The i-th of n distinct shades of a color: hue ±18°, lightness spread, so siblings are told apart but stay family."""
    r, g, b = _parse(hex_color)
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    t = (i / max(1, n - 1)) - 0.5 if n > 1 else 0.0  # -0.5 … 0.5
    h = (h + t * 0.10) % 1.0
    l = min(0.82, max(0.38, l + t * 0.30))
    s = min(1.0, max(0.45, s))
    return _hex(colorsys.hls_to_rgb(h, l, s))


def rgb_triplet(hex_color: str) -> str:
    r, g, b = (round(c * 255) for c in _parse(hex_color))
    return f"{r}, {g}, {b}"


def nodes() -> list[dict]:
    return list(VN.NODES)


def subnode_colors() -> dict[str, str]:
    by_parent: dict[str, list] = {}
    for s in VN.SUBNODES if hasattr(VN, "SUBNODES") else _subnodes():
        by_parent.setdefault(s["parent"], []).append(s)
    out = {}
    colors = {n["id"]: n["color"] for n in nodes()}
    for parent, subs in by_parent.items():
        for i, s in enumerate(sorted(subs, key=lambda s: s["id"])):
            out[s["id"]] = shade(colors.get(parent, "#9E9E9E"), i, len(subs))
    return out


def _subnodes() -> list[dict]:
    try:
        import vault_subnodes
        return vault_subnodes.SUBNODES
    except Exception:
        return []


def _rgb_int(hex_color: str) -> int:
    return int(hex_color.lstrip("#"), 16)


def graph_groups() -> list[dict]:
    """Obsidian colors a note by the first group it matches: most specific first."""
    groups = [{"query": f'file:"{VN.NEXUS}"', "color": {"a": 1, "rgb": 0xFFFFFF}}]
    sc = subnode_colors()
    for s in _subnodes():
        if s["id"] in sc:
            groups.append({"query": f'path:"{VN.subrel(s)}"', "color": {"a": 1, "rgb": _rgb_int(sc[s["id"]])}})
    try:  # each skill library a shade of the AI node's color
        import agent_skills
        ai = next((n["color"] for n in nodes() if n["id"] == "ai"), "#2BD9A8")
        libs = [lab for _r, _d, lab, _e in agent_skills.SOURCES]
        groups += [{"query": f'path:"{agent_skills.VAULT_DIR}/{lab}"', "color": {"a": 1, "rgb": _rgb_int(shade(ai, i, len(libs)))}} for i, lab in enumerate(libs)]
    except Exception:
        pass
    groups += [{"query": 'path:"87 Entities/People"', "color": {"a": 1, "rgb": 0xFFE082}},
               {"query": 'file:"🖥️ This Computer" OR [type:folder]', "color": {"a": 1, "rgb": 0x78909C}}]
    for n in nodes():
        parts = [f'file:"{VN.title(n)}"', f'[up:"{VN.title(n)}"]'] + [f'path:"{f}"' for f in n["folders"]]
        groups.append({"query": " OR ".join(parts), "color": {"a": 1, "rgb": _rgb_int(n["color"])}})
    groups += [{"query": f'path:"{f}"', "color": {"a": 1, "rgb": _rgb_int(c)}} for f, c in EXTRA_FOLDERS]
    return groups


def merge_groups(current: list, ours: list) -> list:
    """Ours first (in order), then any group the user added that isn't one of ours."""
    mine = {g["query"] for g in ours}
    return ours + [g for g in current or [] if isinstance(g, dict) and g.get("query") not in mine]


def classes_for(rel: str, props: dict) -> list[str]:
    """bl-node-<id> for the node(s) a note belongs to."""
    for n in nodes():
        if rel == VN.rel(n):
            return [f"bl-node-{n['id']}"]
    for s in _subnodes():
        if rel == VN.subrel(s):
            return [f"bl-node-{s['parent']}", f"bl-sub-{s['id']}"]
    try:
        prim = VN.primary(rel, props)
    except Exception:
        prim = []
    return [f"bl-node-{n['id']}" for n in prim[:2]]


def merge_classes(existing_value: str, ours: list[str]) -> list[str]:
    """Keep the user's cssclasses, drop our old ones, add ours."""
    have = [x.strip().strip("\"'") for x in re.sub(r"^\[|\]$", "", (existing_value or "").strip()).split(",") if x.strip().strip("\"'")]
    keep = [c for c in have if not c.startswith(("bl-node-", "bl-sub-"))]
    return keep + [c for c in ours if c not in keep]


def css() -> str:
    ns = nodes()
    sc = subnode_colors()
    out = ["/* BlackLab colors — generated from the node registry on every vault pass. Edit nodes, not this file. */", ":root {"]
    out += [f"  --bl-{n['id']}: {n['color']}; --bl-{n['id']}-rgb: {rgb_triplet(n['color'])};" for n in ns]
    out += [f"  --bl-sub-{k}: {c};" for k, c in sc.items()]
    out.append("}")
    # notes that belong to a node
    for n in ns:
        c, rgb = f"var(--bl-{n['id']})", f"var(--bl-{n['id']}-rgb)"
        k = f".bl-node-{n['id']}"
        out += [f"{k} {{ --bl-c: {c}; --bl-rgb: {rgb}; --checkbox-color: {c}; --checkbox-border-color: {c}; --blockquote-border-color: {c};"
                f" --hr-color: rgba({rgb}, .35); --link-color: {c}; --link-color-hover: {c}; --callout-color: {rgb}; }}",
                f"{k} .inline-title, {k} .markdown-rendered h1, {k} .cm-header-1 {{ color: {c}; }}",
                f"{k} .markdown-rendered h1 {{ border-bottom-color: rgba({rgb}, .45); }}",
                f"{k} .markdown-rendered h2, {k} .cm-header-2 {{ color: color-mix(in srgb, {c} 75%, var(--text-normal)); }}",
                f"{k} .markdown-rendered table th {{ border-bottom: 2px solid rgba({rgb}, .5); }}",
                f"{k} .view-header-title {{ color: {c}; }}"]
    for k, col in sc.items():
        out.append(f".bl-sub-{k} {{ --bl-c: {col}; --checkbox-color: {col}; }} .bl-sub-{k} .inline-title {{ color: {col}; }}")
    # file explorer: folders, node notes
    def folder_rule(path: str, col: str) -> list[str]:
        p = path.replace('"', '\\"')
        return [f'.nav-folder-title[data-path="{p}"], .nav-folder-title[data-path^="{p}/"], .nav-file-title[data-path^="{p}/"] {{ color: {col}; }}',
                f'.nav-folder-title[data-path="{p}"] .nav-folder-title-content::before {{ content: "●"; color: {col}; margin-right: .4em; font-size: .8em; }}']
    for n in ns:
        for f in n["folders"]:
            out += folder_rule(f, f"var(--bl-{n['id']})")
        out.append(f'.nav-file-title[data-path="{VN.rel(n)}"] {{ color: var(--bl-{n["id"]}); font-weight: 600; }}')
    for s in _subnodes():
        if s["id"] in sc:
            out.append(f'.nav-file-title[data-path="{VN.subrel(s)}"] {{ color: {sc[s["id"]]}; }}')
    for f, col in EXTRA_FOLDERS:
        out += folder_rule(f, col)
    # tags
    for tag, nid in TAG_NODES.items():
        if any(n["id"] == nid for n in ns):
            c, rgb = f"var(--bl-{nid})", f"var(--bl-{nid}-rgb)"
            out.append(f'a.tag[href="#{tag}"], a.tag[href^="#{tag}/"], .cm-hashtag.cm-tag-{tag} {{ color: {c}; background-color: rgba({rgb}, .14); border-color: rgba({rgb}, .4); }}')
    out.append('a.tag[href="#untrusted"] { color: #FF5252; background-color: rgba(255, 82, 82, .14); }')
    # callouts: > [!facility] …
    for n in ns:
        out.append(f'.callout[data-callout="{n["id"]}"] {{ --callout-color: var(--bl-{n["id"]}-rgb); --callout-icon: lucide-hexagon; }}')
    # the graph's own controls echo the accent; the Nexus canvas already uses node colors
    out.append(".graph-view.color-fill { color: var(--bl-ai, #2BD9A8); }")
    return "\n".join(out) + "\n"


def extended_graph_settings() -> dict:
    """Folder hulls for top-level folders (node colors) and tag arcs."""
    folder_colors = [{"type": f, "color": n["color"]} for n in nodes() for f in n["folders"] if "/" not in f]
    folder_colors += [{"type": f, "color": c} for f, c in EXTRA_FOLDERS]
    tag_colors = []
    for tag, nid in TAG_NODES.items():
        col = next((n["color"] for n in nodes() if n["id"] == nid), None)
        if col:
            tag_colors.append({"type": tag, "color": col})
    feat = {"folders": True, "tags": True}
    return {"enableFeatures": {"graph": feat, "localgraph": dict(feat)},
            "interactiveSettings": {
                "folder": {"colormap": "rainbow", "colors": folder_colors, "unselected": [], "excludeRegex": {"regex": "/", "flags": ""},
                           "noneType": "none", "showOnGraph": True, "enableByDefault": True},
                "tag": {"colormap": "hsv", "colors": tag_colors, "unselected": [], "excludeRegex": {"regex": "", "flags": ""},
                        "noneType": "none", "showOnGraph": True, "enableByDefault": True}},
            "folderShowFullPath": False}


def apply(v) -> dict:
    """Every pass: CSS snippet (written when it changes) and enabled, graph color groups restored, Extended Graph colors."""
    import vault_graph
    report = {}
    v._write(f".obsidian/snippets/{SNIPPET}.css", css())
    ap = v.path(".obsidian/appearance.json")
    try:
        app = json.loads(ap.read_text()) if ap.exists() else {}
    except ValueError:
        app = {}
    snips = app.get("enabledCssSnippets") or []
    if SNIPPET not in snips:
        app["enabledCssSnippets"] = snips + [SNIPPET]
        v._write(".obsidian/appearance.json", json.dumps(app, indent=2) + "\n")
    gp = v.path(".obsidian/graph.json")
    try:
        g = json.loads(gp.read_text()) if gp.exists() else {}
    except ValueError:
        g = {}
    ours = graph_groups()
    merged = merge_groups(g.get("colorGroups"), ours)
    if g.get("colorGroups") != merged:
        g["colorGroups"] = merged
        v._write(".obsidian/graph.json", json.dumps(g, indent=2) + "\n")
        report["graph_restored"] = True
    eg = v.path(".obsidian/plugins/extended-graph/data.json")
    if eg.exists():
        try:
            cur = json.loads(eg.read_text())
        except ValueError:
            cur = {}
        new = vault_graph.deep_merge(cur, extended_graph_settings())
        if new != cur:
            eg.write_text(json.dumps(new, indent=2) + "\n")
            report["extended_graph"] = True
    report["groups"] = len(ours)
    report["graph_fx"] = install_graph_fx(v)
    return report


FX_ID = "blacklab-graph-fx"
FX_SRC = Path(__file__).resolve().parent.parent / "obsidian" / FX_ID


def install_graph_fx(v) -> str:
    """BlackLab's own graph animations (desktop/obsidian/blacklab-graph-fx): copied when they change, enabled once."""
    if not FX_SRC.is_dir():
        return "missing"
    d = v.path(f".obsidian/plugins/{FX_ID}")
    d.mkdir(parents=True, exist_ok=True)
    changed = False
    for name in ("manifest.json", "main.js", "styles.css"):
        src = (FX_SRC / name).read_bytes()
        dst = d / name
        if not dst.exists() or dst.read_bytes() != src:
            dst.write_bytes(src)
            changed = True
    cp = v.path(".obsidian/community-plugins.json")
    try:
        enabled = json.loads(cp.read_text()) if cp.exists() else []
    except ValueError:
        enabled = []
    st = v._state("graph_fx.json", {})
    if FX_ID not in enabled and not st.get("enabled_once"):  # enable once; if Paul turns it off, it stays off
        v._write(".obsidian/community-plugins.json", json.dumps(enabled + [FX_ID], indent=2) + "\n")
        v._save_state("graph_fx.json", {"enabled_once": True})
    return "updated" if changed else "current"
