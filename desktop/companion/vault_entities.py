"""People & topic notes (companion 1.7): the knowledge graph inside the vault.

Every document card, imported note and vault note is read for the things it is *about* — people, organizations,
equipment, credentials, projects, places and topics — and each of those becomes a note in `87 Entities/<Type>/`, linked
to every note that mentions it and to the entities it keeps company with. Cards show their people & topics, so two
documents that share a vendor, a standard or a person are connected in the graph even if nobody linked them.

How names are found
  * Known lists (no AI): credentials (CHFM, CFM, CCNA, CPI…), standards and agencies (NFPA 101, ASHRAE 170, The Joint
    Commission…), equipment tags (AHU-2, RTU-4…) and your assets, your projects, and technical topics.
  * The local model (Ollama, qwen3:8b by default — nothing leaves this computer) for people, organizations, places and
    other topics in your own documents. A name is kept only if it appears word for word in the text, so the model can't
    invent anyone. Software manuals from installed packages skip this step (contributor lists aren't your people).

Privacy: the text it reads has already been through guards.vault_safe_passages (clinical passages dropped, secrets
scrubbed). Entity notes live in a local-only, git-ignored folder; never record health information on a person note.
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import re
import time
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

import guards
import vault_atlas as A
import vault_nodes as VN

ROOT = A.ENTITIES
TYPES = {  # id: (folder, emoji, default node, min docs when found by the model)
    "person": ("People", "👤", "people", 1),
    "organization": ("Organizations", "🏛️", None, 1),
    "equipment": ("Equipment", "⚙️", "facility", 1),
    "credential": ("Credentials", "🎓", "learning", 1),
    "project": ("Projects", "🧩", "projects", 1),
    "place": ("Places", "📍", None, 2),
    "topic": ("Topics", "💡", None, 2),
}
MAX_ENTITIES = 3000

CREDENTIALS = {
    "CHFM": r"\bCHFM\b|Certified Healthcare Facility Manager", "CFM": r"\bCFM\b|Certified Facility Manager", "FMP": r"\bFMP\b|Facility Management Professional",
    "SFP": r"\bSFP\b|Sustainability Facility Professional", "CHC": r"\bCHC\b(?=.{0,40}(?:constructor|certif))", "CHSP": r"\bCHSP\b|Certified Healthcare Safety Professional",
    "CHEP": r"\bCHEP\b", "CPI Nonviolent Crisis Intervention": r"\bCPI\b(?=.{0,60}(?:crisis|training|certif))|Nonviolent Crisis Intervention",
    "Mandt": r"\bMandt\b", "CPR": r"\bCPR\b", "BLS": r"\bBLS\b(?=.{0,30}(?:certif|card|course|provider))|Basic Life Support", "OSHA 10": r"\bOSHA[- ]10\b",
    "OSHA 30": r"\bOSHA[- ]30\b", "PMP": r"\bPMP\b", "CCNA": r"\bCCNA\b", "CCNP": r"\bCCNP\b", "CompTIA A+": r"CompTIA A\+|\bA\+ certif",
    "CompTIA Network+": r"\bNetwork\+", "CompTIA Security+": r"\bSecurity\+", "CompTIA Linux+": r"\bLinux\+", "CISSP": r"\bCISSP\b",
    "Certified Peer Specialist": r"Certified Peer (?:Support )?Specialist|Peer Support Specialist certification", "EPA 608": r"\bEPA (?:Section )?608\b",
    "Universal Boiler License": r"boiler (?:operator )?licen[cs]e", "Certified Pool Operator": r"Certified Pool Operator|\bCPO\b(?=.{0,30}pool)",
    "LEED AP": r"\bLEED AP\b", "Six Sigma": r"\bSix Sigma\b|\bLean Six Sigma\b",
}
ORGS = {
    "The Joint Commission": r"\bJoint Commission\b|\bTJC\b", "CMS": r"\bCMS\b(?=.{0,80}(?:survey|condition|medicare|medicaid|K-tag|regulat|cop\b))|Centers for Medicare",
    "OSHA": r"\bOSHA\b", "EPA": r"\bEPA\b", "NFPA": r"\bNFPA\b", "ASHRAE": r"\bASHRAE\b", "ASHE": r"\bASHE\b|American Society for Health Care Engineering",
    "American Hospital Association": r"\bAHA\b(?=.{0,60}(?:certif|ashe|hospital))|American Hospital Association", "IFMA": r"\bIFMA\b",
    "BOMA": r"\bBOMA\b", "FEMA": r"\bFEMA\b", "CDC": r"\bCDC\b", "NHTSA": r"\bNHTSA\b", "Cisco": r"\bCisco\b", "GitHub": r"\bGitHub\b",
    "Google": r"\bGoogle\b", "Microsoft": r"\bMicrosoft\b", "Anthropic": r"\bAnthropic\b", "Hyundai": r"\bHyundai\b", "Red Hat": r"\bRed Hat\b",
    "PSI": r"\bPSI\b(?=.{0,40}(?:exam|test|center))", "DNV": r"\bDNV\b", "USDA": r"\bUSDA\b",
}
TOPICS = {
    "NFPA 101 Life Safety Code": r"NFPA 101|Life Safety Code", "NFPA 99": r"NFPA 99\b", "NFPA 72": r"NFPA 72\b", "NFPA 25": r"NFPA 25\b",
    "NFPA 110": r"NFPA 110\b", "NFPA 70E": r"NFPA 70E", "ASHRAE 170": r"ASHRAE(?: Standard)? 170", "ASHRAE 62.1": r"ASHRAE(?: Standard)? 62\.1",
    "ASHRAE 188": r"ASHRAE(?: Standard)? 188", "ASHRAE 180": r"ASHRAE(?: Standard)? 180", "Legionella": r"\bLegionella\b",
    "Environment of Care": r"Environment of Care|\bEOC\b", "Ligature risk": r"\bligature", "Interim life safety measures": r"\bILSM\b|interim life safety",
    "Infection control risk assessment": r"\bICRA\b|infection control risk", "Preventive maintenance": r"preventive maintenance|\bPM schedule",
    "CMMS": r"\bCMMS\b", "Facility Condition Index": r"Facility Condition Index|\bFCI\b", "ENERGY STAR": r"ENERGY STAR", "Building automation": r"\bBAS\b|building automation",
    "HVAC": r"\bHVAC\b", "Emergency power": r"emergency power|\bgenerator test", "Fire drills": r"fire drills?", "Statement of Conditions": r"Statement of Conditions",
    "De-escalation": r"de-?escalat", "Trauma-informed care": r"trauma[- ]informed", "Peer support": r"\bpeer support\b", "Recovery": r"\brecovery-oriented\b",
    "Linux": r"\bLinux\b", "Docker": r"\bDocker\b", "Kubernetes": r"\bKubernetes\b|\bk8s\b", "Terraform": r"\bTerraform\b", "Ansible": r"\bAnsible\b",
    "Proxmox": r"\bProxmox\b", "VMware": r"\bVMware\b", "Git": r"\bgit\b(?= (?:commit|push|clone|branch|repo))|\bGit\b", "GitHub Actions": r"GitHub Actions",
    "CI/CD": r"\bCI/CD\b", "Python": r"\bPython\b", "Kotlin": r"\bKotlin\b", "Android": r"\bAndroid\b", "Jetpack Compose": r"Jetpack Compose|\bCompose\b(?= UI)",
    "Obsidian": r"\bObsidian\b", "Ollama": r"\bOllama\b", "Large language models": r"\bLLMs?\b|large language model", "Model Context Protocol": r"\bMCP\b|Model Context Protocol",
    "Ghidra": r"\bGhidra\b", "Reverse engineering": r"reverse[- ]engineer", "Networking": r"\bsubnet|\bVLAN\b|\bOSPF\b|\bTCP/IP\b", "Cybersecurity": r"cyber ?security",
    "Monero": r"\bMonero\b|\bXMR\b", "Bitcoin": r"\bBitcoin\b|\bBTC\b", "Cryptocurrency mining": r"\bhashrate\b|\bmining pool\b", "Budgeting": r"\bcapital budget|\boperating budget",
    "Staffing": r"\bstaffing\b", "Service dogs": r"service dog", "Dog training": r"\bdog training\b|\bobedience\b", "Resume writing": r"\bresum[eé]\b",
}
EQUIP_RE = re.compile(r"\b(?:AHU|RTU|MAU|ERV|FCU|BLR|GEN|ATS|DHW|UPS|EF|CT|CH|HX|FAC)-\d{1,4}[A-Z]?\b")
NOT_PEOPLE = re.compile(r"^(?:the|a|an|dear|mr|mrs|ms|dr|hi|hello|thanks|best|regards|chapter|section|table|figure|step|part|page|united|north|south|"
                        r"east|west|new|life|peer|support|safety|health|facility|facilities|project|team|user|guide|manual|blacklab|claude|google|microsoft)\b", re.I)


def _norm(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip().strip(".,;:()[]\"'")).casefold()


def _fname(name: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r'[*"\\/<>:|?#^\[\]\x00-\x1f]', " ", name)).strip(" .")[:100] or "Unnamed"


def entity_rel(etype: str, name: str) -> str:
    return f"{ROOT}/{TYPES[etype][0]}/{_fname(name)}.md"


# ---------------------------------------------------------------- finding names

def known(text: str, projects: list[str], assets: list[str]) -> dict[str, set[str]]:
    out: dict[str, set[str]] = defaultdict(set)
    for name, rx in CREDENTIALS.items():
        if re.search(rx, text, re.S):
            out["credential"].add(name)
    for name, rx in ORGS.items():
        if re.search(rx, text, re.S):
            out["organization"].add(name)
    for name, rx in TOPICS.items():
        if re.search(rx, text, re.I if name not in ("Git", "CI/CD", "BAS") else 0):
            out["topic"].add(name)
    for m in EQUIP_RE.finditer(text):
        out["equipment"].add(m.group(0))
    for a in assets:
        if len(a) >= 4 and re.search(r"(?<!\w)" + re.escape(a) + r"(?!\w)", text):
            out["equipment"].add(a)
    for p in projects:
        flags = re.I if len(p) >= 5 else 0
        if re.search(r"(?<![\w/.-])" + re.escape(p) + r"(?![\w-])", text, flags):
            out["project"].add(p)
    return out


SCHEMA = {"type": "object", "properties": {k: {"type": "array", "items": {"type": "string"}} for k in ("people", "organizations", "places", "topics")},
          "required": ["people", "organizations", "places", "topics"]}
PROMPT = """You extract named entities from a document for a personal knowledge graph. Return JSON with four lists.
- people: full names of real individual people mentioned (e.g. "Jane Smith"). Not job titles, not fictional characters, not companies.
- organizations: companies, vendors, agencies, schools, hospitals, associations.
- places: cities, states, countries, buildings or sites with a proper name.
- topics: up to 8 specific subjects the document is about (standards, technologies, methods), 1-4 words each.
Copy each name exactly as written in the text. If unsure, leave it out. Lists may be empty.

Document: {title}
---
{text}
---"""


def llm_entities(title: str, text: str, model: str, host: str, timeout=180) -> dict[str, list[str]]:
    body = {"model": model, "stream": False, "think": False, "format": SCHEMA, "options": {"temperature": 0, "num_ctx": 8192, "num_predict": 400},
            "messages": [{"role": "user", "content": PROMPT.format(title=title, text=text)}]}
    req = urllib.request.Request(f"http://{host}/api/chat" if not host.startswith("http") else f"{host}/api/chat",
                                 data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        msg = json.loads(r.read()).get("message", {}).get("content", "{}")
    try:
        data = json.loads(msg)
    except ValueError:
        return {}
    return {k: [str(x) for x in data.get(k, []) if isinstance(x, str)][:40] for k in ("people", "organizations", "places", "topics")}


def verify(found: dict[str, list[str]], text: str) -> dict[str, set[str]]:
    """Only names that really are in the text (case-insensitive, whole words), cleaned and plausible."""
    low = text.casefold()
    out: dict[str, set[str]] = defaultdict(set)
    for key, etype in (("people", "person"), ("organizations", "organization"), ("places", "place"), ("topics", "topic")):
        for raw in found.get(key, []):
            name = re.sub(r"\s+", " ", raw).strip(" .,;:()[]\"'")
            if not (2 <= len(name) <= 60) or not re.search(r"(?<!\w)" + re.escape(name.casefold()) + r"(?!\w)", low):
                continue
            if not guards.vault_safe(name)[0] or re.search(r"\d{3,}|@|https?:|/", name):
                continue
            if etype == "person":
                parts = name.split()
                if not (2 <= len(parts) <= 4) or NOT_PEOPLE.match(name) or not all(re.match(r"^[A-Z][a-zA-Z'’.-]*$", w) for w in parts):
                    continue
            out[etype].add(name)
    return out


# ---------------------------------------------------------------- the pass

class Entities:
    def __init__(self, v, config: dict | None = None, atlas: A.Atlas | None = None):
        self.v = v
        self.config = config or {}
        self.atlas = atlas or A.Atlas(v, self.config)
        self.state = v._state("entities.json", {})
        self.state.setdefault("docs", {})  # doc key → {"h": text hash, "found": {type: [names]}, "llm": bool}

    def sources(self):
        """(doc key, note rel, title, text, llm allowed, nodes)."""
        for rel, rec, text in self.atlas.texts():
            if not rel or not text.strip():
                continue
            yield rec["path"], rel, Path(rec["path"]).name, text, rec.get("mode", "home") == "home", rec.get("nodes", [])
        root = self.v.root
        for p in root.rglob("*.md"):
            r = p.relative_to(root).as_posix()
            drive_or_phone = r.startswith(("88 Drive/", "89 Mobile/"))  # Drive and phone cards are local, but they're documents
            if (not drive_or_phone and r.startswith(VN.LOCAL_PREFIXES + (VN.NODES_DIR + "/", "90 System/", "99 Archive/", "01 Daily/", "95 Library/", "."))) \
                    or r in ("Home.md", f"{VN.NEXUS}.md") or (drive_or_phone and ("📁" in p.name or "📱" in p.name)):
                continue
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            fm, body = self.v.split_frontmatter(text)
            body = re.sub(r"%% blacklab:(begin|end) %%", "", body)
            ok, body = guards.vault_safe(body) if len(body) < 4000 else (True, guards.vault_safe_passages(body)[0])
            if ok and body.strip():
                props = VN._parse_props(fm)
                yield "vault:" + r, r, p.stem, body, True, [n["id"] for n in VN.primary(r, props)]

    def run(self, llm: bool = True, budget: int | None = None, model: str | None = None, host: str | None = None, mining=lambda: False,
            progress=None, lock=None) -> dict:
        model = model or self.config.get("entities_model") or self.config.get("model", "qwen3:8b")
        host = host or self.config.get("ollama_host", "127.0.0.1:11434")
        budget = int(self.config.get("entities_llm_per_pass", 60)) if budget is None else budget
        projects = sorted({Path(p).name for p in self.atlas.state.get("projects", {})} | {p.stem for p in self.v.root.glob("02 Projects/*.md")})
        assets = [p.stem for p in self.v.root.glob("10 Facility/Assets/*.md")]
        docs = self.state["docs"]
        seen, used, errors = set(), 0, 0
        meta = {}
        for key, rel, title, text, llm_ok, nodes in self.sources():
            seen.add(key)
            meta[key] = (rel, nodes)
            h = hashlib.sha1(text[:A.CACHE_CHARS].encode()).hexdigest()[:16]
            d = docs.get(key)
            if not d or d.get("h") != h:
                d = docs[key] = {"h": h, "found": {k: sorted(v) for k, v in known(text, projects, assets).items()}, "llm": False}
            if len(text) < 300:
                llm_ok = False  # a task or a one-line note: the known lists are enough
            if llm and llm_ok and not d.get("llm") and used < budget and not mining() and self.config.get("entities_llm", True) is not False:
                excerpt = text[:2600] + ("\n…\n" + text[len(text) // 2: len(text) // 2 + 1000] if len(text) > 6000 else "")
                try:
                    found = verify(llm_entities(title, excerpt, model, host), text)
                    for k, names in found.items():
                        d["found"][k] = sorted(set(d["found"].get(k, [])) | names)
                    d["llm"] = True
                    used += 1
                    if progress:
                        progress(used, title)
                except Exception:
                    errors += 1
                    if errors >= 3:
                        llm = False  # Ollama is down or busy: the known lists still run; try the model next pass
        for k in [k for k in docs if k not in seen]:
            del docs[k]
        self.state["meta"] = {k: list(v) for k, v in meta.items()}
        self.v._save_state("entities.json", self.state)
        import contextlib
        with (lock or contextlib.nullcontext()):
            stats = self.write(meta)
        return dict(stats, llm_used=used, llm_pending=sum(1 for k, d in docs.items() if not d.get("llm")), errors=errors)

    def write(self, meta: dict) -> dict:
        v = self.v
        docs = self.state["docs"]
        mentions: dict[tuple, set[str]] = defaultdict(set)  # (type, norm) → doc keys
        surface: dict[tuple, Counter] = defaultdict(Counter)
        for key, d in docs.items():
            for etype, names in d.get("found", {}).items():
                if etype not in TYPES:
                    continue
                for n in names:
                    k = (etype, _norm(n))
                    mentions[k].add(key)
                    surface[k][n] += 1
        # a name found as both a person and an organization (etc.) keeps the type it was found as most often
        by_norm: dict[str, list[tuple]] = defaultdict(list)
        for k in mentions:
            by_norm[k[1]].append(k)
        for norm, ks in by_norm.items():
            if len(ks) > 1:
                best = max(ks, key=lambda k: (len(mentions[k]), k[0] in ("credential", "equipment", "project", "organization")))
                for k in ks:
                    if k != best:
                        mentions[best] |= mentions.pop(k)
                        surface[best].update(surface.pop(k))
        known_names = {_norm(n) for n in list(CREDENTIALS) + list(ORGS) + list(TOPICS)}
        keep = {}
        for k, keys in mentions.items():
            etype, norm = k
            need = 1 if etype in ("person", "organization", "equipment", "credential", "project") or norm in known_names else TYPES[etype][3]
            if len(keys) >= need:
                keep[k] = keys
        keep = dict(sorted(keep.items(), key=lambda kv: -len(kv[1]))[:MAX_ENTITIES])
        names = {k: surface[k].most_common(1)[0][0] for k in keep}
        rels = {k: entity_rel(k[0], names[k]) for k in keep}
        # co-occurrence
        per_doc: dict[str, list[tuple]] = defaultdict(list)
        for k, keys in keep.items():
            for key in keys:
                per_doc[key].append(k)
        written = set()
        by_note: dict[str, list[str]] = {}
        for key, ks in per_doc.items():
            rel = meta.get(key, (None,))[0]
            if rel and VN.is_local(rel):
                by_note[rel] = [A.wl(rels[k], names[k]) for k in sorted(ks, key=lambda k: (list(TYPES).index(k[0]), names[k].lower()))]
        for k, keys in keep.items():
            etype, _ = k
            folder, emoji, default_node, _ = TYPES[etype]
            together = Counter()
            node_votes = Counter()
            for key in keys:
                together.update(x for x in per_doc[key] if x != k)
                for n in (meta.get(key, (None, []))[1] or [])[:1]:
                    node_votes[n] += 1
            nodes = ([default_node] if default_node else []) + [n for n, _ in node_votes.most_common(2) if n != default_node]
            nodes = [VN.BY_ID[n] for n in nodes if n in VN.BY_ID][:2] or [VN.BY_ID["knowledge"]]
            notes = sorted((meta[key][0] for key in keys if key in meta and meta[key][0]), key=str.lower)
            lines = [f"# {emoji} {names[k]}", "", f"**{etype.title()}** · mentioned in **{len(notes)}** note{'s' if len(notes) != 1 else ''} · " +
                     " · ".join(VN.link(n) for n in nodes), ""]
            if etype == "person":
                lines += ["> [!note] Add who they are and how you know them below the managed part — role, organization, last contact, next step. "
                          "Never health or clinical information.", ""]
            lines += ["## Mentioned in", ""] + [f"- {A.wl(r)}" for r in notes[:150]] + ([f"- _…and {len(notes) - 150} more_"] if len(notes) > 150 else [])
            if together:
                lines += ["", "## Often together with", "", " · ".join(f"{A.wl(rels[x], names[x])} <small>×{c}</small>" for x, c in together.most_common(15))]
            rel = rels[k]
            aliases = [s for s, _ in surface[k].most_common(5) if s != names[k]]
            v.write_managed(rel, {"type": "entity", "entity": etype, "mentions": len(notes), "aliases": aliases or None,
                                  "up": [VN.link(n) for n in nodes], "nodes": [n["id"] for n in nodes], "tags": ["entity", f"entity/{etype}"]},
                            "\n".join(lines), user_tail="\n## Notes\n\n")
            written.add(rel)
        for etype, (folder, emoji, _, _) in TYPES.items():
            ks = sorted((k for k in keep if k[0] == etype), key=lambda k: -len(keep[k]))
            rel = f"{ROOT}/{emoji} {folder}.md"
            lines = [f"# {emoji} {folder} ({len(ks)})", "", f"From your documents, imported vaults and notes. All entities: [[Entities.base]] · {A.wl(A.COMPUTER)}.", ""]
            lines += [f"- {A.wl(rels[k], names[k])} <small>×{len(keep[k])}</small>" for k in ks[:500]]
            v.write_managed(rel, {"type": "entity-index", "entity": etype, "count": len(ks), "up": [A.wl(A.COMPUTER)] + ([VN.link(VN.BY_ID[TYPES[etype][2]])] if TYPES[etype][2] else []),
                                  "tags": ["entity"]}, "\n".join(lines))
            written.add(rel)
        v._collection("entities", written)
        st = v._state("entities.json", {})
        st.update(self.state)
        st["by_note"] = by_note
        self.state = st
        v._save_state("entities.json", st)
        counts = Counter(k[0] for k in keep)
        return {"entities": len(keep), **{f"{t}s": counts.get(t, 0) for t in TYPES}}


def locked_run(v, config, atlas=None, **kw) -> dict | None:
    """Runs unless another run (the companion or the CLI) holds the lock."""
    lock = Path(v.state_dir) / "entities.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    with open(lock, "w") as f:
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return None
        return Entities(v, config, atlas).run(**kw)


if __name__ == "__main__":  # python3 vault_entities.py [budget]   (the first full run: a large budget)
    import os
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    import vault as V
    cfg_path = Path.home() / ".config" / "blacklab-desktop" / "config.json"
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    v = V.Vault(Path(os.path.expanduser(cfg.get("vault_dir", "~/Obsidian/BlackLab"))), Path.home() / ".cache" / "blacklab-desktop" / "vault",
                cfg.get("timezone", "America/Chicago"))
    budget = int(sys.argv[1]) if len(sys.argv) > 1 else 10_000
    t0 = time.time()
    r = locked_run(v, cfg, budget=budget, progress=lambda i, t: print(f"[{i}] {t} · {time.time() - t0:.0f}s", flush=True))
    print("entities", r)
    if r:
        a = A.Atlas(v, cfg)
        with v.lock:
            print("cards", a.write())
            v.render_nodes()
            v.commit(force=True)
