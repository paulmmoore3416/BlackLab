"""The BlackLab Library: ~50 GB of openly licensed, offline knowledge wired into the vault's 13 nodes.

  * Kiwix ZIM packs (Stack Exchange, LibreTexts, Wikibooks, iFixit, MedlinePlus, Ready.gov, USDA canning, Wikipedia…)
    live in ~/BlackLab Library/zim and are served read-only by kiwix-serve on 127.0.0.1:8780 — full-text search
    and an offline reader, no internet needed once downloaded.
  * Public-domain US government documents live in the vault under 95 Library/<Node>/Files (opened by Obsidian,
    kept out of git).
  * Every source gets a card in 95 Library/<Node>/ (publisher, license, size, what's inside, open link) with
    `up:` links to its node and subnodes, so the graph grows with the library.

Budget (the user granted 50 GB): ≤ 40 GB relevant to the nodes and subnodes, ≤ 10 GB "core" chosen for overall
grounding (Wikipedia: the best ~50k articles in full + the top 1 M article introductions). Every source here is
public domain or openly licensed; licenses are recorded on each card.

    python3 library.py plan        # what would be downloaded, by node, with sizes
    python3 library.py download    # resumable; verifies SHA-256 from the publisher's metalink
    python3 library.py cards       # (re)write the vault cards and subnodes
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HOME = Path.home()
CONFIG = json.loads((HOME / ".config/blacklab-desktop/config.json").read_text()) if (HOME / ".config/blacklab-desktop/config.json").exists() else {}
LIB = Path(os.path.expanduser(CONFIG.get("library_dir", "~/BlackLab Library")))
ZIM_DIR = LIB / "zim"
STATE = LIB / "state.json"
CATALOG_URL = "https://library.kiwix.org/catalog/v2/entries?count=-1&lang=eng"
KIWIX_TOOLS = "https://download.kiwix.org/release/kiwix-tools/kiwix-tools_linux-x86_64.tar.gz"
PORT = int(CONFIG.get("library_port", 8780))
BUDGET_NODES_GB = 40.0
BUDGET_CORE_GB = 10.0
UA = "BlackLab-Library/1.0 (personal offline library)"

# ---------------------------------------------------------------- what goes in (node, subnodes, why)
# (kiwix name, flavour or "", node id, [subnode ids], one line on why it's here)
Z = [
    # Facility Operations
    ("diy.stackexchange.com_en_all", "", "facility", ["tools"], "Home Improvement Q&A — plumbing, electrical, HVAC, carpentry, codes in practice"),
    ("engineering.stackexchange.com_en_all", "", "facility", [], "Engineering Q&A — mechanical, structural, civil"),
    ("ifixit_en_all", "", "facility", ["tools"], "Repair guides for appliances, equipment, vehicles and devices"),
    ("libretexts.org_en_workforce", "", "facility", [], "Workforce textbooks — HVAC, construction, electrical, welding, trades"),
    ("libretexts.org_en_eng", "", "facility", [], "Engineering textbooks — thermodynamics, fluids, materials"),
    ("energypedia_en_all", "maxi", "facility", ["power"], "Renewable energy & energy-efficiency knowledge base (also electrical & controls basics)"),
    ("networkengineering.stackexchange.com_en_all", "", "facility", [], "Network Q&A — building networks, BAS connectivity"),
    ("iot.stackexchange.com_en_all", "", "facility", [], "IoT Q&A — sensors and connected building devices"),
    # Life Safety & Compliance
    ("ready.gov_en_all", "", "safety", ["disaster"], "Ready.gov (FEMA) — plans, kits and every hazard type; public domain"),
    ("zimgit-post-disaster_en", "", "safety", ["disaster", "offgrid", "water"], "Post-disaster resource library — field manuals and relief guides"),
    ("law.stackexchange.com_en_all", "", "vendors", [], "Law Q&A — contracts, liability, employment, regulations"),
    # Peer Support & Milieu Safety
    ("libretexts.org_en_socialsci", "", "milieu", [], "Social science textbooks — psychology, sociology, human services"),
    ("psychology.stackexchange.com_en_all", "", "milieu", [], "Psychology & neuroscience Q&A"),
    ("interpersonal.stackexchange.com_en_all", "", "milieu", [], "Interpersonal skills Q&A — communication and conflict"),
    # Leadership & Team · Finance
    ("workplace.stackexchange.com_en_all", "", "leadership", [], "Workplace Q&A — managing, hiring, feedback, conflict"),
    ("pm.stackexchange.com_en_all", "", "leadership", [], "Project management Q&A"),
    ("libretexts.org_en_biz", "", "leadership", [], "Business textbooks — management, accounting, finance, HR"),
    ("money.stackexchange.com_en_all", "", "finance", [], "Personal finance & money Q&A"),
    ("economics.stackexchange.com_en_all", "", "finance", [], "Economics Q&A"),
    # Projects & Engineering
    ("softwareengineering.stackexchange.com_en_all", "", "projects", [], "Software engineering Q&A — design, architecture, process"),
    ("unix.stackexchange.com_en_all", "", "projects", [], "Unix & Linux Q&A"),
    ("android.stackexchange.com_en_all", "", "projects", [], "Android Q&A — the phone side of BlackLab"),
    ("security.stackexchange.com_en_all", "", "projects", [], "Information security Q&A"),
    ("dba.stackexchange.com_en_all", "", "projects", [], "Database administration Q&A"),
    ("ux.stackexchange.com_en_all", "", "projects", [], "User experience Q&A"),
    ("sqa.stackexchange.com_en_all", "", "projects", [], "Software QA & testing Q&A"),
    ("devops.stackexchange.com_en_all", "", "projects", [], "DevOps Q&A"),
    ("opensource.stackexchange.com_en_all", "", "projects", [], "Open source & licensing Q&A"),
    ("devdocs_en_kotlin", "", "projects", [], "Kotlin reference (DevDocs)"),
    ("devdocs_en_python", "", "projects", [], "Python reference (DevDocs)"),
    ("devdocs_en_git", "", "projects", [], "Git reference (DevDocs)"),
    ("devdocs_en_bash", "", "projects", [], "Bash reference (DevDocs)"),
    ("devdocs_en_sqlite", "", "projects", [], "SQLite reference (DevDocs)"),
    ("devdocs_en_docker", "", "projects", [], "Docker reference (DevDocs)"),
    # AI & Automation
    ("ai.stackexchange.com_en_all", "", "ai", [], "Artificial intelligence Q&A"),
    ("genai.stackexchange.com_en_all", "", "ai", [], "Generative AI Q&A"),
    ("datascience.stackexchange.com_en_all", "", "ai", [], "Data science Q&A"),
    ("stats.stackexchange.com_en_all", "", "ai", [], "Statistics & machine learning Q&A (Cross Validated)"),
    ("cs.stackexchange.com_en_all", "", "ai", [], "Computer science Q&A"),
    ("wikipedia_en_computer", "maxi", "ai", [], "Wikipedia's computing articles, with images"),
    # Learning & Credentials · Knowledge Library
    ("libretexts.org_en_math", "", "learning", [], "Mathematics textbooks"),
    ("libretexts.org_en_stats", "", "learning", [], "Statistics textbooks"),
    ("academia.stackexchange.com_en_all", "", "learning", [], "Academia Q&A — degrees, studying, research"),
    ("writing.stackexchange.com_en_all", "", "learning", [], "Writing Q&A"),
    ("wikibooks_en_all", "nopic", "knowledge", ["sourdough", "plants"], "Wikibooks — open textbooks and the Cookbook"),
    # Vehicles & Travel
    ("mechanics.stackexchange.com_en_all", "", "vehicles", ["tools"], "Motor vehicle maintenance & repair Q&A"),
    ("travel.stackexchange.com_en_all", "", "vehicles", [], "Travel Q&A"),
    ("wikivoyage_en_all", "nopic", "vehicles", [], "Wikivoyage travel guide"),
    ("outdoors.stackexchange.com_en_all", "", "vehicles", ["offgrid", "navigation", "shelter", "fielddress", "weather"], "The Great Outdoors Q&A — backcountry, survival, hunting"),
    # Home, Family & Health (+ Self-Reliance & Survival)
    ("medlineplus.gov_en_all", "", "home", ["firstaid"], "MedlinePlus (NIH) health topics — public domain"),
    ("gardening.stackexchange.com_en_all", "", "home", ["garden", "plants"], "Gardening & landscaping Q&A"),
    ("cooking.stackexchange.com_en_all", "", "home", ["sourdough", "ferment", "preserve"], "Cooking Q&A — baking, bread, fermentation, food safety"),
    ("woodworking.stackexchange.com_en_all", "", "home", ["tools"], "Woodworking Q&A"),
    ("parenting.stackexchange.com_en_all", "", "home", [], "Parenting Q&A"),
    ("fitness.stackexchange.com_en_all", "", "home", [], "Physical fitness Q&A"),
    ("lifehacks.stackexchange.com_en_all", "", "home", [], "Lifehacks Q&A"),
    ("sustainability.stackexchange.com_en_all", "", "home", ["offgrid"], "Sustainable living Q&A"),
    ("pets.stackexchange.com_en_all", "", "home", [], "Pets Q&A"),
    ("zimgit-water_en", "", "home", ["water"], "Water treatment library — collection, filtration, disinfection"),
    ("zimgit-food-preparation_en", "", "home", ["preserve", "offgrid"], "Food for preppers — long-term food and techniques"),
    ("usda-2015_en", "", "home", ["preserve"], "USDA Complete Guide to Home Canning (2015) — public domain"),
    ("zimgit-knots_en", "", "home", ["knots"], "A library of knots — illustrated"),
    ("wikipedia_en_knots", "", "home", ["knots"], "Wikipedia's knot articles"),
    ("zimgit-medicine_en", "", "home", ["firstaid"], "Field and emergency medicine books"),
    ("irp.fas.org_en_military-medicine", "", "home", ["firstaid", "offgrid"], "Military medicine doctrine and manuals (public domain)"),
    ("gardenology_en_all", "", "home", ["plants", "garden"], "Gardenology — plant and garden encyclopedia"),
    ("appropedia_en_all", "", "home", ["offgrid", "water", "power", "shelter"], "Appropedia — appropriate technology and off-grid living"),
    ("solar.lowtechmagazine.com_mul_all", "", "home", ["power", "offgrid"], "Low-Tech Magazine — low-energy and off-grid technology"),
    ("ham.stackexchange.com_en_all", "", "home", ["comms"], "Amateur radio Q&A — emergency communications"),
    ("homebrew.stackexchange.com_en_all", "", "home", ["ferment"], "Homebrewing Q&A — fermentation"),
    ("based.cooking_en_all", "", "home", ["sourdough"], "Simple recipes, incl. bread"),
    ("foss.cooking_en_all", "", "home", ["sourdough", "ferment"], "Open recipes"),
    ("trueprepper.com_en_all", "", "home", ["disaster", "offgrid", "water", "preserve"], "True Prepper — preparedness guides"),
    ("gutenberg_en_lcc-s", "", "home", ["garden", "plants", "fielddress", "offgrid"], "Project Gutenberg: Agriculture shelf — public-domain farming, husbandry, hunting and plant books"),
]
CORE = [
    ("wikipedia_en_top", "maxi", "knowledge", [], "Best of Wikipedia — the most-read ~50,000 articles in full, with images"),
    ("wikipedia_en_top1m", "mini", "knowledge", [], "Wikipedia's top 1 million articles — introductions and infoboxes"),
]
# Verified public-domain US government documents (other agencies' sites refuse automated downloads; not worked around)
DOCS = [
    ("doe_om", "https://www.energy.gov/sites/default/files/2020/04/f74/omguide_complete_w-eo-disclaimer.pdf", "facility", [],
     "O&M Best Practices Guide, Release 3.0", "U.S. Department of Energy (FEMP)", "Operations & maintenance programs, technologies and metrics for federal facilities"),
    ("osha_1910", "https://www.ecfr.gov/api/renderer/v1/content/enhanced/current/title-29?part=1910", "safety", [],
     "29 CFR 1910 — OSHA General Industry", "eCFR / OSHA", "The full general-industry safety standards (current eCFR)"),
    ("osha_1926", "https://www.ecfr.gov/api/renderer/v1/content/enhanced/current/title-29?part=1926", "safety", [],
     "29 CFR 1926 — OSHA Construction", "eCFR / OSHA", "Construction safety standards (current eCFR)"),
    ("samhsa_peer", "https://www.samhsa.gov/sites/default/files/programs_campaigns/brss_tacs/core-competencies_508_12_13_18.pdf", "milieu", [],
     "Core Competencies for Peer Workers", "SAMHSA", "The competencies behind peer support practice"),
    ("far", "https://www.acquisition.gov/sites/default/files/current/far/pdf/FAR.pdf", "vendors", [],
     "Federal Acquisition Regulation (FAR)", "GSA / DoD / NASA", "The federal contracting rulebook — a model for bids, contracts and vendor terms"),
    ("irs_p463", "https://www.irs.gov/pub/irs-pdf/p463.pdf", "vehicles", [],
     "IRS Publication 463 — Travel, Gift and Car Expenses", "Internal Revenue Service", "Business mileage, car expenses and records"),
    ("epa_water", "https://www.epa.gov/ground-water-and-drinking-water/emergency-disinfection-drinking-water", "home", ["water"],
     "Emergency Disinfection of Drinking Water", "U.S. EPA", "Boiling and chlorine disinfection when water is unsafe"),
    ("nws_spotter", "https://www.weather.gov/media/owlie/SGJune6-11.pdf", "home", ["weather"],
     "Advanced Spotters' Field Guide", "NOAA National Weather Service", "Reading storms and severe-weather clouds"),
]

LICENSES = [
    (r"stackexchange|stackoverflow|askubuntu", "CC BY-SA 4.0 (Stack Exchange contributors)", "Stack Exchange"),
    (r"^wikipedia|^wikibooks|^wikivoyage|^wiktionary|^wikiversity", "CC BY-SA 4.0 (Wikimedia contributors)", "Wikimedia"),
    (r"libretexts", "Mostly CC BY-NC-SA (per book; see each page)", "LibreTexts"),
    (r"ifixit", "CC BY-NC-SA 3.0", "iFixit"),
    (r"medlineplus|ready\.gov|usda|irp\.fas|gutenberg", "Public domain (US government / expired copyright)", "US government · Project Gutenberg"),
    (r"energypedia|appropedia", "CC BY-SA", "energypedia · Appropedia"),
    (r"lowtechmagazine", "CC BY-NC-SA", "Low-Tech Magazine"),
    (r"devdocs", "Per project (open-source documentation)", "DevDocs"),
    (r"zimgit", "Collected public-domain and openly licensed manuals", "zimgit (Kiwix)"),
    (r"", "Open license — see the source", "Kiwix library"),
]


def license_for(name: str) -> tuple[str, str]:
    for rx, lic, pub in LICENSES:
        if re.search(rx, name):
            return lic, pub
    return LICENSES[-1][1], LICENSES[-1][2]


# ---------------------------------------------------------------- catalog

def catalog() -> dict[tuple[str, str], dict]:
    """(name, flavour) → {title, size, url (metalink), articles, description, date}."""
    cache = LIB / "catalog.xml"
    if not cache.exists() or time.time() - cache.stat().st_mtime > 86400:
        LIB.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(CATALOG_URL, headers={"User-Agent": UA})
        cache.write_bytes(urllib.request.urlopen(req, timeout=120).read())
    ns = {"a": "http://www.w3.org/2005/Atom", "dc": "http://purl.org/dc/terms/"}
    out = {}
    for e in ET.parse(cache).getroot().findall("a:entry", ns):
        name = e.findtext("a:name", default="", namespaces=ns)
        flav = e.findtext("a:flavour", default="", namespaces=ns) or ""
        for l in e.findall("a:link", ns):
            if l.get("type") == "application/x-zim":
                out[(name, flav)] = {"title": e.findtext("a:title", default=name, namespaces=ns), "size": int(l.get("length") or 0), "url": l.get("href"),
                                     "articles": int(e.findtext("a:articleCount", default="0", namespaces=ns) or 0),
                                     "description": e.findtext("a:summary", default="", namespaces=ns), "date": (e.findtext("a:updated", default="", namespaces=ns) or "")[:10]}
    return out


def resolve(cat, name, flav):
    if (name, flav) in cat:
        return cat[(name, flav)]
    options = [v for (n, f), v in cat.items() if n == name]
    return max(options, key=lambda v: v["size"]) if options and not flav else (options[0] if options else None)


def plan() -> dict:
    cat = catalog()
    rows = []
    for group, items in (("nodes", Z), ("core", CORE)):
        for name, flav, node, subs, why in items:
            c = resolve(cat, name, flav)
            if c:
                rows.append({"group": group, "name": name, "flavour": flav, "node": node, "subnodes": subs, "why": why, **c})
    nodes_gb = sum(r["size"] for r in rows if r["group"] == "nodes") / 1e9
    core_gb = sum(r["size"] for r in rows if r["group"] == "core") / 1e9
    return {"rows": rows, "nodes_gb": nodes_gb, "core_gb": core_gb}


# ---------------------------------------------------------------- state

def load_state() -> dict:
    try:
        return json.loads(STATE.read_text())
    except Exception:
        return {"books": {}, "docs": {}}


def save_state(s: dict):
    LIB.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(s, indent=1))
    os.replace(tmp, STATE)


# ---------------------------------------------------------------- downloads

def _metalink(url: str) -> tuple[str, str | None]:
    """(direct mirror URL, sha256) from a Kiwix .meta4 link."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    return parse_metalink(urllib.request.urlopen(req, timeout=60).read().decode(), url)


def parse_metalink(x: str, url: str = "") -> tuple[str, str | None]:
    sha = re.search(r'<hash type="sha-256">([0-9a-f]{64})</hash>', x)
    # Only the <file>'s mirror URLs (they carry a priority); the first <url> in the file is the publisher's homepage
    mirrors = sorted((int(p), u) for p, u in re.findall(r'<url[^>]*priority="(\d+)"[^>]*>(https?://[^<]+)</url>', x))
    return (mirrors[0][1] if mirrors else url.removesuffix(".meta4")), (sha.group(1) if sha else None)


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download_books(log=print):
    p = plan()
    if p["nodes_gb"] > BUDGET_NODES_GB + 0.5 or p["core_gb"] > BUDGET_CORE_GB + 0.5:
        raise SystemExit(f"Over budget: nodes {p['nodes_gb']:.1f} GB, core {p['core_gb']:.1f} GB")
    st = load_state()
    ZIM_DIR.mkdir(parents=True, exist_ok=True)
    rows = sorted(p["rows"], key=lambda r: r["size"])  # small first: the graph fills in quickly
    for i, r in enumerate(rows, 1):
        key = f"{r['name']}|{r['flavour']}"
        done = st["books"].get(key, {})
        target = ZIM_DIR / Path(urllib.parse.urlparse(r["url"]).path).name.removesuffix(".meta4")
        if done.get("ok") and target.exists() and target.stat().st_size == r["size"]:
            continue
        log(f"[{i}/{len(rows)}] {r['title']} ({r['size'] / 1e9:.2f} GB)")
        try:
            url, sha = _metalink(r["url"])
            for attempt in range(4):
                rc = subprocess.run(["curl", "-fL", "-C", "-", "--retry", "5", "--retry-delay", "5", "-A", UA, "-o", str(target), url]).returncode
                if rc in (0, 33) and target.exists() and target.stat().st_size >= r["size"] * 0.99:
                    break
                time.sleep(10)
            ok = target.exists() and (sha is None or _sha256(target) == sha)
            st["books"][key] = {"ok": ok, "file": str(target), "size": target.stat().st_size if target.exists() else 0, "sha256": sha,
                                "title": r["title"], "node": r["node"], "subnodes": r["subnodes"], "when": time.strftime("%Y-%m-%d %H:%M")}
            if not ok:
                log(f"   checksum mismatch — removed {target.name}")
                target.unlink(missing_ok=True)
        except Exception as e:
            st["books"][key] = {"ok": False, "error": str(e)[:200]}
            log(f"   failed: {e}")
        save_state(st)
    register_all()


def download_docs(vault_root: Path, log=print):
    import vault_nodes as VN
    st = load_state()
    for id_, url, node, subs, title, pub, about in DOCS:
        n = VN.BY_ID[node]
        folder = vault_root / "95 Library" / n["name"] / "Files"
        folder.mkdir(parents=True, exist_ok=True)
        ext = ".pdf" if url.lower().endswith(".pdf") else ".html"
        target = folder / (re.sub(r"[^\w .,()-]+", "", title)[:90] + ext)
        if st["docs"].get(id_, {}).get("ok") and target.exists():
            continue
        rc = subprocess.run(["curl", "-fsL", "--retry", "3", "-A", UA, "-o", str(target), url]).returncode
        ok = rc == 0 and target.exists() and target.stat().st_size > 2000
        st["docs"][id_] = {"ok": ok, "file": str(target), "size": target.stat().st_size if target.exists() else 0, "when": time.strftime("%Y-%m-%d %H:%M")}
        log(f"{'✓' if ok else '✗'} {title}")
        save_state(st)


# ---------------------------------------------------------------- kiwix-serve

def kiwix_bin(name: str) -> Path | None:
    d = LIB / "bin"
    p = d / name
    if p.exists():
        return p
    return Path(shutil.which(name)) if shutil.which(name) else None


def install_kiwix_tools(log=print) -> bool:
    if kiwix_bin("kiwix-serve") and kiwix_bin("kiwix-manage"):
        return True
    d = LIB / "bin"
    d.mkdir(parents=True, exist_ok=True)
    tgz = LIB / "kiwix-tools.tar.gz"
    subprocess.run(["curl", "-fsL", "-A", UA, "-o", str(tgz), KIWIX_TOOLS], check=True)
    with tarfile.open(tgz) as t:
        for m in t.getmembers():
            base = Path(m.name).name
            if base in ("kiwix-serve", "kiwix-manage", "kiwix-search") and m.isfile():
                f = t.extractfile(m)
                (d / base).write_bytes(f.read())
                (d / base).chmod(0o755)
    tgz.unlink(missing_ok=True)
    log("kiwix-tools installed in " + str(d))
    return bool(kiwix_bin("kiwix-serve"))


def register_all():
    """Rebuilds library.xml from the ZIMs that verified."""
    manage = kiwix_bin("kiwix-manage")
    if not manage:
        return
    lib = LIB / "library.xml"
    tmp = LIB / "library.new.xml"
    tmp.unlink(missing_ok=True)
    files = [b["file"] for b in load_state()["books"].values() if b.get("ok") and Path(b.get("file", "")).exists()]
    if files:
        subprocess.run([str(manage), str(tmp), "add", *files], capture_output=True)
        if tmp.exists():
            os.replace(tmp, lib)


def book_ids() -> dict[str, str]:
    """ZIM file stem → kiwix book name used in URLs."""
    out = {}
    for b in load_state()["books"].values():
        if b.get("ok"):
            out[Path(b["file"]).stem] = Path(b["file"]).stem
    return out


def serve_cmd() -> list[str] | None:
    s = kiwix_bin("kiwix-serve")
    lib = LIB / "library.xml"
    if not s or not lib.exists():
        return None
    return [str(s), "--library", str(lib), "--address", "127.0.0.1", "--port", str(PORT), "--monitorLibrary", "--nodatealiases"]


def search(query: str, books: list[str] | None = None, limit: int = 8) -> list[dict]:
    """Full-text search through kiwix-serve (offline). [books]: ZIM stems to search (default: all)."""
    params = [("pattern", query), ("pageLength", str(limit)), ("format", "xml")]
    # kiwix-serve refuses one search across books in different languages: search the English books together,
    # and multilingual ("_mul_") books only when asked for by name
    ok = searchable()
    names = books if books else [b for b in books_for() if "_mul_" not in b]
    if books and len(books) > 1:
        names = [b for b in books if "_mul_" not in b] or books[:1]
    names = [b for b in names if ok.get(b, True)] or names[:1]
    for b in names:
        params.append(("books.name", b))
    url = f"http://127.0.0.1:{PORT}/search?" + urllib.parse.urlencode(params)
    try:
        x = urllib.request.urlopen(url, timeout=20).read().decode("utf-8", "replace")
    except Exception as e:
        return [{"error": f"Library search unavailable ({e}). Is kiwix-serve running?"}]
    out = []
    for item in re.findall(r"<item>(.*?)</item>", x, re.S):
        t = re.search(r"<title>(.*?)</title>", item, re.S)
        l = re.search(r"<link>(.*?)</link>", item, re.S)
        d = re.search(r"<description>(.*?)</description>", item, re.S)
        bk = re.search(r"<book>\s*<title>(.*?)</title>", item, re.S)
        clean = lambda s: re.sub(r"<[^>]+>|&lt;[^&]*&gt;", "", __import__("html").unescape(s or "")).strip()  # noqa: E731
        out.append({"title": clean(t.group(1) if t else ""), "url": f"http://127.0.0.1:{PORT}" + (l.group(1).strip() if l else ""),
                    "book": clean(bk.group(1) if bk else ""), "snippet": clean(d.group(1) if d else "")[:400]})
    return out


def searchable() -> dict[str, bool]:
    """Which books answer full-text search (some ZIMs ship without a search index). Probed once per book, cached."""
    st = load_state()
    cache = st.setdefault("searchable", {})
    changed = False
    for b in books_for():
        if b in cache:
            continue
        u = f"http://127.0.0.1:{PORT}/search?" + urllib.parse.urlencode([("pattern", "the"), ("pageLength", "1"), ("format", "xml"), ("books.name", b)])
        try:
            urllib.request.urlopen(u, timeout=20).read()
            cache[b] = True
        except urllib.error.HTTPError as e:
            if "No such book" in e.read().decode("utf-8", "replace"):
                continue  # not registered yet (still downloading): ask again later
            cache[b] = False
        except Exception:
            continue  # server not up yet: try again later
        changed = True
    if changed:
        save_state(st)
    return cache


def books_for(node: str | None = None, subnode: str | None = None) -> list[str]:
    st = load_state()
    out = []
    for b in st["books"].values():
        if b.get("ok") and ((node is None and subnode is None) or b.get("node") == node or (subnode and subnode in b.get("subnodes", []))):
            out.append(Path(b["file"]).stem)
    return out


# ---------------------------------------------------------------- vault cards

def write_cards(v) -> int:
    """A card per source in 95 Library/<Node>/, linked to its node and subnodes."""
    import vault_nodes as VN
    st = load_state()
    p = plan()
    n = 0
    for r in p["rows"]:
        key = f"{r['name']}|{r['flavour']}"
        b = st["books"].get(key, {})
        node = VN.BY_ID[r["node"]]
        lic, pub = license_for(r["name"])
        stem = Path(b.get("file", "")).stem if b.get("ok") else ""
        subs = [VN.SUB_BY_ID[s] for s in r["subnodes"] if s in VN.SUB_BY_ID]
        ups = [VN.link(node)] + [VN.sublink(s) for s in subs]
        status = "available offline" if b.get("ok") else "downloading" if not b.get("error") else "failed — will retry"
        body = [f"# 📚 {r['title']}", "", f"> {r['why']}", "",
                f"- **Node:** {VN.link(node)}" + (" · **Subnodes:** " + " · ".join(VN.sublink(s) for s in subs) if subs else ""),
                f"- **Publisher / license:** {pub} · {lic}",
                f"- **Size:** {r['size'] / 1e9:.2f} GB · {r['articles']:,} articles · edition {r['date']}",
                f"- **Status:** {status}" + (f" · [Open offline](http://127.0.0.1:{PORT}/viewer#{stem})" if stem else ""), "",
                f"_{r['description']}_", "",
                "## Ask it", "",
                f"- Phone or desktop assistant: *“look it up in the library: …”* — searches every book offline.",
                f"- AIs: `vault_library_search` (MCP) with `node: {r['node']}`.",
                f"- Browser: [search this book](http://127.0.0.1:{PORT}/search?books.name={stem}&pattern=)" if stem else ""]
        rel = f"95 Library/{node['name']}/{v.safe_name(r['title'])}.md"
        if v.write_managed(rel, {"type": "source", "kind": "zim", "node": r["node"], "subnodes": r["subnodes"], "publisher": pub, "license": lic,
                                 "size_gb": round(r["size"] / 1e9, 2), "articles": r["articles"], "status": "ok" if b.get("ok") else "pending",
                                 "group": r["group"], "up": ups, "tags": ["library", f"library/{r['node']}"]}, "\n".join(x for x in body if x is not None)):
            n += 1
    for id_, url, node_id, subs_, title, pub, about in DOCS:
        d = st["docs"].get(id_, {})
        node = VN.BY_ID[node_id]
        subs = [VN.SUB_BY_ID[s] for s in subs_ if s in VN.SUB_BY_ID]
        f = Path(d.get("file", "")) if d.get("ok") else None
        rel_file = f"Files/{f.name}" if f else ""
        body = [f"# 📄 {title}", "", f"> {about}", "", f"- **Node:** {VN.link(node)}" + (" · **Subnodes:** " + " · ".join(VN.sublink(s) for s in subs) if subs else ""),
                f"- **Publisher:** {pub} · public domain (US government work)", f"- **Source:** {url}",
                f"- **File:** [[{rel_file}]]" if f else "- **File:** not downloaded yet"]
        v.write_managed(f"95 Library/{node['name']}/{v.safe_name(title)}.md", {"type": "source", "kind": "document", "node": node_id, "subnodes": subs_,
                        "publisher": pub, "license": "Public domain", "status": "ok" if d.get("ok") else "pending",
                        "up": [VN.link(node)] + [VN.sublink(s) for s in subs], "tags": ["library", f"library/{node_id}"]}, "\n".join(body))
    _gitignore(v)
    return n


def _gitignore(v):
    gi = v.path(".gitignore")
    text = gi.read_text() if gi.exists() else ""
    line = "95 Library/**/Files/"
    if line not in text:
        gi.write_text(text.rstrip() + f"\n# Library documents (large; the cards are versioned, the files are not)\n{line}\n")


def status() -> dict:
    st = load_state()
    p = plan() if (LIB / "catalog.xml").exists() else {"rows": [], "nodes_gb": 0, "core_gb": 0}
    have = sum(b.get("size", 0) for b in st["books"].values() if b.get("ok")) + sum(d.get("size", 0) for d in st["docs"].values() if d.get("ok"))
    return {"dir": str(LIB), "books_ok": sum(1 for b in st["books"].values() if b.get("ok")), "books_planned": len(p["rows"]),
            "docs_ok": sum(1 for d in st["docs"].values() if d.get("ok")), "docs_planned": len(DOCS), "gb_on_disk": round(have / 1e9, 2),
            "gb_planned": round(p["nodes_gb"] + p["core_gb"], 2), "nodes_gb": round(p["nodes_gb"], 2), "core_gb": round(p["core_gb"], 2),
            "serving": bool(serve_cmd()), "port": PORT}


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "plan"
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    if cmd == "plan":
        p = plan()
        by = {}
        for r in p["rows"]:
            by.setdefault((r["group"], r["node"]), []).append(r)
        for (g, node), rs in sorted(by.items()):
            print(f"{g:5s} {node:10s} {sum(r['size'] for r in rs) / 1e9:6.2f} GB  " + ", ".join(r["name"].split('.')[0] for r in rs))
        print(f"nodes {p['nodes_gb']:.2f} GB (budget {BUDGET_NODES_GB}) · core {p['core_gb']:.2f} GB (budget {BUDGET_CORE_GB}) · {len(p['rows'])} books + {len(DOCS)} documents")
    elif cmd in ("download", "fetch"):
        # "fetch" downloads data only (ZIM packs + documents). Installing kiwix-tools (a program) is a separate,
        # explicitly approved step: `python3 library.py install-reader`, or `sudo apt install kiwix-tools`.
        if cmd == "download":
            install_kiwix_tools()
        import vault as V
        root = Path(os.path.expanduser(CONFIG.get("vault_dir", "~/Obsidian/BlackLab")))
        download_docs(root)
        download_books()
    elif cmd == "install-reader":
        install_kiwix_tools()
        register_all()
    elif cmd == "cards":
        import vault as V
        v = V.Vault(Path(os.path.expanduser(CONFIG.get("vault_dir", "~/Obsidian/BlackLab"))), HOME / ".cache/blacklab-desktop/vault")
        print(write_cards(v), "cards written")
    elif cmd == "status":
        print(json.dumps(status(), indent=1))
