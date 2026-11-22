"""Terminal sessions for BlackLab Desktop's Terminal view (companion 1.14). Unit tested in tests/test_terminal.py.

The browser can't speak SSH, so the companion runs OpenSSH's own client (`ssh`) in a pseudo-terminal and streams it:

    POST /local/term/open    {host_id | target: "user@host[:port]", cols, rows} → {id}
    GET  /local/term/read    ?id=…&since=N  → {data (base64), next, closed, why}   (long-poll, up to 20 s)
    POST /local/term/write   {id, data (base64)}
    POST /local/term/resize  {id, cols, rows}
    POST /local/term/close   {id}
    GET  /local/term/hosts   saved hosts (config "ssh_hosts" + this computer over Tailscale)

Safety, on purpose:
  * Only the BlackLab Desktop page on this computer may use it: the /local/* guard plus a stricter origin check (the
    github.io copy of the app can't open a terminal), and the Tailscale listener never serves /local/*.
  * The destination is validated (user, host and port patterns, `--` before it) and passed as an argument list —
    nothing is ever handed to a shell on this side. No agent or X11 forwarding, no port forwards.
  * Host keys: `StrictHostKeyChecking=accept-new` — new hosts are remembered, a CHANGED key is refused by OpenSSH.
  * At most MAX_SESSIONS at once; a session nobody has read for IDLE_S is closed. What's typed and shown is never
    logged; the ledger records only "opened a terminal to <host>".
"""
from __future__ import annotations

import base64
import fcntl
import getpass
import os
import pty
import re
import signal
import struct
import subprocess
import termios
import threading
import time
import uuid

MAX_SESSIONS = 4
IDLE_S = 30 * 60
BUFFER = 1 << 20  # keep the last 1 MB of output per session for re-attaching

USER_RE = re.compile(r"^[a-z_][a-z0-9_.-]{0,31}$")
HOST_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")


def parse_target(text: str, default_user: str = "") -> dict:
    """'user@host[:port]' → {user, host, port}; raises ValueError with a plain reason."""
    t = (text or "").strip()
    if t.startswith("ssh "):
        t = t[4:].strip()
    user, _, rest = t.rpartition("@") if "@" in t else (default_user, "", t)
    port = 22
    if rest.count(":") == 1:
        rest, _, p = rest.partition(":")
        if not p.isdigit():
            raise ValueError("The port must be a number")
        port = int(p)
    if not 1 <= port <= 65535:
        raise ValueError("The port must be 1–65535")
    if not USER_RE.match(user or ""):
        raise ValueError("Add a valid user name, like paul@" + (rest or "host"))
    if not HOST_RE.match(rest or ""):
        raise ValueError("That host name or address isn't valid")
    return {"user": user, "host": rest, "port": port}


def ssh_argv(t: dict, ssh_bin: str = "ssh") -> list[str]:
    return [ssh_bin, "-tt", "-p", str(t["port"]), "-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=4", "-o", "ForwardAgent=no", "-o", "ForwardX11=no", "-o", "ClearAllForwardings=yes",
            "-o", "ConnectTimeout=20", "-l", t["user"], "--", t["host"]]


def hosts(config: dict, tailnet_url: str = "", hostname: str = "") -> list[dict]:
    """This computer (over Tailscale, if known) first, then config "ssh_hosts" ([{name, target}] or [{name, user, host, port}])."""
    out = []
    ip = re.sub(r"^https?://", "", tailnet_url or "").split(":")[0].split("/")[0]
    if ip and HOST_RE.match(ip):
        out.append({"id": "self", "name": f"{hostname or 'This computer'} (Tailscale SSH)", "user": getpass.getuser(), "host": ip, "port": 22})
    for i, h in enumerate(config.get("ssh_hosts") or []):
        try:
            t = parse_target(h.get("target") or f"{h.get('user', '')}@{h.get('host', '')}:{h.get('port', 22)}")
        except (ValueError, AttributeError):
            continue
        out.append(dict(t, id=f"h{i}", name=str(h.get("name") or t["host"])[:60]))
    return out


class Session:
    def __init__(self, target: dict, cols: int, rows: int, argv: list[str] | None = None):
        self.id = uuid.uuid4().hex[:16]
        self.target = target
        self.buf = bytearray()
        self.base = 0          # absolute offset of buf[0]
        self.closed = False
        self.why = ""
        self.last_read = time.time()
        self.cond = threading.Condition()
        pid, fd = pty.fork()
        if pid == 0:  # child: become ssh (argv list, no shell)
            try:
                os.environ["TERM"] = "xterm-256color"
                cmd = argv or ssh_argv(target)
                os.execvp(cmd[0], cmd)
            finally:
                os._exit(127)
        self.pid, self.fd = pid, fd
        self.resize(cols, rows)
        threading.Thread(target=self._pump, daemon=True, name=f"term-{self.id[:6]}").start()

    def _pump(self):
        while True:
            try:
                data = os.read(self.fd, 65536)
            except OSError:
                data = b""
            if not data:
                break
            with self.cond:
                self.buf += data
                if len(self.buf) > BUFFER:
                    cut = len(self.buf) - BUFFER
                    del self.buf[:cut]
                    self.base += cut
                self.cond.notify_all()
        code = None
        try:
            _, status = os.waitpid(self.pid, 0)
            code = os.waitstatus_to_exitcode(status)
        except ChildProcessError:
            pass
        with self.cond:
            self.closed = True
            self.why = "Session ended." if code in (0, None) else f"Session ended (ssh exit {code})."
            self.cond.notify_all()
        try:
            os.close(self.fd)
        except OSError:
            pass

    def read(self, since: int, wait: float = 20.0) -> dict:
        self.last_read = time.time()
        deadline = time.time() + wait
        with self.cond:
            while not self.closed and self.base + len(self.buf) <= since and time.time() < deadline:
                self.cond.wait(max(0.0, deadline - time.time()))
            start = max(since, self.base) - self.base
            data = bytes(self.buf[start:])
            nxt = self.base + len(self.buf)
            return {"data": base64.b64encode(data).decode(), "next": nxt, "closed": self.closed, "why": self.why, "skipped": since < self.base}

    def write(self, data: bytes):
        if self.closed:
            raise ValueError("The session has ended")
        os.write(self.fd, data)

    def resize(self, cols: int, rows: int):
        cols, rows = max(10, min(int(cols or 80), 500)), max(4, min(int(rows or 24), 300))
        try:
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        except OSError:
            pass

    def close(self):
        if not self.closed:
            try:
                os.kill(self.pid, signal.SIGHUP)
            except ProcessLookupError:
                pass


class Manager:
    def __init__(self, argv_for=None):
        self.sessions: dict[str, Session] = {}
        self.lock = threading.Lock()
        self.argv_for = argv_for  # tests run a local program instead of ssh

    def open(self, target: dict, cols=100, rows=30) -> Session:
        with self.lock:
            self._reap()
            live = [s for s in self.sessions.values() if not s.closed]
            if len(live) >= MAX_SESSIONS:
                raise ValueError(f"{MAX_SESSIONS} terminals are already open — close one first")
            s = Session(target, cols, rows, self.argv_for(target) if self.argv_for else None)
            self.sessions[s.id] = s
            return s

    def get(self, sid: str) -> Session:
        s = self.sessions.get(str(sid or ""))
        if not s:
            raise KeyError("No such terminal (it may have timed out)")
        return s

    def close(self, sid: str):
        s = self.sessions.pop(str(sid or ""), None)
        if s:
            s.close()

    def _reap(self):
        now = time.time()
        for sid, s in list(self.sessions.items()):
            if s.closed and now - s.last_read > 60:
                self.sessions.pop(sid, None)
            elif not s.closed and now - s.last_read > IDLE_S:
                s.close()

    def status(self) -> list[dict]:
        return [{"id": s.id, "target": f"{s.target['user']}@{s.target['host']}", "closed": s.closed} for s in self.sessions.values()]


def origin_ok(origin: str | None, port: int) -> bool:
    """Stricter than the rest of /local/*: only the page this companion serves (or no Origin: curl/tests)."""
    if not origin:
        return True
    return origin in (f"http://127.0.0.1:{port}", f"http://localhost:{port}")
