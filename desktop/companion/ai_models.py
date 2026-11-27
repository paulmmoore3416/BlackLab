"""Every model BlackLab can talk to, behind one dropdown (companion 1.11).

    claude:opus | claude:sonnet | claude:haiku | claude:fable   Claude, through Paul's Claude subscription: the Claude Code
                                                               CLI runs headless (`claude -p`) on this computer. No API key.
    gemini:<model>                                             Google's Gemini API with the key in secrets.json ("gemini").
    local:<ollama model>                                       the offline models in Ollama (the desktop assistant, tools and all).
    auto                                                       BlackLab picks, as before (local first, cloud only as a fallback).

Claude runs with no tools, no MCP servers, no settings or hooks and no saved session: a clean chat. Earlier turns travel
in the prompt. Opting in with config `claude_vault_tools: true` lets Claude use only the read-only vault tools.
The real model name each call reports ("claude-opus-5-5") becomes the label ("Claude Opus 5.5"), so the dropdown stays
current when the aliases move to newer models.

Pure helpers are unit tested in tests/test_ai_models.py; process and network calls are injected there.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

CLAUDE = [  # alias, family, fallback canonical name (until a call reports the real one)
    ("opus", "Claude Opus", "claude-opus-5-5"),
    ("sonnet", "Claude Sonnet", "claude-sonnet-5-5"),
    ("haiku", "Claude Haiku", "claude-haiku-5-5"),
    ("fable", "Claude Fable", "claude-fable-5-2"),
]
CLAUDE_NOTES = {"opus": "most capable", "sonnet": "fast and smart", "haiku": "fastest", "fable": "creative"}
GEMINI_DEFAULTS = [("gemini-2.5-pro", "Gemini 2.5 Pro"), ("gemini-2.5-flash", "Gemini 2.5 Flash"), ("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite")]
GEMINI_SKIP = re.compile(r"(embedding|aqa|imagen|veo|tts|image|live|audio|learnlm|robotics|computer-use|native)", re.I)
VAULT_READ_TOOLS = ["mcp__blacklab-vault__vault_search", "mcp__blacklab-vault__vault_read", "mcp__blacklab-vault__vault_recent",
                    "mcp__blacklab-vault__vault_nodes"]
PROVIDERS = ("claude", "gemini", "local", "nano", "auto")
ADVISOR_RANK = {"haiku": 1, "sonnet": 2, "opus": 3, "fable": 4}  # Claude Code: the advisor must rank at or above the main model
_claude_slots = threading.BoundedSemaphore(2)


def find_claude(config: dict | None = None) -> str | None:
    """Paul's own Claude Code first (~/.npm-global, ~/.local, ~/.claude/local), then PATH. A system-wide copy in /usr/bin can
    be months older (slower, older model aliases), and systemd's PATH finds that one first."""
    home = Path.home()
    for c in [(config or {}).get("claude_cli"), home / ".npm-global/bin/claude", home / ".local/bin/claude", home / ".claude/local/claude", shutil.which("claude")]:
        if c and Path(c).exists():
            return str(c)
    return None


def provider_of(model_id: str | None) -> str:
    """'claude:opus' → 'claude'; anything unknown → 'auto'."""
    p = (model_id or "auto").split(":", 1)[0].lower()
    return p if p in PROVIDERS else "auto"


def model_of(model_id: str | None) -> str:
    return (model_id or "").split(":", 1)[1] if ":" in (model_id or "") else ""


def claude_family(name: str | None) -> str:
    """'opus' / 'claude-opus-5-5' → 'opus'; '' when it isn't a Claude model name."""
    m = re.match(r"^(?:claude-)?(haiku|sonnet|opus|fable)\b", (name or "").strip().lower())
    return m.group(1) if m else ""


def advisor_for(main: str, advisor: str | None) -> str | None:
    """The --advisor value to pass, or None: unset, not a Claude model, or ranked below the main model (Claude Code
    would only warn and run without it; dropping it here keeps the reply's label honest)."""
    a = (advisor or "").strip().lower()
    if not a or a in ("off", "none") or not re.fullmatch(r"(?:haiku|sonnet|opus|fable|claude-[a-z0-9-]+)", a):
        return None
    fa, fm = claude_family(a), claude_family(main)
    if not fa or not fm or ADVISOR_RANK[fa] < ADVISOR_RANK[fm]:
        return None
    return a


def pretty_claude(canonical: str) -> str:
    """claude-opus-5-5 → Claude Opus 5.5; claude-sonnet-4-5-20250929 → Claude Sonnet 4.5; unknown → as given."""
    m = re.match(r"^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$", canonical or "")
    if not m:
        return canonical or "Claude"
    return f"Claude {m.group(1).title()} {m.group(2)}" + (f".{m.group(3)}" if m.group(3) else "")


def pretty_gemini(model: str) -> str:
    """gemini-2.5-flash-lite → Gemini 2.5 Flash-Lite."""
    m = re.match(r"^gemini-(.+)$", model or "")
    if not m:
        return model or "Gemini"
    parts = m.group(1).split("-")
    out = []
    for i, p in enumerate(parts):
        if p == "lite" and out:
            out[-1] += "-Lite"
        elif re.fullmatch(r"\d+(\.\d+)?", p):
            out.append(p)
        else:
            out.append(p.title())
    return "Gemini " + " ".join(out)


def transcript(history, text: str, limit: int = 6, max_chars: int = 12000) -> str:
    """Earlier turns + the new message as one prompt (the CLI keeps no session)."""
    turns = list(history or [])[-limit:]
    if not turns:
        return text
    lines = ["Earlier in this conversation:"]
    for u, a in turns:
        lines += [f"Paul: {u}", f"You: {a}"]
    body = "\n".join(lines)
    if len(body) > max_chars:
        body = "…" + body[-max_chars:]
    return body + "\n\nPaul's new message:\n" + text


def system_prompt(surface: str, model_label: str, extra: str = "") -> str:
    return (f"You're {model_label}, answering inside BlackLab — Paul's personal assistant app (phone, desktop and Obsidian). "
            "Paul is a Director of Facility Operations and a student; he relies on you a lot, so be genuinely useful: clear, "
            "accurate and to the point, with detail when the question needs it. Use Markdown sparingly (it shows in a chat bubble "
            f"and in his Obsidian vault). This message came from: {surface}. " + extra).strip()


class Models:
    """The catalog and the calls. Dependencies are injected so tests never start a process or touch the network."""

    def __init__(self, config: dict, secrets: dict, cache_dir: Path, ollama_models=lambda: [], run=None, fetch=None, claude_exe=None):
        self.config, self.secrets = config, secrets
        self.cache = Path(cache_dir)
        self.ollama_models = ollama_models
        self.run = run or subprocess.run
        self.fetch = fetch or self._fetch
        self._exe = claude_exe
        self._gemini_list = None
        self._gemini_at = 0.0
        self.last_error: dict[str, str] = {}

    # ---------------------------------------------------------------- state

    def _state(self) -> dict:
        try:
            return json.loads((self.cache / "models.json").read_text())
        except Exception:
            return {}

    def _save_state(self, st: dict):
        try:
            self.cache.mkdir(parents=True, exist_ok=True)
            (self.cache / "models.json").write_text(json.dumps(st, indent=2))
        except OSError:
            pass

    def claude_exe(self) -> str | None:
        return self._exe if self._exe and Path(self._exe).exists() else find_claude(self.config)

    # ---------------------------------------------------------------- catalog

    def claude_entries(self) -> list[dict]:
        seen = self._state().get("claude", {})
        ok = self.claude_exe() is not None and self.config.get("claude", True) is not False
        return [{"id": f"claude:{a}", "provider": "claude", "label": pretty_claude(seen.get(a) or canon), "family": fam,
                 "highlight": True, "available": ok, "note": CLAUDE_NOTES.get(a, "") + (" · your Claude subscription" if ok else ""),
                 "where": "computer"} for a, fam, canon in CLAUDE]

    def gemini_entries(self) -> list[dict]:
        key = bool(self.secrets.get("gemini"))
        names = self.gemini_models() if key else GEMINI_DEFAULTS
        return [{"id": f"gemini:{m}", "provider": "gemini", "label": label, "highlight": True, "available": key,
                 "note": "Google AI Studio key" if key else "add a Gemini key to secrets.json", "where": "cloud"} for m, label in names]

    def local_entries(self) -> list[dict]:
        try:
            models = list(self.ollama_models() or [])
        except Exception:
            models = []
        return [{"id": f"local:{m}", "provider": "local", "label": m, "highlight": False, "available": True, "note": "offline on this computer",
                 "where": "computer"} for m in models]

    def catalog(self) -> list[dict]:
        auto = {"id": "auto", "provider": "auto", "label": "Auto", "highlight": False, "available": True,
                "note": "BlackLab picks: local first, cloud as a fallback", "where": ""}
        return self.claude_entries() + self.gemini_entries() + self.local_entries() + [auto]

    def label(self, model_id: str) -> str:
        for e in self.catalog():
            if e["id"] == model_id:
                return e["label"]
        p, m = provider_of(model_id), model_of(model_id)
        return pretty_claude(m) if p == "claude" else pretty_gemini(m) if p == "gemini" else (m or "Auto")

    def gemini_models(self) -> list[tuple[str, str]]:
        """Text models from Google's list (cached a day); the built-in defaults when it can't be reached."""
        if self._gemini_list and time.time() - self._gemini_at < 86400:
            return self._gemini_list
        out = []
        try:
            d = self.fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", None, {"x-goog-api-key": self.secrets.get("gemini", "")}, 10)
            for m in d.get("models", []):
                name = m.get("name", "").removeprefix("models/")
                if not name.startswith("gemini-") or "generateContent" not in m.get("supportedGenerationMethods", []) or GEMINI_SKIP.search(name):
                    continue
                if re.search(r"-\d{3}$|-exp|-preview-\d", name):  # dated snapshots and experiments: the plain names are enough
                    continue
                out.append((name, m.get("displayName") or pretty_gemini(name)))
        except Exception as e:
            self.last_error["gemini_list"] = str(e)[:200]
        out.sort(key=lambda x: _gemini_rank(x[0]))
        self._gemini_list = out[:12] or GEMINI_DEFAULTS
        self._gemini_at = time.time()
        return self._gemini_list

    # ---------------------------------------------------------------- calls

    def claude(self, alias: str, prompt: str, system: str, timeout: int = 180, advisor: str | None = None) -> tuple[str, str]:
        """(reply, real model name). Raises RuntimeError with a readable reason. advisor: Claude Code's advisor model
        (1.15), passed as --advisor when it may advise this model (advisor_for)."""
        exe = self.claude_exe()
        if not exe:
            raise RuntimeError("Claude Code isn't installed on this computer (npm i -g @anthropic-ai/claude-code, then `claude` to sign in)")
        if alias not in {a for a, _f, _c in CLAUDE} and not re.fullmatch(r"claude-[a-z0-9-]+", alias or ""):
            raise RuntimeError(f"unknown Claude model: {alias}")
        args = [exe, "-p", "--model", alias, "--output-format", "json", "--no-session-persistence", "--setting-sources", "",
                "--system-prompt", system]
        if adv := advisor_for(alias, advisor):
            args += ["--advisor", adv]
        if self.config.get("claude_vault_tools") is True:
            mcp = self._vault_mcp_config()
            args += ["--tools", "", "--strict-mcp-config", "--mcp-config", str(mcp), "--allowedTools", *VAULT_READ_TOOLS]
        else:
            args += ["--tools", "", "--strict-mcp-config"]
        work = self.cache / "claude-chat"
        work.mkdir(parents=True, exist_ok=True)
        env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "CLAUDECODE")}  # the subscription, not a key
        if not _claude_slots.acquire(timeout=60):
            raise RuntimeError("Claude is busy with two other requests — try again in a moment")
        try:
            r = self.run(args, input=prompt, capture_output=True, text=True, timeout=timeout, cwd=str(work), env=env)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"Claude took longer than {timeout} s")
        finally:
            _claude_slots.release()
        text, canonical, err = parse_claude_output(r.stdout or "", r.stderr or "", r.returncode)
        if err:
            self.last_error["claude"] = err
            raise RuntimeError(err)
        if canonical:
            st = self._state()
            if st.get("claude", {}).get(alias) != canonical:
                st.setdefault("claude", {})[alias] = canonical
                self._save_state(st)
        return text, canonical or dict((a, c) for a, _f, c in CLAUDE).get(alias, alias)

    def _vault_mcp_config(self) -> Path:
        self.cache.mkdir(parents=True, exist_ok=True)
        p = self.cache / "claude-vault-mcp.json"
        server = Path(__file__).resolve().parent / "vault_mcp.py"
        p.write_text(json.dumps({"mcpServers": {"blacklab-vault": {"command": "python3", "args": [str(server)]}}}))
        return p

    def gemini(self, model: str, text: str, history=(), system: str = "", temperature=0.4, max_tokens=2048) -> str:
        key = self.secrets.get("gemini")
        if not key:
            raise RuntimeError("no Gemini key on this computer (add \"gemini\" to ~/.config/blacklab-desktop/secrets.json)")
        if not re.fullmatch(r"gemini-[a-z0-9.-]+", model or ""):
            raise RuntimeError(f"unknown Gemini model: {model}")
        contents = []
        for u, a in list(history or [])[-6:]:
            contents += [{"role": "user", "parts": [{"text": u}]}, {"role": "model", "parts": [{"text": a}]}]
        contents.append({"role": "user", "parts": [{"text": text[:60000]}]})
        body = {"contents": contents, "generationConfig": {"temperature": temperature, "maxOutputTokens": max_tokens}}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        try:
            d = self.fetch(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", body, {"x-goog-api-key": key})
        except Exception as e:
            raise RuntimeError(f"Gemini didn't answer: {e}")
        parts = (d.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
        out = "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
        if not out:
            reason = (d.get("candidates") or [{}])[0].get("finishReason") or (d.get("promptFeedback") or {}).get("blockReason") or "empty reply"
            raise RuntimeError(f"Gemini returned no text ({reason})")
        return out

    def chat(self, model_id: str, text: str, history=(), surface: str = "BlackLab", extra_system: str = "", advisor: str | None = None) -> dict:
        """One cloud turn: {'text', 'source', 'provider', 'model'}. Local and auto are the caller's job (tools live there).
        advisor: a Claude advisor for this turn (long background jobs; config "claude_advisor")."""
        p, m = provider_of(model_id), model_of(model_id)
        if p == "claude":
            label = self.label(model_id)
            adv = advisor_for(m, advisor)
            reply, canonical = self.claude(m, transcript(history, text), system_prompt(surface, label, extra_system), advisor=adv)
            return {"text": reply, "source": f"{pretty_claude(canonical)} · your Claude subscription" + (f" · advisor {adv.title()}" if adv else ""),
                    "provider": "claude", "model": canonical}
        if p == "gemini":
            label = self.label(model_id)
            reply = self.gemini(m, text, history, system_prompt(surface, label, extra_system))
            return {"text": reply, "source": f"{label} (cloud)", "provider": "gemini", "model": m}
        raise ValueError(f"not a cloud model: {model_id}")

    def generate(self, model_id: str, prompt: str, temperature=0.3, max_tokens=900) -> dict:
        """A raw single prompt (the phone's assistant router uses this): {'text', 'model', 'provider'}."""
        p, m = provider_of(model_id), model_of(model_id)
        if p == "claude":
            reply, canonical = self.claude(m, prompt, "Follow the instructions in the user's message exactly.")
            return {"text": reply, "model": canonical, "label": pretty_claude(canonical), "provider": "claude"}
        if p == "gemini":
            return {"text": self.gemini(m, prompt, temperature=temperature, max_tokens=max_tokens), "model": m, "label": self.label(model_id), "provider": "gemini"}
        raise ValueError(f"not a cloud model: {model_id}")

    def _fetch(self, url: str, body, headers: dict, timeout=90):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
        req.add_header("Content-Type", "application/json")
        for k, v in headers.items():
            req.add_header(k, v)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode() or "{}")


def _gemini_rank(name: str):
    """Newest generation first, then Pro, Flash, Flash-Lite."""
    m = re.match(r"gemini-(\d+(?:\.\d+)?)", name)
    ver = -float(m.group(1)) if m else 0
    tier = 0 if "pro" in name else 2 if "lite" in name else 1
    return (ver, tier, name)


def parse_claude_output(stdout: str, stderr: str, code: int) -> tuple[str, str, str | None]:
    """(text, canonical model, error). `claude -p --output-format json` prints one JSON object."""
    try:
        d = json.loads(stdout.strip().splitlines()[-1]) if stdout.strip() else {}
    except (ValueError, IndexError):
        d = {}
    if not d:
        tail = (stderr or stdout or "").strip()[-300:]
        if re.search(r"log ?in|logged out|authenticat|/login", tail, re.I):
            return "", "", "Claude Code is signed out on this computer — run `claude` once and sign in with your Claude account"
        return "", "", f"Claude didn't answer (exit {code}): {tail or 'no output'}"
    text = str(d.get("result") or "").strip()
    canonical = next(iter((d.get("modelUsage") or {}).keys()), "")
    if d.get("is_error") or d.get("subtype", "success") != "success":
        msg = text or str(d.get("subtype") or "error")
        if re.search(r"usage limit|rate limit|limit reached|5-hour|weekly limit", msg, re.I):
            return "", canonical, f"Your Claude plan's usage limit is reached for now: {msg[:200]}"
        return "", canonical, f"Claude: {msg[:300]}"
    if not text:
        return "", canonical, "Claude returned an empty reply"
    return text, canonical, None
