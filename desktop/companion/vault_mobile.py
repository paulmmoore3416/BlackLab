"""The phone in the graph (companion 1.7): everything readable on the linked phone's shared storage, under ◆ Mobile Device,
in `89 Mobile/` (local only — never pushed, never mirrored back to the phone).

  * 📱 the device — model, Android version and build, storage, battery, installed apps, what each folder holds.
  * Documents (Documents/, Download/ and anywhere else: PDF, Word, Excel, PowerPoint, text, Markdown, HTML, EPUB) are copied
    to this computer (~/.local/share/blacklab-desktop/phone-files) and carded with their text, like the Atlas.
  * Photos, videos and music get collection cards — one per photo month, music folder or video folder — with counts,
    sizes and dates. They are never copied, and no location is ever read.

Never read: Android/ (app data), the phone's copy of this vault (Documents/BlackLab), BlackLab's key-provisioning folder
(BlackLab/.provision), hidden files, and anything guards consider protected. App-private data (messages, the encrypted
Behavioral Health workspace…) isn't reachable over adb at all. Unit tested in tests/test_mobile.py with a fake adb.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import os
import re
import shlex
import subprocess
from pathlib import Path

import guards
import vault_atlas as A
import vault_nodes as VN

ROOT = "89 Mobile"
SKIP_PREFIXES = ("Android/", "Documents/BlackLab/", "BlackLab/.provision", "BlackLab/backups", ".")
LOCAL = Path.home() / ".local" / "share" / "blacklab-desktop" / "phone-files"
MAX_PULL = 60 * 1024 * 1024
MONTH_RE = re.compile(r"(?:IMG|VID|PXL|Screenshot|Screen_Recording|MVIMG)[_-]?(\d{4})(\d{2})\d{2}", re.I)


def _adb(adb: str, serial: str, *args, timeout=300) -> str:
    r = subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout


class Mobile:
    def __init__(self, v, adb: str, serial: str, config: dict | None = None, local_root: Path = LOCAL, runner=None):
        self.v = v
        self.adb, self.serial = adb, serial
        self.config = config or {}
        self.local = Path(local_root) / serial
        self.sh = runner or (lambda cmd, timeout=300: _adb(adb, serial, "shell", cmd, timeout=timeout))
        self.pull = (lambda remote, local: subprocess.run([adb, "-s", serial, "pull", "-a", remote, str(local)], capture_output=True, timeout=600)) \
            if runner is None else (lambda remote, local: None)
        self.state = v._state("mobile.json", {"files": {}})
        self.text_dir = Path(v.state_dir) / "mobile_text"

    # ---------------------------------------------------------------- reading the phone

    def device(self) -> dict:
        props = {}
        for k in ("ro.product.model", "ro.product.manufacturer", "ro.build.version.release", "ro.build.display.id", "ro.build.version.security_patch"):
            props[k] = self.sh(f"getprop {k}").strip()
        df = self.sh("df -h /data | tail -1").split()
        batt = self.sh("dumpsys battery")
        level = re.search(r"level: (\d+)", batt)
        pk = sorted(l.removeprefix("package:").strip() for l in self.sh("pm list packages -3").splitlines() if l.startswith("package:"))
        return {"model": props["ro.product.model"], "maker": props["ro.product.manufacturer"], "android": props["ro.build.version.release"],
                "build": props["ro.build.display.id"], "patch": props["ro.build.version.security_patch"],
                "storage": {"size": df[1], "used": df[2], "free": df[3], "pct": df[4]} if len(df) >= 5 else {},
                "battery": int(level.group(1)) if level else None, "apps": pk}

    def listing(self) -> list[dict]:
        """Every file on shared storage: size, mtime, path (relative to /sdcard)."""
        out = self.sh("cd /sdcard && find . -type f ! -path './Android/*' ! -path '*/.*' -exec stat -c '%s|%Y|%n' {} + 2>/dev/null", timeout=900)
        files = []
        for line in out.splitlines():
            parts = line.split("|", 2)
            if len(parts) != 3 or not parts[0].isdigit():
                continue
            rel = parts[2].removeprefix("./")
            if rel.startswith(SKIP_PREFIXES + tuple(self.config.get("mobile_exclude", []))) or guards.PROTECTED_NAME_RE.search(Path(rel).name):
                continue
            files.append({"rel": rel, "size": int(parts[0]), "mtime": int(parts[1])})
        return files

    # ---------------------------------------------------------------- the pass

    def run(self) -> dict:
        dev = self.device()
        self.state["model"] = dev["model"] or self.state.get("model")
        files = self.listing()
        docs = [f for f in files if Path(f["rel"]).suffix.lower() in A.DOC_EXT and f["size"] <= MAX_PULL]
        media = [f for f in files if Path(f["rel"]).suffix.lower() in A.MEDIA_EXT]
        written, pulled = set(), 0
        st = self.state["files"]
        for f in docs:
            key = f["rel"]
            sig = f"{f['size']}:{f['mtime']}"
            local = self.local / f["rel"]
            if st.get(key, {}).get("sig") != sig or not local.exists():
                local.parent.mkdir(parents=True, exist_ok=True)
                self.pull(f"/sdcard/{f['rel']}", local)
                pulled += 1
                text, meta = A.extract_text(local, f["size"]) if local.exists() else ("", {})
                safe, dropped = guards.vault_safe_passages(A.clean_text(text)[: A.CACHE_CHARS * 2])
                tk = hashlib.sha1(key.encode()).hexdigest()[:20]
                self.text_dir.mkdir(parents=True, exist_ok=True)
                (self.text_dir / f"{tk}.txt").write_text(safe[: A.CACHE_CHARS], encoding="utf-8")
                st[key] = {"sig": sig, "text_key": tk, "withheld": dropped, "pages": meta.get("pages"),
                           "heading": A.first_heading(safe) if key.endswith(".md") else meta.get("doc_title", ""),
                           "summary": A.summarize(safe), "nodes": A.classify("phone/" + key, Path(key).stem, safe, "document")}
            written.add(self._doc_card(f, st[key]))
        for k in [k for k in st if k not in {f["rel"] for f in docs}]:
            del st[k]
        groups: dict[str, list] = {}
        for f in media:
            groups.setdefault(self._group(f), []).append(f)
        for g, items in groups.items():
            written.add(self._collection_card(g, items))
        written.add(self._device_card(dev, files, docs, groups))
        self.v._collection("mobile", written)
        self.v._save_state("mobile.json", self.state)
        return {"files": len(files), "documents": len(docs), "pulled": pulled, "collections": len(groups), "apps": len(dev["apps"])}

    def _group(self, f: dict) -> str:
        rel = f["rel"]
        top = rel.split("/")[0]
        folder = str(Path(rel).parent)
        if top in ("DCIM", "Pictures"):
            m = MONTH_RE.search(Path(rel).name)
            ym = f"{m.group(1)}-{m.group(2)}" if m else dt.date.fromtimestamp(f["mtime"]).strftime("%Y-%m")
            return f"{folder} · {ym}"
        if top == "Music":
            parts = Path(rel).parts
            return "/".join(parts[:3]) if len(parts) > 3 else folder
        return folder

    def _device_rel(self) -> str:
        return f"{ROOT}/📱 {self.v.safe_name(self.state.get('model') or 'Phone')}.md"

    def _doc_card(self, f: dict, info: dict) -> str:
        rel_path = f["rel"]
        rel = f"{ROOT}/Files/" + "/".join(self.v.safe_name(x) for x in Path(rel_path).parts) + ".md"
        nodes = [VN.BY_ID[n] for n in (["mobile"] + info.get("nodes", []))[:3] if n in VN.BY_ID]
        text = ""
        try:
            text = (self.text_dir / f"{info['text_key']}.txt").read_text(encoding="utf-8")
        except OSError:
            pass
        title = info.get("heading") or Path(rel_path).name
        lines = [f"# 📄 {title}", ""]
        if info.get("summary"):
            lines += [f"> {info['summary']}", ""]
        local = self.local / rel_path
        lines += [f"- **On the phone:** `/sdcard/{rel_path}` · {A.human(f['size'])} · modified [[{dt.date.fromtimestamp(f['mtime']).isoformat()}]]"
                  + (f" · {info['pages']} pages" if info.get("pages") else ""),
                  f"- **Copy on this computer:** [Open]({A.file_uri(local)})", f"- **Device:** {A.wl(self._device_rel())}",
                  "- **Nodes:** " + " · ".join(VN.link(n) for n in nodes)]
        ents = self.v._state("entities.json", {}).get("by_note", {}).get(rel, [])
        if ents:
            lines += ["", "## People & topics", "", " · ".join(ents[:60])]
        if text:
            lines += ["", "> [!quote]- Text" + (f" · {info['withheld']} private passage(s) left out" if info.get("withheld") else ""), "> ```text"] + \
                     [f"> {l}" for l in text[:8000].replace("```", "ʼʼʼ").split("\n")] + ["> ```"]
        kind = "guide" if A.guide_kind(Path(rel_path), info.get("heading", "")) else "mobile-file"
        self.v.write_managed(rel, {"type": kind, "format": Path(rel_path).suffix.lstrip(".").lower(), "source": f"phone:/sdcard/{rel_path}",
                                   "size": A.human(f["size"]), "modified": dt.date.fromtimestamp(f["mtime"]).isoformat(),
                                   "up": [A.wl(self._device_rel())] + [VN.link(n) for n in nodes], "nodes": [n["id"] for n in nodes],
                                   "tags": ["mobile", "mobile/file"] + (["guide"] if kind == "guide" else [])}, "\n".join(lines))
        return rel

    def _collection_card(self, group: str, items: list) -> str:
        rel = f"{ROOT}/Collections/{self.v.safe_name(group.replace('/', ' › '))}.md"
        kinds = {("video" if Path(i["rel"]).suffix.lower() in A.VIDEO_EXT else "audio" if Path(i["rel"]).suffix.lower() in A.AUDIO_EXT else "image") for i in items}
        size = sum(i["size"] for i in items)
        first, last = min(i["mtime"] for i in items), max(i["mtime"] for i in items)
        lines = [f"# 🎞️ {group}", "", f"- **Items:** {len(items)} · {A.human(size)} · {', '.join(sorted(kinds))}",
                 f"- **Dates:** [[{dt.date.fromtimestamp(first).isoformat()}]] → [[{dt.date.fromtimestamp(last).isoformat()}]]",
                 f"- **On the phone:** `/sdcard/{group.split(' · ')[0]}` · {A.wl(self._device_rel())}", "",
                 "| Item | Size | Date |", "|---|---|---|"]
        for i in sorted(items, key=lambda i: i["mtime"])[:300]:
            lines.append(f"| {Path(i['rel']).name.replace('|', '/')} | {A.human(i['size'])} | {dt.date.fromtimestamp(i['mtime']).isoformat()} |")
        if len(items) > 300:
            lines.append(f"| _…and {len(items) - 300} more_ | | |")
        self.v.write_managed(rel, {"type": "mobile-collection", "items": len(items), "size": A.human(size), "media": sorted(kinds),
                                   "up": [A.wl(self._device_rel()), VN.link(VN.BY_ID["media"])], "nodes": ["mobile", "media"],
                                   "tags": ["mobile", "mobile/collection"] + [f"media/{k}" for k in sorted(kinds)]}, "\n".join(lines))
        return rel

    def _device_card(self, dev: dict, files: list, docs: list, groups: dict) -> str:
        self.state["model"] = dev["model"]
        tops: dict[str, list] = {}
        for f in files:
            tops.setdefault(f["rel"].split("/")[0] if "/" in f["rel"] else "(top level)", []).append(f)
        lines = [f"# 📱 {dev['maker']} {dev['model']}".replace("Google Google", "Google"), "",
                 f"> [!abstract] Android {dev['android']} · build `{dev['build']}` · security patch {dev['patch']} · "
                 f"storage {dev['storage'].get('used', '?')} used of {dev['storage'].get('size', '?')} ({dev['storage'].get('pct', '?')}) · "
                 f"battery {dev['battery']} %", "",
                 f"**In the graph:** {len(docs)} documents with their text · {len(groups)} photo / music / video collections · "
                 f"{len(files):,} files listed · {len(dev['apps'])} apps you installed. Part of {VN.link(VN.BY_ID['mobile'])}.", "",
                 "## Storage by folder", "", "| Folder | Files | Size |", "|---|---|---|"]
        for t, fs in sorted(tops.items(), key=lambda kv: -sum(x["size"] for x in kv[1])):
            lines.append(f"| `{t}` | {len(fs):,} | {A.human(sum(x['size'] for x in fs))} |")
        lines += ["", "## Documents", ""] + [f"- {A.wl(f'{ROOT}/Files/' + '/'.join(self.v.safe_name(x) for x in Path(d['rel']).parts) + '.md', Path(d['rel']).name)}"
                                             for d in sorted(docs, key=lambda d: -d["mtime"])[:300]]
        lines += ["", "## Collections", ""] + [f"- {A.wl(f'{ROOT}/Collections/' + self.v.safe_name(g.replace('/', ' › ')) + '.md', g)} <small>×{len(i)}</small>"
                                               for g, i in sorted(groups.items())[:400]]
        lines += ["", "## Apps you installed", "", " · ".join(f"`{p}`" for p in dev["apps"]), "",
                  "_Not read: app data (Android/), the vault's phone copy, BlackLab key provisioning, hidden files. Photos are listed, never copied; no location is read._"]
        rel = self._device_rel()
        self.v.write_managed(rel, {"type": "mobile-device", "model": dev["model"], "android": dev["android"], "apps": len(dev["apps"]),
                                   "documents": len(docs), "up": [VN.link(VN.BY_ID["mobile"]), A.wl(A.COMPUTER)], "nodes": ["mobile"],
                                   "tags": ["mobile", "mobile/device"]}, "\n".join(lines))
        return rel


if __name__ == "__main__":  # python3 vault_mobile.py [serial]
    import json
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    import vault as V
    cfg_path = Path.home() / ".config" / "blacklab-desktop" / "config.json"
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    v = V.Vault(Path(os.path.expanduser(cfg.get("vault_dir", "~/Obsidian/BlackLab"))), Path.home() / ".cache" / "blacklab-desktop" / "vault",
                cfg.get("timezone", "America/Chicago"))
    adb = str(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb")
    devs = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    serial = sys.argv[1] if len(sys.argv) > 1 else next(l.split()[0] for l in devs if l.endswith("device") and not l.startswith("emulator"))
    with v.lock:
        print(Mobile(v, adb, serial, cfg).run())
