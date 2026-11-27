#!/usr/bin/env python3
"""BlackLab autopilot: the nightly code review (companion 1.15).

At 02:30, before the 03:00 check and the 04:00 restart, if the day had commits: Claude Code reviews them headless, with
Sonnet doing the work and Opus on call as its advisor (Claude Code's --advisor). Read-only — it can Read, Grep and Glob
the repository for context and nothing else: no shell, no edits, no MCP servers, no hooks. The report goes to the vault
(70 AI/Agents/Claude Code/Nightly reviews/), a one-line summary goes to the phone, and the Agent Ops view shows it.

    nightly_review.py              review the last day's commits (skips quietly when there are none)
    nightly_review.py --dry-run    print the prompt it would send, call nothing
    nightly_review.py --since "3 days ago"
    nightly_review.py --on-demand  only commits since the last review; result in review-now.json, its own vault note
                                   (the phone's "Run review now" starts blacklab-review-now.service, which runs this)

~/.config/blacklab-desktop/autopilot.json:
    "nightly_review": true         false turns it off
    "review_repos": ["~/Desktop/BlackLab"]
    "review_model": "sonnet", "review_advisor": "opus", "review_time": "02:30" (install.sh reads the time)

What leaves this computer: the day's diff of the listed repositories (already public on GitHub for BlackLab), after
guards.redact() and with binaries, keystores, releases and office files left out. Uses the Claude subscription, not a key.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "companion"))
import agentops  # noqa: E402
import ai_models  # noqa: E402
import guards  # noqa: E402

CONF = Path.home() / ".config" / "blacklab-desktop" / "autopilot.json"
STATE = Path.home() / ".cache" / "blacklab-desktop" / "nightly-review.json"
NOW_STATE = Path.home() / ".cache" / "blacklab-desktop" / "review-now.json"  # on-demand runs (phone "Run review now")
FALLBACK_DIR = Path.home() / ".cache" / "blacklab-desktop" / "nightly-reviews"
PORT = int(os.environ.get("BLACKLAB_DESKTOP_PORT", "8770"))
MAX_DIFF = 90_000
EXCLUDE = [":(exclude)*.apk", ":(exclude)*.aab", ":(exclude)keystore", ":(exclude)*.jks", ":(exclude)releases", ":(exclude)*.png", ":(exclude)*.jpg",
           ":(exclude)*.pptx", ":(exclude)*.xlsx", ":(exclude)*.docx", ":(exclude)*.pdf", ":(exclude)*.zip", ":(exclude)local.properties",
           ":(exclude)Veteran_Initiative", ":(exclude)vendor"]
ASK = """You are reviewing the commits made in the repository "{name}" (cwd) since {since}. The commits and their diff are below.

Find real problems only:
  - correctness bugs (wrong logic, broken edge cases, crashes, races)
  - security and privacy: secrets or tokens, private or clinical (Behavioral Health) data, anything that would leak
    personal data into this public repository, unsafe input handling
  - risky changes with no test
You may Read, Grep and Glob the repository to check context. Do not suggest style changes or refactors.
Before you finish, consult your advisor about what you might have missed.

Reply in Markdown, under 450 words:
## Verdict
one line: "No findings." or "N finding(s): …"
## Findings
numbered: `path:line` · severity (high/medium/low) · what is wrong and why it matters · the fix in one sentence

COMMITS:
{log}

DIFF{clipped}:
{diff}
"""


def load_conf() -> dict:
    try:
        return json.loads(CONF.read_text())
    except (OSError, ValueError):
        return {}


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=60).stdout


def head_of(repo: Path) -> str:
    return git(repo, "rev-parse", "HEAD").strip()


def since_for(repo: Path, heads: dict, on_demand: bool) -> tuple[str, str | None]:
    """(since, base): what to review. base is the head the last review covered, when it is still in HEAD's history.
    On demand: everything after base (a day when there is no base). Nightly: the last day, minus what base covered."""
    base = heads.get(repo.name) if isinstance(heads, dict) else None
    if base and subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", base, "HEAD"], capture_output=True, timeout=60).returncode != 0:
        base = None
    if on_demand and base:
        return "", base
    return "24 hours ago", base


def day_changes(repo: Path, since: str, base: str | None = None) -> tuple[str, str]:
    """(one line per commit, patch text) for non-merge commits since `since` and after `base`; ('', '') when none."""
    sel = ([f"--since={since}"] if since else []) + ([f"{base}..HEAD"] if base else [])
    log = git(repo, "log", *sel, "--no-merges", "--format=%h %ad %s", "--date=format:%m-%d %H:%M")
    if not log.strip():
        return "", ""
    patch = git(repo, "log", *sel, "--no-merges", "-p", "--format=commit %h %s", "--", ".", *EXCLUDE)
    return log.strip(), patch


def note_name(repo: str, on_demand: bool, now: dt.datetime) -> str:
    """Nightly: one note a day. On demand: its own note, so it never replaces the nightly one."""
    return f"{now:%Y-%m-%d %H%M} {repo} (on demand)" if on_demand else f"{now:%Y-%m-%d} {repo}"


def read_heads() -> dict:
    """The head each repository was last reviewed at, from both state files (the newer run wins)."""
    states = []
    for path in (STATE, NOW_STATE):
        try:
            states.append(json.loads(path.read_text()))
        except (OSError, ValueError):
            pass
    heads: dict = {}
    for st in sorted((x for x in states if isinstance(x, dict)), key=lambda x: x.get("at") or 0):
        heads.update(st.get("heads") if isinstance(st.get("heads"), dict) else {})
    return heads


def build_prompt(name: str, log: str, patch: str, since: str = "24 hours ago") -> str:
    patch = guards.redact(patch)
    clipped = f" (first {MAX_DIFF:,} of {len(patch):,} characters)" if len(patch) > MAX_DIFF else ""
    return ASK.format(name=name, since=since, log=log, diff=patch[:MAX_DIFF], clipped=clipped)


def claude_args(exe: str, model: str, adv: str | None) -> list[str]:
    args = [exe, "-p", "--model", model, "--output-format", "json", "--no-session-persistence", "--setting-sources", "",
            "--strict-mcp-config", "--tools", "Read,Grep,Glob", "--allowedTools", "Read", "Grep", "Glob", "--max-turns", "30"]
    return args + (["--advisor", adv] if adv else [])


def verdict(report: str) -> str:
    lines = [x.strip() for x in report.splitlines()]
    for i, x in enumerate(lines):
        if x.lower().startswith("## verdict"):
            return next((y for y in lines[i + 1:] if y), "")[:200]
    return next((y for y in lines if y and not y.startswith("#")), "")[:200]


def companion(path: str, body: dict) -> dict | None:
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Host": f"127.0.0.1:{PORT}", "X-BlackLab": "1"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode() or "{}")
    except Exception:
        return None


def save_state(path: Path, **kw):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"at": time.time(), **kw}, indent=2))
    os.chmod(path, 0o600)


def review(repo: Path, cfg: dict, since: str, base: str | None = None, dry: bool = False, on_demand: bool = False) -> dict:
    name, head = repo.name, head_of(repo)
    log, patch = day_changes(repo, since, base)
    if not log:
        note = "Nothing new since the last review" if on_demand else f"no commits since {since}"
        return {"repo": name, "status": "Skipped", "summary": note, "head": head if base else None}
    since = since or f"the last review ({base[:7]})"
    setup = agentops.review_setup(cfg)  # the same model/advisor the Agent Ops panel shows
    model, adv = setup["model"], setup["advisor"] or None
    prompt = build_prompt(name, log, patch, since + (f", after the last review ({base[:7]})" if base and "last review" not in since else ""))
    if dry:
        print(prompt)
        return {"repo": name, "status": "Dry run", "summary": f"{len(log.splitlines())} commit(s), prompt {len(prompt):,} characters"}
    exe = ai_models.find_claude({}) or shutil.which("claude")
    if not exe:
        return {"repo": name, "status": "Failed", "summary": "Claude Code isn't installed"}
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "CLAUDECODE")}
    t0 = time.time()
    try:
        r = subprocess.run(claude_args(exe, model, adv), input=prompt, capture_output=True, text=True, timeout=25 * 60, cwd=str(repo), env=env)
    except subprocess.TimeoutExpired:
        return {"repo": name, "status": "Failed", "summary": "Claude took longer than 25 minutes"}
    text, canonical, err = ai_models.parse_claude_output(r.stdout or "", r.stderr or "", r.returncode)
    if err:
        return {"repo": name, "status": "Failed", "summary": err[:200]}
    who = ai_models.pretty_claude(canonical) if canonical else model
    day = dt.date.today().isoformat()
    note = (f"# {'On-demand' if on_demand else 'Nightly'} review · {name} · {day}\n\n"
            f"_{len(log.splitlines())} commit(s) since {since} · {who}" + (f" with the {adv.title()} advisor" if adv else "")
            + f" · {time.time() - t0:.0f} s · read-only_\n\n" + text.strip() + "\n\n## Commits\n\n" + "\n".join(f"- `{x}`" for x in log.splitlines()) + "\n")
    rel = f"70 AI/Agents/Claude Code/Nightly reviews/{note_name(name, on_demand, dt.datetime.now())}"
    w = companion("/local/vault/write", {"path": rel, "content": note, "mode": "replace", "author": "Claude Code (nightly review)"})
    if not (w and w.get("ok")):
        FALLBACK_DIR.mkdir(parents=True, exist_ok=True)
        p = FALLBACK_DIR / (note_name(name, on_demand, dt.datetime.now()).replace(" ", "-") + ".md")
        p.write_text(note)
        os.chmod(p, 0o600)
        rel = str(p)
    summary = verdict(text) or "Reviewed."
    companion("/local/notify", {"title": f"{'On-demand' if on_demand else 'Nightly'} code review · {name}", "text": summary})
    return {"repo": name, "status": "Reviewed", "summary": summary, "report": rel, "model": who, "advisor": adv or "", "head": head}


def pick(results: list[dict]) -> dict:
    """The result the panel shows: a failure first (so it can't hide behind another repo's success), then a review."""
    for status in ("Failed", "Reviewed"):
        hit = next((r for r in results if r["status"] == status), None)
        if hit:
            return hit
    return results[0] if results else {"status": "Skipped", "summary": "no repositories"}


def main() -> int:
    cfg = load_conf()
    dry = "--dry-run" in sys.argv
    on_demand = "--on-demand" in sys.argv
    if cfg.get("nightly_review", True) is False and not dry:
        print("nightly review is off (autopilot.json \"nightly_review\": false)")
        return 0
    forced = sys.argv[sys.argv.index("--since") + 1] if "--since" in sys.argv else None
    heads = read_heads()
    repos = [Path(os.path.expanduser(p)) for p in cfg.get("review_repos", [str(HERE.parent.parent)])]
    results = []
    for repo in repos:
        if not (repo / ".git").exists():
            results.append({"repo": repo.name, "status": "Skipped", "summary": "not a git repository"})
            continue
        try:
            since, base = (forced, None) if forced else since_for(repo, heads, on_demand)
            results.append(review(repo, cfg, since, base, dry=dry, on_demand=on_demand))
        except Exception as e:  # noqa: BLE001 — record it, so a crash at 02:30 shows up instead of vanishing
            results.append({"repo": repo.name, "status": "Failed", "summary": f"{type(e).__name__}: {e}"[:200]})
    for r in results:
        print(f"{r['repo']}: {r['status']} — {r['summary']}" + (f" ({r['report']})" if r.get("report") else ""))
    if not dry:
        main_r = pick(results)
        heads.update({r["repo"]: r["head"] for r in results if r.get("head")})
        save_state(NOW_STATE if on_demand else STATE, **main_r, all=results, heads=heads, on_demand=on_demand)
        for r in results:
            if r["status"] == "Failed":
                companion("/local/notify", {"title": f"{'On-demand' if on_demand else 'Nightly'} code review failed · {r['repo']}", "text": r["summary"]})
    return 1 if any(r["status"] == "Failed" for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
