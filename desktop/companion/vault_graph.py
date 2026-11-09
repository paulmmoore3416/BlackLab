"""The vault's graph, kept useful and interactive (companion 1.6). Called on every vault pass; unit tested in
tests/test_vault_graph.py.

Graph enhancements
  1. Forces       — links pull (linkStrength was 0, so the 13 nodes never clustered); tuned repel/center/distance.
  2. Colors       — one group per node (its notes by `up:` or folder) + the Nexus, the subnodes and the AI's own notes,
                    in the node's own color, most specific first.
  3. Typed links  — node↔node `synapses:` and semantic `related:` properties instead of tag clutter (tags hidden), so
                    every edge in the graph says what kind of relation it is.
  4. Views + health — a "Graph views" bookmark group (one saved graph per node, the hubs, what's new, the AI layer)
                    and `90 System/Graph Health.md`: orphans with suggested homes, unresolved links, hubs, coverage.

Integrations
  1. Breadcrumbs (michaelpporter/breadcrumbs)  — hierarchy from `up:` (implied `down:`), `same:` siblings, `synapses:`
                    and `related:` as typed fields, daily notes chained prev/next by date, trail on every note.
  2. Extended Graph (elsatam/obsidian-extended-graph) — link types drawn in their own colors, hubs sized by links,
                    the open note highlighted, focus and arrows, on the global and local graph.
  3. Related notes (BlackLab, local)  — nomic-embed-text embeddings (Ollama, offline) → `related:` links between notes
                    that are about the same thing but never linked. Your own `related:` entries are kept.
  4. Advanced URI (vinzent03/obsidian-advanced-uri) — obsidian:// links that open a note's local graph; the assistant
                    on the phone and the desktop uses them ("show RTU-4 in my graph"), and every node note has buttons.

Plugins are installed from their GitHub releases, pinned to a version and verified against SHA-256 before anything is
written; their code is git-ignored in the vault (the companion reinstalls it). Obsidian asks once to turn community
plugins on (Settings › Community plugins).
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import math
import re
import urllib.parse
import urllib.request
from pathlib import Path

import vault_nodes as VN

PROFILE = 3  # bump to re-apply BlackLab's graph and plugin settings once (your later changes are kept until the next bump)

# ---------------------------------------------------------------- plugins (pinned + verified)

PLUGINS = {
    "breadcrumbs": ("michaelpporter/breadcrumbs", "4.22.3", {
        "main.js": "6de7d02aec3ba4fb07275f4b6c5014705337cb8556249d01c75cf3e46c60cd9d",
        "manifest.json": "b93b29ac79b9d628c25a283279880ce1c41cecfafc7efa17ee4e5723d6cfe50f",
        "styles.css": "a665ef3d591c0c05dbb35600fbcd2c554d7f14916e285ddd1f3c3d1a30a2fd05"}),
    "extended-graph": ("elsatam/obsidian-extended-graph", "2.7.7", {
        "main.js": "025616a1d0752aa4b0ca01fcd4e71a49b6dbd8284f9a8725f2624d5bba15055a",
        "manifest.json": "d02c3be055973d8e79a0d6fe56f9aa32d71ccbb3cf55d0ce83b0f1c64905a772",
        "styles.css": "ce754cb01a6435e83c0197f765feee866896556f14cff24166bf399c3a329cef"}),
    "obsidian-advanced-uri": ("vinzent03/obsidian-advanced-uri", "2.2.1", {
        "main.js": "8555d9a54de0d5534007047ac1e58dee746f394f9e23fe5740ca5857852477f3",
        "manifest.json": "055795794773ccc952688cb01b24bf9c329ccf23731c2ff8ec9835533e0cb38b",
        "styles.css": "f302a82f135abe50bcbb56523cbe97ab405290a43585821b8e52df4ede4f4417"}),
}

LINK_COLORS = {"up": "#8A93A6", "synapses": "#FFB020", "related": "#2BD9A8", "same": "#AB7CFF", "next": "#64B5F6", "prev": "#64B5F6"}

PLUGIN_SETTINGS = {
    "breadcrumbs": {
        "edge_fields": [{"label": f} for f in ("up", "down", "same", "next", "prev", "synapses", "related")],
        "edge_field_groups": [{"label": "ups", "fields": ["up"]}, {"label": "downs", "fields": ["down"]}, {"label": "sames", "fields": ["same"]},
                              {"label": "nexts", "fields": ["next"]}, {"label": "prevs", "fields": ["prev"]},
                              {"label": "synapses", "fields": ["synapses"]}, {"label": "relateds", "fields": ["related"]}],
        "implied_relations": {"transitive": [
            {"name": "down from up", "rounds": 1, "chain": [{"field": "up"}], "close_field": "down", "close_reversed": True},
            {"name": "up from down", "rounds": 1, "chain": [{"field": "down"}], "close_field": "up", "close_reversed": True},
            {"name": "same both ways", "rounds": 1, "chain": [{"field": "same"}], "close_field": "same", "close_reversed": True},
            {"name": "prev from next", "rounds": 1, "chain": [{"field": "next"}], "close_field": "prev", "close_reversed": True},
            {"name": "next from prev", "rounds": 1, "chain": [{"field": "prev"}], "close_field": "next", "close_reversed": True},
            {"name": "synapses both ways", "rounds": 1, "chain": [{"field": "synapses"}], "close_field": "synapses", "close_reversed": True},
            {"name": "related both ways", "rounds": 1, "chain": [{"field": "related"}], "close_field": "related", "close_reversed": True},
            {"name": "siblings share a parent", "rounds": 1, "chain": [{"field": "up"}, {"field": "down"}], "close_field": "same", "close_reversed": False},
        ]},
        "exclude_folders": ["99 Archive", "95 Library"],
        "explicit_edge_sources": {"date_note": {"enabled": True, "date_format": "yyyy-MM-dd", "default_field": "next", "stretch_to_existing": True}},
        "views": {"page": {"trail": {"enabled": True, "format": "path", "selection": "shortest", "default_depth": 6, "show_controls": True,
                                     "field_group_labels": ["ups"]},
                           "prev_next": {"enabled": True}},
                  "side": {"matrix": {"field_group_labels": ["ups", "downs", "synapses", "relateds", "sames", "nexts", "prevs"],
                                      "show_attributes": ["source", "implied_kind"]},
                           "tree": {"default_depth": 4, "field_group_labels": ["downs"], "find_root": True, "find_root_field_group_labels": ["ups"]}}},
        "commands": {"rebuild_graph": {"notify": False, "trigger": {"note_save": True, "layout_change": True}}},
    },
    "extended-graph": {
        "enableFeatures": {kind: {"auto-enabled": True, "links": True, "arrows": True, "focus": True, "elements-stats": True, "names": True,
                                  "tags": False, "properties": False, "folders": False, "shapes": False}
                           for kind in ("graph", "localgraph")},
        "interactiveSettings": {"link": {"colormap": "rainbow", "colors": [{"type": t, "color": c} for t, c in LINK_COLORS.items()],
                                         "unselected": [], "excludeRegex": {"regex": "", "flags": ""}, "noneType": "none",
                                         "showOnGraph": True, "enableByDefault": True}},
        "allowMultipleLinkTypes": True, "curvedLinks": True, "curvedFactor": 0.6, "displayLinkTypeLabel": False,
        "nodesSizeFunction": "totalUniquelinksCount", "nodesSizeRange": {"min": 0.7, "max": 2.4}, "recomputeStatsOnGraphChange": True,
        "highlightOpenNodes": True, "highlightSearchResults": True, "focusScaleFactor": 2.0, "showNamesWhenNeighborHighlighted": True,
        "currentNode": {"useColor": True, "color": "#2BD9A8", "size": 160, "shape": "circle"},
        "excludedSourcesFolder": ["99 Archive"], "excludedTargetsFolder": ["99 Archive"],
    },
    "obsidian-advanced-uri": {"openFileOnWrite": True, "openDailyInNewPane": False, "openFileOnWriteInNewPane": False, "allowEval": False},
}


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch_plugin(pid: str, fetch=None) -> dict[str, bytes]:
    """Downloads one pinned plugin and verifies every file. Raises ValueError on any mismatch (nothing is written)."""
    repo, tag, files = PLUGINS[pid]
    fetch = fetch or (lambda url: urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "BlackLab-companion"}), timeout=60).read())
    out = {}
    for name, digest in files.items():
        data = fetch(f"https://github.com/{repo}/releases/download/{tag}/{name}")
        if _sha(data) != digest:
            raise ValueError(f"{pid} {name}: checksum mismatch — not installing")
        out[name] = data
    return out


def deep_merge(base, ours):
    """[ours] laid over [base]: dicts merge key by key, everything else is replaced."""
    if isinstance(base, dict) and isinstance(ours, dict):
        out = dict(base)
        for k, v in ours.items():
            out[k] = deep_merge(base.get(k), v) if k in base else v
        return out
    return ours


def install_plugins(v, fetch=None, force_settings=False) -> list[str]:
    """Installs or updates the pinned plugins, enables the ones that are in place and applies BlackLab's settings
    (once per PROFILE). A failed download leaves that plugin as it was and raises after the others are done."""
    gi = v.path(".gitignore")
    text = gi.read_text() if gi.exists() else ""
    if ".obsidian/plugins/*/main.js" not in text:
        v._write(".gitignore", text.rstrip("\n") + "\n# Plugin code (the companion reinstalls it, pinned + verified); settings are versioned\n"
                 ".obsidian/plugins/*/main.js\n.obsidian/plugins/*/styles.css\n")
    plugdir = v.path(".obsidian/plugins")
    done, errors, ready = [], [], []
    for pid, (_, tag, files) in PLUGINS.items():
        d = plugdir / pid
        have = {n: (d / n).read_bytes() for n in files if (d / n).exists()}
        if len(have) != len(files) or any(_sha(have[n]) != files[n] for n in files):
            try:
                data = fetch_plugin(pid, fetch)
            except Exception as e:
                errors.append(f"{pid}: {e}")
                continue
            d.mkdir(parents=True, exist_ok=True)
            for n, b in data.items():
                (d / n).write_bytes(b)
            done.append(f"{pid} {tag}")
        ready.append(pid)
        settings = d / "data.json"
        try:
            current = json.loads(settings.read_text()) if settings.exists() else {}
        except ValueError:
            current = {}
        if force_settings or not settings.exists():
            settings.write_text(json.dumps(deep_merge(current, PLUGIN_SETTINGS[pid]), indent=2) + "\n")
    cp = v.path(".obsidian/community-plugins.json")
    try:
        enabled = json.loads(cp.read_text()) if cp.exists() else []
    except ValueError:
        enabled = []
    enabled += [pid for pid in ready if pid not in enabled]
    v._write(".obsidian/community-plugins.json", json.dumps(enabled, indent=2) + "\n")
    if errors:
        raise RuntimeError("; ".join(errors))
    return done


# ---------------------------------------------------------------- 1 + 2: forces and colors

FORCES = {"centerStrength": 0.45, "repelStrength": 11.0, "linkStrength": 0.85, "linkDistance": 140, "nodeSizeMultiplier": 1.15,
          "lineSizeMultiplier": 0.9, "textFadeMultiplier": -0.6, "showArrow": True, "showTags": False, "showAttachments": False,
          "hideUnresolved": False, "showOrphans": True}


def _rgb(hex_color: str) -> int:
    return int(hex_color.lstrip("#"), 16)


def color_groups() -> list[dict]:
    """Most specific first (Obsidian colors a note with the first group it matches)."""
    groups = [{"query": f'file:"{VN.NEXUS}"', "color": {"a": 1, "rgb": 0xFFFFFF}},
              {"query": f'path:"{VN.NODES_DIR}/{VN.SUB_GROUP}"', "color": {"a": 1, "rgb": 0xC8E6C9}}]
    for n in VN.NODES:
        parts = [f'file:"{VN.title(n)}"', f'[up:"{VN.title(n)}"]'] + [f'path:"{f}"' for f in n["folders"]]
        groups.append({"query": " OR ".join(parts), "color": {"a": 1, "rgb": _rgb(n["color"])}})
    groups += [{"query": 'path:"70 AI"', "color": {"a": 1, "rgb": 0x2BD9A8}},
               {"query": 'path:"01 Daily"', "color": {"a": 1, "rgb": 0x90A4AE}},
               {"query": 'path:"90 System"', "color": {"a": 1, "rgb": 0x546E7A}}]
    return groups


def graph_config(current: dict) -> dict:
    g = dict(current)
    g.update(FORCES)
    g["search"] = '-path:"99 Archive" -path:"95 Library"'
    g["colorGroups"] = color_groups()
    return g


# ---------------------------------------------------------------- 4: saved views (bookmarks)

def _graph_bookmark(title: str, search: str, extra: dict | None = None) -> dict:
    opts = {"collapse-filter": True, "search": search, "showTags": False, "showAttachments": False, "hideUnresolved": True,
            "showOrphans": False, "collapse-color-groups": True, "colorGroups": color_groups(), "collapse-display": True,
            "showArrow": True, "textFadeMultiplier": -0.4, "nodeSizeMultiplier": 1.2, "lineSizeMultiplier": 1, "collapse-forces": True, **FORCES}
    opts["showOrphans"] = False
    opts.update(extra or {})
    return {"type": "graph", "title": title, "options": opts}


def graph_views() -> dict:
    items = [_graph_bookmark("◉ The brain (nodes + synapses)", f'path:"{VN.NODES_DIR}" OR file:"{VN.NEXUS}"', {"linkDistance": 260, "repelStrength": 16}),
             _graph_bookmark("🕸️ Everything (no archive)", '-path:"99 Archive" -path:"95 Library" -path:"01 Daily"'),
             _graph_bookmark("🤖 AI layer", 'path:"70 AI" OR [up:"◆ AI & Automation"]'),
             _graph_bookmark("⚠️ Needs attention", '[status:overdue] OR [status:due] OR [status:open] OR [priority:P1] OR [priority:P2]'),
             _graph_bookmark("🧠 Related (semantic links)", '[related:]', {"linkDistance": 120})]
    for n in VN.NODES:
        items.append(_graph_bookmark(f"{n['emoji']} {n['name']}", " OR ".join([f'file:"{VN.title(n)}"', f'[up:"{VN.title(n)}"]'] + [f'path:"{f}"' for f in n["folders"]])))
    return {"type": "group", "title": "🕸️ Graph views", "items": items}


def apply_bookmarks(v):
    p = v.path(".obsidian/bookmarks.json")
    try:
        bm = json.loads(p.read_text()) if p.exists() else {"items": []}
    except ValueError:
        bm = {"items": []}
    items = [x for x in bm.get("items", []) if not (x.get("type") == "group" and x.get("title") == "🕸️ Graph views")]
    items.append(graph_views())
    bm["items"] = items
    v._write(".obsidian/bookmarks.json", json.dumps(bm, indent=2, ensure_ascii=False) + "\n")


# ---------------------------------------------------------------- note scan (links, props, text)

WIKILINK = re.compile(r"\[\[([^\]|#^]+)(?:[#^][^\]|]*)?(?:\|[^\]]*)?\]\]")
SKIP_DIRS = {".obsidian", ".git", ".trash", "99 Archive"}


def scan(v) -> dict[str, dict]:
    """Every note: its title, frontmatter lines, body, outgoing link targets and props (cheap; no YAML library)."""
    notes = {}
    for p in v.root.rglob("*.md"):
        rel = p.relative_to(v.root).as_posix()
        if set(Path(rel).parts) & SKIP_DIRS or "/Files/" in rel:
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        fm, body = v.split_frontmatter(text)
        props = VN._parse_props(fm)
        targets = {m.group(1).strip().removesuffix(".md") for m in WIKILINK.finditer(text)}
        links = {x.split("/")[-1] for x in targets}
        notes[rel] = {"rel": rel, "title": p.stem, "fm": fm, "body": body, "props": props, "links": links, "targets": targets, "mtime": p.stat().st_mtime}
    return notes


DATE_TARGET = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def graph_health(notes: dict[str, dict], root: Path | None = None) -> dict:
    """Links, orphans, hubs and real unresolved links. Not counted as unresolved: folder links, template placeholders,
    attachments, and day notes that don't exist yet (agenda days ahead — they appear when the day comes)."""
    titles = {n["title"] for n in notes.values()}
    alias = {}
    for n in notes.values():
        for a in re.findall(r'"([^"]+)"', n["props"].get("aliases", "")):
            alias[a] = n["title"]
    incoming = {t: 0 for t in titles}
    unresolved: dict[str, int] = {}
    future_days = set()
    for n in notes.values():
        for raw in n.get("targets", n["links"]):
            base = raw.split("/")[-1]
            hit = base if base in titles else alias.get(base) or alias.get(raw)
            if hit:
                if hit != n["title"]:
                    incoming[hit] = incoming.get(hit, 0) + 1
            elif "{{" in raw or raw.endswith((".canvas", ".base", ".png", ".jpg", ".jpeg", ".pdf", ".svg")):
                continue
            elif root is not None and ((root / raw).exists() or (root / f"{raw}.md").exists()):
                continue
            elif DATE_TARGET.match(base):
                future_days.add(base)
            else:
                unresolved[raw] = unresolved.get(raw, 0) + 1
    orphans = sorted(n["rel"] for n in notes.values() if incoming.get(n["title"], 0) == 0 and not (n["links"] & titles)
                     and not n["rel"].startswith(("90 System/", "01 Daily/")))
    hubs = sorted(((incoming.get(n["title"], 0) + len(n["links"] & titles), n["title"]) for n in notes.values()), reverse=True)[:12]
    linked = sum(1 for n in notes.values() if n["props"].get("up"))
    return {"notes": len(notes), "orphans": orphans, "unresolved": sorted(unresolved.items(), key=lambda kv: -kv[1])[:30],
            "future_days": len(future_days), "hubs": hubs, "with_up": linked, "edges": sum(len(n["links"] & titles) for n in notes.values())}


def suggest_home(note: dict) -> str | None:
    """Best node for an orphan, from keywords (the same rules the nodes use for 'mentions')."""
    low = (note["title"] + " " + note["body"]).lower()
    best = max(VN.NODES, key=lambda n: VN._keyword_hits(n, low))
    return VN.title(best) if VN._keyword_hits(best, low) > 0 else None


def render_health(v, notes: dict, related_stats: dict | None = None):
    h = graph_health(notes, v.root)
    lines = ["# 🩺 Graph health", "", f"> [!info] {h['notes']} notes · {h['edges']} links · {h['with_up']} wired to a node with `up:` · "
             f"{len(h['orphans'])} orphans · {len(h['unresolved'])} unresolved targets · {h['future_days']} agenda days ahead", "",
             "Bookmarks › **🕸️ Graph views** has a saved graph per node. In any note: Breadcrumbs' trail at the top, its matrix in the "
             "side bar, and *Open local graph* (Advanced URI) on every node note.", ""]
    if related_stats:
        lines += [f"**Related links** (local embeddings): {related_stats.get('pairs', 0)} between {related_stats.get('notes', 0)} notes, "
                  f"threshold {related_stats.get('threshold', 0):.2f}, updated {related_stats.get('at', '')}.", ""]
    lines += ["## Hubs", ""] + [f"- [[{t}]] — {k} links" for k, t in h["hubs"]] + [""]
    lines += ["## Orphans (no links in or out)", ""]
    if h["orphans"]:
        for rel in h["orphans"][:60]:
            home = suggest_home(notes[rel])
            lines.append(f"- [[{notes[rel]['title']}]]" + (f" → add `up: \"[[{home}]]\"`" if home else ""))
    else:
        lines.append("- _None — every note is connected._")
    lines += ["", "## Unresolved links (targets that don't exist yet)", ""]
    lines += [f"- `{t}` ×{c}" for t, c in h["unresolved"]] or ["- _None._"]
    v.write_managed("90 System/Graph Health.md", {"type": "system", "orphans": len(h["orphans"]), "unresolved": len(h["unresolved"]),
                                                  "tags": ["system/graph"]}, "\n".join(lines), user_tail="\n")
    return h


# ---------------------------------------------------------------- integration 3: related notes (local embeddings)

RELATED_EXCLUDE = ("01 Daily/", "90 System/", "95 Library/", "99 Archive/", f"{VN.NODES_DIR}/", "Home.md", "AI Registry.md", "AI Protocol.md", "README.md",
                   f"{VN.NEXUS}.md")
MIN_BODY = 80          # shorter notes have too little to compare
FLOOR_RANGE = (0.72, 0.90)  # the bar is this vault's 95th-percentile similarity, kept inside this range
MANAGED_RE = re.compile(r"%% blacklab:begin %%.*?%% blacklab:end %%", re.S)


def embed_text(note: dict) -> str:
    body = re.sub(r"<[^>]+>|`[^`]*`|\[\[|\]\]|[#>*_|]", " ", note["body"])
    return (note["title"] + "\n" + re.sub(r"\s+", " ", body))[:2000]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def unit(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def similarity_matrix(vecs: list[list[float]]) -> list[list[float]]:
    u = [unit(v) for v in vecs]
    n = len(u)
    sims = [[0.0] * n for _ in range(n)]
    for i in range(n):
        ui = u[i]
        for j in range(i + 1, n):
            sims[i][j] = sims[j][i] = sum(a * b for a, b in zip(ui, u[j]))
    return sims


def adaptive_floor(sims: list[list[float]], q=0.95) -> float:
    """nomic-embed-text rates almost any two notes 0.6+; only pairs in this vault's top 5% are 'about the same thing'."""
    vals = sorted(sims[i][j] for i in range(len(sims)) for j in range(i + 1, len(sims)))
    if len(vals) < 20:
        return FLOOR_RANGE[0]
    return min(FLOOR_RANGE[1], max(FLOOR_RANGE[0], vals[int(q * (len(vals) - 1))]))


def pick_related(names: list[str], vecs: list[list[float]], linked: dict[str, set[str]], k=3, floor=0.62, relative=0.9,
                 previous: dict[str, list[str]] | None = None, slack=0.03, sims=None) -> dict[str, list[tuple[str, float]]]:
    """Top-k neighbours per note above an absolute floor and within [relative] of that note's best match, skipping
    pairs already linked either way. Mutual: a pair shows up on both notes. A pair picked last time stays while it is
    within [slack] of the bar, so small edits don't make links flicker (and churn the vault's git history)."""
    previous = previous or {}
    n = len(names)
    sims = sims or similarity_matrix(vecs)
    out: dict[str, list[tuple[str, float]]] = {x: [] for x in names}
    for i, a in enumerate(names):
        cands = sorted(((sims[i][j], names[j]) for j in range(n) if j != i), reverse=True)
        if not cands:
            continue
        best = cands[0][0]
        need = max(floor, best * relative)
        for s, b in cands:
            if len(out[a]) >= k or s < need - slack:
                break  # sorted: nothing further can qualify, not even a kept pair
            if s < need and b not in previous.get(a, ()):
                continue
            if b in linked.get(a, set()) or a in linked.get(b, set()):
                continue
            out[a].append((b, s))
    for a in names:  # make it mutual
        for b, s in list(out[a]):
            if all(x != a for x, _ in out[b]):
                out[b].append((a, s))
    return out


def update_related(v, notes: dict, embed, state: dict, k=3) -> dict:
    """Writes `related:` on notes (only that property; text and other keys untouched). Keeps links you added yourself.
    Notes are keyed by path, so notes that share a title ("Untitled page") are never confused: a shared title is linked
    by path, and two notes with the same title aren't paired (they're usually copies of one template)."""
    pool = {r: n for r, n in notes.items() if not r.startswith(RELATED_EXCLUDE) and len(n["body"]) >= MIN_BODY}
    cache = state.setdefault("vectors", {})
    todo = []
    for r, n in pool.items():
        h = hashlib.sha1(embed_text(n).encode()).hexdigest()[:16]
        if cache.get(r, {}).get("h") != h:
            todo.append((r, h))
    for i in range(0, len(todo), 32):
        chunk = todo[i:i + 32]
        vecs = embed([embed_text(pool[r]) for r, _ in chunk])
        for (r, h), vec in zip(chunk, vecs):
            cache[r] = {"h": h, "v": [round(x, 5) for x in vec]}
    removed = [r for r in cache if r not in pool]
    for r in removed:
        del cache[r]
    names = sorted(r for r in pool if r in cache)
    if not todo and not removed and state.get("names") == names and "ours_rel" in state:
        return dict(state.get("stats", {}), changed=0, embedded=0)  # nothing new: same picks, no O(n²) work
    title_count: dict[str, int] = {}
    for n in notes.values():
        title_count[n["title"]] = title_count.get(n["title"], 0) + 1
    by_title: dict[str, set[str]] = {}
    for r in names:
        by_title.setdefault(pool[r]["title"], set()).add(r)
    ours_rel = state.get("ours_rel", {})
    ours_links = state.get("ours", {})

    def link(r):
        title = notes[r]["title"]
        return f"[[{title}]]" if title_count.get(title, 0) == 1 else f"[[{r.removesuffix('.md')}|{title}]]"

    # existing links — minus the related links we wrote ourselves (or they'd count as "already linked" and vanish);
    # notes with the same title count as linked (never pair a template's copies)
    linked = {}
    for r in names:
        own = {pool[x]["title"] for x in ours_rel.get(r, [])}
        targets = {x for t in (pool[r]["links"] - own) for x in by_title.get(t, ())}
        linked[r] = targets | (by_title.get(pool[r]["title"], set()) - {r})
    sims = similarity_matrix([cache[r]["v"] for r in names])
    floor = adaptive_floor(sims)
    picks = pick_related(names, [cache[r]["v"] for r in names], linked, k=k, floor=floor, previous=ours_rel, sims=sims)
    new_rel, new_links, pairs, changed = {}, {}, 0, 0
    for r in names:
        rels = [b for b, _ in picks.get(r, [])]
        mine = [link(b) for b in rels]
        pairs += len(mine)
        existing = re.findall(r'\[\[[^\]]+\]\]', notes[r]["props"].get("related", ""))
        users = [x for x in existing if x not in ours_links.get(r, [])]
        final = list(dict.fromkeys(users + mine))
        new_rel[r], new_links[r] = rels, mine
        if final != existing:
            text = v.path(r).read_text(encoding="utf-8", errors="replace")
            fm, rest = v.split_frontmatter(text)
            if not final:  # drop the related key we added earlier
                fm = [l for l in fm if not l.startswith("related:")]
            props = {"related": final} if final else {}
            v._write(r, (v.frontmatter(props, fm) if (fm or props) else "") + rest)
            changed += 1
    state["ours_rel"], state["ours"], state["names"] = new_rel, new_links, names
    state["stats"] = {"notes": len(names), "pairs": pairs // 2, "threshold": round(floor, 3), "at": dt.datetime.now().strftime("%b %d %H:%M")}
    return dict(state["stats"], changed=changed, embedded=len(todo))


# ---------------------------------------------------------------- integration 4: Advanced URI

def uri(vault_name: str, rel: str | None = None, command: str | None = None) -> str:
    q = {"vault": vault_name}
    if rel:
        q["filepath"] = rel
    if command:
        q["commandid"] = command
    return "obsidian://adv-uri?" + urllib.parse.urlencode(q, quote_via=urllib.parse.quote)


def local_graph_uri(vault_name: str, rel: str) -> str:
    return uri(vault_name, rel, "graph:open-local")


def graph_uri(vault_name: str) -> str:
    return uri(vault_name, None, "graph:open")


def find_note(v, query: str, notes: dict | None = None) -> str | None:
    """The note a spoken name means: exact title or alias, then a node by name, then the vault search."""
    q = re.sub(r"^(?:the|my)\s+|\s+(?:node|note|subnode|page)$", "", (query or "").strip().strip("\"'").lower()).strip()
    if not q:
        return None
    notes = notes or scan(v)
    for r, n in notes.items():
        if n["title"].lower() == q or n["title"].lower().lstrip("◆◇◉ ").strip() == q:
            return r
    for r, n in notes.items():
        if q in [a.lower() for a in re.findall(r'"([^"]+)"', n["props"].get("aliases", ""))]:
            return r
    for n in VN.NODES:
        if q in n["name"].lower() or q == n["id"]:
            return VN.rel(n)
    hits = v.search(query, 1)
    return hits[0].get("path") if hits else None


# ---------------------------------------------------------------- the pass

def apply(v, embed=None, fetch=None, mining=lambda: False) -> dict:
    """Everything above, idempotent. Settings are (re)applied when PROFILE changes; related links refresh each pass."""
    state = v._state("graph.json", {})
    report = {}
    fresh = state.get("profile") != PROFILE
    gpath = v.path(".obsidian/graph.json")
    if fresh:
        try:
            current = json.loads(gpath.read_text()) if gpath.exists() else {}
        except ValueError:
            current = {}
        v._write(".obsidian/graph.json", json.dumps(graph_config(current), indent=2) + "\n")
        apply_bookmarks(v)
        stray = v.path("Untitled.canvas")
        if stray.exists() and stray.read_text().strip() in ("", "{}"):
            stray.unlink()
    try:
        report["plugins"] = install_plugins(v, fetch, force_settings=fresh)
    except Exception as e:  # offline or GitHub down: try again next pass
        report["plugins_error"] = str(e)[:200]
    notes = scan(v)
    rel_state = v._state("related.json", {})
    if embed and not mining():
        try:
            report["related"] = update_related(v, notes, embed, rel_state)
            v._save_state("related.json", rel_state)
            notes = scan(v)
        except Exception as e:
            report["related_error"] = str(e)[:200]
    stats = report.get("related") or state.get("related")
    report["health"] = {k: (len(x) if isinstance(x, list) else x) for k, x in render_health(v, notes, stats).items()}
    state.update(profile=PROFILE, related=stats, at=dt.datetime.now().isoformat(timespec="seconds"))
    if "plugins_error" not in report:
        v._save_state("graph.json", state)
    return report
