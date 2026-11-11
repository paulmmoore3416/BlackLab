"""Google Drive in the graph (companion 1.7): a card for every document in Paul's Drive — Docs, Sheets, Slides, PDFs, Word,
Excel, PowerPoint — with its folder, dates, link and the text Drive gives back, in `88 Drive/` (local only, never pushed).

Drive is read by Claude's Google Drive connector (search results with content snippets, saved as JSON pages); this module
turns those pages into cards. `python3 vault_drive.py <folder of pages>` imports them; running it again only updates what
changed (Drive's modifiedTime), and folder notes give the cards a tree in the graph. Code that was synced to Drive
(node_modules READMEs, changelogs, licences) is skipped.

Privacy: the same rules as the Atlas — guards.vault_safe_passages drops clinical paragraphs and scrubs secrets; injury
logs (OSHA 300/301) and other records about people's health are carded by title only.
"""
from __future__ import annotations

import datetime as dt
import glob
import hashlib
import json
import re
import sys
from pathlib import Path

import guards
import vault_atlas as A
import vault_nodes as VN

ROOT = "88 Drive"
HUB = f"{ROOT}/☁️ Google Drive.md"
KIND = {"application/vnd.google-apps.document": "Google Doc", "application/vnd.google-apps.spreadsheet": "Google Sheet",
        "application/vnd.google-apps.presentation": "Google Slides", "application/pdf": "PDF", "application/msword": "Word",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "Word",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "Excel",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation": "PowerPoint", "text/markdown": "Markdown"}
CODE_NOISE = re.compile(r"^(readme|changelog|history|license|licence|contributing|security|code_of_conduct|api|authors|notice)(\.md)?$", re.I)
TITLE_ONLY = re.compile(r"osha'?s? (?:form )?30[01]|injur(?:y|ies)|incident report|workers'? comp|drug (?:screen|test)|medical|physical exam|"
                        r"background check|ssn|social security|i-9|w-4|direct deposit|bank statement|tax return|\btdiu\b|disability|\bva\b (?:claim|rating|benefit)|"
                        r"c-?file|dbq|nexus letter|diagnos|prescription|lab results|therapy|counsel(?:ing|or)", re.I)
PATH_HINTS = [(r"facilit|plant operations|director of plant|work order|pm\b|maintenance|eoc|sds|chemical", ["facility", "safety"]),
              (r"responsible container|container|tote|drum|spec sheet|stock order", ["projects", "vendors"]),
              (r"family|paul\b|personal", ["home"]), (r"english|course|class|cert", ["learning"]),
              (r"ai_|ai |aurora|aura|omnix|software|p2p|db\b|ingest", ["ai", "projects"]), (r"music|movies|photos", ["media"])]


def load_pages(folder: str) -> dict[str, dict]:
    files: dict[str, dict] = {}
    for p in sorted(glob.glob(str(Path(folder) / "*.json"))):
        try:
            for f in json.load(open(p)).get("files", []):
                if f.get("id") and (f["id"] not in files or f.get("modifiedTime", "") >= files[f["id"]].get("modifiedTime", "")):
                    files[f["id"]] = f
        except (ValueError, OSError):
            continue
    return files


def keep(f: dict) -> bool:
    if f.get("trashed") or f.get("mimeType") == "application/vnd.google-apps.folder":
        return False
    if f.get("mimeType") == "text/markdown" and CODE_NOISE.match(f.get("title", "")):
        return False
    return f.get("mimeType") in KIND


class DriveImport:
    def __init__(self, v, config: dict | None = None):
        self.v = v
        self.config = config or {}
        self.state = v._state("drive.json", {"files": {}})
        self.text_dir = Path(v.state_dir) / "drive_text"

    def run(self, pages_dir: str, folder_names: dict[str, str] | None = None) -> dict:
        files = {k: f for k, f in load_pages(pages_dir).items() if keep(f)}
        names = dict(self.state.get("folders", {}))
        names.update(folder_names or {})
        self.state["folders"] = names
        written, by_parent = set(), {}
        new = updated = withheld_docs = 0
        for fid, f in sorted(files.items(), key=lambda kv: kv[1].get("modifiedTime", ""), reverse=True):
            st = self.state["files"].get(fid, {})
            title = f.get("title", "Untitled").strip() or "Untitled"
            parent = f.get("parentId", "")
            folder = names.get(parent, "")
            rel = self._rel(f, folder)
            text = (f.get("contentSnippet") or "").strip()
            sensitive = bool(TITLE_ONLY.search(f"{folder} {title}") or guards.CLINICAL_RE.search(f"{folder} {title}") or guards.PROTECTED_NAME_RE.search(title))
            safe, dropped = ("", 0) if sensitive else guards.vault_safe_passages(A.clean_text(text))
            if sensitive:
                withheld_docs += 1
            nodes = self._nodes(title, folder, safe)
            key = hashlib.sha1(fid.encode()).hexdigest()[:20]
            self.text_dir.mkdir(parents=True, exist_ok=True)
            (self.text_dir / f"{key}.txt").write_text(safe, encoding="utf-8")
            props = {"type": "drive-file", "drive_kind": KIND.get(f["mimeType"], "File"), "drive_id": fid, "modified": f.get("modifiedTime", "")[:10],
                     "created": f.get("createdTime", "")[:10], "size": A.human(int(f["fileSize"])) if f.get("fileSize") else None,
                     "up": [A.wl(self._folder_rel(parent, folder))] + [VN.link(VN.BY_ID[n]) for n in nodes], "nodes": nodes,
                     "tags": ["drive", f"drive/{KIND.get(f['mimeType'], 'file').lower().replace(' ', '-')}"] + (["drive/starred"] if f.get("starred") else [])}
            if sensitive:
                props["withheld"] = "title only (records about people)"
            elif dropped:
                props["withheld"] = dropped
            lines = [f"# ☁️ {title}", "", f"- **In Drive:** [{title}]({f.get('viewUrl', '')}) · {KIND.get(f['mimeType'], 'File')}"
                     f" · modified [[{f.get('modifiedTime', '')[:10]}]]" + (f" · {props['size']}" if props.get("size") else ""),
                     f"- **Folder:** {A.wl(self._folder_rel(parent, folder))}"]
            if nodes:
                lines.append("- **Nodes:** " + " · ".join(VN.link(VN.BY_ID[n]) for n in nodes if n in VN.BY_ID))
            ents = self.v._state("entities.json", {}).get("by_note", {}).get(rel, [])
            if ents:
                lines += ["", "## People & topics", "", " · ".join(ents[:60])]
            if sensitive:
                lines += ["", "_Contents not copied: this looks like a record about people (injuries, HR, IDs or finances). Open it in Drive._"]
            elif safe:
                summary = A.summarize(safe)
                if summary:
                    lines[1:1] = [f"> {summary}", ""]
                shown = safe[:8000].replace("```", "ʼʼʼ")
                lines += ["", "> [!quote]- Text from Drive" + (f" · {dropped} private passage(s) left out" if dropped else ""), "> ```text"] + \
                         [f"> {l}" for l in shown.split("\n")] + ["> ```"]
            if self.v.write_managed(rel, props, "\n".join(lines)):
                if st:
                    updated += 1
                else:
                    new += 1
            self.state["files"][fid] = {"rel": rel, "modified": f.get("modifiedTime", ""), "text_key": key, "title": title, "nodes": nodes}
            written.add(rel)
            by_parent.setdefault(parent, []).append((rel, title, f))
        for parent, items in by_parent.items():
            written.add(self._write_folder(parent, names.get(parent, ""), items))
        written.add(self._write_hub(files, by_parent, names))
        self.v._collection("drive", written | {r for r in self._private_cards()})
        self.v._save_state("drive.json", self.state)
        return {"files": len(files), "new": new, "updated": updated, "folders": len(by_parent), "title_only": withheld_docs}

    def _private_cards(self):
        root = self.v.path(f"{ROOT}/Paul's Private")
        return [str(p.relative_to(self.v.root)) for p in root.rglob("*.md")] if root.exists() else []

    def _rel(self, f: dict, folder: str) -> str:
        fold = self.v.safe_name(folder or "Folder " + f.get("parentId", "")[:8])
        return f"{ROOT}/{fold}/{self.v.safe_name(f.get('title', 'Untitled'))} ({f['id'][:6]}).md"

    def _folder_rel(self, parent: str, folder: str) -> str:
        fold = self.v.safe_name(folder or "Folder " + parent[:8])
        return f"{ROOT}/{fold}/📁 {fold}.md"

    def _nodes(self, title: str, folder: str, text: str) -> list[str]:
        nodes = A.classify(f"drive/{folder}", title, text, "document")
        low = f"{folder} {title}".lower()
        for rx, ids in PATH_HINTS:
            if re.search(rx, low):
                nodes = list(dict.fromkeys([i for i in ids if i in VN.BY_ID] + nodes))[:3]
                break
        return nodes

    def _write_folder(self, parent: str, folder: str, items: list) -> str:
        rel = self._folder_rel(parent, folder)
        name = folder or f"Folder {parent[:8]}"
        lines = [f"# 📁 {name}", "", f"- **In Drive:** [Open the folder](https://drive.google.com/drive/folders/{parent}) · {len(items)} documents",
                 f"- **Part of:** {A.wl(HUB)}", "", "## Documents", ""]
        for r, title, f in sorted(items, key=lambda x: x[2].get("modifiedTime", ""), reverse=True)[:400]:
            lines.append(f"- {A.wl(r, title)} <small>{f.get('modifiedTime', '')[:10]}</small>")
        if len(items) > 400:
            lines.append(f"- _…and {len(items) - 400} more_")
        self.v.write_managed(rel, {"type": "folder", "source": "Google Drive", "drive_id": parent, "cards": len(items), "up": [A.wl(HUB)],
                                   "tags": ["drive", "drive/folder"]}, "\n".join(lines))
        return rel

    def _write_hub(self, files: dict, by_parent: dict, names: dict) -> str:
        kinds: dict[str, int] = {}
        years: dict[str, int] = {}
        for f in files.values():
            kinds[KIND.get(f["mimeType"], "File")] = kinds.get(KIND.get(f["mimeType"], "File"), 0) + 1
            y = f.get("modifiedTime", "")[:4]
            years[y] = years.get(y, 0) + 1
        lines = ["# ☁️ Google Drive", "", f"> [!abstract] {len(files)} documents from Paul's Google Drive, one card each, in {len(by_parent)} folders — "
                 "on this computer only (never pushed). The originals stay in Drive; each card links to it.", "",
                 "**By type:** " + " · ".join(f"{k} ×{v}" for k, v in sorted(kinds.items(), key=lambda kv: -kv[1])),
                 "", "**By year:** " + " · ".join(f"{y} ×{c}" for y, c in sorted(years.items(), reverse=True)), "",
                 f"**Private drive:** [[90 System/Private Drive|🗄️ Private Drive]] — *“save to my private drive: …”* files things into Drive › BlackLab › Paul's Private.", "",
                 "## Folders", ""]
        for parent, items in sorted(by_parent.items(), key=lambda kv: -len(kv[1])):
            lines.append(f"- {A.wl(self._folder_rel(parent, names.get(parent, '')))} <small>×{len(items)}</small>")
        self.v.write_managed(HUB, {"type": "drive-index", "documents": len(files), "up": [A.wl(A.COMPUTER), "[[◉ Nexus]]"], "tags": ["drive"]},
                             "\n".join(lines))
        return HUB

    def texts(self):
        for fid, info in self.state.get("files", {}).items():
            try:
                t = (self.text_dir / f"{info['text_key']}.txt").read_text(encoding="utf-8")
            except OSError:
                continue
            if t.strip():
                yield info["rel"], {"path": f"drive:{fid}", "mode": "home", "nodes": info.get("nodes", [])}, t


if __name__ == "__main__":  # python3 vault_drive.py <pages folder> [folder-names.json]
    import os
    sys.path.insert(0, str(Path(__file__).parent))
    import vault as V
    cfg_path = Path.home() / ".config" / "blacklab-desktop" / "config.json"
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    v = V.Vault(Path(os.path.expanduser(cfg.get("vault_dir", "~/Obsidian/BlackLab"))), Path.home() / ".cache" / "blacklab-desktop" / "vault",
                cfg.get("timezone", "America/Chicago"))
    names = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv) > 2 else {}
    with v.lock:
        print(DriveImport(v, cfg).run(sys.argv[1], names))
