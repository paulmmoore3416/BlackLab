#!/usr/bin/env python3
"""Claude Code hooks for the BlackLab vault (configured in ~/.claude/settings.json).

  vault_hook.py start   SessionStart: reminds the session to sign the AI Registry and log its work (vault MCP tools),
                        and puts "session started" on today's timeline.
  vault_hook.py end     SessionEnd: writes a session note in 70 AI/Agents/Claude Code/ — project, duration, files
                        changed, commits made, tool counts — and a timeline line. Prompts and replies are never copied
                        (they can hold anything, passwords included); only what changed on disk is recorded.

Never fails the session: any error is swallowed and the hook exits 0.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import subprocess
import sys
import urllib.request
from collections import Counter
from pathlib import Path

COMPANION = os.environ.get("BLACKLAB_DESKTOP_URL", "http://127.0.0.1:8770")
HOME = str(Path.home())


def companion(op: str, body: dict):
    req = urllib.request.Request(f"{COMPANION}/local/vault/{op}", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-BlackLab": "1"})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.loads(r.read() or b"{}")
    except Exception:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import vault_mcp  # the companion isn't running: write to the vault directly
        return vault_mcp.run_direct(op, body)


def rel(p: str) -> str:
    return p.replace(HOME, "~", 1) if p.startswith(HOME) else p


def project_name(cwd: str) -> str:
    try:
        top = subprocess.run(["git", "-C", cwd, "rev-parse", "--show-toplevel"], capture_output=True, text=True, timeout=5).stdout.strip()
        return Path(top or cwd).name
    except Exception:
        return Path(cwd).name


def start(data: dict):
    cwd = data.get("cwd") or os.getcwd()
    proj = project_name(cwd)
    if data.get("source") in (None, "startup", "clear"):
        companion("log", {"kind": "agent", "title": f"Claude Code session started · {proj}", "source": "Claude Code", "links": [proj]})
    ctx = ("BlackLab vault: this computer has Paul's Obsidian vault (~/Obsidian/BlackLab) with the blacklab-vault MCP server. "
           "Before changing anything in this session, call vault_checkin once (agent 'Claude Code', your model, the purpose, a short friendly note). "
           "When you finish a meaningful piece of work, add one vault_log line (what changed, links to notes like the project name). "
           "Follow 'AI Protocol' in the vault; never write Behavioral Health data, keys, passwords or VINs there.")
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))


def end(data: dict):
    cwd = data.get("cwd") or os.getcwd()
    proj = project_name(cwd)
    sid = (data.get("session_id") or "session")[:8]
    files, tools = Counter(), Counter()
    first = last = None
    tp = data.get("transcript_path")
    if tp and Path(tp).is_file():
        with open(tp, encoding="utf-8", errors="replace") as f:
            for line in f:
                try:
                    e = json.loads(line)
                except json.JSONDecodeError:
                    continue
                ts = e.get("timestamp")
                if ts:
                    first = first or ts
                    last = ts
                content = (e.get("message") or {}).get("content")
                if e.get("type") != "assistant" or not isinstance(content, list):
                    continue
                for c in content:
                    if isinstance(c, dict) and c.get("type") == "tool_use":
                        name = c.get("name", "")
                        tools[name.split("__")[-1] if name.startswith("mcp__") else name] += 1
                        fp = (c.get("input") or {}).get("file_path") or (c.get("input") or {}).get("notebook_path")
                        if fp and name in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
                            files[rel(fp)] += 1
    if not tools and not files:
        return  # nothing happened (an empty or instantly closed session)

    def parse(ts):
        try:
            return dt.datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone()
        except Exception:
            return None
    t0, t1 = parse(first) if first else None, parse(last) if last else None
    commits = []
    if t0:
        try:
            out = subprocess.run(["git", "-C", cwd, "log", f"--since={t0.isoformat()}", "--pretty=format:%h %s"], capture_output=True, text=True, timeout=10).stdout
            commits = [l for l in out.splitlines() if l.strip()][:30]
        except Exception:
            pass
    day = (t0 or dt.datetime.now().astimezone()).date().isoformat()
    mins = round((t1 - t0).total_seconds() / 60) if t0 and t1 else 0
    lines = [f"# Claude Code · {proj} · {day}", "", f"- **Project:** [[{proj}]]", f"- **Day:** [[{day}]]",
             f"- **Time:** {t0.strftime('%H:%M') if t0 else '?'}–{t1.strftime('%H:%M') if t1 else '?'} ({mins} min)",
             f"- **Session:** `{sid}` · ended because: {data.get('reason', 'exit')}", ""]
    if files:
        lines += [f"## Files changed ({len(files)})", ""] + [f"- `{p}` ×{n}" for p, n in sorted(files.items())[:200]] + [""]
    if commits:
        lines += [f"## Commits ({len(commits)})", ""] + [f"- `{c.split(' ', 1)[0]}` {c.split(' ', 1)[1] if ' ' in c else ''}" for c in commits] + [""]
    lines += ["## Tools", "", ", ".join(f"{k} ×{v}" for k, v in tools.most_common(20)), ""]
    note = f"70 AI/Agents/Claude Code/{day} {proj} {sid}.md"
    companion("write", {"path": note, "content": "\n".join(lines), "mode": "append", "author": "Claude Code (hook)"})
    companion("log", {"kind": "agent", "title": f"Claude Code session · {proj}: {len(files)} files, {len(commits)} commits",
                      "source": "Claude Code", "links": [proj, Path(note).stem]})


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        data = json.loads(sys.stdin.read() or "{}")
    except Exception:
        data = {}
    try:
        if mode == "start":
            start(data)
        elif mode == "end":
            end(data)
    except Exception as e:
        print(f"vault hook skipped: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
