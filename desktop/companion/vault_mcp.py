#!/usr/bin/env python3
"""BlackLab Vault — an MCP server (stdio, standard library only) for any AI that should work in the Obsidian vault.

    claude mcp add --scope user blacklab-vault -- python3 /path/to/BlackLab/desktop/companion/vault_mcp.py

Tools: vault_checkin (sign the AI Registry — call it first), vault_status, vault_search, vault_read, vault_write,
vault_log (a line on today's timeline), vault_capture (quick note into 00 Inbox), vault_recent (the last few days).

While the BlackLab Desktop companion runs, every call goes through it (one writer, same privacy rules, git commits);
otherwise the vault is used directly. Privacy rules live in guards.py: clinical text is refused; keys, passwords, SSNs,
card numbers and VINs are scrubbed.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vault as V  # noqa: E402

PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
COMPANION = os.environ.get("BLACKLAB_DESKTOP_URL", "http://127.0.0.1:8770")
CONFIG = Path.home() / ".config" / "blacklab-desktop" / "config.json"

INSTRUCTIONS = (
    "This is Paul's BlackLab Obsidian vault — his second brain for facility operations, learning, projects and life. "
    "1) Call vault_checkin FIRST with your agent name, model, purpose and a friendly note (every session). "
    "2) Search before you write; extend existing notes. 3) Write outside BlackLab's %% blacklab %% blocks; new notes go to '00 Inbox' "
    "or '70 AI/Agents/<your name>/'; add lasting knowledge to one of the 13 nodes with vault_node_add (see vault_nodes). 4) Log each meaningful change with vault_log. 5) Never write patient/Behavioral Health "
    "information, keys, passwords, tokens or VINs. 6) Link with [[wikilinks]]."
)

TOOLS = [
    {"name": "vault_checkin", "description": "Sign the vault's AI Registry: who you are, which model, why you came, and a friendly note for Paul. Call this first, every session.",
     "inputSchema": {"type": "object", "properties": {"agent": {"type": "string", "description": "Your name, e.g. 'Claude Code'"},
                                                      "model": {"type": "string"}, "purpose": {"type": "string", "description": "What you're here to do"},
                                                      "note": {"type": "string", "description": "A short, friendly note"}}, "required": ["agent", "note"]}},
    {"name": "vault_status", "description": "Where the vault is, how many notes, last syncs, recent commits and the last AI visitor.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "vault_search", "description": "Full-text search across the vault (notes, daily timelines, assets, projects, meetings). Returns paths and snippets.",
     "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer", "default": 8}}, "required": ["query"]}},
    {"name": "vault_read", "description": "Read a note by its vault path (e.g. 'Home.md', '10 Facility/Assets/AHU-2.md').",
     "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
    {"name": "vault_write", "description": "Create or add to a note. mode: create (new only), append (default; adds a stamped section), replace (only notes BlackLab doesn't manage). Never edits BlackLab's managed blocks.",
     "inputSchema": {"type": "object", "properties": {"path": {"type": "string", "description": "e.g. '00 Inbox/Idea.md' or '70 AI/Agents/Claude Code/2026-11-08 Session.md'"},
                                                      "content": {"type": "string", "description": "Markdown"}, "mode": {"type": "string", "enum": ["create", "append", "replace"]},
                                                      "author": {"type": "string"}}, "required": ["path", "content"]}},
    {"name": "vault_log", "description": "Add one line to today's timeline (01 Daily): what you did, with links to the notes involved.",
     "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "detail": {"type": "string"},
                                                      "kind": {"type": "string", "enum": ["ai", "agent", "project", "note", "facility", "learning", "file"], "default": "agent"},
                                                      "links": {"type": "array", "items": {"type": "string"}, "description": "Note names to link, without brackets"},
                                                      "source": {"type": "string", "description": "Who is logging, e.g. 'Claude Code'"}}, "required": ["title"]}},
    {"name": "vault_capture", "description": "Quick capture into 00 Inbox.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "title": {"type": "string"}, "source": {"type": "string"}}, "required": ["text"]}},
    {"name": "vault_nodes", "description": "The neural network: the Nexus and its 13 main nodes (id, name, mission, synapses, member and signal counts). Read a node with vault_read(path).",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "vault_node_add", "description": "Add knowledge to one of the 13 main nodes (facility, safety, milieu, leadership, vendors, finance, projects, ai, learning, knowledge, vehicles, home, time). Goes under the node's Knowledge, never into BlackLab's managed part.",
     "inputSchema": {"type": "object", "properties": {"node": {"type": "string", "description": "Node id or name"}, "content": {"type": "string", "description": "Markdown: facts, procedures, lessons, links"},
                                                      "author": {"type": "string"}}, "required": ["node", "content"]}},
    {"name": "vault_library_search", "description": "Full-text search of the offline library (~50 GB of openly licensed references mapped to the nodes: Wikipedia, Stack Exchange, LibreTexts, Wikibooks, iFixit, MedlinePlus, Ready.gov, USDA canning, survival and field manuals). Optional node or subnode id narrows it.",
     "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "node": {"type": "string"}, "subnode": {"type": "string"}, "limit": {"type": "integer", "default": 8}}, "required": ["query"]}},
    {"name": "vault_recent", "description": "The timeline of the last few days (what happened, across phone, desktop and AIs).",
     "inputSchema": {"type": "object", "properties": {"days": {"type": "integer", "default": 2}}}},
]


def vault_dir() -> Path:
    if os.environ.get("BLACKLAB_VAULT_DIR"):
        return Path(os.environ["BLACKLAB_VAULT_DIR"]).expanduser()
    try:
        cfg = json.loads(CONFIG.read_text())
    except Exception:
        cfg = {}
    return Path(os.path.expanduser(cfg.get("vault_dir", "~/Obsidian/BlackLab")))


def via_companion(op: str, args: dict):
    """The companion's /local/vault/<op> (same Host/header rules as its own UI). None when it isn't running."""
    req = urllib.request.Request(f"{COMPANION}/local/vault/{op}", data=json.dumps(args).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-BlackLab": "1"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None  # an older companion without the vault
        return {"ok": False, "error": e.read().decode(errors="replace")[:300]}
    except (urllib.error.URLError, OSError):
        return None


_DIRECT: V.Vault | None = None


def direct() -> V.Vault:
    global _DIRECT
    if _DIRECT is None:
        state = Path(os.environ.get("BLACKLAB_VAULT_STATE", Path.home() / ".cache" / "blacklab-desktop" / "vault"))
        _DIRECT = V.Vault(vault_dir(), state)
        _DIRECT.ensure()
    return _DIRECT


def run_direct(op: str, a: dict):
    v = direct()
    if op == "checkin":
        out = v.checkin(a.get("agent", ""), a.get("model", ""), a.get("purpose", ""), a.get("note", ""), where="MCP")
    elif op == "status":
        out = v.status()
    elif op == "search":
        out = {"results": v.search(a.get("query", ""), int(a.get("limit") or 8))}
    elif op == "read":
        out = {"text": v.read(a.get("path", ""))}
    elif op == "write":
        out = v.write_note(a.get("path", ""), a.get("content", ""), a.get("mode") or "append", a.get("author") or "AI agent")
    elif op == "log":
        out = {"ok": v.log(a.get("kind") or "agent", a.get("title", ""), a.get("detail", ""), a.get("source") or "AI agent", a.get("links") or [])}
    elif op == "capture":
        out = v.capture(a.get("text", ""), a.get("title", ""), a.get("source") or "AI agent")
    elif op == "recent":
        out = {"text": recent_text(v, int(a.get("days") or 2))}
    elif op == "library":
        import library
        out = {"ok": True, "results": library.search(a.get("query", ""), library.books_for(a.get("node"), a.get("subnode")) or None, int(a.get("limit") or 8))}
    elif op == "nodes":
        import vault_nodes
        out = {"ok": True, "nodes": vault_nodes.overview(v), "nexus": vault_nodes.NEXUS, "function": vault_nodes.FUNCTION}
    elif op == "node_add":
        import vault_nodes
        out = vault_nodes.add(v, a.get("node", ""), a.get("content", ""), a.get("author") or "AI agent")
    else:
        raise ValueError(f"unknown tool {op}")
    if op in ("checkin", "write", "log", "capture", "node_add"):
        v.commit(min_gap=0)
    return out


def recent_text(v: V.Vault, days: int) -> str:
    out = []
    today = v.now().date()
    for i in range(max(1, min(days, 14))):
        d = today - dt.timedelta(days=i)
        items = v._state(f"timeline/{d.isoformat()}.json", [])
        if items:
            out.append(f"## {d.isoformat()}")
            out += [f"- {e['t']} [{e['kind']}] {e['title']} ({e['source']})" for e in sorted(items, key=lambda x: x["ts"])]
    return "\n".join(out) or "Nothing logged in that range."


def call(name: str, args: dict) -> tuple[str, bool]:
    op = name.removeprefix("vault_")
    if op == "library_search":
        op = "library"
    out = via_companion(op, args)
    if out is None:
        out = run_direct(op, args)
    is_error = isinstance(out, dict) and out.get("ok") is False
    if op == "read" and isinstance(out, dict) and "text" in out:
        return out["text"], False
    if op == "recent" and isinstance(out, dict) and "text" in out:
        return out["text"], False
    return json.dumps(out, indent=1, ensure_ascii=False), is_error


def handle(msg: dict) -> dict | None:
    mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None:  # notifications (initialized, cancelled…) need no answer
        return None
    try:
        if method == "initialize":
            asked = params.get("protocolVersion")
            result = {"protocolVersion": asked if asked in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "blacklab-vault", "version": "1.0.0"}, "instructions": INSTRUCTIONS}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            text, err = call(params.get("name", ""), params.get("arguments") or {})
            result = {"content": [{"type": "text", "text": text}], "isError": err}
        elif method in ("resources/list", "prompts/list"):
            result = {"resources": []} if method == "resources/list" else {"prompts": []}
        else:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}
        return {"jsonrpc": "2.0", "id": mid, "result": result}
    except Exception as e:
        if method == "tools/call":
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}}
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            out = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        else:
            out = handle(msg)
        if out is not None:
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
