"""The Atlas: everything on this computer, as a living part of the vault's graph (companion 1.7).

One card per document, guide, photo/video/music file (or per collection — a season pack, a photo folder), one card per
project, a folder note for every folder on the way (`up:` → its parent, so Breadcrumbs and the graph show the tree), and
**🖥️ This Computer** (hardware, OS, disks, services, toolchains, local AI models, apps) at the root. Other Obsidian vaults
on the machine are imported note-for-note into `86 Imported/` and kept in sync. Every user, technical, how-to and training
guide (yours, your projects', your toolchains' and installed software's) is a `type: guide` card, gathered in **📘 Guides**.

Privacy (unit tested in tests/test_atlas.py):
  * Local only — `85 Atlas/`, `86 Imported/` and `87 Entities/` are git-ignored, so none of it is pushed; notes that *are*
    synced only count them (vault_nodes.LOCAL_PREFIXES).
  * Protected paths and secret-looking files (keys, wallets, `client_secret*.json`, `.env`, keystores…) are never opened;
    hidden folders, browser profiles, the Crypto Desk and wallet folders are skipped entirely.
  * Text goes through guards.vault_safe_passages: a clinical or "off the record" paragraph is dropped (the card says how
    many), and keys, tokens, passwords, card numbers and VINs are scrubbed from the rest. JSON/config files are listed, never read.
  * Photos: no GPS or location is ever read or written.

Nothing is copied but text excerpts (≤ atlas_text_chars per card); the files stay where they are. A card whose file is
gone moves to 99 Archive (never deleted), like everything else BlackLab writes.
"""
from __future__ import annotations

import datetime as dt
import glob
import gzip
import hashlib
import html as htmllib
import json
import os
import re
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

import guards
import vault_nodes as VN

ATLAS = "85 Atlas"
IMPORTED = "86 Imported"
ENTITIES = "87 Entities"
COMPUTER = f"{ATLAS}/🖥️ This Computer.md"
GUIDES = f"{ATLAS}/📘 Guides.md"
HOME_HUB = f"{ATLAS}/📁 Home folder.md"
GITIGNORE = ["# On this computer only (the Atlas, imported vaults, people & topics) — never pushed", f"{ATLAS}/", f"{IMPORTED}/", f"{ENTITIES}/", "88 Drive/", "89 Mobile/"]

DOC_EXT = {".pdf", ".docx", ".pptx", ".xlsx", ".odt", ".ods", ".odp", ".rtf", ".md", ".markdown", ".txt", ".csv", ".tsv", ".html", ".htm",
           ".epub", ".ipynb", ".tex", ".rst", ".org", ".adoc"}
LEGACY_EXT = {".doc", ".xls", ".ppt"}  # carded with metadata (no reader without LibreOffice conversions)
VIDEO_EXT = {".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".wmv", ".mpg", ".mpeg", ".ts"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus", ".aac", ".wma"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp", ".tif", ".tiff"}
MEDIA_EXT = VIDEO_EXT | AUDIO_EXT | IMAGE_EXT
PROJECT_MARKERS = {".git", "package.json", "build.gradle.kts", "build.gradle", "settings.gradle.kts", "pyproject.toml", "setup.py", "Cargo.toml",
                   "go.mod", "pom.xml", "CMakeLists.txt", "requirements.txt", "composer.json", "Gemfile"}
CODE_EXT = {".kt", ".kts", ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs", ".c", ".h", ".cpp", ".cs", ".swift", ".rb", ".php", ".sh",
            ".html", ".css", ".scss", ".sql", ".lua", ".dart", ".vue", ".svelte"}
SKIP_DIRS = {"node_modules", "build", "dist", "target", ".gradle", "__pycache__", "venv", ".venv", "env", "site-packages", ".idea", ".vscode",
             ".next", "obj", "vendor", ".tox", "coverage", "Pods", "DerivedData", ".dart_tool", "intermediates", "Crypto Desk", "wallet-backup"}
GUIDE_SKIP = {"images", "languages", "ghidra_stubs", "ExerciseFiles", "src", "examples"}  # inside toolchain docs only
HOME_SKIP = {"Android", "go", "snap", "Obsidian", "BlackLab Library", "Public"}  # SDKs, module caches, the vaults themselves
PROJECT_DOC_EXT = {".md", ".markdown", ".pdf", ".docx", ".pptx", ".xlsx", ".txt", ".rst", ".adoc", ".ipynb"}
PROJECT_DOC_CAP = 80

GUIDE_RE = re.compile(r"(guide|manual|handbook|how[-_ ]?to|howto|tutorial|training|study|getting[-_ ]?started|quick[-_ ]?start|cheat[-_ ]?sheet|"
                      r"walkthrough|playbook|runbook|\bsop\b|instructions|setup|install(?:ation)?|\bfaq\b|primer|course|lesson|curriculum|onboarding)", re.I)
GUIDE_KINDS = [  # first match wins
    ("training", re.compile(r"training|course|lesson|class|study|exam|certif|curriculum|candidate|onboarding|bootcamp|ghidraclass|exercise", re.I)),
    ("how-to", re.compile(r"how[-_ ]?to|howto|tutorial|walkthrough|setup|install|getting[-_ ]?started|quick[-_ ]?start|instructions|recipe|step[-_ ]?by", re.I)),
    ("technical", re.compile(r"technical|developer|architecture|\bapi\b|reference|spec|design|admin|coding|cheat[-_ ]?sheet|runbook|sop|playbook|"
                             r"contributing|migration|internals|security|deploy|config", re.I)),
    ("user", re.compile(r"user|manual|handbook|faq|help|guide|primer|care", re.I)),
]
GUIDE_KIND_LABEL = {"user": "📗 User guides", "technical": "🛠️ Technical guides", "how-to": "🧭 How-to guides", "training": "🎓 Training & study guides"}
EPISODE_RE = re.compile(r"\bS\d{1,2}E\d{1,3}\b|\b\d{1,2}x\d{2}\b", re.I)
TEXT_CHARS = 12_000      # shown on a card
CACHE_CHARS = 80_000     # kept (safe text) for people & topic extraction


# ---------------------------------------------------------------- small helpers

def _home() -> Path:
    return Path.home()


def tilde(p: Path | str) -> str:
    s = str(p)
    h = str(_home())
    return "~" + s[len(h):] if s == h or s.startswith(h + "/") else s


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def wl(rel: str, label: str | None = None) -> str:
    """A wikilink by full path (titles repeat across folders: README, index…)."""
    r = rel.removesuffix(".md")
    return f"[[{r}|{label or Path(r).name}]]"


def file_uri(p: Path) -> str:
    import urllib.parse
    return "file://" + urllib.parse.quote(str(p))


def sha_of(p: Path, size: int) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        if size <= 256 * 1024 * 1024:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        else:  # big media: size + head + tail is plenty to spot duplicates
            h.update(str(size).encode())
            h.update(f.read(4 << 20))
            f.seek(-4 << 20, 2)
            h.update(f.read(4 << 20))
    return h.hexdigest()


def guide_kind(path: Path, heading: str = "", in_docs: bool = False, forced: bool = False) -> str | None:
    probe = f"{path.stem} {heading} {path.parent.name}"
    if not (forced or GUIDE_RE.search(path.stem) or GUIDE_RE.search(heading or "") or in_docs or path.stem.lower() in ("readme", "getting_started")):
        return None
    full = f"{probe} {path.parent.parent.name}"
    for kind, rx in GUIDE_KINDS:
        if rx.search(full):
            return kind
    return "technical" if in_docs or path.stem.lower() == "readme" else "user"


# ---------------------------------------------------------------- text extraction (stdlib + pdftotext)

def _strip_tags(t: str) -> str:
    t = re.sub(r"(?is)<(script|style|head)\b.*?</\1>", " ", t)
    t = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</h\d>|</li>|</tr>", "\n", t)
    return htmllib.unescape(re.sub(r"<[^>]+>", " ", t))


def _zip_text(p: Path, member_re: str, limit=400) -> str:
    out = []
    with zipfile.ZipFile(p) as z:
        def order(n):
            m = re.search(r"(\d+)\.x?ml$", n)
            return (int(m.group(1)) if m else 0, n)
        for n in sorted((n for n in z.namelist() if re.search(member_re, n)), key=order)[:limit]:
            xml = z.read(n)[:4_000_000].decode("utf-8", "replace")
            xml = re.sub(r"<w:tab/>", "\t", xml)
            xml = re.sub(r"</w:p>|</a:p>|</text:p>|</text:h>|<w:br/>|</si>|</row>", "\n", xml)
            out.append(_strip_tags(xml))
            if sum(map(len, out)) > CACHE_CHARS * 2:
                break
    return "\n".join(out)


def extract_text(p: Path, size: int) -> tuple[str, dict]:
    """(text, extra metadata). Never raises; unknown or unreadable formats give ''."""
    ext = p.suffix.lower()
    meta: dict = {}
    try:
        if ext == ".pdf":
            if shutil.which("pdfinfo"):
                info = subprocess.run(["pdfinfo", str(p)], capture_output=True, text=True, timeout=30).stdout
                m = re.search(r"^Pages:\s+(\d+)", info, re.M)
                if m:
                    meta["pages"] = int(m.group(1))
                m = re.search(r"^Title:\s+(.+)$", info, re.M)
                if m and len(re.findall(r"[A-Za-z]", m.group(1))) >= 4 and not re.match(r"^(untitled|microsoft word|document\d*)\b", m.group(1).strip(), re.I):
                    meta["doc_title"] = m.group(1).strip()[:160]
            if not shutil.which("pdftotext"):
                return "", meta
            r = subprocess.run(["pdftotext", "-l", "80", "-q", "-enc", "UTF-8", str(p), "-"], capture_output=True, text=True, timeout=120)
            return r.stdout, meta
        if ext == ".docx":
            return _zip_text(p, r"^word/(document|footnotes)\.xml$"), meta
        if ext == ".pptx":
            t = _zip_text(p, r"^ppt/slides/slide\d+\.xml$")
            meta["slides"] = t.count("\n\n") or None
            return t, meta
        if ext == ".xlsx":
            with zipfile.ZipFile(p) as z:
                wb = z.read("xl/workbook.xml").decode("utf-8", "replace") if "xl/workbook.xml" in z.namelist() else ""
            sheets = re.findall(r'<sheet [^>]*name="([^"]+)"', wb)
            meta["sheets"] = [htmllib.unescape(s) for s in sheets][:30]
            return "Sheets: " + ", ".join(meta["sheets"]) + "\n\n" + _zip_text(p, r"^xl/sharedStrings\.xml$"), meta
        if ext in (".odt", ".ods", ".odp"):
            return _zip_text(p, r"^content\.xml$"), meta
        if ext == ".epub":
            return _zip_text(p, r"\.(x?html?)$", limit=200), meta
        if ext == ".ipynb":
            nb = json.loads(p.read_text(errors="replace")[:5_000_000])
            return "\n\n".join("".join(c.get("source", [])) for c in nb.get("cells", [])), meta
        if ext == ".gz":
            with gzip.open(p, "rt", errors="replace") as f:
                return f.read(2_000_000), meta
        if ext in (".html", ".htm"):
            return _strip_tags(p.read_text(errors="replace")[:3_000_000]), meta
        if ext == ".rtf":
            t = p.read_text(errors="replace")[:2_000_000]
            return re.sub(r"\\[a-z]+-?\d* ?|[{}]", "", t), meta
        if ext in (".csv", ".tsv"):
            with open(p, errors="replace") as f:
                return "".join(f.readline() for _ in range(300)), meta
        if ext in DOC_EXT:
            with open(p, errors="replace") as f:
                return f.read(2_000_000), meta
    except Exception as e:  # corrupt, encrypted, timed out…
        meta["read_error"] = type(e).__name__
    return "", meta


def clean_text(t: str) -> str:
    t = t.replace("\x00", "").replace("\r", "")
    t = re.sub(r"[ \t\u00a0]+", " ", t)
    t = re.sub(r"\n[ \t]+", "\n", t)
    return re.sub(r"\n{3,}", "\n\n", t).strip()


def summarize(t: str) -> str:
    for para in re.split(r"\n\s*\n", t[:20_000]):
        s = re.sub(r"\s+", " ", para).strip()
        if len(s) < 50 or s.startswith(("#", "|", "```", "<", "![", "---", "{", "[")) or s.count("|") > 3:
            continue
        s = re.sub(r"[*_`]|\[\[|\]\]", "", s)
        return (s[:380].rsplit(" ", 1)[0] + "…") if len(s) > 380 else s
    return ""


def first_heading(t: str) -> str:
    m = re.search(r"^#\s+(.+)$", t[:3000], re.M)
    return m.group(1).strip()[:160] if m else ""


def media_meta(p: Path) -> dict:
    ext = p.suffix.lower()
    meta: dict = {}
    name = p.stem
    if ext in IMAGE_EXT:
        try:
            from PIL import Image
            with Image.open(p) as im:
                meta["dims"] = f"{im.width}×{im.height}"
                try:
                    exif = im.getexif()
                    when = exif.get(36867) or exif.get(306)  # DateTimeOriginal / DateTime — never GPS (34853)
                    if when:
                        meta["taken"] = str(when)[:10].replace(":", "-")
                except Exception:
                    pass
        except Exception:
            pass
        return meta
    m = re.search(r"\b(2160p|1080p|720p|480p|4k)\b", name, re.I)
    if m:
        meta["resolution"] = m.group(1).lower()
    y = re.search(r"(?<!\d)(19[3-9]\d|20[0-4]\d)(?!\d)", name)
    if y:
        meta["year"] = int(y.group(1))
    ep = EPISODE_RE.search(name)
    if ep:
        meta["episode"] = ep.group(0).upper()
    words = re.split(r"[._]+|\s+", name)
    title = []
    for w in words:
        if re.fullmatch(r"(19[3-9]\d|20[0-4]\d)|\(?(2160p|1080p|720p|480p|4k)\)?|S\d{1,2}(E\d{1,3})?|web-?dl|bluray|x26[45]|h\.?26[45]|hevc", w.strip("()[]"), re.I):
            break
        title.append(w)
    meta["title"] = " ".join(title).strip(" -([") or name
    return meta


# ---------------------------------------------------------------- node classification

PATH_RULES = [  # (path fragment, node ids, weight). Your own folders go in config "atlas_path_rules": [["Desktop/Certs", ["learning"], 25], …]
    ("Videos/", ["media"], 30), ("Music/", ["media"], 30), ("Pictures/", ["media"], 30), ("mining", ["mining"], 20),
    ("GhidraClass", ["learning", "systems"], 25), ("/usr/share/doc", ["systems"], 25), ("blacklab-toolchain", ["systems"], 20),
    ("BlackLab AI/Business Intelligence", ["finance", "projects"], 15), ("Reverse Engineering", ["systems", "ai"], 15),
]
_EXTRA_RULES: list = []


def set_path_rules(rules) -> None:
    """Extra rules from the companion config (kept out of the code: they name your own folders)."""
    _EXTRA_RULES[:] = [(str(r[0]), list(r[1]), int(r[2]) if len(r) > 2 else 20) for r in (rules or []) if isinstance(r, (list, tuple)) and len(r) >= 2]


def classify(path_str: str, title: str, text: str, kind: str) -> list[str]:
    low = f"{path_str} {title} {text[:8000]}".lower()
    scores = {n["id"]: VN._keyword_hits(n, low) for n in VN.NODES}
    for frag, ids, w in PATH_RULES + _EXTRA_RULES:
        if frag.lower() in path_str.lower():
            for i in ids:
                if i in scores:
                    scores[i] += w
    if kind in ("media", "media-collection"):
        scores["media"] = scores.get("media", 0) + 30
    if kind == "project":
        scores["projects"] = scores.get("projects", 0) + 30
    if kind == "guide":
        scores["knowledge"] = scores.get("knowledge", 0) + 2
    ranked = sorted(scores.items(), key=lambda kv: -kv[1])
    top = ranked[0][1] if ranked else 0
    picks = [i for i, s in ranked if s >= max(3, top * 0.5)][:3]
    return picks or (["media"] if kind.startswith("media") else ["knowledge"])


# ---------------------------------------------------------------- the scan

class Atlas:
    def __init__(self, v, config: dict | None = None, protected: list[Path] | None = None, home: Path | None = None):
        self.v = v
        self.config = config or {}
        self.home = Path(home or _home())
        self.protected = list(protected or guards.protected_paths(self.home, self.config.get("protected_paths", [])))
        self.protected += [self.home / "Desktop" / "BlackLab AI" / "Crypto Desk", self.home / ".config", self.home / ".local" / "share" / "keyrings"]
        self.text_dir = Path(v.state_dir) / "atlas_text"
        self.state = v._state("atlas.json", {"files": {}, "imports": {}})
        self.state.setdefault("files", {})
        self.state.setdefault("imports", {})
        self.text_chars = int(self.config.get("atlas_text_chars", TEXT_CHARS))
        set_path_rules(self.config.get("atlas_path_rules"))
        self.stats = {"scanned": 0, "read": 0, "withheld": 0, "skipped_protected": 0}

    # --- what to look at

    def vault_roots(self) -> list[Path]:
        """Every Obsidian vault on this machine except the BlackLab vault (those are imported, not carded)."""
        out = []
        try:
            data = json.loads((self.home / ".config" / "obsidian" / "obsidian.json").read_text())
            for x in data.get("vaults", {}).values():
                p = Path(x.get("path", ""))
                if p.is_dir() and p.resolve() != self.v.root.resolve():
                    out.append(p)
        except Exception:
            pass
        for extra in self.config.get("vault_import", []):
            p = Path(os.path.expanduser(extra))
            if p.is_dir() and p not in out and p.resolve() != self.v.root.resolve():
                out.append(p)
        return out

    def roots(self) -> list[tuple[Path, str, str]]:
        """(path, mode, label). mode: home (everything) · guides (doc files under it are guides) · guides-match (only guide-named files)."""
        roots = [(Path(os.path.expanduser(r)), "home", "") for r in self.config.get("atlas_roots", [str(self.home)])]
        for g in self.config.get("atlas_guide_roots", ["~/.local/blacklab-toolchain/*/docs", "~/.local/blacklab-toolchain/*/README*"]):
            for p in sorted(glob.glob(os.path.expanduser(g))):
                pp = Path(p)
                label = pp.parent.name if pp.is_dir() else pp.parent.name
                roots.append((pp, "guides", f"Toolchain docs/{label}"))
        if self.config.get("atlas_system_guides", True) is not False and Path("/usr/share/doc").is_dir():
            roots.append((Path("/usr/share/doc"), "guides-match", "Installed software docs"))
        return roots

    def _skip_dir(self, d: Path, name: str, top: bool, vaults: set[Path], mode: str = "home") -> bool:
        if mode != "home" and name in GUIDE_SKIP:
            return True
        if name.startswith(".") or name in SKIP_DIRS or (top and name in HOME_SKIP) or name in self.config.get("atlas_exclude_names", []):
            return True
        if any(str(d).startswith(os.path.expanduser(x)) for x in self.config.get("atlas_exclude", [])):
            return True
        if d.resolve() == self.v.root.resolve():
            return True
        if guards.is_protected(d, self.protected):
            self.stats["skipped_protected"] += 1
            return True
        return False

    def scan(self, budget_s: float | None = None) -> dict:
        """Walks every root; reads only what changed (size + mtime). Returns counts."""
        t0 = time.time()
        vaults = {p.resolve() for p in self.vault_roots()}
        seen: set[str] = set()
        projects: dict[str, dict] = self.state.setdefault("projects", {})
        seen_projects: set[str] = set()
        files = self.state["files"]
        for root, mode, label in self.roots():
            if not root.exists():
                continue
            self._root = str(root if root.is_dir() else root.parent)
            if root.is_file():
                self._consider(root, root.parent, mode, label, None, seen, files, in_docs=True)
                continue
            for dirpath, dirnames, filenames in os.walk(root):
                d = Path(dirpath)
                top = d == self.home
                dirnames[:] = sorted(x for x in dirnames if not self._skip_dir(d / x, x, top, vaults, mode))
                if mode == "home" and d.resolve() in vaults:
                    # an imported vault: its notes come in through import_vaults; a project vault (the app repo) still gets its card
                    if not (PROJECT_MARKERS & set(filenames + os.listdir(d))):
                        dirnames[:] = []
                        continue
                project = None
                if mode == "home":
                    names = set(os.listdir(d))
                    if d != self.home and PROJECT_MARKERS & names and not any(str(d).startswith(pr + "/") for pr in seen_projects):
                        projects[str(d)] = self._project(d, projects.get(str(d), {}))
                        seen_projects.add(str(d))
                    project = next((pr for pr in seen_projects if str(d) == pr or str(d).startswith(pr + "/")), None)
                    if project and Path(project).resolve() in vaults:
                        continue  # docs of a project that is itself an imported vault come in through the import
                in_docs = mode == "guides" or bool(re.search(r"(^|/)(docs?|guides?|manuals?|wiki|handbook|training|tutorials?)(/|$)", str(d), re.I))
                for fn in sorted(filenames):
                    if fn.startswith(".") or fn.endswith((".tmp", ".part", ".crdownload", "~")):
                        continue
                    self._consider(d / fn, d, mode, label, project, seen, files, in_docs)
                if budget_s and time.time() - t0 > budget_s:
                    break
        for k in [k for k in files if k not in seen]:
            del files[k]
        for k in [k for k in projects if k not in seen_projects]:
            del projects[k]
        self._mark_duplicates()
        self.state["scanned_at"] = time.time()
        self.v._save_state("atlas.json", self.state)
        return dict(self.stats, files=len(files), projects=len(projects), seconds=round(time.time() - t0, 1))

    def _consider(self, p: Path, d: Path, mode: str, label: str, project: str | None, seen: set, files: dict, in_docs: bool):
        ext = p.suffix.lower()
        is_gz_doc = p.name.lower().endswith((".txt.gz", ".md.gz", ".html.gz"))
        if mode == "guides-match" and not (GUIDE_RE.search(p.name) and (ext in DOC_EXT or is_gz_doc)):
            return
        if project and ext not in PROJECT_DOC_EXT:
            return
        if not (ext in DOC_EXT or ext in LEGACY_EXT or ext in MEDIA_EXT or is_gz_doc):
            return
        if guards.is_protected(p, self.protected):
            self.stats["skipped_protected"] += 1
            return
        try:
            st = p.stat()
        except OSError:
            return
        if st.st_size == 0:
            return
        k = str(p)
        seen.add(k)
        self.stats["scanned"] += 1
        sig = f"{st.st_mtime_ns}:{st.st_size}"
        rec = files.get(k)
        if rec and rec.get("sig") == sig and rec.get("v") == 2:
            rec.update(mode=mode, label=label, project=project, root=self._root)
            return
        rec = {"v": 2, "sig": sig, "path": k, "dir": str(d), "ext": ".gz" if is_gz_doc else ext, "size": st.st_size, "mtime": st.st_mtime,
               "mode": mode, "label": label, "project": project, "root": self._root}
        try:
            rec["sha"] = sha_of(p, st.st_size)
        except OSError:
            return
        if ext in MEDIA_EXT:
            rec["kind"] = "media"
            rec["media"] = "video" if ext in VIDEO_EXT else "audio" if ext in AUDIO_EXT else "image"
            rec.update(media_meta(p))
        else:
            text, meta = extract_text(p, st.st_size) if (ext in DOC_EXT or is_gz_doc) else ("", {})
            self.stats["read"] += 1
            text = clean_text(text)
            safe, dropped = guards.vault_safe_passages(text[: CACHE_CHARS * 2])
            safe = safe[:CACHE_CHARS]
            rec.update(meta)
            rec["withheld"] = dropped
            self.stats["withheld"] += dropped
            rec["words"] = len(text.split())
            rec["heading"] = first_heading(safe) if ext in (".md", ".markdown") else meta.get("doc_title", "")
            rec["summary"] = summarize(safe)
            gk = guide_kind(p, rec["heading"], in_docs=in_docs and ext not in (".csv", ".tsv"), forced=mode != "home")
            if gk and project and p.stem.lower() not in ("readme",) and not in_docs and not GUIDE_RE.search(p.stem):
                gk = None
            rec["kind"] = "guide" if gk else "document"
            if gk:
                rec["guide_kind"] = gk
            key = hashlib.sha1(k.encode()).hexdigest()[:20]
            self.text_dir.mkdir(parents=True, exist_ok=True)
            (self.text_dir / f"{key}.txt").write_text(safe, encoding="utf-8")
            rec["text_key"] = key
        rec["nodes"] = classify(tilde(p), p.stem, self.cached_text(rec), rec["kind"])
        files[k] = rec

    def cached_text(self, rec: dict) -> str:
        if not rec.get("text_key"):
            return ""
        try:
            return (self.text_dir / f"{rec['text_key']}.txt").read_text(encoding="utf-8")
        except OSError:
            return ""

    def _mark_duplicates(self):
        by_sha: dict[str, list[dict]] = {}
        for rec in self.state["files"].values():
            if rec.get("sha"):
                by_sha.setdefault(rec["sha"], []).append(rec)
        for recs in by_sha.values():
            copyish = re.compile(r"\bcopy\b|\(\d+\)|[_ -]copy|\bbackup\b|\bold\b", re.I)
            recs.sort(key=lambda r: (r["mode"] != "home", bool(copyish.search(Path(r["path"]).name)), "/Downloads/" in r["path"], r["mtime"], len(r["path"]), r["path"]))
            recs[0].pop("dup_of", None)
            recs[0]["also_at"] = [r["path"] for r in recs[1:]]
            for r in recs[1:]:
                r["dup_of"] = recs[0]["path"]
                r.pop("also_at", None)

    def _project(self, d: Path, old: dict) -> dict:
        def git(*args):
            try:
                r = subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True, timeout=20)
                return r.stdout.strip() if r.returncode == 0 else ""
            except Exception:
                return ""
        info = {"path": str(d), "name": d.name}
        if (d / ".git").exists():
            info["branch"] = git("rev-parse", "--abbrev-ref", "HEAD")
            info["tag"] = git("describe", "--tags", "--abbrev=0")
            remote = git("remote", "get-url", "origin")
            web = re.sub(r"^git@github\.com:", "https://github.com/", remote).removesuffix(".git") if remote else ""
            info["repo"] = re.sub(r"https://[^@/]+@", "https://", web) if web.startswith("http") else ""
            info["log"] = git("log", "-15", "--date=short", "--pretty=format:%h\t%ad\t%s").splitlines()
            listed = git("ls-files").splitlines()
        else:
            listed = []
            for dp, dn, fn in os.walk(d):
                dn[:] = [x for x in dn if not x.startswith(".") and x not in SKIP_DIRS]
                listed += [os.path.relpath(os.path.join(dp, f), d) for f in fn]
                if len(listed) > 20000:
                    break
        langs: dict[str, int] = {}
        for f in listed:
            e = Path(f).suffix.lower()
            if e in CODE_EXT or e in (".md", ".kts", ".gradle", ".toml", ".yml", ".yaml"):
                langs[e] = langs.get(e, 0) + 1
        info["files"] = len(listed)
        info["langs"] = dict(sorted(langs.items(), key=lambda kv: -kv[1])[:10])
        info["markers"] = sorted(PROJECT_MARKERS & set(os.listdir(d)))
        try:
            info["top"] = sorted(x + ("/" if (d / x).is_dir() else "") for x in os.listdir(d) if not x.startswith("."))[:60]
        except OSError:
            info["top"] = []
        readme = next((d / r for r in ("README.md", "readme.md", "README.MD", "README.txt", "README") if (d / r).is_file()), None)
        if readme:
            ok, txt = guards.vault_safe(readme.read_text(errors="replace")[:20000])
            info["summary"] = summarize(txt) if ok else ""
        info["nodes"] = classify(tilde(d), d.name, info.get("summary", ""), "project")
        return info

    # ---------------------------------------------------------------- card paths

    def dir_rel(self, rec_dir: str, rec: dict | None = None) -> str:
        """The vault folder for a source folder. Guide roots outside home are flattened under one folder per root."""
        if rec and rec.get("mode") != "home":
            sub = os.path.relpath(rec_dir, rec.get("root") or rec_dir)
            return f"{ATLAS}/Guides/{rec['label']}" + ("" if sub == "." else "/" + "/".join(self.v.safe_name(x) for x in Path(sub).parts))
        rel = os.path.relpath(rec_dir, self.home)
        if rel == ".":
            return ATLAS
        if rel.startswith(".."):
            rel = "System" + rec_dir
        return ATLAS + "/" + "/".join(self.v.safe_name(x) for x in Path(rel).parts)

    def hub_rel(self, src_dir: str) -> str:
        d = Path(src_dir)
        if d == self.home:
            return HOME_HUB
        icon = "🧩" if src_dir in self.state.get("projects", {}) else "📁"
        return f"{self.dir_rel(src_dir)}/{icon} {self.v.safe_name(d.name)}.md"

    def card_rel(self, rec: dict) -> str:
        if rec.get("card"):
            return rec["card"]
        return f"{self.dir_rel(rec['dir'], rec)}/{self.v.safe_name(Path(rec['path']).name)}.md"

    # ---------------------------------------------------------------- writing the cards

    def write(self) -> dict:
        """Renders every card, folder note, project, collection, the Guides index and This Computer. Idempotent."""
        v = self.v
        files = self.state["files"]
        projects = self.state.get("projects", {})
        ents = v._state("entities.json", {}).get("by_note", {})
        written: set[str] = set()
        hubs: dict[str, dict] = {}  # src dir → {"cards": [(rel, title, kind)], "children": set(), "other": {}}

        def hub_for(src_dir: str) -> dict:
            h = hubs.get(src_dir)
            if h is None:
                h = hubs[src_dir] = {"cards": [], "children": set()}
                d = Path(src_dir)
                if d != self.home and self.home in d.parents and src_dir not in projects:
                    parent = str(d.parent)
                    hub_for(parent)["children"].add(src_dir)
            return h

        # collections: a folder of episodes, or more than 8 photos / songs, is one card
        media_by_dir: dict[str, list[dict]] = {}
        for rec in files.values():
            if rec.get("kind") == "media" and not rec.get("dup_of"):
                media_by_dir.setdefault(rec["dir"], []).append(rec)
        collections = {}
        for d, recs in media_by_dir.items():
            episodes = sum(1 for r in recs if r.get("episode"))
            non_video = sum(1 for r in recs if r["media"] != "video")
            if episodes >= 3 or non_video > 8:
                collections[d] = recs

        guides: list[dict] = []
        for rec in sorted(files.values(), key=lambda r: r["path"]):
            if rec.get("dup_of") or (rec.get("kind") == "media" and rec["dir"] in collections):
                continue
            rec.pop("card", None)
            rel = self.card_rel(rec)
            if rel in written:  # two names that only differ in characters a file name can't hold
                rel = rel.removesuffix(".md") + f" ({rec.get('sha', '')[:6]}).md"
            rec["card"] = rel
            parent_hub = self._parent_hub_rel(rec)
            v.write_managed(rel, self._card_props(rec, parent_hub), self._card_body(rec, parent_hub, ents.get(rel, [])))
            written.add(rel)
            if rec.get("kind") == "guide":
                guides.append(dict(rec, rel=rel))
            if rec.get("mode") == "home":
                owner = rec["project"] if rec.get("project") else rec["dir"]
                hub_for(owner)["cards"].append((rel, Path(rec["path"]).name, rec["kind"]))
            else:
                hubs.setdefault("guides:" + rec["label"], {"cards": [], "children": set(), "label": rec["label"]})["cards"].append((rel, Path(rec["path"]).name, rec["kind"]))
        for d, recs in collections.items():
            rel = f"{self.dir_rel(d)}/🎞️ {self.v.safe_name(Path(d).name)} (collection).md"
            v.write_managed(rel, self._collection_props(d, recs), self._collection_body(d, recs))
            written.add(rel)
            hub_for(str(Path(d).parent) if Path(d) != self.home else d)["cards"].append((rel, Path(d).name, "media-collection"))
        for pdir, info in projects.items():
            if pdir in self.state.get("vault_projects_skip", []):
                continue
            hub_for(pdir)
            d = Path(pdir)
            if d != self.home:
                hub_for(str(d.parent))["children"].add(pdir)
        for src_dir, h in hubs.items():
            if src_dir.startswith("guides:"):
                rel = f"{ATLAS}/Guides/{h['label']}/📁 {self.v.safe_name(Path(h['label']).name)}.md"
                self._write_hub(rel, h["label"], None, h, guides_root=True)
            else:
                rel = self.hub_rel(src_dir)
                self._write_hub(rel, src_dir, projects.get(src_dir), h)
            written.add(rel)
        written.add(self.write_guides(guides))
        written.add(self.write_computer(hubs))
        self.v._collection("atlas", written)
        self.v._save_state("atlas.json", self.state)
        self.ensure_gitignore()
        return {"cards": len(written), "guides": len(guides), "collections": len(collections), "projects": len(projects)}

    def _parent_hub_rel(self, rec: dict) -> str:
        if rec.get("mode") != "home":
            return f"{ATLAS}/Guides/{rec['label']}/📁 {self.v.safe_name(Path(rec['label']).name)}.md"
        return self.hub_rel(rec["project"] or rec["dir"])

    def _card_props(self, rec: dict, parent_hub: str) -> dict:
        nodes = [VN.BY_ID[n] for n in rec.get("nodes", []) if n in VN.BY_ID]
        kind = rec["kind"]
        tags = ["atlas", f"atlas/{kind}"] + ([f"guide/{rec['guide_kind']}"] if rec.get("guide_kind") else []) + \
               ([f"media/{rec['media']}"] if rec.get("media") else [])
        props = {"type": kind, "format": rec["ext"].lstrip("."), "source": tilde(rec["path"]), "size": human(rec["size"]),
                 "modified": dt.date.fromtimestamp(rec["mtime"]).isoformat(), "sha256": rec.get("sha", "")[:16],
                 "up": [wl(parent_hub)] + [VN.link(n) for n in nodes], "nodes": [n["id"] for n in nodes], "tags": tags}
        for k in ("guide_kind", "pages", "words", "dims", "taken", "resolution", "year", "episode", "media", "slides"):
            if rec.get(k):
                props[k] = rec[k]
        if rec.get("heading") or rec.get("title"):
            props["aliases"] = [rec.get("heading") or rec.get("title")]
        if rec.get("withheld"):
            props["withheld"] = rec["withheld"]
        if rec.get("also_at"):
            props["copies"] = 1 + len(rec["also_at"])
        return props

    def _card_body(self, rec: dict, parent_hub: str, entities: list[str]) -> str:
        p = Path(rec["path"])
        icon = {"guide": "📘", "document": "📄", "media": {"video": "🎬", "audio": "🎵", "image": "🖼️"}.get(rec.get("media"), "🎞️")}.get(rec["kind"], "📄")
        title = rec.get("heading") or rec.get("title") or p.name
        lines = [f"# {icon} {title}", ""]
        if rec.get("summary"):
            lines += [f"> {rec['summary']}", ""]
        facts = [f"[Open the file]({file_uri(p)}) · `{tilde(p)}`", human(rec["size"]), f"modified [[{dt.date.fromtimestamp(rec['mtime']).isoformat()}]]"]
        if rec.get("pages"):
            facts.append(f"{rec['pages']} pages")
        if rec.get("words"):
            facts.append(f"{rec['words']:,} words")
        for k in ("dims", "resolution", "year", "episode", "taken"):
            if rec.get(k):
                facts.append(str(rec[k]))
        lines += ["- **File:** " + " · ".join(facts), f"- **In:** {wl(parent_hub)}"]
        if rec.get("guide_kind"):
            lines.append(f"- **Guide:** {GUIDE_KIND_LABEL[rec['guide_kind']]} · listed in {wl(GUIDES)}")
        nodes = [VN.BY_ID[n] for n in rec.get("nodes", []) if n in VN.BY_ID]
        if nodes:
            lines.append("- **Nodes:** " + " · ".join(VN.link(n) for n in nodes))
        if rec.get("sheets"):
            lines.append("- **Sheets:** " + ", ".join(rec["sheets"]))
        if rec.get("also_at"):
            lines.append("- **Identical copies:** " + " · ".join(f"`{tilde(x)}`" for x in rec["also_at"][:10]))
        if rec.get("read_error"):
            lines.append(f"- _Couldn't read the contents ({rec['read_error']})._")
        if entities:
            lines += ["", "## People & topics", "", " · ".join(entities[:60])]
        text = self.cached_text(rec)
        if text:
            shown = text[: self.text_chars].replace("```", "ʼʼʼ")
            more = f" · first {self.text_chars:,} of {len(text):,} characters" if len(text) > self.text_chars else ""
            withheld = f" · {rec['withheld']} private passage(s) left out" if rec.get("withheld") else ""
            lines += ["", f"> [!quote]- Text{more}{withheld}", "> ```text"] + [f"> {l}" for l in shown.split("\n")] + ["> ```"]
        elif rec.get("withheld"):
            lines += ["", f"_Contents not shown: {rec['withheld']} passage(s) looked private or clinical._"]
        return "\n".join(lines)

    def _collection_props(self, d: str, recs: list[dict]) -> dict:
        size = sum(r["size"] for r in recs)
        kinds = {r["media"] for r in recs}
        return {"type": "media-collection", "source": tilde(d), "items": len(recs), "size": human(size), "media": sorted(kinds),
                "modified": dt.date.fromtimestamp(max(r["mtime"] for r in recs)).isoformat(),
                "up": [wl(self.hub_rel(str(Path(d).parent)) if Path(d) != self.home else HOME_HUB), VN.link(VN.BY_ID["media"])],
                "nodes": ["media"], "tags": ["atlas", "atlas/media-collection"] + [f"media/{k}" for k in sorted(kinds)]}

    def _collection_body(self, d: str, recs: list[dict]) -> str:
        recs = sorted(recs, key=lambda r: (r.get("episode") or "", r["path"]))
        title = media_meta(Path(d + ".x")).get("title") or Path(d).name
        lines = [f"# 🎞️ {title}", "", f"- **Folder:** [Open]({file_uri(Path(d))}) · `{tilde(d)}`",
                 f"- **Items:** {len(recs)} · {human(sum(r['size'] for r in recs))}", f"- **Node:** {VN.link(VN.BY_ID['media'])}", "",
                 "| | Item | Size | Details |", "|---|---|---|---|"]
        for r in recs[:400]:
            det = " · ".join(str(r[k]) for k in ("episode", "resolution", "dims", "taken") if r.get(k))
            lines.append(f"| {'🎬' if r['media'] == 'video' else '🎵' if r['media'] == 'audio' else '🖼️'} | {Path(r['path']).name.replace('|', '/')} | {human(r['size'])} | {det} |")
        if len(recs) > 400:
            lines.append(f"| | _…and {len(recs) - 400} more_ | | |")
        return "\n".join(lines)

    def _write_hub(self, rel: str, src: str, project: dict | None, h: dict, guides_root=False):
        v = self.v
        if guides_root:
            title, up = f"📁 {Path(src).name}", [wl(GUIDES)]
            lines = [f"# 📘 {src}", "", f"Guides and docs from `{src}`, one card each. All guides: {wl(GUIDES)}.", ""]
            props = {"type": "folder", "tags": ["atlas", "atlas/folder", "guide"], "up": up + [VN.link(VN.BY_ID["knowledge"]), VN.link(VN.BY_ID["systems"])]}
        else:
            d = Path(src)
            parent = HOME_HUB if d.parent == self.home else (COMPUTER if d == self.home else self.hub_rel(str(d.parent)))
            if d != self.home and self.home not in d.parents:
                parent = COMPUTER
            up = [wl(parent)]
            if project:
                nodes = [VN.BY_ID[n] for n in project.get("nodes", []) if n in VN.BY_ID]
                up += [VN.link(n) for n in nodes]
                lines = [f"# 🧩 {project['name']}", ""]
                if project.get("summary"):
                    lines += [f"> {project['summary']}", ""]
                lines += [f"- **Folder:** [Open]({file_uri(d)}) · `{tilde(d)}`"]
                if project.get("branch"):
                    lines.append(f"- **Branch:** {project['branch']}" + (f" · **latest tag** {project['tag']}" if project.get("tag") else ""))
                if project.get("repo"):
                    lines.append(f"- **Repository:** {project['repo']}")
                lines.append(f"- **Files:** {project.get('files', 0):,}" + (" · **code:** " + ", ".join(f"{k} ×{c}" for k, c in project["langs"].items()) if project.get("langs") else ""))
                if project.get("markers"):
                    lines.append("- **Build:** " + ", ".join(f"`{m}`" for m in project["markers"]))
                if project.get("top"):
                    lines += ["", "## Layout", "", " · ".join(f"`{x}`" for x in project["top"])]
                if project.get("log"):
                    lines += ["", "## Recent commits", ""]
                    for row in project["log"][:15]:
                        parts = row.split("\t", 2)
                        if len(parts) == 3:
                            lines.append(f"- `{parts[0]}` [[{parts[1]}]] {parts[2].replace('[', '(').replace(']', ')')}")
                props = {"type": "project", "status": "active", "source": tilde(d), "branch": project.get("branch", ""), "release": project.get("tag", ""),
                         "repo": project.get("repo", ""), "nodes": [n["id"] for n in nodes], "tags": ["atlas", "atlas/project", "project"]}
            else:
                name = "Home folder" if d == self.home else d.name
                lines = [f"# 📁 {name}", "", f"- **Folder:** [Open]({file_uri(d)}) · `{tilde(d)}`"]
                props = {"type": "folder", "source": tilde(d), "tags": ["atlas", "atlas/folder"]}
                try:
                    entries = [e for e in os.scandir(d) if not e.name.startswith(".")]
                except OSError:
                    entries = []
                carded = {Path(r).name.removesuffix(".md") for r, _, _ in h["cards"]}
                others: dict[str, int] = {}
                names = []
                for e in entries:
                    if e.is_file(follow_symlinks=False) and self.v.safe_name(e.name) not in carded and not guards.is_protected(Path(e.path), self.protected):
                        ext = Path(e.name).suffix.lower() or "(none)"
                        others[ext] = others.get(ext, 0) + 1
                        names.append(e.name)
                if others:
                    lines += ["", "## Other files (listed, not read)", "", " · ".join(f"`{k}` ×{c}" for k, c in sorted(others.items(), key=lambda kv: -kv[1])[:20]), ""]
                    lines += [f"- `{n}`" for n in sorted(names)[:40]] + ([f"- _…and {len(names) - 40} more_"] if len(names) > 40 else [])
                props["files"] = len(entries)
            props["up"] = up
        if h["children"]:
            lines += ["", "## Folders", ""]
            for c in sorted(h["children"]):
                lines.append(f"- {wl(self.hub_rel(c))}")
        if h["cards"]:
            lines += ["", f"## Cards ({len(h['cards'])})", ""]
            icon = {"guide": "📘", "document": "📄", "media": "🎬", "media-collection": "🎞️"}
            for r, name, kind in sorted(h["cards"], key=lambda x: (x[2] != "guide", x[1].lower()))[:300]:
                lines.append(f"- {icon.get(kind, '📄')} {wl(r, name)}")
            if len(h["cards"]) > 300:
                lines.append(f"- _…and {len(h['cards']) - 300} more_")
        props["cards"] = len(h["cards"])
        v.write_managed(rel, props, "\n".join(lines))

    # ---------------------------------------------------------------- the guides index

    def write_guides(self, guides: list[dict]) -> str:
        v = self.v
        by_kind: dict[str, list[dict]] = {}
        for g in guides:
            by_kind.setdefault(g["guide_kind"], []).append(g)
        lines = ["# 📘 Guides", "", f"> [!abstract] Every user, technical, how-to and training guide on this computer — {len(guides)} in all.",
                 "> Yours, your projects', your toolchains' and installed software's. Each one is a card you can search, link and annotate.", "",
                 "**BlackLab's own guides:** [[BlackLab User Guide]] · [[BlackLab Technical Guide]] · [[BlackLab How-to Guides]] · [[BlackLab Training Guide]]", ""]
        for kind in ("user", "technical", "how-to", "training"):
            xs = sorted(by_kind.get(kind, []), key=lambda g: (g["mode"] != "home", g["path"].lower()))
            if not xs:
                continue
            lines += [f"## {GUIDE_KIND_LABEL[kind]} ({len(xs)})", ""]
            groups: dict[str, list] = {}
            for g in xs:
                where = g["label"] if g["mode"] != "home" else (Path(g["project"]).name if g.get("project") else tilde(g["dir"]))
                groups.setdefault(where, []).append(g)
            for where, gs in sorted(groups.items(), key=lambda kv: (kv[0].startswith(("Installed", "Toolchain")), kv[0].lower())):
                lines.append(f"**{where}**")
                for g in gs[:80]:
                    nodes = " ".join(VN.BY_ID[n]["emoji"] for n in g.get("nodes", []) if n in VN.BY_ID)
                    shown = g.get("heading") or (os.path.relpath(g["path"], g["root"]) if g["mode"] != "home" and g.get("root") else Path(g["path"]).name)
                    lines.append(f"- {wl(g['rel'], shown.replace('|', '/'))} {nodes}" + (f" <small>{g['pages']} pp</small>" if g.get("pages") else ""))
                if len(gs) > 80:
                    lines.append(f"- _…and {len(gs) - 80} more_")
                lines.append("")
        lib = sorted(p for p in v.root.glob("95 Library/*/*.md"))
        if lib:
            lines += [f"## 📚 Offline reference (BlackLab Library, {len(lib)} sources)", "",
                      "Searchable offline with *“look it up in the library: …”* — e.g. the devdocs manuals for bash, git, Docker, Python, Kotlin and SQLite.", ""]
            lines += [f"- [[{p.stem}]]" for p in lib[:200]] + [""]
        lines += ["## Dashboards", "", "![[Guides.base]]"]
        v.write_managed(GUIDES, {"type": "guide-index", "guides": len(guides), "up": [wl(COMPUTER), VN.link(VN.BY_ID["knowledge"]), VN.link(VN.BY_ID["learning"])],
                                 "tags": ["atlas", "guide"]}, "\n".join(lines))
        return GUIDES

    # ---------------------------------------------------------------- this computer

    def write_computer(self, hubs: dict) -> str:
        prof = machine_profile(self.home)
        files = [r for r in self.state["files"].values() if not r.get("dup_of")]
        kinds: dict[str, int] = {}
        for r in files:
            kinds[r["kind"]] = kinds.get(r["kind"], 0) + 1
        lines = ["# 🖥️ This Computer", "",
                 f"> [!abstract] {kinds.get('document', 0)} documents · {kinds.get('guide', 0)} guides · {kinds.get('media', 0)} media files · "
                 f"{len(self.state.get('projects', {}))} projects — on this machine only (never pushed).", ""]
        for title, rows in prof.items():
            if rows:
                lines += [f"## {title}", ""] + [f"- {r}" for r in rows] + [""]
        lines += ["## Explore", "", f"- {wl(HOME_HUB)} — your home folder, folder by folder", f"- {wl(GUIDES)} — every guide on the machine"]
        if self.v.path(f"{IMPORTED}").exists():
            for p in sorted(self.v.path(IMPORTED).glob("*/📥 *.md")):
                lines.append(f"- {wl(str(p.relative_to(self.v.root)))} — imported vault")
        sys_hubs = sorted(k for k in hubs if k.startswith("guides:"))
        for k in sys_hubs:
            lab = hubs[k]["label"]
            lines.append(f"- {wl(f'{ATLAS}/Guides/{lab}/📁 {self.v.safe_name(Path(lab).name)}.md', lab)}")
        lines += ["- [[Atlas.base]] · [[Entities.base]] · [[Guides.base]]", "",
                  "## How the Atlas works", "",
                  "Every 30 minutes the companion looks for new and changed files (size + time), reads only those, and updates the cards. "
                  "Protected paths, hidden folders, keys and secrets are never opened; private or clinical passages are left out. "
                  "Settings: `atlas_roots`, `atlas_exclude`, `atlas_guide_roots`, `atlas_text_chars` in the companion's config."]
        self.v.write_managed(COMPUTER, {"type": "machine", "documents": kinds.get("document", 0), "guides": kinds.get("guide", 0),
                                        "media": kinds.get("media", 0), "projects": len(self.state.get("projects", {})),
                                        "up": ["[[◉ Nexus]]", VN.link(VN.BY_ID["systems"])], "tags": ["atlas", "atlas/machine"]},
                             "\n".join(lines))
        if str(self.home) not in hubs:
            self._write_hub(HOME_HUB, str(self.home), None, {"cards": [], "children": set()})
        return COMPUTER

    def ensure_gitignore(self):
        p = self.v.path(".gitignore")
        text = p.read_text() if p.exists() else ""
        missing = [l for l in GITIGNORE[1:] if l not in text.splitlines()]
        if missing:
            self.v._write(".gitignore", text.rstrip("\n") + "\n" + GITIGNORE[0] + "\n" + "\n".join(missing) + "\n")
            # anything already committed from these folders leaves the index (the files stay on disk)
            for l in missing:
                try:
                    self.v._git("rm", "-r", "-q", "--cached", "--ignore-unmatch", l.rstrip("/"))
                except Exception:
                    pass

    # ---------------------------------------------------------------- other vaults

    IMPORT_SKIP = {"BlackLab AI": ["Chat History", "Task Logs", "Crypto Desk", "Documents/Meetings", "Documents/Briefs"]}

    def import_vaults(self) -> dict:
        """Brings every other Obsidian vault on the machine in, note for note, and keeps it in sync (the originals stay untouched).
        Folders the companion already mirrors (meetings, briefs, task logs, conversations) and the Crypto Desk are left out."""
        v = self.v
        out = {}
        written: set[str] = set()
        seen: set[str] = set()
        st = self.state["imports"]
        for src in self.vault_roots():
            label = src.name if src.name != v.name else f"{src.name} ({src.parent.name}" + (" · app repo)" if PROJECT_MARKERS & set(os.listdir(src)) else ")")
            label = v.safe_name(label)
            skip = [src / x for x in self.IMPORT_SKIP.get(src.name, [])] + [src / x for x in self.config.get("vault_import_skip", {}).get(src.name, [])]
            hub = f"{IMPORTED}/{label}/📥 {label}.md"
            notes = []
            for dirpath, dirnames, filenames in os.walk(src):
                d = Path(dirpath)
                dirnames[:] = sorted(x for x in dirnames if not x.startswith(".") and x not in SKIP_DIRS and (d / x) not in skip
                                     and not guards.is_protected(d / x, self.protected) and (d / x).resolve() != v.root.resolve())
                for fn in sorted(filenames):
                    p = d / fn
                    if not fn.endswith((".md", ".canvas")) or fn.startswith(".") or guards.is_protected(p, self.protected):
                        continue
                    relsrc = p.relative_to(src)
                    rel = f"{IMPORTED}/{label}/" + "/".join(v.safe_name(x) for x in relsrc.parts[:-1]) + ("/" if len(relsrc.parts) > 1 else "") + \
                          v.safe_name(p.stem) + p.suffix
                    try:
                        stt = p.stat()
                    except OSError:
                        continue
                    sig = f"{stt.st_mtime_ns}:{stt.st_size}"
                    written.add(rel)
                    notes.append((rel, relsrc))
                    seen.add(str(p))
                    if st.get(str(p), {}).get("sig") == sig and v.path(rel).exists():
                        continue
                    text = p.read_text(encoding="utf-8", errors="replace")
                    if p.suffix == ".canvas":
                        ok, _ = guards.vault_safe(text)
                        if ok:
                            v._write(rel, text)
                        st[str(p)] = {"sig": sig, "rel": rel}
                        continue
                    self._write_imported(rel, p, text, hub, label)
                    st.setdefault(str(p), {}).update(sig=sig, rel=rel)
            self._write_import_hub(hub, label, src, notes)
            written.add(hub)
            out[label] = len(notes)
        for k in [k for k in st if k not in seen]:
            del st[k]
        v._collection("imported", written)
        v._save_state("atlas.json", self.state)
        return out

    def _write_imported(self, rel: str, src: Path, text: str, hub: str, label: str):
        v = self.v
        src_fm, body = v.split_frontmatter(text)
        safe, dropped = guards.vault_safe_passages(body)
        key = hashlib.sha1(str(src).encode()).hexdigest()[:20]
        self.text_dir.mkdir(parents=True, exist_ok=True)
        (self.text_dir / f"{key}.txt").write_text(safe[:CACHE_CHARS], encoding="utf-8")
        self.state["imports"].setdefault(str(src), {})["text_key"] = key
        nodes = [VN.BY_ID[n] for n in classify(tilde(src), src.stem, safe, "document") if n in VN.BY_ID]
        props = VN._parse_props(src_fm)
        up = [wl(hub)] + [VN.link(n) for n in nodes]
        if props.get("up"):
            up = list(dict.fromkeys(re.findall(r"\[\[[^\]]+\]\]", props["up"]) + up))
        ours = {"imported_from": label, "source": tilde(src), "up": up, "nodes": [n["id"] for n in nodes],
                "tags": list(dict.fromkeys(VN._tags(props) + ["imported", f"imported/{VN._slug(label)}"]))}
        if "type" not in props:
            ours["type"] = "imported-note"
        if dropped:
            ours["withheld"] = dropped
        p = v.path(rel)
        old = p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
        old_fm, old_rest = v.split_frontmatter(old)
        # source keys first (refreshed), then any key someone added here, then ours
        src_keys = {m.group(1) for l in src_fm if (m := re.match(r"^([A-Za-z0-9_-]+):", l))}
        extra, keep = [], False
        for l in old_fm:
            m = re.match(r"^([A-Za-z0-9_-]+):", l)
            if m:
                keep = m.group(1) not in src_keys and m.group(1) not in ours and m.group(1) not in ("managed_by", "blacklab_schema")
            if keep:
                extra.append(l)
        fm = v.frontmatter(dict(ours, managed_by="blacklab", blacklab_schema=1), [l for l in src_fm if l.strip()] + extra)
        block = f"{'%% blacklab:begin %%'}\n{safe.rstrip()}\n\n_Imported from **{label}** · `{tilde(src)}` · in sync with the original (edit there; your notes below this line stay here)._\n{'%% blacklab:end %%'}"
        if "%% blacklab:begin %%" in old_rest and "%% blacklab:end %%" in old_rest:
            i, j = old_rest.index("%% blacklab:begin %%"), old_rest.index("%% blacklab:end %%") + len("%% blacklab:end %%")
            rest = old_rest[:i] + block + old_rest[j:]
        else:
            rest = block + "\n"
        v._write(rel, fm + rest)

    def _write_import_hub(self, hub: str, label: str, src: Path, notes: list):
        by_folder: dict[str, list] = {}
        for rel, relsrc in notes:
            by_folder.setdefault(str(relsrc.parent) if str(relsrc.parent) != "." else "(top)", []).append(rel)
        lines = [f"# 📥 {label}", "", f"> [!info] The Obsidian vault at `{tilde(src)}`, imported note for note ({len(notes)} notes) and kept in sync. "
                 "The original stays where it is — edit there; notes you add below the managed part here are kept.", ""]
        skipped = self.IMPORT_SKIP.get(src.name, [])
        if skipped:
            lines += ["_Left out (already mirrored elsewhere in the vault, or private): " + ", ".join(f"`{s}`" for s in skipped) + "._", ""]
        for folder, rels in sorted(by_folder.items()):
            lines += [f"## {folder}", ""] + [f"- {wl(r)}" for r in sorted(rels)[:200]] + [""]
        self.v.write_managed(hub, {"type": "imported-vault", "source": tilde(src), "notes": len(notes), "up": [wl(COMPUTER), "[[◉ Nexus]]"],
                                   "tags": ["imported"]}, "\n".join(lines))

    def texts(self):
        """(note rel, safe text) for every card and imported note — the input for people & topic extraction."""
        for rec in self.state["files"].values():
            if rec.get("dup_of") or not rec.get("text_key"):
                continue
            yield self.card_rel(rec), rec, self.cached_text(rec)
        for src, info in self.state["imports"].items():
            if info.get("text_key"):
                try:
                    t = (self.text_dir / f"{info['text_key']}.txt").read_text(encoding="utf-8")
                except OSError:
                    continue
                yield info.get("rel"), {"path": src, "import": True}, t


def summary(v) -> dict:
    """Counts for the MCP tool, the assistant and the companion UI (names stay out of it)."""
    st = v._state("atlas.json", {})
    files = [r for r in st.get("files", {}).values() if not r.get("dup_of")]
    kinds: dict[str, int] = {}
    for r in files:
        kinds[r.get("kind", "?")] = kinds.get(r.get("kind", "?"), 0) + 1
    guides: dict[str, int] = {}
    for r in files:
        if r.get("guide_kind"):
            guides[r["guide_kind"]] = guides.get(r["guide_kind"], 0) + 1
    ents = v._state("entities.json", {})
    return {"documents": kinds.get("document", 0), "guides": guides, "media": kinds.get("media", 0), "projects": len(st.get("projects", {})),
            "imported_notes": len(st.get("imports", {})), "duplicates": sum(1 for r in st.get("files", {}).values() if r.get("dup_of")),
            "entity_docs": len(ents.get("docs", {})), "entity_llm_pending": sum(1 for d in ents.get("docs", {}).values() if not d.get("llm")),
            "last_scan": dt.datetime.fromtimestamp(st["scanned_at"]).isoformat(timespec="minutes") if st.get("scanned_at") else None,
            "notes": {"computer": COMPUTER, "guides": GUIDES, "home": HOME_HUB}}


# ---------------------------------------------------------------- machine profile (no addresses, serials or secrets)

def _run(*cmd, timeout=10) -> str:
    try:
        r = subprocess.run(list(cmd), capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""


def machine_profile(home: Path) -> dict[str, list[str]]:
    prof: dict[str, list[str]] = {}
    hw = []
    try:
        cpu = re.search(r"^model name\s*:\s*(.+)$", Path("/proc/cpuinfo").read_text(), re.M)
        hw.append(f"**CPU:** {cpu.group(1).strip() if cpu else '?'} · {os.cpu_count()} threads")
    except OSError:
        pass
    try:
        mem = re.search(r"^MemTotal:\s+(\d+)", Path("/proc/meminfo").read_text(), re.M)
        hw.append(f"**Memory:** {int(mem.group(1)) / 1024 / 1024:.1f} GB")
    except (OSError, AttributeError):
        pass
    for l in _run("lspci").splitlines():
        if re.search(r"VGA|3D controller|Display", l):
            hw.append("**GPU:** " + re.sub(r"^\S+\s+[^:]+:\s*", "", l))
    nv = _run("nvidia-smi", "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader")
    if nv:
        hw += [f"**NVIDIA:** {x}" for x in nv.splitlines()]
    prof["Hardware"] = hw
    osr = {}
    try:
        for l in Path("/etc/os-release").read_text().splitlines():
            if "=" in l:
                k, val = l.split("=", 1)
                osr[k] = val.strip('"')
    except OSError:
        pass
    up = _run("uptime", "-p")
    prof["System"] = [f"**OS:** {osr.get('PRETTY_NAME', '?')}", f"**Kernel:** {os.uname().release}", f"**Desktop:** {os.environ.get('XDG_CURRENT_DESKTOP', '?')}"] + \
                     ([f"**Uptime:** {up}"] if up else [])
    disks = []
    for l in _run("df", "-h", "-x", "tmpfs", "-x", "devtmpfs", "-x", "squashfs", "-x", "overlay", "--output=target,size,used,avail,pcent").splitlines()[1:]:
        parts = l.split()
        if len(parts) == 5 and not parts[0].startswith(("/boot", "/snap", "/run")):
            disks.append(f"`{parts[0]}` {parts[2]} used of {parts[1]} ({parts[4]}) · {parts[3]} free")
    prof["Storage"] = disks
    big = []
    for name in sorted(os.listdir(home)):
        p = home / name
        if name.startswith(".") or not p.is_dir():
            continue
        du = _run("du", "-sh", "--one-file-system", str(p), timeout=40)
        if du:
            big.append((du.split()[0], name))
    prof["Home folder sizes"] = [f"`~/{n}` {s}" for s, n in big]
    units = _run("systemctl", "--user", "list-unit-files", "--state=enabled", "--no-legend", "--type=service,timer")
    prof["Services (yours, enabled)"] = [f"`{l.split()[0]}`" for l in units.splitlines() if l.strip()][:40]
    sysd = _run("systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain")
    keep = [l.split()[0] for l in sysd.splitlines() if re.search(r"ollama|docker|kiwix|ssh|cups|tailscale|nginx|apache|postgres|mysql|redis|libvirt|syncthing", l)]
    if keep:
        prof["Services (system, notable)"] = [f"`{k}`" for k in keep]
    tools = []
    tc = home / ".local" / "blacklab-toolchain"
    if tc.is_dir():
        tools += [f"`{x.name}` (BlackLab toolchain)" for x in sorted(tc.iterdir()) if x.is_dir() and x.name != "downloads"]
    for exe, args in (("python3", ["--version"]), ("node", ["--version"]), ("git", ["--version"]), ("docker", ["--version"]), ("ollama", ["--version"]),
                      ("adb", ["version"]), ("kiwix-serve", ["--version"]), ("obsidian", ["--version"]), ("go", ["version"]), ("rustc", ["--version"])):
        if shutil.which(exe):
            out = _run(exe, *args).splitlines()
            tools.append(f"`{exe}` {out[0][:80] if out else ''}".strip())
    prof["Toolchains & tools"] = tools
    models = _run("ollama", "list", timeout=15).splitlines()[1:]
    prof["Local AI models (Ollama)"] = [f"`{m.split()[0]}` {' '.join(m.split()[2:4])}" for m in models if m.strip()]
    apps = set()
    for d in ("/usr/share/applications", str(home / ".local/share/applications"), "/var/lib/snapd/desktop/applications", "/var/lib/flatpak/exports/share/applications"):
        for f in glob.glob(d + "/*.desktop"):
            try:
                t = Path(f).read_text(errors="replace")
            except OSError:
                continue
            if re.search(r"^NoDisplay=true", t, re.M):
                continue
            m = re.search(r"^Name=(.+)$", t, re.M)
            if m:
                apps.add(m.group(1).strip())
    prof[f"Applications ({len(apps)})"] = [" · ".join(sorted(apps, key=str.lower))] if apps else []
    vaults = []
    try:
        data = json.loads((home / ".config/obsidian/obsidian.json").read_text())
        vaults = [f"`{tilde(x['path'])}`" + (" (open)" if x.get("open") else "") for x in data.get("vaults", {}).values()]
    except Exception:
        pass
    prof["Obsidian vaults"] = vaults
    return prof


# ---------------------------------------------------------------- CLI (python3 vault_atlas.py [scan|write|import|all])

def _cli():
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    import vault as V
    cfg_path = Path.home() / ".config" / "blacklab-desktop" / "config.json"
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    v = V.Vault(Path(os.path.expanduser(cfg.get("vault_dir", "~/Obsidian/BlackLab"))), Path.home() / ".cache" / "blacklab-desktop" / "vault",
                cfg.get("timezone", "America/Chicago"))
    a = Atlas(v, cfg)
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    with v.lock:
        if what in ("scan", "all"):
            print("scan", a.scan())
        if what in ("import", "all"):
            print("import", a.import_vaults())
        if what in ("write", "all"):
            print("write", a.write())


if __name__ == "__main__":
    _cli()
