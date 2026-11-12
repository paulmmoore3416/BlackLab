"""Keeps the desktop assistant's model loaded while it is pinned.

./daddyshome sets config "keep_alive" to -1 (always loaded) and ./daddyshome --stop sets it back to
"30m". While it is -1, a reboot, an Ollama restart or Night Mining (which unloads every model at
23:00) would otherwise leave the model cold until the next question, so the companion checks every
minute and loads it again. It never loads while mining owns the GPUs.
"""
from __future__ import annotations

from typing import Callable


def pinned(cfg: dict) -> bool:
    v = cfg.get("keep_alive", "30m")
    return str(v).strip() == "-1"


def _same(a: str, b: str) -> bool:
    norm = lambda n: n if ":" in n else n + ":latest"  # noqa: E731
    return norm(a) == norm(b)


def pass_once(cfg: dict, http_json: Callable, mining_active: Callable[[], bool], ollama: str, model: str, num_ctx: int) -> str:
    """One check. Returns what happened: off | mining | ok | loaded <model> | ollama down: … | failed: …"""
    if not pinned(cfg):
        return "off"
    if mining_active():
        return "mining"
    try:
        loaded = [m.get("name", "") for m in (http_json(f"{ollama}/api/ps", None, timeout=5) or {}).get("models", [])]
    except Exception as e:  # noqa: BLE001 — Ollama restarting or not installed: try again next minute
        return f"ollama down: {e}"
    if any(_same(n, model) for n in loaded):
        return "ok"
    try:
        http_json(f"{ollama}/api/generate", {"model": model, "prompt": "", "keep_alive": -1, "options": {"num_ctx": num_ctx}}, timeout=300)
    except Exception as e:  # noqa: BLE001
        return f"failed: {e}"
    return f"loaded {model}"
