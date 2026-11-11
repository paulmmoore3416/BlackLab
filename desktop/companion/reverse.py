"""Reverse engineering for the desktop AI and the phone (companion 1.6), powered by REA.

REA ("Reverse Engineer Anything", github.com/morluto/rea, MIT, npm package rea-agents) is a CLI and MCP
server that drives Ghidra and a set of built-in static parsers (ELF/PE/Mach-O, Go and Rust build info, .NET,
APK signatures, Python .pyc, WASM, GGUF/safetensors, kernel modules, firmware…). This module gives the
local model (qwen3:8b) and the phone a safe way to use it:

  * tool(args)      — the `reverse_engineer` tool the desktop model calls: overview, strings, functions,
                      search, decompile, xrefs, disassemble, inspect, help (REA's own docs), status, report.
  * run_job(j)      — the "reverse" background job: a full triage of one file (static inspectors, Ghidra
                      overview, strings, functions, decompiles of the interesting code) and a written report.
                      The phone sends files here (Library ⋮ › Reverse-engineer on computer) or asks by voice.
  * Workspace       — ~/Desktop/BlackLab AI/Reverse Engineering/<date> <name>/ (report.md, evidence/*.json,
                      strings.txt, functions.txt, code/*.c), so follow-up questions never redo the work.
  * REA reference   — REA's skill guide, per-target references and command manifest, copied into
                      Reverse Engineering/REA reference/ and indexed for search_documents and help.

Safety (unit tested in tests/test_reverse.py):
  * Only read-only static commands run: nothing that launches, debugs, instruments or captures the target,
    talks to a device, installs, updates or edits REA/agent settings, or writes outside the workspace.
  * Targets go through the companion's path guard (home folder or read-only system folders, never protected
    paths like keystores, wallets, ~/.ssh), and values can't smuggle in extra flags.
  * One Ghidra at a time (it takes every core); nothing heavy starts while the computer is mining.

Toolchain (user space, no sudo): ~/.local/blacklab-toolchain/{node-v24…, jdk-21…, ghidra_12.1.x_PUBLIC} and
REA in ~/.local/share/blacklab-desktop/rea (`python3 reverse.py install` sets it up; config.json can override
rea_dir, rea_node, java_home and ghidra_dir).
"""
from __future__ import annotations

import datetime as dt
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

C = None  # the companion module (set by setup)
REA_VERSION = "6.8.0"  # pinned: update deliberately (python3 reverse.py install --version X)
HOME = Path.home()
TOOLCHAIN = HOME / ".local" / "blacklab-toolchain"
DEFAULT_REA_DIR = HOME / ".local" / "share" / "blacklab-desktop" / "rea"
RE_DIR = HOME / "Desktop" / "BlackLab AI" / "Reverse Engineering"
REF_DIR = RE_DIR / "REA reference"
SYSTEM_READ_ROOTS = (Path("/usr"), Path("/opt"), Path("/snap"))
MAX_TARGET = 512 * 1024 * 1024
TOOL_TIMEOUT = 300   # one interactive Ghidra call (≈15 s for a small binary, minutes for a big one)
JOB_TIMEOUT = 1200
_GHIDRA = threading.Lock()  # one Ghidra at a time

# ---------------------------------------------------------------- what may run

# Ghidra-backed commands: name → the flag that carries the one value (None: no value).
NATIVE = {
    "analyze": None,
    "search": "--pattern",
    "function": "--procedure",
    "decompile": "--procedure",
    "instructions": "--procedure",
    "inspect-native-api": "--procedure",
    "xrefs": "--address",
    "trace": "--query",
}
# Pure parsers: read the file, never run it.
STATIC = {
    "inspect-artifact", "inspect-byte-entropy", "inspect-elf-notes", "inspect-native-exports", "inspect-go-binary",
    "inspect-rust-binary", "inspect-kernel-module", "inspect-pe-imports", "inspect-pe-certificates", "inspect-pe-load-config",
    "inspect-pe-resources", "inspect-managed-artifact", "inspect-managed-members", "inspect-managed-native-boundaries",
    "inspect-python-pyc", "inspect-erlang-beam", "inspect-il2cpp-metadata", "inspect-model-artifact", "inspect-apk-signature",
    "identify-flutter-build", "inspect-android-package", "inspect-wasm-artifact", "inspect-electron-fuses",
    "inspect-desktop-application", "inspect-apple-ips", "inspect-devicetree", "inspect-firmware-regions",
    "inspect-gpu-code-objects", "inspect-dart-aot", "analyze-javascript-application",
}
OBJDUMP_OPS = {"file_headers", "section_headers", "symbols", "relocations", "dwarf", "disassemble"}
# Never: these run, debug, instrument or capture the target, touch a device, install or reconfigure things,
# or write outside the workspace. (Listed so a test can prove none of them slipped into the allowlist.)
DENIED_PREFIXES = ("capture-", "debug-", "observe-", "instrument-", "install-", "uninstall", "start-adb", "stop-adb", "push-adb",
                   "pull-adb", "list-adb", "read-adb", "inspect-adb", "collect-adb", "environment-", "setup", "update", "mcp",
                   "annotate-", "decompile-all", "extract-", "recover-", "cutter-", "execute-", "analyze-web", "export-web")

VALUE_RE = re.compile(r"^[^\x00-\x1f]{1,200}$")
ADDRESS_RE = re.compile(r"^(?:0x[0-9a-fA-F]{1,16}|[0-9a-fA-F]{4,16}|[A-Za-z_.$@?][\w.$@?:<>~-]{0,150})$")


def allowed(cmd: str) -> bool:
    return (cmd in NATIVE or cmd in STATIC or cmd == "inspect-with-objdump") and not cmd.startswith(DENIED_PREFIXES)


def build_argv(cmd: str, target: str, value: str | None = None, kind: str | None = None, regex: bool = False) -> list[str]:
    """The REA arguments for one allowed command (after `node rea.mjs`). Raises ValueError for anything else."""
    if not allowed(cmd):
        raise ValueError(f"{cmd} isn't one of the read-only commands BlackLab runs")
    if not target.startswith("/"):
        raise ValueError("REA needs an absolute path")
    argv = [cmd, target]
    if cmd == "inspect-with-objdump":
        if value not in OBJDUMP_OPS:
            raise ValueError(f"objdump operation must be one of {', '.join(sorted(OBJDUMP_OPS))}")
        return argv + [value, "--json"]
    flag = NATIVE.get(cmd)
    if flag:
        value = (value or "").strip()
        if not value or not VALUE_RE.match(value) or value.startswith("-"):
            raise ValueError(f"{cmd} needs a {flag[2:]} (no leading '-')")
        if flag in ("--procedure", "--address") and not ADDRESS_RE.match(value):
            raise ValueError(f"'{value}' isn't an address (0x1234) or a function name")
        argv += [f"{flag}={value}"]
        if cmd == "search":
            argv += [f"--kind={'procedures' if kind == 'procedures' else 'strings'}", f"--mode={'regex' if regex else 'literal'}"]
    if cmd in NATIVE:
        argv += ["--provider=ghidra"]
    return argv + ["--json"]


# ---------------------------------------------------------------- toolchain

def _newest(pattern: str) -> Path | None:
    found = sorted(TOOLCHAIN.glob(pattern), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", p.name)])
    return found[-1] if found else None


def _cfg(key: str):
    return (C.CONFIG.get(key) if C else None) or None


def toolchain() -> dict:
    rea_dir = Path(os.path.expanduser(_cfg("rea_dir") or str(DEFAULT_REA_DIR)))
    node = _cfg("rea_node") or (str(_newest("node-v2[46]*/bin/node") or "") or shutil.which("node") or "")
    java = _cfg("java_home") or str(_newest("jdk-21*") or "")
    ghidra = _cfg("ghidra_dir") or os.environ.get("GHIDRA_INSTALL_DIR") or str(_newest("ghidra_12.1*_PUBLIC") or "")
    rea_js = rea_dir / "node_modules" / "rea-agents" / "scripts" / "rea.mjs"
    return {"rea_dir": rea_dir, "rea_js": rea_js if rea_js.is_file() else None, "node": node or None,
            "java_home": java if java and Path(java, "bin", "java").exists() else None,
            "ghidra": ghidra if ghidra and Path(ghidra, "support", "analyzeHeadless").exists() else None}


def rea_version(tc: dict | None = None) -> str | None:
    tc = tc or toolchain()
    try:
        return json.loads((tc["rea_dir"] / "node_modules" / "rea-agents" / "package.json").read_text())["version"]
    except (OSError, ValueError, KeyError):
        return None


def env(tc: dict, job=False) -> dict:
    e = {k: v for k, v in os.environ.items() if k in ("HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "XDG_RUNTIME_DIR")}
    path = ["/usr/bin", "/bin"]
    if tc["node"]:
        path.insert(0, str(Path(tc["node"]).parent))
    if tc["java_home"]:
        path.insert(0, str(Path(tc["java_home"]) / "bin"))
        e["JAVA_HOME"] = tc["java_home"]
    if tc["ghidra"]:
        e["GHIDRA_INSTALL_DIR"] = tc["ghidra"]
        e["REA_GHIDRA_STARTUP_TIMEOUT_MS"] = str((JOB_TIMEOUT - 60 if job else TOOL_TIMEOUT - 20) * 1000)
    e.update(PATH=":".join(path), REA_ANALYSIS_PROVIDER="ghidra", REA_LOG_LEVEL="warn", NO_COLOR="1", REA_TELEMETRY="0", DO_NOT_TRACK="1")
    return e


# ---------------------------------------------------------------- targets

def kind_of(path: Path) -> str:
    """What sort of thing [path] is, from its magic bytes (and name when the bytes are ambiguous)."""
    if path.is_dir():
        if (path / "package.json").exists() or (path / "resources" / "app.asar").exists() or (path / "app.asar").exists():
            return "javascript"
        return "directory"
    suffix = path.suffix.lower()
    try:
        with open(path, "rb") as f:
            head = f.read(64)
    except OSError:
        return "unknown"
    if head.startswith(b"\x7fELF"):
        return "kmod" if suffix == ".ko" else "elf"
    if head.startswith(b"MZ"):
        return "pe"
    if head[:4] in (b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf", b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe"):
        return "macho"
    if head.startswith(b"\xca\xfe\xba\xbe"):
        return "java-class" if suffix == ".class" else "macho"
    if head.startswith(b"PK\x03\x04"):
        if suffix in (".apk", ".aab", ".xapk", ".apks"):
            return "apk"
        try:
            with zipfile.ZipFile(path) as z:
                names = set(z.namelist()[:5000])
        except (zipfile.BadZipFile, OSError):
            return "zip"
        if "AndroidManifest.xml" in names or "classes.dex" in names:
            return "apk"
        return "jar" if suffix == ".jar" or "META-INF/MANIFEST.MF" in names else "zip"
    if head.startswith(b"dex\n"):
        return "dex"
    if head.startswith(b"\x00asm"):
        return "wasm"
    if head.startswith(b"GGUF") or suffix == ".safetensors":
        return "model"
    if head.startswith(b"\xd0\x0d\xfe\xed"):
        return "devicetree"
    if suffix == ".pyc":
        return "pyc"
    if suffix == ".beam" or head.startswith(b"FOR1"):
        return "beam"
    if suffix == ".ips":
        return "apple-ips"
    if suffix in (".js", ".mjs", ".cjs", ".asar"):
        return "javascript"
    return "blob"


NATIVE_KINDS = {"elf", "kmod", "pe", "macho"}
INSPECTORS = {
    "elf": ["inspect-elf-notes", "inspect-native-exports", "inspect-go-binary", "inspect-rust-binary"],
    "kmod": ["inspect-kernel-module", "inspect-elf-notes"],
    "pe": ["inspect-pe-imports", "inspect-native-exports", "inspect-pe-certificates", "inspect-pe-load-config", "inspect-managed-artifact",
           "inspect-go-binary", "inspect-rust-binary"],
    "macho": ["inspect-go-binary", "inspect-rust-binary"],
    "apk": ["inspect-apk-signature", "identify-flutter-build", "inspect-android-package"],
    "pyc": ["inspect-python-pyc"],
    "wasm": ["inspect-wasm-artifact"],
    "model": ["inspect-model-artifact"],
    "beam": ["inspect-erlang-beam"],
    "devicetree": ["inspect-devicetree"],
    "apple-ips": ["inspect-apple-ips"],
    "javascript": ["analyze-javascript-application"],
}
COMMON = ["inspect-artifact", "inspect-byte-entropy"]


def resolve_target(p: str | None) -> tuple[Path | None, str]:
    """(path, "") for a file the AI may analyze, or (None, why not). Bare names are looked up in the workspace and
    the files the phone sent."""
    raw = str(p or "").strip().strip("\"'")
    if not raw:
        return None, "Say which file to look at (a path like ~/Downloads/app.bin)."
    if not raw.startswith(("/", "~")):
        home = getattr(C, "HOME", HOME)
        for root in (RE_DIR, getattr(C, "DOWNLOADS_DIR", RE_DIR), home / "Downloads"):
            hits = sorted((h for h in Path(root).rglob(glob.escape(raw)) if h.is_file()), key=lambda h: h.stat().st_mtime, reverse=True) if Path(root).is_dir() else []
            if hits:
                raw = str(hits[0])
                break
    expanded = Path(os.path.expanduser(raw))
    path = None
    if any(expanded.resolve() == r or r in expanded.resolve().parents for r in SYSTEM_READ_ROOTS):
        path = expanded.resolve()
        if C and C.is_protected(path):
            path = None
    elif C:
        path = C.safe_home_path(raw)
    if path is None:
        return None, "That file is outside what I may read (your home folder or /usr, /opt — never keys, wallets or ~/.ssh)."
    if not path.exists():
        fixed = _case_fix(path)  # small models retype paths ("blackLab-desktop")
        if not fixed:
            return None, f"There's no {path.name} at {path.parent}."
        return resolve_target(str(fixed))
    if path.is_file() and path.stat().st_size > MAX_TARGET:
        return None, "That file is over 512 MB — too big to analyze here."
    return path, ""


def _case_fix(path: Path) -> Path | None:
    """The existing path that differs from [path] only in letter case, if there is exactly one."""
    cur = Path(path.anchor)
    for part in path.parts[1:]:
        exact = cur / part
        if exact.exists():
            cur = exact
            continue
        try:
            same = [c for c in cur.iterdir() if c.name.lower() == part.lower()]
        except OSError:
            return None
        if len(same) != 1:
            return None
        cur = same[0]
    return cur


def sha256(path: Path) -> str:
    if path.is_dir():
        return hashlib.sha256(str(path).encode()).hexdigest()
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- running REA

def _mining() -> bool:
    try:
        import agents
        return agents.mining_active()
    except Exception:
        return False


def run(cmd: str, target: Path, value: str | None = None, kind: str | None = None, regex=False, job=False) -> dict:
    """One REA command → {"ok", "result" | "error", "remedy", "op", "cached"}. Results are cached per file hash."""
    tc = toolchain()
    if not tc["rea_js"] or not tc["node"]:
        return {"ok": False, "error": "REA isn't installed on this computer yet.", "remedy": "Run: python3 desktop/companion/reverse.py install"}
    if cmd in NATIVE and not tc["ghidra"]:
        return {"ok": False, "error": "Ghidra isn't installed, so native decompiling is off.", "remedy": "Run: python3 desktop/companion/reverse.py install"}
    argv = build_argv(cmd, str(target), value, kind, regex)
    digest = sha256(target)
    cache = (C.CACHE_DIR if C else HOME / ".cache" / "blacklab-desktop") / "rea" / digest[:16]
    key = hashlib.sha1(json.dumps(argv[:1] + argv[2:]).encode()).hexdigest()[:16] + ".json"
    if (cache / key).exists():
        try:
            return {**json.loads((cache / key).read_text()), "cached": True}
        except ValueError:
            pass
    lock = _GHIDRA if cmd in NATIVE else threading.Lock()
    started = time.time()
    with lock:
        try:
            p = subprocess.run([tc["node"], str(tc["rea_js"]), *argv], capture_output=True, text=True, stdin=subprocess.DEVNULL,
                               timeout=JOB_TIMEOUT if job else TOOL_TIMEOUT, env=env(tc, job), cwd=str(tc["rea_dir"]))
        except subprocess.TimeoutExpired:
            return {"ok": False, "error": f"{cmd} ran out of time.", "remedy": "Run it as a background report (it allows longer)." if not job else ""}
    out = parse(p.stdout or p.stderr)
    out["op"], out["seconds"] = cmd, round(time.time() - started, 1)
    if out["ok"]:
        cache.mkdir(parents=True, exist_ok=True)
        os.chmod(cache.parent, 0o700)
        (cache / key).write_text(json.dumps(out))
    if C:
        C.task_step("tool", f"REA {cmd} {target.name}" + (f" {value}" if value else ""), f"{out['seconds']} s" + ("" if out["ok"] else f" · {out.get('error')}"))
    return out


META_KEYS = {"evidence_id", "subject", "provider", "analysis_profile", "predicate_type", "parameters", "confidence", "authority", "environment",
             "relationship_coverage", "evidence_links", "caller_input_path", "locations"}


def parse(text: str) -> dict:
    """REA's JSON envelope → the useful part. Failures keep REA's own message and remediation."""
    try:
        d = json.loads(text[text.index("{"):]) if "{" in (text or "") else None
    except ValueError:
        d = None
    if d is None:
        return {"ok": False, "error": (text or "REA printed nothing")[-400:].strip()}
    if not isinstance(d, dict):
        return {"ok": True, "result": d}
    if d.get("error") or (d.get("code") and d.get("message") and "normalized_result" not in d):
        remedy = (d.get("remediation") or {}).get("action", "") if isinstance(d.get("remediation"), dict) else ""
        issues = [i.get("message", "") for i in ((d.get("details") or {}).get("issues") or []) if isinstance(i, dict)]
        issues += [f"{e.get('path')}: {e.get('message')}" for e in d.get("fieldErrors") or [] if isinstance(e, dict)]
        return {"ok": False, "error": " ".join([d.get("message") or d.get("error") or "REA failed", *issues])[:600], "code": d.get("code"), "remedy": remedy[:400]}
    subject = d.get("subject") or {}
    result = d.get("normalized_result")
    if result is None:
        result = d.get("raw_result")
    if result is None:
        result = {k: v for k, v in d.items() if k not in META_KEYS}
    return {"ok": True, "result": result, "format": subject.get("format"), "arch": subject.get("architecture"),
            "limitations": [x for x in (d.get("limitations") or []) if isinstance(x, str)][:4]}


NOISE = {"artifact", "inspection_id", "manifest_id", "root_artifact_id", "substep_id", "name_bytes_base64", "name_utf8_decodable",
         "limitations", "evidence", "digest", "sha256", "local_path", "path", "caller_input_path"}


def slim(o, depth=0):
    """Drops REA's provenance fields and empty values, and caps lists, so a small model sees the findings."""
    if isinstance(o, dict):
        return {k: slim(v, depth + 1) for k, v in o.items() if k not in NOISE and v not in (None, [], {}, "")}
    if isinstance(o, list):
        items = [slim(x, depth + 1) for x in o[:25]]
        return items + ([f"... {len(o) - 25} more"] if len(o) > 25 else [])
    return o


def meaningful(cmd: str, result) -> bool:
    """False for probes that only say "not this kind of file" (a C program has no Go build info)."""
    if not isinstance(result, dict):
        return bool(result)
    if cmd == "inspect-go-binary":
        return bool(result.get("build_info"))
    if cmd == "inspect-rust-binary":
        return bool(result.get("dependencies") or result.get("packages") or result.get("audit_data"))
    return bool(slim(result))


def render(result, limit=3500) -> str:
    """Compact text for an 8B model: "0xaddr  value" lines for inventories, code as-is, JSON otherwise."""
    if isinstance(result, (dict, list)) and not (isinstance(result, list) and result and all(isinstance(x, dict) and "address" in x for x in result[:20])):
        result = slim(result)
    if isinstance(result, str):
        text = result.strip()
    elif isinstance(result, list) and result and all(isinstance(x, dict) and "address" in x for x in result[:20]):
        text = "\n".join(f"{x.get('address')}  {json.dumps(x.get('value'))[1:-1] if isinstance(x.get('value'), str) else x.get('value')}" for x in result)
    else:
        text = json.dumps(result, separators=(",", ":"), ensure_ascii=False, default=str)
    if len(text) > limit:
        text = text[:limit] + f"\n[… {len(text) - limit} more characters — narrow the query]"
    return text


def outcome(r: dict, limit=3500) -> str:
    if r["ok"]:
        return render(r["result"], limit)
    return f"REA couldn't do that: {r.get('error')}" + (f" Fix: {r['remedy']}" if r.get("remedy") else "")


def path_in(text: str) -> str | None:
    """The first file path in a request: quoted, or starting with ~ or /."""
    m = re.search(r"\"([^\"]+)\"|'([^']+)'|((?:~|/)[^\s,;]+)", text or "")
    return next((g for g in m.groups() if g), None) if m else None


def submit_job(target: Path, goal: str = "") -> dict:
    """Queues a report. Asked from the phone (directly or through the desktop AI) → the result goes back to the phone."""
    import agents
    task = C.current_task() if C else None
    origin = task.origin if task and task.origin else "BlackLab Desktop (this computer)"
    return agents.submit("reverse", f"Reverse-engineer {target.name}", {"path": str(target), "goal": (goal or "")[:300]},
                         origin=origin, private=bool(task and task.private))


# ---------------------------------------------------------------- the model's tool

ACTIONS = ("overview", "find_code", "strings", "functions", "search", "decompile", "function", "xrefs", "disassemble", "inspect", "objdump", "report", "help", "status")


def tool(args: dict) -> str:
    args = args if isinstance(args, dict) else {}
    action = str(args.get("action") or "overview").lower().strip()
    query = str(args.get("query") or args.get("address") or args.get("value") or "").strip()
    if action in ("help", "docs", "how"):
        return help_text(query)
    if action == "status":
        return status_text()
    target, why = resolve_target(args.get("path"))
    if not target:
        return why
    if action == "report":
        j = submit_job(target, query)
        return f"Started a full reverse-engineering report on {target.name} in the background (job {j['id']}). It'll be in {C._home_rel(RE_DIR) if C else RE_DIR}."
    kind = kind_of(target)
    needs_ghidra = action in ("overview", "strings", "functions", "search", "decompile", "function", "xrefs", "disassemble") and kind in NATIVE_KINDS
    if needs_ghidra and _mining():
        return "The computer is mining right now (it has every core), so I won't start Ghidra. Ask for a report and it runs when mining stops."
    if action == "overview":
        parts = [f"{target.name}: {describe(target, kind)}, {target.stat().st_size if target.is_file() else 0:,} bytes, sha256 {sha256(target)[:16]}…"]
        if kind in NATIVE_KINDS:
            parts.append("Ghidra overview: " + outcome(run("analyze", target), 1200))
        for cmd in INSPECTORS.get(kind, [])[:3] + ["inspect-artifact"]:
            r = run(cmd, target)
            if r["ok"] and meaningful(cmd, r["result"]):
                parts.append(f"{cmd}: {render(r['result'], 900)}")
        parts.append("Next: strings / functions, then decompile an address from those lists.")
        return "\n".join(parts)[:4000]
    if kind not in NATIVE_KINDS and action in ("strings", "functions", "search", "decompile", "function", "xrefs", "disassemble"):
        return f"{target.name} is {kind}, not a native program — use action 'inspect' (or 'report')."
    if action in ("find_code", "code_for", "find"):
        return find_code(target, query)
    if action == "functions":
        r = run("search", target, query or ".", kind="procedures", regex=not query)
        if r["ok"] and isinstance(r["result"], list):  # the program's own functions first; imports as one line
            ext = [x for x in r["result"] if "EXTERNAL" in str(x.get("address", "")) + str(x.get("value", ""))]
            own = [x for x in r["result"] if x not in ext]
            names = sorted({str(x.get("value", "")).split("::")[-1] for x in ext})
            return (render(own, 3000) + (f"\nImports: {', '.join(names)}" if names else ""))[:4000]
        return outcome(r)
    if action == "strings":
        return outcome(run("search", target, query or ".", kind="strings", regex=not query))
    if action == "search":
        return outcome(run("search", target, query or ".", kind=str(args.get("kind") or "strings"), regex=bool(args.get("regex"))))
    if action in ("decompile", "function", "disassemble"):
        cmd = {"decompile": "decompile", "function": "function", "disassemble": "instructions"}[action]
        r = run(cmd, target, query or "entry")
        if not r["ok"] and re.fullmatch(r"0x[0-9a-fA-F]+", query or ""):
            x = run("xrefs", target, query)  # a data address (a string): decompile the code that uses it
            refs = x["result"] if x["ok"] and isinstance(x["result"], list) else []
            if refs:
                return f"{query} is data, used by code at {refs[0]}:\n" + outcome(run(cmd, target, str(refs[0])))
            return outcome(r) + " Tip: use find_code with a word the program prints."
        if not r["ok"] and query and "unknown" in str(r.get("error", "")).lower():
            # a guessed name like print_usage: look for the behaviour instead
            return f"There's no function named {query} (stripped binaries use FUN_<address>). Code matching it:\n" + find_code(target, re.sub(r"[_-]+", " ", query))
        return outcome(r)
    if action == "xrefs":
        return outcome(run("xrefs", target, query))
    if action == "objdump":
        return outcome(run("inspect-with-objdump", target, query or "file_headers"))
    if action == "inspect":
        out = []
        for cmd in INSPECTORS.get(kind, []) + COMMON:
            r = run(cmd, target)
            if r["ok"] and not meaningful(cmd, r["result"]):
                continue
            out.append(f"{cmd}: " + (render(r["result"], 1200) if r["ok"] else f"(not available: {r.get('error', '')[:160]})"))
        return "\n".join(out)[:4000]
    return f"Unknown action {action}. Use one of: {', '.join(ACTIONS)}."


FILLER = {"print", "prints", "printed", "message", "text", "string", "strings", "decompile", "show", "shows", "uses", "used",
                      "handles", "handle", "where", "code", "part", "routine", "function", "functions", "the", "and", "for"}


def search_terms(text: str) -> list[str]:
    """The phrase itself, then its meaningful words, longest first."""
    words = sorted({w for w in re.findall(r"[\w./:-]{3,}", text) if w.lower() not in FILLER | GOAL_STOP}, key=len, reverse=True)
    return list(dict.fromkeys([text.strip(), *words]))[:5]


def describe(path: Path, kind: str) -> str:
    if kind == "elf" and path.is_file():
        with open(path, "rb") as f:
            head = f.read(4096)
        etype = int.from_bytes(head[16:18], "little") if len(head) > 18 else 0
        if etype == 3:
            return "ELF executable (position-independent)" if b"ld-linux" in head or b"ld-musl" in head else "ELF shared library"
        return {2: "ELF executable", 1: "ELF object file", 4: "ELF core dump"}.get(etype, "ELF")
    return {"pe": "Windows PE (exe/dll)", "macho": "Mach-O (Apple)", "apk": "Android app (APK)", "kmod": "Linux kernel module"}.get(kind, kind)


def func_name(code: str, fallback: str) -> str:
    m = re.search(r"^\s*[\w\s\*]+?\b(\w+)\s*\(", code or "", re.M)
    return m.group(1) if m else fallback


def find_code(target: Path, text: str, max_funcs=3, job=False) -> str:
    """Text → the strings that contain it → who references them → those functions, decompiled. One step for a small model."""
    if not text:
        return "Say what text to look for (a message, option, URL or word the program prints or uses)."
    s, hits = None, []
    for q in search_terms(text):  # small models pass whole phrases ("print usage message"): fall back to the words
        s = run("search", target, q, kind="strings", job=job)
        if not s["ok"]:
            return outcome(s)
        hits = s["result"] if isinstance(s["result"], list) else []
        if hits:
            text = q
            break
    if not hits:
        p = run("search", target, text, kind="procedures", job=job)
        procs = p["result"] if p["ok"] and isinstance(p["result"], list) else []
        if not procs:
            return f"No string or function name in {target.name} contains “{text}”. Try a shorter word, or action 'strings' to list them."
        d = run("decompile", target, str(procs[0]["address"]), job=job)
        return f"No string matches, but function {procs[0]['value']} ({procs[0]['address']}) does:\n" + outcome(d, 3200)
    out, seen = [], set()
    for h in hits[:4]:
        x = run("xrefs", target, str(h["address"]), job=job)
        refs = x["result"] if x["ok"] and isinstance(x["result"], list) else []
        if not refs:
            out.append(f"String {h['address']} “{str(h['value'])[:80]}” has no code references.")
        for ref in refs[:2]:
            d = run("decompile", target, str(ref), job=job)
            if not d["ok"] or not isinstance(d["result"], str):
                continue
            name = func_name(d["result"], str(ref))
            if name in seen:
                continue
            seen.add(name)
            out.append(f"String {h['address']} “{str(h['value'])[:80]}” is used at {ref}, in function {name}:\n{d['result'].strip()[:2400 // max_funcs * 2]}")
            if len(seen) >= max_funcs:
                break
        if len(seen) >= max_funcs:
            break
    return ("\n\n".join(out) or f"Found “{text}” but couldn't decompile the code that uses it.")[:4000]


def answer_howto(question: str) -> str:
    """A how-to question about REA, answered by the local model from REA's own docs (no guessing at flags)."""
    ref = help_text(question)
    try:
        import agents
        return agents.generate("Answer the user's question about REA (a reverse-engineering CLI) using ONLY the reference below. Give the exact "
                               "command(s) with a short explanation; if the reference doesn't cover it, say so. Mention that BlackLab can run "
                               "the read-only ones for them (e.g. 'reverse engineer ~/path/file').\n\nREFERENCE\n" + ref[:9000]
                               + "\n\nQUESTION\n" + question, max_tokens=500, temperature=0.1) or ref
    except Exception:
        return ref


def check_code(reply: str, outputs: list[str]) -> str:
    """Flags code blocks in a reply that aren't in any tool output: an 8B model will happily 'simplify' (invent) code."""
    blocks = re.findall(r"```[\w+-]*\n(.*?)```", reply or "", re.S)
    if not blocks:
        return reply
    norm = lambda s: re.sub(r"[^A-Za-z0-9]", "", s)  # reflowed or re-quoted real code still matches; invented code doesn't
    seen = norm("\n".join(outputs))
    for b in blocks:
        lines = [norm(ln) for ln in b.splitlines() if not ln.strip().startswith(("//", "/*", "#"))]
        lines = [ln for ln in lines if len(ln) > 8]
        if lines and sum(ln in seen for ln in lines) / len(lines) < 0.6:
            return reply + ("\n\n⚠ That code isn't from the decompiler — the model wrote it, so treat it as a guess. "
                            "Ask “find the code that uses <text> in <file>” to see the real thing.")
    return reply


# ---------------------------------------------------------------- REA's own docs

def ensure_reference(force=False) -> Path | None:
    """Copy REA's skill guide and references, plus its command manifest, into the workspace (indexed for search)."""
    tc = toolchain()
    version = rea_version(tc)
    if not version or not tc["rea_js"]:
        return None
    stamp = REF_DIR / ".version"
    if not force and stamp.exists() and stamp.read_text().strip() == version:
        return REF_DIR
    pkg = tc["rea_dir"] / "node_modules" / "rea-agents"
    REF_DIR.mkdir(parents=True, exist_ok=True)
    skill = pkg / "skills" / "reverse-engineer-anything"
    for src in [skill / "SKILL.md", *sorted((skill / "references").glob("*.md")), pkg / "README.md", pkg / "DISCLAIMER.md"]:
        if src.is_file():
            name = "REA skill guide.md" if src.name == "SKILL.md" else ("REA README.md" if src.name == "README.md" else
                                                                         "REA " + src.stem.replace("-", " ") + ".md")
            (REF_DIR / name).write_text(src.read_text(errors="ignore"))
    try:
        p = subprocess.run([tc["node"], str(tc["rea_js"]), "--llms"], capture_output=True, text=True, timeout=120, env=env(tc), cwd=str(tc["rea_dir"]))
        if p.stdout.strip():
            (REF_DIR / "REA commands.md").write_text(p.stdout)
    except (OSError, subprocess.SubprocessError):
        pass
    (REF_DIR / "How BlackLab uses REA.md").write_text(BLACKLAB_NOTES.format(version=version, allowed=", ".join(sorted([*NATIVE, *STATIC]))))
    stamp.write_text(version)
    return REF_DIR


BLACKLAB_NOTES = """# How BlackLab uses REA ({version})

REA (github.com/morluto/rea) runs on this computer for the desktop AI and the phone. BlackLab only runs
read-only commands that inspect a file without executing it: {allowed}, plus objdump headers/symbols/disassembly.
Anything that launches, debugs, instruments or captures a program, talks to a device, installs or updates
software, or edits agent settings is refused — run those yourself in a terminal (`rea <command> --help`).

Ask the desktop AI or the phone:
- "reverse engineer ~/Downloads/thing.bin" — a full report in the background (Reverse Engineering/<date> <name>/report.md)
- "reverse engineer ~/Downloads/thing.bin and find how it checks the license" — the same, aimed at a question
- "what strings are in ~/Downloads/thing.bin", "decompile 0x401000 in ~/Downloads/thing.bin", "who calls 0x4012a0"
- "how do I inspect an APK with REA?" — answers from these reference notes
- On the phone: any file › ⋮ › Reverse-engineer on computer

Only analyze software you own or are allowed to examine. Commercial engines (IDA, Hopper, JEB) need your own licence.
"""


def help_text(query: str) -> str:
    ensure_reference()
    words = [w for w in re.findall(r"[a-z0-9.-]{3,}", query.lower()) if w not in {"how", "the", "with", "rea", "can", "what", "does", "use"}]
    sections = []
    for f in sorted(REF_DIR.glob("*.md")) if REF_DIR.is_dir() else []:
        for block in re.split(r"\n(?=#{1,3} )", f.read_text(errors="ignore")):
            low = block.lower()
            score = sum(low.count(w) for w in words) + (3 if words and any(w in low[:120] for w in words) else 0)
            if score or not words:
                sections.append((score, f.stem, block.strip()))
    sections.sort(key=lambda s: -s[0])
    head = (f"BlackLab runs these REA actions for you: {', '.join(ACTIONS)}. Reference: {C._home_rel(REF_DIR) if C else REF_DIR}.\n\n")
    body = "\n\n".join(f"[{name}]\n{text[:1400]}" for _, name, text in sections[:3])
    return (head + (body or "REA's reference notes aren't here yet — run the install.")).strip()[:4000]


def status() -> dict:
    tc = toolchain()
    optional = {t: bool(shutil.which(t)) for t in ("objdump", "gdb", "rizin", "apktool", "jadx", "wasm-objdump", "binwalk", "tshark")}
    return {"rea": rea_version(tc), "node": tc["node"], "java_home": tc["java_home"], "ghidra": Path(tc["ghidra"]).name if tc["ghidra"] else None,
            "ready": bool(tc["rea_js"] and tc["node"]), "decompiler": bool(tc["ghidra"] and tc["java_home"]), "optional": optional,
            "workspace": str(RE_DIR), "reports": [p.name for p in sorted(RE_DIR.glob("20*"), reverse=True)[:5]] if RE_DIR.is_dir() else []}


def status_text() -> str:
    s = status()
    if not s["ready"]:
        return "REA isn't installed yet. On the computer run: python3 desktop/companion/reverse.py install"
    missing = [k for k, v in s["optional"].items() if not v]
    return (f"Reverse engineering is ready: REA {s['rea']}" + (f", decompiler {s['ghidra']}" if s["decompiler"] else ", no Ghidra (static inspection only)")
            + f". Reports go to {C._home_rel(RE_DIR) if C else RE_DIR}." + (f" Recent: {', '.join(s['reports'][:3])}." if s["reports"] else "")
            + (f" Optional tools not installed: {', '.join(missing)}." if missing else ""))


# ---------------------------------------------------------------- the "reverse" job

INTERESTING = re.compile(r"usage|version|https?://|password|passwd|token|secret|api[_ -]?key|licen[cs]e|serial|activation|trial|expired|"
                         r"error|failed|config|socket|connect|/etc/|\.ini|registry|HKEY_|crypt|aes|rsa|sha|md5|base64|update|download|"
                         r"admin|debug|login|auth", re.I)
REPORT_SCHEMA = {"type": "object", "properties": {
    "summary": {"type": "string"}, "purpose": {"type": "string"},
    "behaviors": {"type": "array", "items": {"type": "string"}},
    "notable_functions": {"type": "array", "items": {"type": "object", "properties": {"address": {"type": "string"}, "role": {"type": "string"}}, "required": ["address", "role"]}},
    "security_notes": {"type": "array", "items": {"type": "string"}},
    "answer": {"type": "string"},
    "next_steps": {"type": "array", "items": {"type": "string"}}},
    "required": ["summary", "purpose", "behaviors", "notable_functions", "security_notes", "next_steps"]}


GOAL_STOP = {"does", "this", "that", "what", "when", "where", "which", "work", "works", "with", "from", "into", "have", "find", "show",
             "program", "binary", "file", "function", "code", "mode", "about", "there", "their", "they", "them", "then", "than"}


def pick_strings(strings: list, goal: str = "", n=5, imports=()) -> list[dict]:
    """The strings most worth following into the code: ones the goal names first, then tell-tale ones. Symbol names
    (perror, libc.so.6) are skipped: they live in the import tables, not in the program's logic."""
    words = [w for w in re.findall(r"[a-z0-9_]{4,}", (goal or "").lower()) if w not in GOAL_STOP]
    names = {str(i) for i in imports}
    seen, out = set(), []
    def ok(s):
        v = s.get("value") if isinstance(s, dict) else None
        return (isinstance(v, str) and 4 <= len(v) <= 300 and not v.startswith(("GLIBC", "__", ".", "_Z")) and v not in names
                and not re.fullmatch(r"[\w.@]+\.so(\.\d+)*|[A-Za-z_]\w{0,30}", v))
    for rank in (lambda v: any(w in v.lower() for w in words), lambda v: bool(INTERESTING.search(v))):
        for s in strings:
            if ok(s) and s["value"] not in seen and rank(s["value"]):
                seen.add(s["value"])
                out.append(s)
                if len(out) >= n:
                    return out
    return out


def _slug(s: str, n=60) -> str:
    return re.sub(r"[^\w .,()+-]", "", s).strip()[:n] or "target"


def run_job(j: dict) -> dict:
    params = j.get("params") or {}
    target, why = resolve_target(j.get("file") or params.get("path"))
    if not target:
        raise RuntimeError(why)
    goal = str(params.get("goal") or "").strip()[:300]
    progress = (lambda t: (j.__setitem__("progress", t), _save_job())) if j else (lambda t: None)
    while _mining():
        progress("Waiting: the computer is mining")
        time.sleep(60)
    kind, digest = kind_of(target), sha256(target)
    ws = RE_DIR / f"{dt.date.today():%Y-%m-%d} {_slug(target.stem)}"
    for d in (RE_DIR, ws, ws / "evidence", ws / "code"):
        d.mkdir(parents=True, exist_ok=True)
        os.chmod(d, 0o700)
    facts: list[str] = [f"File: {target.name} ({kind}, {target.stat().st_size if target.is_file() else 0:,} bytes, sha256 {digest})"]
    if goal:
        facts.append(f"User's question: {goal}")

    def keep(name, r):
        (ws / "evidence" / f"{name}.json").write_text(json.dumps(r, indent=1, default=str))

    for cmd in INSPECTORS.get(kind, []) + COMMON:
        progress(f"Inspecting: {cmd}")
        r = run(cmd, target, job=True)
        keep(cmd, r)
        if r["ok"] and meaningful(cmd, r["result"]):
            if cmd == "inspect-byte-entropy" and isinstance(r["result"], dict):
                e = r["result"].get("file_entropy")
                facts.append(f"[entropy] {e} (above 7.2 suggests packed or encrypted data)")
            elif cmd != "inspect-artifact":  # its inventory is in evidence/; too long for the model
                facts.append(f"[{cmd}] {render(r['result'], 600)}")
    code_blocks: list[tuple[str, str]] = []
    if kind in NATIVE_KINDS and toolchain()["ghidra"]:
        progress("Ghidra: analyzing")
        r = run("analyze", target, job=True)
        keep("analyze", r)
        if r["ok"]:
            res = r["result"] if isinstance(r["result"], dict) else {}
            facts.append(f"[ghidra] format {r.get('format')}, arch {r.get('arch')}, image base {res.get('image_base')}, "
                         f"{res.get('procedure_count')} functions, {res.get('string_count')} strings, {res.get('segment_count')} segments")
        progress("Ghidra: strings and functions")
        strings = run("search", target, ".", kind="strings", regex=True, job=True)
        procs = run("search", target, ".", kind="procedures", regex=True, job=True)
        s_list = strings["result"] if strings["ok"] and isinstance(strings["result"], list) else []
        p_list = procs["result"] if procs["ok"] and isinstance(procs["result"], list) else []
        (ws / "strings.txt").write_text(render(s_list, 10 ** 8))
        (ws / "functions.txt").write_text(render(p_list, 10 ** 8))
        imports = [str(p.get("value", "")).split("::")[-1] for p in p_list if "EXTERNAL" in str(p.get("address", ""))]
        internal = [p for p in p_list if "EXTERNAL" not in str(p.get("address", "")) + str(p.get("value", ""))]
        facts.append(f"[imports] {', '.join(imports[:80])}")
        facts.append(f"[functions] {len(internal)} internal; named: {', '.join(str(p.get('value')) for p in internal if not str(p.get('value', '')).startswith(('FUN_', 'thunk_')))[:600]}")
        picks = pick_strings(s_list, goal, imports=imports)
        facts.append("[strings worth following] " + " | ".join(f"{s['address']} {s['value'][:120]!r}" for s in picks))
        targets: list[str] = []
        names = {str(p.get("value")): str(p.get("address")) for p in internal}
        main = next((names[n] for n in ("main", "WinMain", "wWinMain", "wmain", "DllMain") if n in names), None)
        if not main and "entry" in names:  # stripped ELF: entry → __libc_start_main(main, …)
            progress("Ghidra: finding main")
            e = run("decompile", target, "entry", job=True)
            m = re.search(r"__libc_start_main\(\s*(?:FUN_)?([0-9a-fA-F]{6,16}|\w+)", e["result"] if e["ok"] and isinstance(e["result"], str) else "")
            if m:
                main = ("0x" + m.group(1).lstrip("0").lower()) if re.fullmatch(r"[0-9a-fA-F]{6,16}", m.group(1)) else m.group(1)
        if main:
            targets.append(main)
            facts.append(f"[main] {main}")
        for s in picks:
            progress(f"Ghidra: who uses {s['value'][:40]!r}")
            x = run("xrefs", target, str(s["address"]), job=True)
            if x["ok"] and isinstance(x["result"], list) and x["result"]:
                targets.append(str(x["result"][0]))
            if len(targets) >= 5:
                break
        done = set()
        for t in targets:
            progress(f"Ghidra: decompiling {t}")
            d = run("decompile", target, t, job=True)
            if not d["ok"] or not isinstance(d["result"], str):
                continue
            fname = func_name(d["result"], t)
            if fname in done:
                continue
            done.add(fname)
            (ws / "code" / f"{_slug(fname)}.c").write_text(d["result"])
            code_blocks.append((fname, d["result"]))
    progress("Writing the report")
    budget = 9000 if C is None else max(6000, (C.num_ctx() - 2200) * 3)
    evidence = "\n".join(facts)
    code_text = "\n\n".join(f"// {n}\n{c[:2500]}" for n, c in code_blocks)
    prompt = ("You are a careful reverse engineer. From the evidence below (from REA, Ghidra and static parsers), explain what this "
              "program is and does. Only state what the evidence shows; say 'unclear' instead of guessing. Refer to functions by "
              "address or name. next_steps are concrete follow-ups (e.g. 'decompile 0x1234 to see how the key is checked').\n"
              + (f"Answer the user's question in 'answer': {goal}\n" if goal else "")
              + "\nEVIDENCE\n" + evidence[: budget // 2] + "\n\nDECOMPILED CODE\n" + code_text[: budget // 2])
    try:
        import agents
        rep = agents.generate_json(prompt, REPORT_SCHEMA, max_tokens=1400, temperature=0.1)
    except Exception as e:  # the facts are still worth having without the model
        rep = {"summary": f"(The local model couldn't write the summary: {e})"}
    md = render_report(target, kind, digest, goal, rep, facts, code_blocks, ws)
    (ws / "report.md").write_text(md)
    if C:
        C.task_file("created", ws / "report.md")
    rel = C._home_rel(ws / "report.md") if C else str(ws / "report.md")
    return {"text": md, "title": f"Reverse engineering · {target.name}", "doc": rel, "folder": str(ws),
            "notify": {"title": f"Reverse-engineering report ready: {target.name}"[:80], "text": (rep.get("answer") or rep.get("summary") or "")[:300]}}


def render_report(target: Path, kind: str, digest: str, goal: str, rep: dict, facts: list[str], code: list[tuple[str, str]], ws: Path) -> str:
    def bullets(xs):
        return "\n".join(f"- {x}" for x in xs if str(x).strip()) or "- (none found)"
    lines = [f"# Reverse engineering · {target.name}", "",
             f"_{dt.datetime.now():%b %d %Y %I:%M %p} · {kind} · sha256 `{digest[:16]}…` · REA {rea_version() or '?'} + Ghidra · local model, offline_", ""]
    if goal:
        lines += ["## Your question", "", goal, "", "## Answer", "", rep.get("answer") or "unclear from this pass", ""]
    lines += ["## Summary", "", rep.get("summary", ""), "", "## What it is for", "", rep.get("purpose", ""), "",
              "## Behaviours", "", bullets(rep.get("behaviors") or []), "",
              "## Notable functions", "", bullets(f"**{f.get('address')}** — {f.get('role')}" for f in rep.get("notable_functions") or [] if isinstance(f, dict)), "",
              "## Security notes", "", bullets(rep.get("security_notes") or []), "",
              "## Next steps", "", bullets(rep.get("next_steps") or []), "",
              "## Evidence", "", *[f"- {f[:400]}" for f in facts], ""]
    for name, c in code[:3]:
        lines += [f"### {name}", "", "```c", c.strip()[:3000], "```", ""]
    lines += [f"Workspace: {ws} (evidence/*.json, strings.txt, functions.txt, code/*.c). The summary was written by a small local model "
              "from the evidence above — check the code before relying on it."]
    return "\n".join(lines)


def _save_job():
    try:
        import agents
        agents._save()
    except Exception:
        pass


# ---------------------------------------------------------------- setup + install

def setup(core):
    global C, RE_DIR, REF_DIR
    C = core
    RE_DIR = C._cfg_dir("reverse_dir", C.AI_DIR / "Reverse Engineering")
    REF_DIR = RE_DIR / "REA reference"
    C.DOC_ROOTS.append(REF_DIR)
    for k in ("reverse engineering", "reverse", "re reports"):
        C.FOLDERS.setdefault(k, str(RE_DIR.relative_to(C.HOME)) if str(RE_DIR).startswith(str(C.HOME) + "/") else "")
    threading.Thread(target=lambda: _quiet(ensure_reference), daemon=True, name="rea-ref").start()


def _quiet(fn):
    try:
        fn()
    except Exception as e:
        print(f"reverse: {e}", file=sys.stderr, flush=True)


def install(version=REA_VERSION) -> int:
    """Installs REA into its private folder with the toolchain's Node (no global npm, no install scripts)."""
    tc = toolchain()
    if not tc["node"]:
        print("No Node.js found. Put Node 24 in ~/.local/blacklab-toolchain (nodejs.org, verify SHASUMS256).")
        return 1
    npm = Path(tc["node"]).parent / "npm"
    tc["rea_dir"].mkdir(parents=True, exist_ok=True)
    cmd = [str(npm) if npm.exists() else "npm", "install", "--prefix", str(tc["rea_dir"]), "--ignore-scripts", "--no-fund", "--no-audit", f"rea-agents@{version}"]
    print("$", " ".join(cmd))
    rc = subprocess.call(cmd, env={**os.environ, "PATH": f"{Path(tc['node']).parent}:{os.environ.get('PATH', '')}"})
    if rc == 0:
        print("REA", rea_version(), "installed.", "Ghidra:", toolchain()["ghidra"] or "missing (github.com/NationalSecurityAgency/ghidra, 12.1.x)")
    return rc


if __name__ == "__main__":
    if sys.argv[1:2] == ["install"]:
        v = sys.argv[sys.argv.index("--version") + 1] if "--version" in sys.argv else REA_VERSION
        sys.exit(install(v))
    print(json.dumps(status(), indent=1))
