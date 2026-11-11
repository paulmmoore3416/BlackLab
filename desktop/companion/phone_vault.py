"""Keeps a copy of the Obsidian vault on the phone (Documents/BlackLab) so Obsidian on Android opens the same vault
and graph — over the existing USB / wireless-debugging link, never through a cloud.

Each pass (every 5 minutes while the phone is linked):
  1. pull: notes you edited in Obsidian on the phone since the last pass come back into the desktop vault
     (Markdown and canvas files outside .obsidian; BlackLab's managed blocks are re-rendered as usual),
  2. push: everything changed on the desktop goes to the phone (`adb push --sync` only sends newer files);
     the phone's own workspace/layout files are left alone, and .git never goes,
  3. delete: files that left the desktop vault (moved to 99 Archive, etc.) are removed from the phone copy,
  4. a marker file records the time, so step 1 next time only looks at what you changed after it.
"""
from __future__ import annotations

import json
import os
import shlex
import subprocess
import time
from pathlib import Path

SKIP_TOP = {".git", ".trash"}
# The Atlas, imported vaults and people & topics stay on the computer (phone shared storage is readable by other apps);
# config "phone_vault_local": true copies them too.
LOCAL_TOP = {"85 Atlas", "86 Imported", "87 Entities", "88 Drive", "89 Mobile"}
PHONE_LOCAL = ("workspace", "workspace-mobile", "cache")  # .obsidian files the phone keeps for itself
MARKER = ".blacklab-sync"


def _adb(adb: str, serial: str, *args, timeout=600) -> subprocess.CompletedProcess:
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def _sh(adb, serial, cmd: str, timeout=120) -> str:
    return _adb(adb, serial, "shell", cmd, timeout=timeout).stdout


def local_files(root: Path, include_local: bool = False) -> list[str]:
    out = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        if rel.parts[0] in SKIP_TOP or (rel.parts[0] in LOCAL_TOP and not include_local) or p.name.startswith(".") and p.name != ".gitignore":
            continue
        if rel.parts[0] == ".obsidian" and any(p.stem.startswith(x) for x in PHONE_LOCAL):
            continue
        out.append(rel.as_posix())
    return sorted(out)


def sync(adb: str, serial: str, root: Path, dest: str, state_file: Path, on_pull=None, include_local: bool = False) -> dict:
    state = json.loads(state_file.read_text()) if state_file.exists() else {}
    dq = shlex.quote(dest)
    _sh(adb, serial, f"mkdir -p {dq}")
    pulled = []
    # 1. pull what changed on the phone since the last pass
    if _sh(adb, serial, f"[ -f {dq}/{MARKER} ] && echo yes").strip() == "yes":
        changed = _sh(adb, serial, f"cd {dq} && find . -type f -newer {MARKER} \\( -name '*.md' -o -name '*.canvas' \\) ! -path '*/.obsidian/*'")
        for line in changed.splitlines():
            rel = line.strip().removeprefix("./")
            if not rel or rel.startswith(".") or (rel.split("/")[0] in LOCAL_TOP and not include_local):
                continue
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_name(f".{target.name}.phone")
            r = _adb(adb, serial, "pull", f"{dest}/{rel}", str(tmp))
            if r.returncode == 0 and tmp.exists():
                os.replace(tmp, target)
                pulled.append(rel)
            else:
                tmp.unlink(missing_ok=True)
    if pulled and on_pull:
        on_pull(pulled)
    # 2. push the desktop's changes (newer files only)
    files = local_files(root, include_local)
    tops = sorted({f.split("/")[0] for f in files if "/" in f and not f.startswith(".obsidian/")})
    for t in tops:
        _adb(adb, serial, "push", "--sync", str(root / t), f"{dest}/", timeout=1800)
    seeded = state.get("obsidian_seeded", False)
    for f in files:
        if f.startswith(".obsidian/") and seeded and f != ".obsidian/graph.json":
            continue  # the phone's own Obsidian settings stay its own after the first copy (the graph colors stay in step)
        if "/" not in f or f.startswith(".obsidian/"):
            _sh(adb, serial, f"mkdir -p {shlex.quote(str(Path(dest) / f).rsplit('/', 1)[0])}")
            _adb(adb, serial, "push", "--sync", str(root / f), f"{dest}/{f}")
    # 3. remove what left the desktop vault
    gone = sorted(set(state.get("files", [])) - set(files))
    for g in gone:
        _sh(adb, serial, f"rm -f {shlex.quote(dest + '/' + g)}")
    # 4. marker for the next pull
    _sh(adb, serial, f"touch {dq}/{MARKER}")
    state.update({"files": [f for f in files if not f.startswith(".obsidian/")], "at": time.time(), "serial": serial, "pulled": pulled[-50:], "obsidian_seeded": True})
    state_file.write_text(json.dumps(state))
    return {"pushed_files": len(files), "pulled": pulled, "removed": len(gone)}
