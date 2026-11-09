"""The BlackLab Obsidian vault: a living, linked, versioned mirror of everything BlackLab does.

Default location ~/Obsidian/BlackLab (config.json "vault_dir"). Written by:
  * the phone      — POST /phone/vault (a feed of Facility, Garage, tasks, calendar, notes, notebooks, courses,
                     recordings; never Behavioral Health, keys, the VIN or the plate) and POST /phone/vault/note
  * the desktop AI — every conversation, job and task log, meeting notes and briefs; vault_search / vault_write tools
  * any AI agent   — the vault MCP server (vault_mcp.py): Claude Code, the desktop AI, anything that speaks MCP
  * this computer  — your git projects and new files in the folders you watch

Rules every writer follows (enforced here, unit tested in tests/test_vault.py):
  1. **Your writing is never overwritten.** What BlackLab owns sits between `%% blacklab:begin %%` and
     `%% blacklab:end %%` (Obsidian comments, invisible in reading view). Everything outside — your own notes,
     a teammate's, another AI's — is kept as-is on every sync.
  2. **Nothing is deleted.** A note whose source is gone moves to `99 Archive/`.
  3. **Private stays private.** Clinical text and "off the record" requests are refused; keys, passwords,
     SSNs, card numbers and VINs are scrubbed (guards.vault_safe).
  4. **Everything is versioned.** The vault is a git repository; each sync is a commit, so any change can be undone.
  5. **AIs sign in.** Every AI that works in the vault adds its signature and a note to `AI Registry.md`.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

import guards

SCHEMA = 1
BEGIN, END = "%% blacklab:begin %%", "%% blacklab:end %%"
BAD_NAME = re.compile(r'[*"\\/<>:|?#^\[\]\x00-\x1f]')

FOLDERS = {
    "inbox": "00 Inbox",
    "daily": "01 Daily",
    "projects": "02 Projects",
    "facility": "10 Facility",
    "garage": "20 Garage",
    "work": "30 Work",
    "learning": "40 Learning",
    "notes": "50 Notes",
    "meetings": "60 Meetings",
    "ai": "70 AI",
    "knowledge": "80 Knowledge",
    "system": "90 System",
    "archive": "99 Archive",
    "attachments": "Attachments",
}
ACCENT = "#2BD9A8"  # BlackLab mint


class Vault:
    def __init__(self, root: Path, state_dir: Path, tz: str = "America/Chicago"):
        self.root = Path(root).expanduser()
        self.state_dir = Path(state_dir)
        self.name = self.root.name
        self.lock = threading.RLock()
        self.dirty: set[str] = set()
        self.last_commit = 0.0
        self.last_sync: dict[str, float] = {}
        self.errors: list[str] = []
        try:
            from zoneinfo import ZoneInfo
            self.tz = ZoneInfo(tz)
        except Exception:
            self.tz = None

    # ------------------------------------------------------------------ basics

    def now(self) -> dt.datetime:
        return dt.datetime.now(self.tz) if self.tz else dt.datetime.now()

    def path(self, rel: str) -> Path:
        p = (self.root / rel).resolve()
        if p != self.root.resolve() and self.root.resolve() not in p.parents:
            raise ValueError(f"{rel} is outside the vault")
        return p

    @staticmethod
    def safe_name(name: str, fallback="Untitled") -> str:
        n = BAD_NAME.sub(" ", (name or "").strip())
        n = re.sub(r"\s+", " ", n).strip(" .")
        return (n or fallback)[:120]

    def _state(self, name: str, default):
        try:
            return json.loads((self.state_dir / name).read_text())
        except Exception:
            return default

    def _save_state(self, name: str, value):
        target = self.state_dir / name
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.parent / f".{target.name}.{os.getpid()}.{threading.get_ident()}.tmp"
        tmp.write_text(json.dumps(value, ensure_ascii=False))
        os.replace(tmp, target)

    def _write(self, rel: str, text: str) -> bool:
        """Writes only when the content changed (keeps git history and Obsidian's file watcher quiet)."""
        p = self.path(rel)
        if p.exists() and p.read_text(encoding="utf-8", errors="replace") == text:
            return False
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.parent / f".{p.name}.{os.getpid()}.tmp"
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, p)
        self.dirty.add(rel)
        return True

    # ------------------------------------------------------------------ frontmatter + managed blocks

    @staticmethod
    def _yaml_value(v) -> str:
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        if isinstance(v, (list, tuple)):
            return "[" + ", ".join(Vault._yaml_value(x) for x in v) + "]"
        s = str(v)
        if s == "" or re.search(r'[:#\[\]{},&*!|>\'"%@`]|^\s|\s$|^(true|false|null|\d[\d.-]*)$', s):
            return json.dumps(s, ensure_ascii=False)
        return s

    @staticmethod
    def split_frontmatter(text: str) -> tuple[list[str], str]:
        if text.startswith("---\n"):
            end = text.find("\n---", 4)
            if end != -1:
                nl = text.find("\n", end + 4)
                return text[4:end].split("\n"), text[nl + 1:] if nl != -1 else ""
        return [], text

    def frontmatter(self, props: dict, existing: list[str] | None = None) -> str:
        """Our keys replaced, everyone else's keys kept in their original order."""
        ours = {k: v for k, v in props.items() if v is not None}
        lines, skipping = [], False
        for line in existing or []:
            m = re.match(r"^([A-Za-z0-9_-]+):", line)
            if m:
                skipping = m.group(1) in ours
            if not skipping:
                lines.append(line)
        lines += [f"{k}: {self._yaml_value(v)}" for k, v in ours.items()]
        return "---\n" + "\n".join(l for l in lines if l.strip() or l == "") + "\n---\n"

    def write_managed(self, rel: str, props: dict, body: str, user_tail: str = "\n## Notes\n\n") -> bool:
        """Sets the managed block (and our frontmatter keys) of a note; everything else in it is kept."""
        p = self.path(rel)
        old = p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
        fm, rest = self.split_frontmatter(old)
        block = f"{BEGIN}\n{body.rstrip()}\n{END}"
        if BEGIN in rest and END in rest:
            i, j = rest.index(BEGIN), rest.index(END) + len(END)
            new_rest = rest[:i] + block + rest[j:]
        elif old:
            new_rest = block + "\n\n" + rest.lstrip("\n")  # an existing note we hadn't managed: our block goes first
        else:
            new_rest = block + "\n" + user_tail
        props = dict(props, managed_by="blacklab", blacklab_schema=SCHEMA)
        if "up" not in props:  # wire the note into the neural network (its node or nodes)
            import vault_nodes
            ups = vault_nodes.up_links(rel, props)
            if ups:
                props["up"] = ups
        return self._write(rel, self.frontmatter(props, fm) + new_rest)

    def user_text(self, rel: str) -> str:
        """What people (not BlackLab) wrote in a note."""
        p = self.path(rel)
        if not p.exists():
            return ""
        _, rest = self.split_frontmatter(p.read_text(encoding="utf-8", errors="replace"))
        if BEGIN in rest and END in rest:
            rest = rest[:rest.index(BEGIN)] + rest[rest.index(END) + len(END):]
        return rest.strip()

    def _collection(self, key: str, written: set[str]):
        """Notes this collection wrote last time but not now move to 99 Archive (never deleted)."""
        state = self._state("collections.json", {})
        before = set(state.get(key, []))
        for rel in sorted(before - written):
            src = self.path(rel)
            if src.exists():
                dst = self.path(f"{FOLDERS['archive']}/{rel}")
                dst.parent.mkdir(parents=True, exist_ok=True)
                if dst.exists():
                    dst = dst.with_name(f"{dst.stem} ({int(time.time())}){dst.suffix}")
                shutil.move(str(src), str(dst))
                self.dirty.update({rel, str(dst.relative_to(self.root))})
        state[key] = sorted(written)
        self._save_state("collections.json", state)

    # ------------------------------------------------------------------ setup

    def ensure(self):
        """Creates the vault on first run: folders, Obsidian settings, templates, bases, canvas, README, registry."""
        with self.lock:
            for f in FOLDERS.values():
                self.path(f).mkdir(parents=True, exist_ok=True)
            import vault_layout  # the static files (kept separate so this module stays readable)
            for rel, text in vault_layout.files(self).items():
                p = self.path(rel)
                if not p.exists():  # created once; after that they're yours to change
                    self._write(rel, text)
            for rel, text in vault_layout.always(self).items():
                self._write(rel, text)  # BlackLab-owned system files, refreshed every time
            if not self.path("Home.md").exists():
                self.render_home()
            if not self.path("AI Registry.md").exists():
                self.render_registry([])
            self._git_init()

    # ------------------------------------------------------------------ timeline (daily notes)

    def day_rel(self, d: dt.date) -> str:
        return f"{FOLDERS['daily']}/{d.year}/{d.isoformat()}.md"

    def log(self, kind: str, title: str, detail: str = "", source: str = "BlackLab", links: list[str] | None = None,
            at: dt.datetime | None = None) -> bool:
        """One line on today's timeline. Refused (False) when the text is clinical or off the record."""
        ok, title = guards.vault_safe(title)
        ok2, detail = guards.vault_safe(detail)
        if not ok or not ok2:
            return False
        at = at or self.now()
        entry = {"t": at.strftime("%H:%M"), "ts": at.timestamp(), "kind": kind, "title": title[:200], "detail": detail[:1500],
                 "source": source[:60], "links": [l[:120] for l in (links or [])][:8]}
        with self.lock:
            name = f"timeline/{at.date().isoformat()}.json"
            items = self._state(name, [])
            key = (entry["kind"], entry["title"], entry["source"])
            if items and (items[-1]["kind"], items[-1]["title"], items[-1]["source"]) == key and entry["ts"] - items[-1]["ts"] < 120:
                return True  # the same thing twice within two minutes
            items.append(entry)
            self._save_state(name, items[-500:])
            self.render_day(at.date())
        return True

    KIND_ICON = {"task": "✅", "event": "📅", "note": "📝", "facility": "🏢", "garage": "🚙", "meeting": "🎙️", "ai": "🤖",
                 "agent": "🧭", "project": "🧩", "file": "📄", "learning": "🎓", "sync": "🔄", "brief": "📰", "registry": "✍️"}

    def render_day(self, d: dt.date, agenda: list[dict] | None = None):
        items = self._state(f"timeline/{d.isoformat()}.json", [])
        lines = [f"# {d.strftime('%A, %B')} {d.day}, {d.year}", ""]
        prev, nxt = d - dt.timedelta(days=1), d + dt.timedelta(days=1)
        lines.append(f"← [[{prev.isoformat()}]] · [[Home]] · [[{nxt.isoformat()}]] →")
        if agenda is None:
            agenda = self._state(f"agenda/{d.isoformat()}.json", None)
        else:
            self._save_state(f"agenda/{d.isoformat()}.json", agenda)
        if agenda:
            lines += ["", "## Agenda", ""]
            for e in agenda:
                lines.append(f"- {e.get('time', '')} **{e.get('title', '')}**" + (f" · {e['location']}" if e.get("location") else ""))
        lines += ["", "## Timeline", ""]
        if not items:
            lines.append("_Nothing logged yet today._")
        for e in sorted(items, key=lambda x: x["ts"]):
            links = " ".join(f"[[{l}]]" for l in e.get("links", []))
            icon = self.KIND_ICON.get(e["kind"], "•")
            lines.append(f"- `{e['t']}` {icon} **{e['title']}**" + (f" — {links}" if links else "") + f" <small>({e['source']})</small>")
            if e.get("detail"):
                for dl in e["detail"].splitlines()[:12]:
                    lines.append(f"    {dl}")
        kinds = sorted({e["kind"] for e in items})
        self.write_managed(self.day_rel(d), {"type": "daily", "date": d.isoformat(), "entries": len(items), "tags": ["daily"] + [f"log/{k}" for k in kinds]},
                           "\n".join(lines), user_tail="\n## Notes\n\n")

    # ------------------------------------------------------------------ AI registry

    def checkin(self, agent: str, model: str = "", purpose: str = "", note: str = "", where: str = "") -> dict:
        """An AI signs the registry: who, which model, why it came, and a friendly note."""
        ok, note = guards.vault_safe(note)
        ok2, purpose = guards.vault_safe(purpose)
        if not (ok and ok2):
            return {"ok": False, "error": "That note can't go in the vault (it looks clinical or private)."}
        at = self.now()
        with self.lock:
            regs = self._state("registry.json", [])
            entry = {"ts": at.timestamp(), "when": at.strftime("%Y-%m-%d %H:%M %Z").strip(), "agent": agent[:60] or "Unknown AI", "model": model[:80],
                     "purpose": purpose[:300], "note": note[:600], "where": where[:80],
                     "signature": hashlib.sha256(f"{agent}|{model}|{at.timestamp()}|{note}".encode()).hexdigest()[:12]}
            regs.append(entry)
            self._save_state("registry.json", regs[-2000:])
            self.render_registry(regs)
            self.log("registry", f"{entry['agent']} signed the AI Registry", purpose, source=entry["agent"], links=["AI Registry"], at=at)
        return {"ok": True, "signature": entry["signature"], "when": entry["when"]}

    def render_registry(self, regs: list[dict] | None = None):
        regs = regs if regs is not None else self._state("registry.json", [])
        agents: dict[str, dict] = {}
        for r in regs:
            a = agents.setdefault(r["agent"], {"visits": 0, "first": r["when"], "last": r["when"], "model": r["model"]})
            a["visits"] += 1
            a["last"], a["model"] = r["when"], r["model"] or a["model"]
        lines = ["# 🖋️ AI Registry", "",
                 "> Every AI that works in this vault signs in here — **before** it changes anything — with its name, model,",
                 "> why it came and a friendly note. Read [[AI Protocol]] first. (Agents with the vault MCP server call",
                 "> `vault_checkin`; anything else may add a line under *Guest book* by hand.)", "",
                 "## Who has been here", "", "| Agent | Model | Visits | First seen | Last seen |", "|---|---|---|---|---|"]
        for name, a in sorted(agents.items(), key=lambda kv: -kv[1]["visits"]):
            lines.append(f"| **{name}** | {a['model'] or '—'} | {a['visits']} | {a['first']} | {a['last']} |")
        if not agents:
            lines.append("| — | — | 0 | — | — |")
        lines += ["", "## Signatures (newest first)", ""]
        for r in reversed(regs[-300:]):
            lines.append(f"> [!quote] {r['agent']}" + (f" · {r['model']}" if r['model'] else "") + f" — {r['when']}")
            if r.get("purpose"):
                lines.append(f"> **Came to:** {r['purpose']}")
            if r.get("note"):
                lines.append(f"> {r['note']}")
            lines.append(f"> <small>signature `{r['signature']}`" + (f" · from {r['where']}" if r.get("where") else "") + "</small>")
            lines.append("")
        self.write_managed("AI Registry.md", {"type": "registry", "signatures": len(regs), "agents": len(agents), "tags": ["system/registry"],
                                              "cssclasses": ["blacklab-registry"]},
                           "\n".join(lines), user_tail="\n## Guest book\n\n_AIs without the MCP server: add `- YYYY-MM-DD · Name · model — note` here._\n\n")

    # ------------------------------------------------------------------ the phone's feed

    @staticmethod
    def _date(ms_or_day) -> dt.date | None:
        if ms_or_day in (None, "", 0):
            return None
        if isinstance(ms_or_day, str):
            try:
                return dt.date.fromisoformat(ms_or_day[:10])
            except ValueError:
                return None
        if ms_or_day > 10_000_000:  # epoch millis
            return dt.datetime.fromtimestamp(ms_or_day / 1000).date()
        return dt.date.fromordinal(dt.date(1970, 1, 1).toordinal() + int(ms_or_day))  # epoch day

    def _clean(self, text) -> str:
        ok, t = guards.vault_safe(str(text or ""))
        return t if ok else "_(withheld: private)_"

    def apply_feed(self, feed: dict) -> dict:
        """Renders the phone's feed into notes. Returns counts per collection."""
        with self.lock:
            self.ensure()
            counts = {}
            for key, fn in (("facility", self._facility), ("garage", self._garage), ("tasks", self._tasks), ("events", self._events),
                            ("notes", self._notes), ("notebooks", self._notebooks), ("learning", self._learning),
                            ("recordings", self._recordings), ("journal", self._journal)):
                if key in feed and feed[key] is not None:
                    try:
                        counts[key] = fn(feed[key], feed)
                    except Exception as e:
                        self.errors = (self.errors + [f"{key}: {e}"])[-20:]
            for a in (feed.get("activity") or [])[-200:]:
                at = dt.datetime.fromtimestamp(a.get("time", 0) / 1000, self.tz) if a.get("time") else None
                if at and (self.now() - at).days < 2:
                    seen = self._state("activity-seen.json", [])
                    k = f"{a.get('time')}|{a.get('title')}"
                    if k not in seen:
                        self.log(a.get("kind", "note"), a.get("title", ""), a.get("detail", ""), source="Phone", at=at)
                        self._save_state("activity-seen.json", (seen + [k])[-3000:])
            self.last_sync["phone"] = time.time()
            self._save_state("sync.json", {"last_sync": self.last_sync, "counts": counts})
            self.render_nodes()
            self.render_home()
            return counts

    def _facility(self, f: dict, feed: dict) -> int:
        base = FOLDERS["facility"]
        written = set()
        assets = {a["id"]: a for a in f.get("assets", [])}
        log_by_asset: dict[str, list] = {}
        for e in f.get("log", []):
            log_by_asset.setdefault(e.get("assetId"), []).append(e)
        names = {}
        for a in assets.values():
            names[a["id"]] = self.safe_name(a.get("name"), "Asset")
        for aid, a in assets.items():
            name = names[aid]
            rel = f"{base}/Assets/{name}.md"
            pms = a.get("pms", [])
            lines = [f"# {name}", ""]
            facts = [("Type", a.get("kind")), ("Tag", f"`{a['tag']}`" if a.get("tag") else ""), ("Location", a.get("location")),
                     ("Make / model", " ".join(x for x in (a.get("make"), a.get("model")) if x)), ("Serial", a.get("serial")),
                     ("Installed", a.get("installed") or "")]
            lines += [f"- **{k}:** {self._clean(v)}" for k, v in facts if v]
            if a.get("notes"):
                lines += ["", self._clean(a["notes"])]
            if pms:
                lines += ["", "## Inspections & PM", "", "| Schedule | Every | Standard | Last done | Next due | Status |", "|---|---|---|---|---|---|"]
                for p in pms:
                    lines.append(f"| {p.get('title', '')} | {p.get('everyDays', '')} d | {p.get('standard', '') or '—'} | {self._date(p.get('lastDay')) or '—'} "
                                 f"| {self._date(p.get('dueDay')) or '—'} | {self._status_badge(p.get('status', ''))} |")
            hist = sorted(log_by_asset.get(aid, []), key=lambda e: e.get("day", 0), reverse=True)
            if hist:
                lines += ["", "## History", ""]
                for e in hist[:60]:
                    d = self._date(e.get("day"))
                    res = f" — **{e['result']}**" if e.get("result") else ""
                    lines.append(f"- {d} · {e.get('kind', '')}{res}: {self._clean(e.get('text', ''))}" + (f" _(by {e['by']})_" if e.get("by") else "")
                                 + (f" · [[{d}]]" if d else ""))
            worst = min((p.get("status", "OK") for p in pms), key=lambda s: {"OVERDUE": 0, "DUE_SOON": 1}.get(s, 2), default="OK")
            kind_tag = re.sub(r"[^a-z0-9]+", "-", (a.get("kind") or "other").lower()).strip("-")
            self.write_managed(rel, {"type": "asset", "asset_kind": a.get("kind", ""), "tag": a.get("tag", ""), "location": a.get("location", ""),
                                     "status": worst.lower(), "inspections": len(pms), "site": f.get("site", ""),
                                     "tags": ["facility", f"facility/{kind_tag}"]}, "\n".join(lines))
            written.add(rel)
        # One log note per month, every entry linked to its asset and day
        months: dict[str, list] = {}
        for e in f.get("log", []):
            d = self._date(e.get("day"))
            if d:
                months.setdefault(d.strftime("%Y-%m"), []).append((d, e))
        for m, entries in months.items():
            rel = f"{base}/Log/{m}.md"
            lines = [f"# Facility log · {dt.date.fromisoformat(m + '-01').strftime('%B %Y')}", "", "| Day | What | Asset | Result | Notes | By |", "|---|---|---|---|---|---|"]
            fails = 0
            for d, e in sorted(entries, key=lambda x: x[0], reverse=True):
                asset = names.get(e.get("assetId"), "")
                fails += e.get("result") == "Fail"
                note = self._clean(e.get("text", "")).replace("|", "/").replace("\n", " ")[:160]
                lines.append(f"| [[{d}]] | {e.get('kind', '')} | {f'[[{asset}]]' if asset else '—'} | {e.get('result', '') or '—'} | {note} | {e.get('by', '')} |")
            self.write_managed(rel, {"type": "facility-log", "month": m, "entries": len(entries), "failures": fails, "tags": ["facility/log"]}, "\n".join(lines))
            written.add(rel)
        self._collection("facility", written)
        # Today's facility entries on the timeline
        today = self.now().date()
        for e in f.get("log", []):
            if self._date(e.get("day")) == today:
                self._log_once(f"fac:{e.get('id')}", "facility", f"{e.get('kind', '')}: {names.get(e.get('assetId'), '')}" + (f" — {e['result']}" if e.get("result") else ""),
                               self._clean(e.get("text", "")), "Phone · Facility", [names.get(e.get("assetId"), "")])
        return len(written)

    @staticmethod
    def _status_badge(s: str) -> str:
        return {"OVERDUE": "🔴 Overdue", "DUE_SOON": "🟡 Due soon", "OK": "🟢 OK"}.get(s, s or "—")

    def _log_once(self, key: str, kind, title, detail, source, links):
        seen = self._state("logged.json", [])
        if key in seen:
            return
        if self.log(kind, title, detail, source, [l for l in links if l]):
            self._save_state("logged.json", (seen + [key])[-5000:])

    def _garage(self, g: dict, feed: dict) -> int:
        base = FOLDERS["garage"]
        v = g.get("vehicle", {})
        name = self.safe_name(v.get("name") or "Vehicle")
        written = set()
        lines = [f"# 🚙 {name}", ""]
        facts = [("Year / make / model", " ".join(str(x) for x in (v.get("year"), v.get("make"), v.get("model")) if x)), ("Trim", v.get("trim")),
                 ("Engine", v.get("engine")), ("Drive", v.get("drive")), ("Odometer", f"{v['odometer']:,} mi" if v.get("odometer") else ""),
                 ("Tire pressure", v.get("tirePsi")), ("Oil", v.get("oil")), ("Dealer", v.get("dealer")), ("Insurance", v.get("insurance"))]
        lines += [f"- **{k}:** {self._clean(x)}" for k, x in facts if x]
        if g.get("due"):
            lines += ["", "## What's due", ""] + [f"- {self._status_badge(d.get('status'))} **{d.get('name')}** — {d.get('text', '')}" for d in g["due"]]
        if g.get("recalls"):
            lines += ["", "## Recalls", ""] + [f"- {'✅' if r.get('resolved') else '⚠️'} **{r.get('component', '')}** ({r.get('campaign', '')}) — {r.get('summary', '')[:300]}" for r in g["recalls"]]
        if g.get("services"):
            lines += ["", "## Service history", "", "| Day | Miles | Work | Cost | Shop |", "|---|---|---|---|---|"]
            for s in sorted(g["services"], key=lambda s: s.get("day", 0), reverse=True)[:80]:
                d = self._date(s.get("day"))
                cost = f"${s['cost']:.2f}" if isinstance(s.get("cost"), (int, float)) else "—"
                lines.append(f"| [[{d}]] | {s.get('miles') or '—'} | {self._clean(s.get('title', ''))} | {cost} | {s.get('shop', '')} |")
        if g.get("fuel"):
            total = sum(f.get("cost") or 0 for f in g["fuel"])
            lines += ["", f"## Fuel ({len(g['fuel'])} fill-ups, ${total:,.2f})", ""]
            lines += [f"- {self._date(f.get('time'))} · {f.get('gallons', 0):.1f} gal" + (f" · ${f['cost']:.2f}" if f.get("cost") else "") + (f" · {f['station']}" if f.get("station") else "")
                      for f in sorted(g["fuel"], key=lambda f: f.get("time", 0), reverse=True)[:20]]
        rel = f"{base}/{name}.md"
        self.write_managed(rel, {"type": "vehicle", "odometer": v.get("odometer", 0), "due_soon": sum(d.get("status") in ("OVERDUE", "DUE_SOON") for d in g.get("due", [])),
                                 "tags": ["garage"]}, "\n".join(lines))
        written.add(rel)
        self._collection("garage", written)
        return len(written)

    def _tasks(self, tasks: list, feed: dict) -> int:
        base = FOLDERS["work"]
        written = set()
        asset_names = [self.safe_name(a.get("name")) for a in (feed.get("facility") or {}).get("assets", [])]
        by_list: dict[str, list] = {}
        for t in tasks:
            by_list.setdefault(f"{t.get('profile', '').title()} · {t.get('list') or 'Tasks'}", []).append(t)
        lines = ["# ✅ Tasks", "", "_Mirrored from BlackLab. Check items off in the app — edits here are kept in your notes below, not sent back._", ""]
        open_n = 0
        for lst, items in sorted(by_list.items()):
            lines += [f"## {lst}", ""]
            for t in sorted(items, key=lambda t: (t.get("completed", False), t.get("due") or "9999", -(t.get("priority") or 0))):
                open_n += not t.get("completed")
                due = f" 📅 {t['due']}" if t.get("due") else ""
                pri = " ⏫" if (t.get("priority") or 0) >= 2 else (" 🔼" if (t.get("priority") or 0) == 1 else "")
                title = self._clean(t.get("title", ""))
                links = [n for n in asset_names if n and n.lower() in title.lower()]
                lines.append(f"- [{'x' if t.get('completed') else ' '}] {title}{pri}{due}" + "".join(f" [[{n}]]" for n in links)
                             + (" #workorder" if t.get("workorder") else ""))
            lines.append("")
        rel = f"{base}/Tasks.md"
        self.write_managed(rel, {"type": "tasks", "open": open_n, "total": len(tasks), "tags": ["work/tasks"]}, "\n".join(lines))
        written.add(rel)
        # Work orders get their own notes: they link assets, days and follow-ups in the graph
        for t in tasks:
            if not t.get("workorder"):
                continue
            title = self.safe_name(t.get("title"), "Work order")
            wrel = f"{base}/Work Orders/{title}.md"
            created = self._date(t.get("created"))
            links = [n for n in asset_names if n and n.lower() in (t.get("title", "") + t.get("notes", "")).lower()]
            body = [f"# 🛠️ {title}", "", f"- **Status:** {'Done' if t.get('completed') else 'Open'}", f"- **Opened:** [[{created}]]" if created else "",
                    f"- **Due:** {t['due']}" if t.get("due") else "", f"- **Asset:** " + ", ".join(f"[[{n}]]" for n in links) if links else "",
                    "", self._clean(t.get("notes", ""))]
            self.write_managed(wrel, {"type": "work-order", "status": "done" if t.get("completed") else "open", "due": t.get("due") or "",
                                      "tags": ["work/workorder", "facility"]}, "\n".join(l for l in body if l is not None))
            written.add(wrel)
        self._collection("tasks", written)
        return len(written)

    def _events(self, events: list, feed: dict) -> int:
        today = self.now().date()
        by_day: dict[dt.date, list] = {}
        for e in events:
            d = self._date(e.get("start"))
            if d:
                by_day.setdefault(d, []).append(e)
        for d, es in by_day.items():
            if abs((d - today).days) <= 1 or self.path(self.day_rel(d)).exists():
                agenda = [{"time": (e.get("start") or "")[11:16], "title": self._clean(e.get("title")), "location": e.get("location", "")} for e in sorted(es, key=lambda e: e.get("start", ""))]
                self.render_day(d, agenda)
        lines = ["# 📅 Upcoming", ""]
        for d in sorted(k for k in by_day if k >= today)[:30]:
            lines += [f"## [[{d}]] · {d.strftime('%A')}", ""] + [f"- {(e.get('start') or '')[11:16]} **{self._clean(e.get('title'))}**" + (f" · {e['location']}" if e.get("location") else "")
                                                                 for e in sorted(by_day[d], key=lambda e: e.get("start", ""))] + [""]
        self.write_managed(f"{FOLDERS['work']}/Calendar.md", {"type": "calendar", "events": len(events), "tags": ["work/calendar"]}, "\n".join(lines))
        return len(events)

    def _notes(self, notes: list, feed: dict) -> int:
        base = f"{FOLDERS['notes']}/Keep"
        written, used = set(), set()
        for n in notes:
            if n.get("trashed"):
                continue
            ok, text = guards.vault_safe(n.get("text", ""))
            if not ok:
                continue
            title = self.safe_name(n.get("title") or text.split("\n")[0][:50], "Note")
            if title.lower() in used:
                title = f"{title} ({str(n.get('id'))[-4:]})"
            used.add(title.lower())
            rel = f"{base}/{title}.md"
            labels = [re.sub(r"[^A-Za-z0-9/_-]+", "-", l).strip("-") for l in n.get("labels", []) if l]
            self.write_managed(rel, {"type": "note", "source": "BlackLab Notes", "pinned": bool(n.get("pinned")), "archived": bool(n.get("archived")),
                                     "updated": str(self._date(n.get("updatedAt")) or ""), "tags": ["notes"] + [f"label/{l}" for l in labels]},
                               f"# {title}\n\n{text}")
            written.add(rel)
        self._collection("notes", written)
        return len(written)

    def _notebooks(self, notebooks: list, feed: dict) -> int:
        base = f"{FOLDERS['notes']}/Notebooks"
        written = set()
        for nb in notebooks:
            nbname = self.safe_name(nb.get("name"), "Notebook")
            index = [f"# {nb.get('emoji', '📓')} {nbname}", ""]
            for s in nb.get("sections", []):
                sname = self.safe_name(s.get("name"), "Section")
                index.append(f"## {sname}" + (" 🔒" if s.get("locked") else ""))
                for p in s.get("pages", []):
                    if s.get("locked"):
                        continue  # locked sections never leave the phone
                    ok, text = guards.vault_safe(p.get("text", ""))
                    if not ok:
                        continue
                    ptitle = self.safe_name(p.get("title"), "Untitled page")
                    rel = f"{base}/{nbname}/{sname}/{ptitle}.md"
                    self.write_managed(rel, {"type": "page", "notebook": nbname, "section": sname, "updated": str(self._date(p.get("updatedAt")) or ""),
                                             "tags": ["notebook"]}, f"# {ptitle}\n\n_In [[{nbname}]] › {sname}_\n\n{text}")
                    written.add(rel)
                    index.append(f"- [[{ptitle}]]")
                index.append("")
            rel = f"{base}/{nbname}/{nbname}.md"
            self.write_managed(rel, {"type": "notebook", "tags": ["notebook"]}, "\n".join(index))
            written.add(rel)
        self._collection("notebooks", written)
        return len(written)

    def _learning(self, l: dict, feed: dict) -> int:
        base = FOLDERS["learning"]
        written = set()
        for c in l.get("courses", []):
            name = self.safe_name(" ".join(x for x in (c.get("code"), c.get("title")) if x), "Course")
            rel = f"{base}/Courses/{name}.md"
            asg = [a for a in l.get("assignments", []) if a.get("courseId") == c.get("id")]
            body = [f"# 🎓 {name}", ""] + [f"- **{k}:** {v}" for k, v in (("Institution", c.get("institution")), ("Instructor", c.get("instructor")),
                                                                         ("Office hours", c.get("officeHours")), ("Where", c.get("location"))) if v]
            if asg:
                body += ["", "## Assignments", ""] + [f"- [{'x' if a.get('status') in ('SUBMITTED', 'GRADED') else ' '}] {a.get('title')} ({a.get('kind', '')})"
                                                     + (f" 📅 {a['due']}" if a.get("due") else "") for a in asg]
            if c.get("notes"):
                body += ["", self._clean(c["notes"])]
            self.write_managed(rel, {"type": "course", "tags": ["learning/course"]}, "\n".join(body))
            written.add(rel)
        for c in l.get("certs", []):
            name = self.safe_name(c.get("name"), "Certification")
            rel = f"{base}/Certifications/{name}.md"
            body = [f"# 🏅 {name}", ""] + [f"- **{k}:** {v}" for k, v in (("Issuer", c.get("issuer")), ("Status", c.get("status")),
                                                                         ("Expires", c.get("expires")), ("Exam", c.get("exam")),
                                                                         ("CEUs logged", c.get("ceus"))) if v not in (None, "")]
            if c.get("notes"):
                body += ["", self._clean(c["notes"])]
            self.write_managed(rel, {"type": "certification", "status": c.get("status", ""), "expires": c.get("expires") or "", "tags": ["learning/cert"]}, "\n".join(body))
            written.add(rel)
        self._collection("learning", written)
        return len(written)

    def _recordings(self, recs: list, feed: dict) -> int:
        base = f"{FOLDERS['meetings']}/Recordings"
        written = set()
        for r in recs:
            ok, transcript = guards.vault_safe(r.get("transcript", ""))
            ok2, notes = guards.vault_safe(r.get("notes", ""))
            if not (ok and ok2) or not (transcript or notes):
                continue
            d = self._date(r.get("createdAt"))
            title = self.safe_name(r.get("title"), "Recording")
            rel = f"{base}/{d} {title}.md" if d else f"{base}/{title}.md"
            body = [f"# 🎙️ {title}", "", f"Recorded [[{d}]]" if d else ""] + (["", "## Notes", "", notes] if notes else []) + (["", "## Transcript", "", transcript[:60_000]] if transcript else [])
            self.write_managed(rel, {"type": "recording", "date": str(d or ""), "minutes": round((r.get("durationMs") or 0) / 60000), "tags": ["meeting/recording"]}, "\n".join(body))
            written.add(rel)
        self._collection("recordings", written)
        return len(written)

    def _journal(self, entries: list, feed: dict) -> int:
        base = f"{FOLDERS['notes']}/Journal"
        written = set()
        for j in entries:
            ok, text = guards.vault_safe(j.get("text", ""))
            d = self._date(j.get("day"))
            if not ok or not d:
                continue
            rel = f"{base}/{d.year}/{d} {self.safe_name(j.get('title') or 'Journal')}.md"
            self.write_managed(rel, {"type": "journal", "date": str(d), "tags": ["journal"]}, f"# {j.get('title') or d.strftime('%A, %B %d')}\n\n[[{d}]]\n\n{text}")
            written.add(rel)
        self._collection("journal", written)
        return len(written)

    # ------------------------------------------------------------------ this computer

    def mirror_folder(self, src: Path, dest_rel: str, kind: str, since_days: int = 3650, limit: int = 2000) -> int:
        """Copies Markdown documents (meeting notes, briefs, task logs, chat transcripts) into the vault when they change."""
        if not src.is_dir():
            return 0
        n = 0
        state = self._state("mirror.json", {})
        cutoff = time.time() - since_days * 86400
        files = sorted((p for p in src.rglob("*.md") if p.is_file() and p.stat().st_mtime >= cutoff and not any(x.startswith(".") for x in p.parts)),
                       key=lambda p: p.stat().st_mtime, reverse=True)[:limit]
        for p in files:
            key = str(p)
            sig = f"{p.stat().st_mtime_ns}:{p.stat().st_size}"
            if state.get(key) == sig:
                continue
            ok, text = guards.vault_safe(p.read_text(encoding="utf-8", errors="replace"))
            if not ok:
                state[key] = sig
                continue
            rel = f"{dest_rel}/{p.relative_to(src).with_suffix('').as_posix()}.md"
            d = dt.date.fromtimestamp(p.stat().st_mtime)
            self.write_managed(rel, {"type": kind, "source": str(p).replace(str(Path.home()), "~"), "date": str(d), "tags": [f"desktop/{kind}"]},
                               text.strip() + f"\n\n_From the desktop, {d} · [[{d}]]_")
            state[key] = sig
            n += 1
        self._save_state("mirror.json", state)
        return n

    def projects(self, roots: list[Path], max_commits=40) -> int:
        """One note per git project: branch, latest release, recent commits, README summary, languages."""
        n = 0
        for root in roots:
            if not (root / ".git").exists():
                continue
            name = self.safe_name(root.name)

            def git(*args):
                r = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, timeout=20)
                return r.stdout.strip() if r.returncode == 0 else ""
            branch = git("rev-parse", "--abbrev-ref", "HEAD")
            tag = git("describe", "--tags", "--abbrev=0")
            remote = git("remote", "get-url", "origin")
            web = re.sub(r"^git@github\.com:", "https://github.com/", remote).removesuffix(".git") if remote else ""
            log = git("log", f"-{max_commits}", "--date=short", "--pretty=format:%h\t%ad\t%s")
            langs: dict[str, int] = {}
            for f in git("ls-files").splitlines():
                ext = Path(f).suffix.lower()
                if ext in (".kt", ".py", ".js", ".ts", ".html", ".css", ".md", ".sh", ".kts", ".java", ".rs", ".go", ".sql"):
                    langs[ext] = langs.get(ext, 0) + 1
            readme = next((root / r for r in ("README.md", "readme.md") if (root / r).is_file()), None)
            summary = ""
            if readme:
                paras = [p.strip() for p in re.split(r"\n\s*\n", readme.read_text(errors="replace")) if p.strip() and not p.strip().startswith(("#", "!", "<", "[!", "|"))]
                summary = paras[0][:800] if paras else ""
            lines = [f"# 🧩 {name}", ""]
            if summary:
                lines += [f"> {summary}", ""]
            lines += [f"- **Folder:** `{str(root).replace(str(Path.home()), '~')}`", f"- **Branch:** {branch}" if branch else "",
                      f"- **Latest release:** {tag}" if tag else "", f"- **Repository:** {web}" if web.startswith("http") else "",
                      "- **Code:** " + ", ".join(f"{k} ×{v}" for k, v in sorted(langs.items(), key=lambda kv: -kv[1])[:8]) if langs else ""]
            lines = [l for l in lines if l != ""] + ["", "## Recent commits", ""]
            days = set()
            for row in log.splitlines():
                parts = row.split("\t", 2)
                if len(parts) == 3:
                    h, d, s = parts
                    days.add(d)
                    lines.append(f"- `{h}` [[{d}]] {s.replace('[', '(').replace(']', ')')}")
            rel = f"{FOLDERS['projects']}/{name}.md"
            if self.write_managed(rel, {"type": "project", "status": "active", "branch": branch, "release": tag, "repo": web, "tags": ["project"]}, "\n".join(lines)):
                n += 1
            # New commits on today's timeline
            today = self.now().date().isoformat()
            for row in log.splitlines():
                parts = row.split("\t", 2)
                if len(parts) == 3 and parts[1] == today:
                    self._log_once(f"commit:{name}:{parts[0]}", "project", f"{name}: {parts[2][:140]}", "", "git", [name])
        return n

    def file_activity(self, folders: list[Path], protected: list[Path], days=2, cap=300) -> int:
        """New or changed files in the folders you watch, as a monthly index (names and links, never contents)."""
        cutoff = time.time() - days * 86400
        found = []
        for base in folders:
            if not base.is_dir():
                continue
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames[:] = [d for d in dirnames if not d.startswith(".") and d not in ("node_modules", "build", ".gradle", "__pycache__")
                               and not guards.is_protected(Path(dirpath) / d, protected) and Path(dirpath, d).resolve() != self.root.resolve()]
                for fn in filenames:
                    p = Path(dirpath) / fn
                    try:
                        st = p.stat()
                    except OSError:
                        continue
                    if fn.startswith(".") or st.st_mtime < cutoff or guards.is_protected(p, protected) or fn.endswith((".tmp", ".part", ".crdownload")):
                        continue
                    found.append((st.st_mtime, p, st.st_size))
                if len(found) > cap * 4:
                    break
        state = self._state("files.json", {})
        changed = 0
        for mtime, p, size in sorted(found, reverse=True)[:cap]:
            k = str(p)
            if state.get(k) == int(mtime):
                continue
            state[k] = int(mtime)
            changed += 1
        if changed:
            self._save_state("files.json", dict(sorted(state.items(), key=lambda kv: -kv[1])[:5000]))
        by_month: dict[str, list] = {}
        for k, m in state.items():
            by_month.setdefault(dt.date.fromtimestamp(m).strftime("%Y-%m"), []).append((m, k))
        this_month = self.now().strftime("%Y-%m")
        items = sorted(by_month.get(this_month, []), reverse=True)[:500]
        lines = [f"# 📄 Files · {self.now().strftime('%B %Y')}", "", "_New and changed files in the folders BlackLab watches (Settings › Obsidian). Names only — contents stay where they are._", ""]
        cur = None
        for m, k in items:
            d = dt.date.fromtimestamp(m)
            if d != cur:
                lines += ["", f"## [[{d}]]", ""]
                cur = d
            home_rel = k.replace(str(Path.home()), "~")
            lines.append(f"- [{Path(k).name}](file://{k.replace(' ', '%20')}) · `{home_rel}`")
        self.write_managed(f"{FOLDERS['system']}/File Activity/{this_month}.md", {"type": "file-activity", "month": this_month, "files": len(items), "tags": ["system/files"]}, "\n".join(lines))
        return changed

    # ------------------------------------------------------------------ the neural network (13 nodes + Nexus)

    def render_nodes(self) -> dict:
        import vault_nodes
        try:
            return vault_nodes.render(self)
        except Exception as e:
            self.errors = (self.errors + [f"nodes: {e}"])[-20:]
            return {}

    # ------------------------------------------------------------------ home dashboard

    def render_home(self):
        sync = self._state("sync.json", {})
        counts = sync.get("counts", {})
        regs = self._state("registry.json", [])
        today = self.now().date()
        tl = self._state(f"timeline/{today.isoformat()}.json", [])
        md_count = sum(1 for _ in self.root.rglob("*.md") if ".obsidian" not in _.parts and ".git" not in _.parts)
        last_phone = sync.get("last_sync", {}).get("phone")
        lines = [f"# BlackLab", "", f"> [!abstract] {self.now().strftime('%A, %B')} {today.day} · {md_count} notes · " +
                 (f"phone synced {dt.datetime.fromtimestamp(last_phone, self.tz).strftime('%b %d %H:%M')}" if last_phone else "waiting for the phone's first sync"),
                 "", f"**Brain:** [[◉ Nexus]] · [[Neural Map.canvas|Neural Map]] · **Today:** [[{today.isoformat()}]] · **Start here:** [[README]] · **AIs:** [[AI Registry]] · [[AI Protocol]] · **Map:** [[BlackLab Map.canvas|System map]]", "",
                 f"**Graph:** 🕸️ [Open the graph]({self._graph_uri()}) · [[Graph Health]] · Bookmarks › *Graph views* · say *“show RTU-4 in my graph”*", "",
                 "## Today so far", ""]
        lines += [f"- `{e['t']}` {self.KIND_ICON.get(e['kind'], '•')} {e['title']}" for e in sorted(tl, key=lambda x: x["ts"])[-12:]] or ["- _Nothing yet._"]
        lines += ["", "## Areas", "",
                  f"| | Area | What's there |", "|---|---|---|",
                  f"| 🏢 | [[{FOLDERS['facility']}/Assets|Facility]] | {counts.get('facility', 0)} notes · assets, inspections, monthly logs |",
                  f"| 🚙 | [[{FOLDERS['garage']}|Garage]] | service, fuel, recalls |",
                  f"| ✅ | [[Tasks]] · [[Calendar]] | {counts.get('tasks', 0)} task notes · {counts.get('events', 0)} events |",
                  f"| 📝 | [[{FOLDERS['notes']}/Keep|Notes]] · [[{FOLDERS['notes']}/Notebooks|Notebooks]] | {counts.get('notes', 0)} notes · {counts.get('notebooks', 0)} pages |",
                  f"| 🎓 | [[{FOLDERS['learning']}|Learning]] | {counts.get('learning', 0)} courses & certifications |",
                  f"| 🎙️ | [[{FOLDERS['meetings']}|Meetings]] | recordings, minutes, briefs |",
                  f"| 🧩 | [[{FOLDERS['projects']}|Projects]] | your git projects and releases |",
                  f"| 🤖 | [[{FOLDERS['ai']}|AI]] | conversations, task logs, agent sessions |", "",
                  "## Dashboards", "", "![[Open work.base]]", "", "![[Facility.base]]", "",
                  "## Recent AI visitors", ""]
        lines += [f"- **{r['agent']}** — {r['when']}: {r.get('note', '')[:120]}" for r in reversed(regs[-5:])] or ["- _No one has signed in yet._"]
        self.write_managed("Home.md", {"type": "home", "notes": md_count, "cssclasses": ["blacklab-home"], "tags": ["system/home"]}, "\n".join(lines),
                           user_tail="\n## Pinned\n\n")

    def _graph_uri(self) -> str:
        import vault_graph
        return vault_graph.graph_uri(self.name)

    # ------------------------------------------------------------------ search / read / write (assistant, MCP)

    def search(self, query: str, limit: int = 8) -> list[dict]:
        terms = [t for t in re.findall(r"[\w-]+", (query or "").lower()) if len(t) > 1]
        if not terms:
            return []
        hits = []
        for p in self.root.rglob("*.md"):
            if ".obsidian" in p.parts or ".git" in p.parts or FOLDERS["archive"] in p.parts:
                continue
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            low = text.lower()
            title = p.stem.lower()
            score = sum(low.count(t) for t in terms) + sum(5 for t in terms if t in title)
            if score and all(t in low or t in title for t in terms[:3]):
                i = min((low.find(t) for t in terms if t in low), default=0)
                snippet = re.sub(r"\s+", " ", text[max(0, i - 120): i + 240]).strip()
                hits.append({"path": str(p.relative_to(self.root)), "title": p.stem, "score": score, "snippet": snippet})
        return sorted(hits, key=lambda h: -h["score"])[:limit]

    def read(self, rel: str, max_chars=40_000) -> str:
        p = self.path(rel if rel.endswith((".md", ".canvas", ".base")) else rel + ".md")
        if ".obsidian" in p.parts or ".git" in p.parts:
            raise ValueError("Settings and history aren't readable this way.")
        return p.read_text(encoding="utf-8", errors="replace")[:max_chars]

    def write_note(self, rel: str, content: str, mode: str = "append", author: str = "AI") -> dict:
        """Writes a note for a person or an agent. Never touches BlackLab's managed blocks or Obsidian's settings."""
        ok, content = guards.vault_safe(content)
        if not ok:
            return {"ok": False, "error": "Refused: that looks clinical or private, and the vault never stores it."}
        rel = rel.strip().lstrip("/")
        if not rel.endswith(".md"):
            rel += ".md"
        parts = Path(rel).parts
        if any(x in (".obsidian", ".git") for x in parts) or not parts:
            return {"ok": False, "error": "That folder isn't writable."}
        rel = "/".join(self.safe_name(x) if i < len(parts) - 1 else self.safe_name(Path(x).stem) + ".md" for i, x in enumerate(parts))
        with self.lock:
            p = self.path(rel)
            old = p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
            stamp = self.now().strftime("%Y-%m-%d %H:%M")
            if mode == "create" and old:
                return {"ok": False, "error": f"{rel} already exists (use append)."}
            if mode == "replace" and BEGIN in old:
                mode = "append"  # a managed note: only the part outside BlackLab's block is anyone else's
            if mode == "replace":
                text = content.rstrip() + "\n"
            elif old:
                text = old.rstrip() + f"\n\n%% {author} · {stamp} %%\n" + content.rstrip() + "\n"
            else:
                text = self.frontmatter({"type": "note", "author": author, "created": stamp[:10], "tags": ["inbox"] if rel.startswith(FOLDERS["inbox"]) else []}) + content.rstrip() + "\n"
            self._write(rel, text)
        self.log("note", f"{author} wrote {Path(rel).stem}", "", source=author, links=[Path(rel).stem])
        return {"ok": True, "path": rel}

    def capture(self, text: str, title: str = "", source: str = "Phone") -> dict:
        """Quick capture into 00 Inbox ("save this to Obsidian: …")."""
        title = self.safe_name(title or text.split("\n")[0][:60], "Capture")
        return self.write_note(f"{FOLDERS['inbox']}/{title}.md", text, mode="append", author=source)

    # ------------------------------------------------------------------ git (versioned backup)

    def _git(self, *args, timeout=60) -> subprocess.CompletedProcess:
        env = dict(os.environ, GIT_AUTHOR_NAME="BlackLab Vault", GIT_AUTHOR_EMAIL="vault@blacklab.local",
                   GIT_COMMITTER_NAME="BlackLab Vault", GIT_COMMITTER_EMAIL="vault@blacklab.local")
        return subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, text=True, timeout=timeout, env=env)

    def _git_init(self):
        if shutil.which("git") is None or (self.root / ".git").exists():
            return
        self._git("init", "-q", "-b", "main")
        self.commit("Vault created by BlackLab", force=True)

    def commit(self, message: str = "", force: bool = False, min_gap: float = 300) -> str:
        """Commits pending changes (at most every [min_gap] seconds unless forced). Pushes when a remote is configured."""
        if shutil.which("git") is None or not (self.root / ".git").exists():
            return ""
        with self.lock:
            if not force and (not self.dirty or time.time() - self.last_commit < min_gap):
                return ""
            self._git("add", "-A")
            if self._git("diff", "--cached", "--quiet").returncode == 0:
                self.dirty.clear()
                return ""
            n = len(self.dirty)
            r = self._git("commit", "-q", "-m", message or f"BlackLab sync · {n} note{'s' if n != 1 else ''} · {self.now().strftime('%Y-%m-%d %H:%M')}")
            self.dirty.clear()
            self.last_commit = time.time()
            if r.returncode != 0:
                self.errors = (self.errors + [r.stderr.strip()[:200]])[-20:]
                return ""
            if self._git("remote").stdout.strip():
                p = self._git("push", "-q", "origin", "HEAD", timeout=120)
                if p.returncode != 0:
                    self.errors = (self.errors + ["push: " + p.stderr.strip()[:200]])[-20:]
            return self._git("rev-parse", "--short", "HEAD").stdout.strip()

    def history(self, n=10) -> list[dict]:
        if not (self.root / ".git").exists():
            return []
        out = self._git("log", f"-{n}", "--date=iso", "--pretty=format:%h\t%ad\t%s").stdout
        return [dict(zip(("hash", "when", "message"), l.split("\t", 2))) for l in out.splitlines() if l.count("\t") >= 2]

    # ------------------------------------------------------------------ status

    def status(self) -> dict:
        exists = self.root.is_dir()
        sync = self._state("sync.json", {})
        regs = self._state("registry.json", [])
        today = self.now().date().isoformat()
        commits = self._git("rev-list", "--count", "HEAD").stdout.strip() if exists and (self.root / ".git").exists() else "0"
        return {"path": str(self.root), "name": self.name, "exists": exists, "open_url": f"obsidian://open?vault={self.name.replace(' ', '%20')}",
                "notes": sum(1 for p in self.root.rglob("*.md") if ".obsidian" not in p.parts and ".git" not in p.parts) if exists else 0,
                "last_sync": sync.get("last_sync", {}), "counts": sync.get("counts", {}), "commits": int(commits or 0),
                "recent_commits": self.history(5) if exists else [], "registry": len(regs), "last_visitor": regs[-1] if regs else None,
                "today": len(self._state(f"timeline/{today}.json", [])), "errors": self.errors[-5:],
                "remote": (self._git("remote", "get-url", "origin").stdout.strip() if exists and (self.root / ".git").exists() else "")}
