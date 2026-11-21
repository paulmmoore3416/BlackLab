#!/usr/bin/env python3
"""BlackLab autopilot — the nightly system check, the after-restart check and the link watchdog.

    blacklab_health.py --nightly   full scan (03:00): desktop AI, phone link, services, system, storage, data
    blacklab_health.py --boot      after a restart: put the machine in BlackLab-only mode, wait for the stack,
                                   add an "After the restart" section to tonight's report and send it again
    blacklab_health.py --watch     every few minutes: keep the companion answering and the phone linked
    blacklab_health.py --print     run the full scan and print it (sends nothing)

The report is written to ~/Desktop/BlackLab AI/Documents/System Health/ and handed to the BlackLab Desktop
companion (POST /local/health), which passes it to the phone; BlackLab shows it the first time it opens.

What counts as "BlackLab and its services" — and what gets stopped at boot — lives in
~/.config/blacklab-desktop/autopilot.json (never in this repo). Stdlib only; nothing here prints keys.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path.home()
REPO = Path(__file__).resolve().parents[2]
CFG_DIR = HOME / ".config" / "blacklab-desktop"
STATE = HOME / ".local" / "state" / "blacklab-desktop" / "health"
COMP = f"http://127.0.0.1:{os.environ.get('BLACKLAB_DESKTOP_PORT', '8770')}"
OLLAMA = "http://127.0.0.1:11434"
ADB = shutil.which("adb") or str(HOME / "Android/Sdk/platform-tools/adb")

DEFAULT_PROFILE = {
    # user units that make up BlackLab: the companion and what it calls (vault sync, recall memory, cameras)
    "keep_user_units": ["blacklab-desktop.service", "blacklab-syncthing.service", "omnix-kb-core.service", "nightjar.service"],
    # system services BlackLab needs (checked, never stopped)
    "keep_system_services": ["ollama.service", "tailscaled.service"],
    # other projects: stopped and kept off at boot (user units / docker containers / system services)
    "stop_user_units": [],
    "stop_containers": [],
    "stop_system_services": [],
    "scan_time": "03:00",
    "reboot_time": "04:00",
}

OK, WARN, FAIL, INFO = "ok", "warn", "fail", "info"
RANK = {OK: 0, INFO: 0, WARN: 1, FAIL: 2}


# ---------------------------------------------------------------------------------------------- helpers

def sh(cmd: list[str] | str, timeout: float = 30, ok_codes=(0,)) -> tuple[int, str]:
    try:
        r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + ("" if r.returncode in ok_codes else (r.stderr or ""))
    except subprocess.TimeoutExpired:
        return 124, "timed out"
    except FileNotFoundError:
        return 127, "not installed"


def http(url: str, body: dict | None = None, headers: dict | None = None, timeout: float = 5, method: str | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data is not None else "GET"))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        try:
            return r.status, json.loads(raw or b"null")
        except ValueError:
            return r.status, raw[:200].decode("utf-8", "replace")


def local(path: str, body: dict | None = None, timeout: float = 8):
    return http(COMP + path, body, {"X-BlackLab": "1"}, timeout)


def load_json(p: Path, default):
    try:
        return json.loads(p.read_text())
    except Exception:
        return default


def write_private(p: Path, text: str):
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.replace(tmp, p)


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit in ("B", "KB") else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def ago(ts: float) -> str:
    s = max(0, time.time() - ts)
    if s < 90:
        return "just now"
    if s < 5400:
        return f"{s / 60:.0f} min ago"
    if s < 172800:
        return f"{s / 3600:.0f} h ago"
    return f"{s / 86400:.0f} days ago"


def profile() -> dict:
    p = dict(DEFAULT_PROFILE)
    p.update(load_json(CFG_DIR / "autopilot.json", {}))
    return p


def companion_version_in_repo() -> str:
    m = re.search(r'^VERSION\s*=\s*"([^"]+)"', (REPO / "desktop/companion/blacklab_desktop.py").read_text(), re.M)
    return m.group(1) if m else "?"


def app_version_in_repo() -> str:
    m = re.search(r'versionName\s*=\s*"([^"]+)"', (REPO / "app/build.gradle.kts").read_text())
    return m.group(1) if m else "?"


class Scan:
    def __init__(self):
        self.checks: list[dict] = []
        self.fixed: list[str] = []
        self.facts: dict = {}

    def add(self, area: str, status: str, title: str, detail: str = "", fix: str = ""):
        self.checks.append({"area": area, "status": status, "title": title, "detail": detail, "fix": fix})

    def guarded(self, area: str, fn):
        try:
            fn(self)
        except Exception as e:  # a broken check is itself a finding, never a crash
            self.add(area, WARN, f"Check didn't finish: {fn.__name__}", f"{type(e).__name__}: {e}"[:300])

    @property
    def status(self) -> str:
        worst = max((RANK[c["status"]] for c in self.checks), default=0)
        return {0: OK, 1: WARN, 2: FAIL}[worst]


# ---------------------------------------------------------------------------------------------- desktop AI

def check_ollama(s: Scan):
    A = "Desktop AI"
    try:
        _, v = http(OLLAMA + "/api/version", timeout=4)
        s.add(A, OK, f"Ollama {v.get('version', '')} is running")
    except Exception as e:
        s.add(A, FAIL, "Ollama isn't answering", str(e)[:200], "The 04:00 restart starts it again; if it stays down: sudo systemctl restart ollama")
        return
    cfg = load_json(CFG_DIR / "config.json", {})
    model = cfg.get("model") or "qwen3:8b"
    _, tags = http(OLLAMA + "/api/tags", timeout=6)
    names = [m["name"] for m in tags.get("models", [])]
    s.facts["models"] = len(names)
    if model not in names:
        s.add(A, FAIL, f"The assistant's model {model} isn't downloaded", fix=f"./daddyshome {model} pulls it")
        return
    _, ps = http(OLLAMA + "/api/ps", timeout=6)
    loaded = {m["name"]: m for m in ps.get("models", [])}
    if model in loaded:
        vram = (loaded[model].get("size_vram") or 0) / 1048576
        s.add(A, OK, f"{model} is loaded and pinned", f"{vram:.0f} MB on the GPU")
    else:
        s.add(A, WARN, f"{model} isn't loaded right now", "The first answer will be slow until it loads.", "The companion re-pins it; ./daddyshome loads it now")
    # end to end: can the model actually answer? (same num_ctx as the companion, so nothing reloads)
    t0 = time.time()
    try:
        _, r = http(OLLAMA + "/api/generate", {"model": model, "prompt": "Reply with the single word READY.", "stream": False,
                                               "keep_alive": cfg.get("keep_alive", -1), "think": False,
                                               "options": {"num_ctx": int(cfg.get("num_ctx") or 8192), "num_predict": 8}}, timeout=240)
        took = time.time() - t0
        answer = (r.get("response") or "").strip()
        s.facts["ai_answer_s"] = round(took, 1)
        if answer:
            s.add(A, OK if took < 60 else WARN, f"The assistant answered a test question in {took:.1f} s",
                  fix="" if took < 60 else "Slow — something else may be using the GPU or memory")
        else:
            s.add(A, WARN, "The model replied with nothing", f"{took:.1f} s")
    except Exception as e:
        s.add(A, FAIL, "The model couldn't answer a test question", str(e)[:200], "Run ./daddyshome; check: journalctl -u ollama -n 50")
    embed = [n for n in names if "embed" in n]
    s.add(A, OK if embed else WARN, "Embedding model for vault recall: " + (embed[0] if embed else "missing"),
          fix="" if embed else "ollama pull nomic-embed-text")


def check_companion(s: Scan):
    A = "Desktop AI"
    try:
        _, st = local("/local/status", timeout=6)
    except Exception as e:
        rc, _ = sh(["systemctl", "--user", "restart", "blacklab-desktop.service"], 20)
        time.sleep(6)
        try:
            _, st = local("/local/status", timeout=6)
            s.fixed.append("The BlackLab Desktop companion wasn't answering — restarted it")
        except Exception:
            s.add(A, FAIL, "BlackLab Desktop companion is down", str(e)[:200], "journalctl --user -u blacklab-desktop -n 80")
            return
    s.facts["companion"] = st
    running, repo = st.get("version", "?"), companion_version_in_repo()
    if running == repo:
        s.add(A, OK, f"BlackLab Desktop companion {running} is running")
    else:
        s.add(A, WARN, f"Companion is running {running} but {repo} is installed",
              "It hasn't restarted since the last update.", "The 04:00 restart (./daddyshome) loads the new version")
    kw = (st.get("keep_warm") or {}).get("last", "")
    if kw and kw not in ("ok", "mining") and not kw.startswith("loaded"):
        s.add(A, WARN, f"Keep-warm says: {kw}", fix="./daddyshome")
    ag = st.get("agents") or {}
    s.add(A, OK if ag.get("whisper") else WARN, "Offline transcription (Whisper): " + ("ready" if ag.get("whisper") else "not installed"),
          fix="" if ag.get("whisper") else "./daddyshome --install-whisper")
    s.add(A, OK if ag.get("vision") else INFO, "Nameplate reader: " + (ag.get("vision") or "not installed"))
    try:
        _, jobs = local("/local/jobs", timeout=6)
        day = time.time() - 86400
        failed = [j for j in jobs.get("jobs", []) if j.get("status") == "failed" and (j.get("finished") or 0) > day]
        stuck = [j for j in jobs.get("jobs", []) if j.get("status") == "running" and time.time() - (j.get("started") or time.time()) > 3 * 3600]
        if failed:
            s.add(A, WARN, f"{len(failed)} desktop job(s) failed in the last 24 h",
                  "; ".join(f"{j.get('kind')}: {(j.get('error') or '')[:80]}" for j in failed[:4]))
        else:
            s.add(A, OK, "No desktop jobs failed in the last 24 h")
        if stuck:
            s.add(A, WARN, f"{len(stuck)} job(s) running for more than 3 hours", ", ".join(j.get("title", "")[:40] for j in stuck[:3]),
                  "The 04:00 restart clears them; they resume from the queue")
    except Exception:
        pass
    # the companion's own code must import cleanly (a broken edit would stop it at the next restart)
    bad = []
    for f in sorted((REPO / "desktop/companion").glob("*.py")):
        rc, out = sh([sys.executable, "-m", "py_compile", str(f)], 30)
        if rc:
            bad.append(f.name)
    s.add(A, FAIL if bad else OK, "Companion code compiles" if not bad else "Companion code has errors: " + ", ".join(bad),
          fix="" if not bad else "Fix before the restart, or the companion won't start")


# ---------------------------------------------------------------------------------------------- phone link

def tailscale_status() -> dict:
    rc, out = sh(["tailscale", "status", "--json"], 10)
    return json.loads(out) if rc == 0 and out.strip().startswith("{") else {}


def check_phone(s: Scan):
    A = "Phone link"
    st = s.facts.get("companion") or {}
    secrets = load_json(CFG_DIR / "secrets.json", {})
    tok = secrets.get("phone_ai_token") or ""
    if not tok:
        s.add(A, FAIL, "No phone pairing key on this computer", fix="./daddyshome --repair with the phone plugged in")
    else:
        try:
            code, _ = http(COMP + "/phone/status", headers={"Authorization": "Bearer " + tok}, timeout=6)
            s.add(A, OK, "Phone AI endpoint answers with the shared key")
        except Exception as e:
            s.add(A, FAIL, "Phone AI endpoint refused the shared key", str(e)[:150], "./daddyshome --repair")
    seen = (st.get("phone_ai") or {}).get("last_seen") or 0
    phone = (st.get("phone_ai") or {}).get("phone") or "the phone"
    if seen:
        age = time.time() - seen
        s.add(A, OK if age < 6 * 3600 else WARN if age < 3 * 86400 else FAIL, f"{phone} last checked in {ago(seen)}",
              fix="" if age < 6 * 3600 else "Open BlackLab on the phone; check Tailscale is on there")
    else:
        s.add(A, WARN, "The phone hasn't checked in since the companion started",
              "Normal right after a restart — it checks in the next time BlackLab opens.")
    # Tailscale: the always-on path (USB only works while plugged in)
    ts = tailscale_status()
    if not ts:
        s.add(A, FAIL, "Tailscale isn't answering on this computer", fix="sudo systemctl restart tailscaled")
    else:
        me = ts.get("Self") or {}
        s.add(A, OK if me.get("Online") else FAIL, "This computer is " + ("online" if me.get("Online") else "OFFLINE") + " on the tailnet")
        tn = (st.get("tailnet") or {}).get("url") or ""
        s.add(A, OK if tn.startswith("http") else WARN, "Companion listens on the tailnet (phone API only)" if tn.startswith("http")
              else "Companion isn't on the tailnet: " + str((st.get("tailnet") or {}).get("note") or "off"),
              fix="" if tn.startswith("http") else "Run ./daddyshome once Tailscale is up")
        phones = [p for p in (ts.get("Peer") or {}).values() if (p.get("OS") or "").lower() == "android"]
        for p in phones:
            name = p.get("HostName") or "phone"
            if p.get("Online"):
                s.add(A, OK, f"{name} is online on the tailnet — the phone reaches this AI from anywhere")
            else:
                last = p.get("LastSeen") or ""
                try:
                    when = ago(dt.datetime.fromisoformat(last.replace("Z", "+00:00")).timestamp())
                except Exception:
                    when = "a while ago"
                s.add(A, WARN, f"{name} is offline on the tailnet (last seen {when})",
                      "Away from USB, the phone can't reach the desktop AI until Tailscale is on.",
                      "Phone: open Tailscale and turn it on (set it as Always-on VPN in Android settings)")
        funnel = sh(["tailscale", "funnel", "status"], 10)[1]
        ports = sorted(set(re.findall(r"ts\.net:(\d+) \(Funnel on\)", funnel)))
        if ports:
            s.add("Security", WARN, "Tailscale Funnel is publishing port(s) " + ", ".join(ports) + " to the public internet",
                  "Not part of BlackLab (BlackLab is tailnet-only).", "If it's not needed: tailscale funnel --https=" + ports[0] + " off")
    # USB link
    rc, out = sh([ADB, "devices"], 10)
    devs = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device") and not l.startswith("emulator-")]
    if devs:
        rc2, rev = sh([ADB, "-s", devs[0], "reverse", "--list"], 10)
        s.add(A, OK if "8770" in rev else WARN, "Phone on USB " + ("and linked both ways" if "8770" in rev else "but not linked"),
              fix="" if "8770" in rev else "./daddyshome")
    else:
        s.add(A, INFO, "No phone on USB right now (the tailnet link covers it)")
    # app version on the phone vs this repo's release
    want = app_version_in_repo()
    apk = REPO / "releases" / f"BlackLab-v{want}.apk"
    if apk.exists():
        s.facts["latest_apk"] = want


# ---------------------------------------------------------------------------------------------- services & profile

def unit_state(unit: str, user: bool) -> tuple[str, str]:
    base = ["systemctl"] + (["--user"] if user else [])
    a = sh(base + ["is-active", unit], 10, ok_codes=(0, 3, 4))[1].strip().splitlines()
    e = sh(base + ["is-enabled", unit], 10, ok_codes=(0, 1, 4))[1].strip().splitlines()
    return (a[0] if a else "unknown"), (e[0] if e else "unknown")


def check_services(s: Scan):
    A, P = "Services", "BlackLab-only mode"
    prof = profile()
    for u in prof["keep_user_units"]:
        act, en = unit_state(u, True)
        if act == "active" and en in ("enabled", "linked"):
            s.add(A, OK, f"{u.removesuffix('.service')} is running and starts at boot")
        elif act == "active":
            s.add(A, WARN, f"{u.removesuffix('.service')} is running but won't start at boot ({en})", fix=f"systemctl --user enable {u}")
        else:
            s.add(A, FAIL, f"{u.removesuffix('.service')} is {act}", fix=f"systemctl --user restart {u}; journalctl --user -u {u} -n 50")
    for u in prof["keep_system_services"]:
        act, en = unit_state(u, False)
        s.add(A, OK if act == "active" else FAIL, f"{u.removesuffix('.service')} (system) is {act}",
              fix="" if act == "active" else f"sudo systemctl restart {u}")
    for name, url in (("Vault sync (Syncthing)", "http://127.0.0.1:8384/rest/noauth/health"), ("Recall memory (kb-core)", "http://127.0.0.1:8100/health")):
        try:
            _, r = http(url, timeout=5)
            good = isinstance(r, dict) and str(r.get("status", "")).lower() == "ok"
            extra = f" · {r.get('documents')} documents, {r.get('pending_embeddings')} waiting to embed" if isinstance(r, dict) and "documents" in r else ""
            s.add(A, OK if good else WARN, f"{name} healthy{extra}" if good else f"{name}: {r}")
            if isinstance(r, dict) and r.get("embed_error"):
                s.add(A, WARN, f"{name} embedding error", str(r["embed_error"])[:200])
        except Exception as e:
            s.add(A, FAIL, f"{name} isn't answering", str(e)[:150])
    try:
        req = urllib.request.Request("http://127.0.0.1:8790/", method="HEAD")
        urllib.request.urlopen(req, timeout=5)
        s.add(A, OK, "Nightjar cameras (the assistant's camera tool) answering")
    except urllib.error.HTTPError:
        s.add(A, OK, "Nightjar cameras (the assistant's camera tool) answering")
    except Exception as e:
        s.add(A, WARN, "Nightjar isn't answering", str(e)[:150])
    check_syncthing_folders(s)
    # failed units anywhere
    for user in (False, True):
        out = sh(["systemctl"] + (["--user"] if user else []) + ["--failed", "--no-legend", "--plain"], 10)[1]
        failed = [l.split()[0] for l in out.splitlines() if l.strip()]
        if failed:
            s.add(A, WARN, ("User" if user else "System") + f" units failed: {', '.join(failed[:6])}",
                  fix=f"systemctl {'--user ' if user else ''}status {failed[0]}")
    # other projects
    on = []
    for u in prof["stop_user_units"]:
        act, en = unit_state(u, True)
        if act in ("active", "activating") or en in ("enabled",):
            on.append(f"{u} ({act}, {en})")
    for c in prof["stop_containers"]:
        rc, out = sh(["docker", "inspect", "-f", "{{.State.Running}} {{.HostConfig.RestartPolicy.Name}}", c], 15)
        if rc == 0:
            running, policy = (out.split() + ["", ""])[:2]
            if running == "true" or policy not in ("no", ""):
                on.append(f"container {c} (running={running}, restart={policy})")
    for u in prof["stop_system_services"]:
        act, en = unit_state(u, False)
        if act == "active" or en == "enabled":
            on.append(f"{u} ({act}, {en}) — needs sudo")
    if on:
        s.add(P, WARN, f"{len(on)} non-BlackLab item(s) still on", "; ".join(on),
              "The boot step stops the user ones; system services need: sudo desktop/autopilot/install-system.sh")
    else:
        s.add(P, OK, "Only BlackLab and its services are set to run")


def check_syncthing_folders(s: Scan):
    cfg = HOME / ".local/state/blacklab-syncthing/config.xml"
    m = re.search(r"<apikey>([^<]+)</apikey>", cfg.read_text()) if cfg.exists() else None
    if not m:
        return
    hdr = {"X-API-Key": m.group(1)}
    try:
        _, conf = http("http://127.0.0.1:8384/rest/config/folders", headers=hdr, timeout=5)
        _, conns = http("http://127.0.0.1:8384/rest/system/connections", headers=hdr, timeout=5)
        _, errs = http("http://127.0.0.1:8384/rest/system/error", headers=hdr, timeout=5)
    except Exception:
        return
    for f in conf:
        try:
            _, st = http(f"http://127.0.0.1:8384/rest/db/status?folder={urllib.request.quote(f['id'])}", headers=hdr, timeout=8)
        except Exception:
            continue
        state, errors, need = st.get("state", "?"), st.get("errors", 0) + st.get("pullErrors", 0), st.get("needFiles", 0)
        good = state in ("idle", "scanning", "syncing") and not errors
        s.add("Data", OK if good else WARN, f"Vault sync folder '{f.get('label') or f['id']}': {state}"
              + (f", {errors} error(s)" if errors else "") + (f", {need} file(s) waiting" if need else ""))
    peers = conns.get("connections", {}) if isinstance(conns, dict) else {}
    if peers:
        up = sum(1 for c in peers.values() if c.get("connected"))
        s.add("Data", OK if up else WARN, f"Vault sync: {up} of {len(peers)} device(s) connected right now",
              "" if up else "The phone syncs when it's on the tailnet.")
    for e in (errs.get("errors") or [])[-3:] if isinstance(errs, dict) else []:
        s.add("Data", WARN, "Syncthing error", str(e.get("message", ""))[:200])


# ---------------------------------------------------------------------------------------------- system

def check_system(s: Scan):
    A = "System"
    up = float(Path("/proc/uptime").read_text().split()[0])
    load = os.getloadavg()
    s.add(A, INFO, f"Up {up / 3600:.1f} h · load {load[0]:.2f} / {load[1]:.2f} / {load[2]:.2f} on {os.cpu_count()} cores")
    mem = {l.split(":")[0]: int(l.split()[1]) * 1024 for l in Path("/proc/meminfo").read_text().splitlines()}
    avail, total = mem.get("MemAvailable", 0), mem.get("MemTotal", 1)
    swap_used = mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)
    s.add(A, OK if avail / total > 0.15 else WARN, f"Memory: {human(avail)} free of {human(total)}")
    if mem.get("SwapTotal"):
        frac = swap_used / mem["SwapTotal"]
        s.add(A, OK if frac < 0.6 else WARN, f"Swap: {human(swap_used)} of {human(mem['SwapTotal'])} used",
              "Heavy swap makes the assistant slow." if frac >= 0.6 else "", "The 04:00 restart clears it" if frac >= 0.6 else "")
    rc, out = sh(["timedatectl", "show", "-p", "NTPSynchronized", "--value"], 5)
    s.add(A, OK if out.strip() == "yes" else WARN, "Clock synchronized" if out.strip() == "yes" else "Clock isn't synchronized")
    # drive health (udisks exposes SMART without root)
    rc, tree = sh(["busctl", "tree", "--list", "org.freedesktop.UDisks2"], 10)
    for d in sorted(set(re.findall(r"/org/freedesktop/UDisks2/drives/\w+", tree))):
        rc, out = sh(["busctl", "get-property", "org.freedesktop.UDisks2", d, "org.freedesktop.UDisks2.Drive.Ata",
                      "SmartSupported", "SmartFailing", "SmartTemperature", "SmartNumBadSectors"], 10)
        vals = [l.split(" ", 1)[1] if " " in l else l for l in out.strip().splitlines()]
        name = d.rsplit("/", 1)[1].replace("_", " ")
        if rc or len(vals) < 4 or vals[0] != "true":
            continue
        failing, kelvin, bad = vals[1] == "true", float(vals[2]), int(vals[3])
        c = kelvin - 273.15 if kelvin > 200 else kelvin
        status = FAIL if failing else WARN if bad > 0 or c > 55 else OK
        s.add(A, status, f"Drive {name}: " + ("SMART says it is FAILING" if failing else "SMART healthy") + f" · {c:.0f} °C"
              + (f" · {bad} bad sector(s)" if bad else ""),
              fix="Back up now and replace the drive" if failing or bad else "")
    rc, out = sh(["nvidia-smi", "--query-gpu=name,temperature.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"], 15)
    if rc == 0:
        for line in out.strip().splitlines():
            n, t, mu, mt = [x.strip() for x in line.split(",")]
            s.add(A, OK if int(t) < 83 else WARN, f"GPU {n}: {t} °C · {mu} / {mt} MiB in use")
    elif rc != 127:
        s.add(A, WARN, "nvidia-smi failed — the GPU driver may need attention", out.strip()[:200])
    temps = []
    for z in Path("/sys/class/thermal").glob("thermal_zone*"):
        try:
            temps.append(int((z / "temp").read_text()) / 1000)
        except Exception:
            pass
    if temps:
        s.add(A, OK if max(temps) < 90 else WARN, f"Hottest CPU sensor: {max(temps):.0f} °C")
    # errors since boot / last day
    rc, out = sh("journalctl -p err -q --no-pager --since '-24h' -o cat | sort | uniq -c | sort -rn | head -5", 60)
    rc2, n = sh("journalctl -p err -q --no-pager --since '-24h' -o cat | wc -l", 60)
    count = int(n.strip() or 0) if n.strip().isdigit() else 0
    s.add(A, OK if count < 50 else WARN, f"{count} error line(s) in the system log in the last 24 h",
          "Most common: " + " | ".join(l.strip()[:90] for l in out.strip().splitlines()[:3]) if count else "")
    rc, oom = sh("journalctl -k -q --no-pager --since '-24h' -o cat | grep -ci 'out of memory\\|oom-kill' || true", 30)
    if oom.strip() not in ("", "0"):
        s.add(A, WARN, f"The kernel killed {oom.strip()} process(es) for running out of memory in the last 24 h", fix="Fewer projects running helps")
    if Path("/var/run/reboot-required").exists():
        s.add(A, INFO, "Updates are waiting for a restart (the 04:00 restart takes care of it)")
    rc, out = sh(["/usr/lib/update-notifier/apt-check"], 60)
    m = re.match(r"(\d+);(\d+)", out.strip().splitlines()[-1] if out.strip() else "")
    if m:
        n, sec = int(m.group(1)), int(m.group(2))
        s.add(A, WARN if sec else INFO if n else OK, f"{n} system update(s) available" + (f", {sec} security" if sec else ""),
              fix="sudo apt update && sudo apt upgrade" if n else "")
    # Ollama on every interface (LAN-exposed, no auth)
    rc, out = sh(["ss", "-ltnH"], 5)
    if re.search(r"(\*|0\.0\.0\.0|\[::\]):11434\b", out):
        s.add("Security", WARN, "Ollama listens on every network interface (anyone on the LAN can use it)",
              fix="sudo desktop/autopilot/install-system.sh --lock-ollama (only if nothing else on your network uses it)")


# ---------------------------------------------------------------------------------------------- storage

def dir_size(p: Path, timeout=600) -> int:
    rc, out = sh(["du", "-sxb", str(p)], timeout, ok_codes=(0, 1))
    try:
        return int(out.split()[0])
    except Exception:
        return 0


def check_storage(s: Scan, deep: bool):
    A = "Storage"
    hist = load_json(STATE / "storage.json", {})
    for mount in sorted({"/", str(HOME)}):
        du = shutil.disk_usage(mount)
        st = os.statvfs(mount)
        free_frac = du.free / du.total
        if mount == "/" or os.stat(mount).st_dev != os.stat("/").st_dev:
            prev = hist.get(mount, {}).get("used")
            delta = f" · {human(prev - du.used)} freed since the last scan" if prev and prev - du.used > 1 << 30 else \
                    f" · {human(du.used - prev)} more used since the last scan" if prev and du.used - prev > 5 << 30 else ""
            s.add(A, OK if free_frac > 0.15 else WARN if free_frac > 0.05 else FAIL,
                  f"{mount}: {human(du.free)} free of {human(du.total)} ({100 * du.used / du.total:.0f}% used){delta}")
            if st.f_files and st.f_ffree / st.f_files < 0.1:
                s.add(A, WARN, f"{mount}: running out of inodes ({st.f_ffree} left)")
            hist[mount] = {"used": du.used, "at": time.time()}
    trash = HOME / ".local/share/Trash"
    if trash.exists():
        t = dir_size(trash, 300)
        s.facts["trash"] = t
        if t > 1 << 30:
            s.add(A, INFO, f"Trash holds {human(t)} — that space isn't free until it's emptied", fix="Empty the Trash (Files › Trash › Empty)")
    if deep:
        rc, out = sh(["du", "-xb", "-d1", str(HOME)], 900, ok_codes=(0, 1))
        rows = []
        for l in out.splitlines():
            try:
                n, p = l.split("\t", 1)
                if p != str(HOME):
                    rows.append((int(n), p))
            except ValueError:
                pass
        rows.sort(reverse=True)
        s.facts["largest"] = [(human(n), p.replace(str(HOME), "~", 1)) for n, p in rows[:6]]
        ai = HOME / "Desktop" / "BlackLab AI"
        if ai.exists():
            s.add(A, INFO, f"BlackLab AI folder: {human(dir_size(ai))}")
        cache = HOME / ".cache"
        c = dir_size(cache, 300)
        if c > 20 << 30:
            s.add(A, INFO, f"~/.cache is {human(c)}", fix="Safe to trim old caches if space gets tight")
    write_private(STATE / "storage.json", json.dumps(hist))


# ---------------------------------------------------------------------------------------------- data

def check_data(s: Scan):
    A = "Data"
    for name, mode in (("config.json", None), ("secrets.json", 0o600), ("autopilot.json", None)):
        p = CFG_DIR / name
        if not p.exists():
            if name != "autopilot.json":
                s.add(A, FAIL, f"~/.config/blacklab-desktop/{name} is missing")
            continue
        try:
            json.loads(p.read_text())
            good = True
        except Exception:
            good = False
        perm = p.stat().st_mode & 0o777
        if not good:
            s.add(A, FAIL, f"{name} isn't valid JSON", fix=f"Restore ~/.config/blacklab-desktop/{name}")
        elif mode and perm & 0o077:
            os.chmod(p, mode)
            s.fixed.append(f"{name} was readable by others — locked it to you only")
    for f in ("jobs.json",):
        p = HOME / ".cache/blacklab-desktop" / f
        if p.exists():
            try:
                json.loads(p.read_text())
            except Exception:
                s.add(A, WARN, f"Companion {f} is corrupt", fix="The companion starts a fresh one; old jobs are lost")
    # release signing key: losing it means no more updates to the installed app
    ks = list((REPO / "keystore").glob("*.jks"))
    if ks and all(k.stat().st_size > 0 for k in ks):
        loose = [k.name for k in ks if k.stat().st_mode & 0o077]
        s.add(A, OK if not loose else WARN, "App signing key present" + (" (readable by others)" if loose else ""),
              fix="chmod 600 keystore/*.jks" if loose else "")
    else:
        s.add(A, FAIL, "App signing key is MISSING from keystore/", fix="Restore it from your backup — without it the phone app can't be updated")
    # newest release APK matches its checksum
    apks = sorted((REPO / "releases").glob("BlackLab-v*.apk"), key=lambda p: p.stat().st_mtime)
    if apks:
        a = apks[-1]
        sha = a.with_suffix(".apk.sha256")
        if sha.exists():
            rc, out = sh(["sha256sum", str(a)], 120)
            good = out.split()[0] == sha.read_text().split()[0] if out else False
            s.add(A, OK if good else FAIL, f"{a.name} matches its checksum" if good else f"{a.name} does NOT match its checksum")
    # repository
    rc, out = sh(["git", "-C", str(REPO), "fsck", "--connectivity-only", "--no-progress"], 300)
    s.add(A, OK if rc == 0 else FAIL, "BlackLab code repository intact" if rc == 0 else "BlackLab repository has errors", out.strip()[:200] if rc else "")
    rc, out = sh(["git", "-C", str(REPO), "status", "--porcelain", "--untracked-files=no"], 30)
    changed = [l for l in out.splitlines() if l.strip()]
    if changed:
        s.add(A, INFO, f"{len(changed)} uncommitted change(s) in the BlackLab repo")
    check_vault(s)


def check_vault(s: Scan):
    A = "Data"
    try:
        _, v = local("/local/vault/status", {}, timeout=30)
    except Exception as e:
        s.add(A, WARN, "Couldn't ask the companion about the vault", str(e)[:150])
        return
    if not v.get("exists"):
        s.add(A, FAIL, "The Obsidian vault folder is missing", v.get("path", ""))
        return
    root = Path(v["path"])
    s.add(A, OK, f"Obsidian vault: {v.get('notes', 0)} notes, {v.get('today', 0)} changed today")
    errs = list(dict.fromkeys(e.split("\n")[0][:160] for e in (v.get("errors") or [])))
    for e in errs[:4]:
        s.add(A, WARN, "Vault reported: " + e)
    # vault history (git): corrupt objects stop every commit
    if (root / ".git").is_dir():
        empty = [p for p in (root / ".git/objects").rglob("*") if p.is_file() and p.stat().st_size == 0]
        rc, out = sh(["git", "-C", str(root), "rev-parse", "--verify", "-q", "HEAD"], 20)
        if empty or rc:
            s.add(A, FAIL, "Vault history (git) is damaged" + (f": {len(empty)} empty object file(s)" if empty else ""),
                  "New vault versions aren't being saved.", "desktop/autopilot/repair-vault-git.sh (backs up .git first)")
        else:
            rc, last = sh(["git", "-C", str(root), "log", "-1", "--format=%ct"], 20)
            when = float(last.strip() or 0)
            s.add(A, OK if time.time() - when < 3 * 86400 else WARN, f"Vault history intact · last saved version {ago(when)}")
    # sync conflicts and empty notes
    conflicts = [p for p in root.rglob("*.sync-conflict-*") if ".git" not in p.parts]
    if conflicts:
        s.add(A, WARN, f"{len(conflicts)} sync conflict file(s) in the vault",
              ", ".join(str(p.relative_to(root))[:60] for p in conflicts[:3]), "Open each, keep the right version, delete the conflict copy")
    empty_notes = [p for p in root.rglob("*.md") if p.is_file() and p.stat().st_size == 0 and not set(p.parts) & {".git", ".obsidian", ".trash"}]
    if len(empty_notes) > 5:
        s.add(A, INFO, f"{len(empty_notes)} empty notes in the vault")
    broken = [p for p in root.rglob("*") if p.is_symlink() and not p.exists()]
    if broken:
        s.add(A, WARN, f"{len(broken)} broken link(s) in the vault folder", ", ".join(str(p.relative_to(root))[:60] for p in broken[:3]))


# ---------------------------------------------------------------------------------------------- report

AREAS = ["Desktop AI", "Phone link", "Services", "BlackLab-only mode", "System", "Storage", "Data", "Security"]


def build_report(s: Scan, kind: str, previous: dict | None = None) -> dict:
    now = dt.datetime.now()
    date = (now - dt.timedelta(hours=4)).date() if kind == "boot" else now.date()  # the 04:xx check belongs to the same night
    issues = [c for c in s.checks if c["status"] in (WARN, FAIL)]
    issues.sort(key=lambda c: -RANK[c["status"]])
    n_fail = sum(1 for c in issues if c["status"] == FAIL)
    n_warn = len(issues) - n_fail
    if not issues:
        summary = f"All {len(s.checks)} checks passed. BlackLab is healthy and ready for today."
    else:
        summary = (f"{n_fail} problem(s) and " if n_fail else "") + f"{n_warn} thing(s) to look at" + \
                  f" out of {len(s.checks)} checks." + (" Start with the red ones." if n_fail else "")
    md = [f"# System check · {date:%a %b %d, %Y}", "",
          f"**{ {OK: 'Healthy', WARN: 'Needs a look', FAIL: 'Problems found'}[s.status] }** — {summary}", "",
          f"Scanned {now:%H:%M} on {os.uname().nodename}. The computer restarts at {profile()['reboot_time']} and comes back in BlackLab-only mode.", ""]
    if issues:
        md += ["## Needs your attention", ""]
        for c in issues:
            md.append(f"- **{'🔴' if c['status'] == FAIL else '🟡'} {c['title']}**" + (f" — {c['detail']}" if c["detail"] else "")
                      + (f" Fix: `{c['fix']}`" if c["fix"] else ""))
        md.append("")
    if s.fixed:
        md += ["## Fixed automatically", ""] + [f"- {f}" for f in s.fixed] + [""]
    for area in AREAS:
        rows = [c for c in s.checks if c["area"] == area]
        if not rows:
            continue
        md += [f"## {area}", ""]
        for c in rows:
            mark = {OK: "✅", INFO: "•", WARN: "🟡", FAIL: "🔴"}[c["status"]]
            md.append(f"- {mark} {c['title']}" + (f" — {c['detail']}" if c["detail"] and c["status"] in (OK, INFO) else ""))
        md.append("")
    if s.facts.get("largest"):
        md += ["## Biggest folders in your home", ""] + [f"- {n} · {p}" for n, p in s.facts["largest"]] + [""]
    rid = f"health-{date.isoformat()}"
    return {"id": rid, "date": date.isoformat(), "kind": kind, "status": s.status, "title": f"System check · {date:%a %b %d}",
            "summary": summary, "generated": time.time(), "fixed": s.fixed,
            "issues": [{k: c[k] for k in ("area", "status", "title", "detail", "fix")} for c in issues[:30]],
            "counts": {"checks": len(s.checks), "fail": n_fail, "warn": n_warn}, "text": "\n".join(md)}


def save_and_publish(rep: dict, send: bool = True) -> Path:
    folder = HOME / "Desktop" / "BlackLab AI" / "Documents" / "System Health"
    folder.mkdir(parents=True, exist_ok=True)
    os.chmod(folder, 0o700)
    path = folder / f"{rep['date']} System check.md"
    write_private(path, rep["text"] + "\n")
    rep["published"] = False
    if send:
        for attempt in range(10):
            try:
                code, _ = local("/local/health", rep, timeout=15)
                rep["published"] = code == 200
                break
            except Exception:
                time.sleep(15)
    write_private(STATE / "latest.json", json.dumps(rep, ensure_ascii=False))
    with open(STATE / "history.jsonl", "a") as f:
        f.write(json.dumps({k: rep[k] for k in ("id", "kind", "status", "generated", "counts")}) + "\n")
    return path


def full_scan(deep=True) -> Scan:
    s = Scan()
    for area, fn in (("Desktop AI", check_companion), ("Desktop AI", check_ollama), ("Phone link", check_phone),
                     ("Services", check_services), ("System", check_system), ("Data", check_data)):
        s.guarded(area, fn)
    s.guarded("Storage", lambda sc: check_storage(sc, deep))
    return s


# ---------------------------------------------------------------------------------------------- boot / watch

def enforce_profile(log: list[str]):
    """BlackLab-only mode: stop other projects' user units and containers; make sure BlackLab's are up."""
    prof = profile()
    for u in prof["stop_user_units"]:
        act, en = unit_state(u, True)
        if en == "enabled":
            sh(["systemctl", "--user", "disable", u], 20)
            log.append(f"Turned off {u} at boot")
        if act in ("active", "activating"):
            sh(["systemctl", "--user", "stop", u], 60)
            log.append(f"Stopped {u}")
    for c in prof["stop_containers"]:
        rc, out = sh(["docker", "inspect", "-f", "{{.State.Running}} {{.HostConfig.RestartPolicy.Name}}", c], 15)
        if rc != 0:
            continue
        running, policy = (out.split() + ["", ""])[:2]
        if policy not in ("no", ""):
            sh(["docker", "update", "--restart=no", c], 30)
            log.append(f"Container {c} no longer starts by itself")
        if running == "true":
            sh(["docker", "stop", "-t", "120", c], 180)
            log.append(f"Stopped container {c}")
    for u in prof["keep_user_units"]:
        act, en = unit_state(u, True)
        if act != "active":
            sh(["systemctl", "--user", "restart", u], 60)
            log.append(f"Started {u}")


def boot():
    log: list[str] = list(load_json(STATE / "enforce.json", {}).get("log", []))  # what --enforce did a moment ago
    enforce_profile(log)
    # wait for the stack daddyshome just brought up (Ollama can be slow on a cold boot)
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            local("/local/status", timeout=4)
            break
        except Exception:
            time.sleep(5)
    s = Scan()
    for area, fn in (("Desktop AI", check_companion), ("Desktop AI", check_ollama), ("Phone link", check_phone), ("Services", check_services)):
        s.guarded(area, fn)
    s.fixed += log
    prev = load_json(STATE / "latest.json", {})
    boot_rep = build_report(s, "boot")
    if prev.get("id") == boot_rep["id"] and prev.get("kind") == "nightly":
        # one report per night: the 03:00 scan, plus how the restart went
        night = prev
        up_issues = boot_rep["issues"]
        verdict = "Came back healthy — every BlackLab service is up." if not up_issues else f"Came back with {len(up_issues)} issue(s)."
        lines = ["", f"## After the {profile()['reboot_time']} restart", "", f"- {'✅' if not up_issues else '🟡'} {verdict} (checked {dt.datetime.now():%H:%M})"]
        lines += [f"- {'🔴' if c['status'] == FAIL else '🟡'} {c['title']}" + (f" Fix: `{c['fix']}`" if c["fix"] else "") for c in up_issues]
        lines += [f"- {l}" for l in log]
        # issues the restart fixed drop out; new ones are added
        still = {c["title"] for c in boot_rep["issues"]}
        restart_fixable = ("Companion is running", "isn't loaded right now", "Swap:", "running for more than 3 hours", "non-BlackLab item", "units failed",
                           "hasn't checked in", "Keep-warm says")
        kept = [c for c in night.get("issues", []) if not (c["area"] in ("Desktop AI", "Services", "BlackLab-only mode", "System")
                                                          and any(t in c["title"] for t in restart_fixable) and c["title"] not in still)]
        merged = kept + [c for c in up_issues if c["title"] not in {k["title"] for k in kept}]
        rank = max((RANK[c["status"]] for c in merged), default=0)
        night.update(kind="nightly+boot", issues=merged, status={0: OK, 1: WARN, 2: FAIL}[rank], generated=time.time(),
                     text=night["text"].split("\n## After the")[0].rstrip() + "\n" + "\n".join(lines),
                     summary=night["summary"] if merged else "Healthy after last night's scan and restart. Ready for today.")
        night["counts"] = {"checks": night.get("counts", {}).get("checks", 0), "fail": sum(1 for c in merged if c["status"] == FAIL),
                           "warn": sum(1 for c in merged if c["status"] == WARN)}
        rep = night
    else:
        rep = boot_rep  # no scan tonight (the computer was off or just installed): the boot check stands alone
    path = save_and_publish(rep)
    print(f"boot check: {rep['status']} → {path}")


def watch():
    """Every few minutes: the companion must answer and a plugged-in phone must be linked. Quiet unless it fixes something."""
    try:
        local("/local/status", timeout=6)
    except Exception:
        sh(["systemctl", "--user", "restart", "blacklab-desktop.service"], 30)
        print("watchdog: companion wasn't answering — restarted it", flush=True)
        return
    rc, out = sh([ADB, "devices"], 10)
    for serial in [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device") and not l.startswith("emulator-")]:
        rc, rev = sh([ADB, "-s", serial, "reverse", "--list"], 10)
        if "tcp:8770" not in rev:
            sh([ADB, "-s", serial, "reverse", "tcp:8770", "tcp:8770"], 10)
            sh([ADB, "-s", serial, "forward", "tcp:8765", "tcp:8765"], 10)
            print(f"watchdog: re-linked phone {serial[:4]}…", flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--nightly", action="store_true")
    g.add_argument("--boot", action="store_true")
    g.add_argument("--watch", action="store_true")
    g.add_argument("--print", action="store_true")
    g.add_argument("--enforce", action="store_true", help="BlackLab-only mode now (stop other projects)")
    g.add_argument("--publish-latest", action="store_true", help="send the last saved report to the companion again")
    a = ap.parse_args()
    STATE.mkdir(parents=True, exist_ok=True)
    os.chmod(STATE, 0o700)
    if a.watch:
        return watch()
    if a.enforce:
        log: list[str] = []
        enforce_profile(log)
        print("\n".join(log) or "BlackLab-only mode: nothing to stop")
        write_private(STATE / "enforce.json", json.dumps({"at": time.time(), "log": log}))
        return
    if a.boot:
        return boot()
    if a.publish_latest:
        rep = load_json(STATE / "latest.json", None)
        if rep:
            print(save_and_publish(rep))
        return
    lock = STATE / "scan.pid"
    lock.write_text(str(os.getpid()))  # the 04:00 restart waits while this exists
    try:
        s = full_scan(deep=True)
        rep = build_report(s, "nightly")
        if a.print:
            print(rep["text"])
            return
        path = save_and_publish(rep)
        print(f"nightly scan: {rep['status']} ({rep['counts']}) → {path}")
    finally:
        lock.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
