"""Syncthing ignore rules for the vault: the same privacy boundary as the USB vault sync (phone_vault.local_files)."""
import fnmatch
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import phone_vault  # noqa: E402


def ignored(rules: str, rel: str) -> bool:
    """A small model of Syncthing's matcher for the patterns we generate (first match wins, ! re-includes)."""
    parts = rel.split("/")
    for line in rules.splitlines():
        line = line.strip()
        if not line or line.startswith("//") or line.startswith("#"):
            continue
        neg = line.startswith("!")
        pat = line[1:] if neg else line
        if pat.startswith("/"):  # anchored at the folder root: the path or any of its parent directories
            hit = any(fnmatch.fnmatchcase("/".join(parts[:i]), pat[1:]) for i in range(1, len(parts) + 1))
        else:  # unanchored: any path component
            hit = any(fnmatch.fnmatchcase(p, pat) for p in parts)
        if hit:
            return not neg
    return False


class SyncthingRules(unittest.TestCase):
    def test_agrees_with_the_usb_sync(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            files = ["Home.md", "10 Facility/a.md", "85 Atlas/doc.md", "89 Mobile/phone.md", ".git/config", ".trash/x.md",
                     ".obsidian/app.json", ".obsidian/workspace.json", ".obsidian/workspace-mobile.json", ".obsidian/cache",
                     "notes/.hidden.md", ".blacklab-sync", "40 Learning/🏅 Credentials.md"]
            for f in files:
                (root / f).parent.mkdir(parents=True, exist_ok=True)
                (root / f).write_text("x")
            for include_local in (False, True):
                rules = phone_vault.stignore_rules(include_local)
                synced = sorted(f for f in files if not ignored(rules, f))
                self.assertEqual(phone_vault.local_files(root, include_local), synced, f"include_local={include_local}")

    def test_the_rules_file_itself_syncs(self):
        # .stglobalignore travels with the vault so the phone gets the same rules; its .stignore just includes it
        self.assertFalse(ignored(phone_vault.stignore_rules(False), ".stglobalignore"))
        self.assertEqual(phone_vault.STIGNORE, "#include .stglobalignore\n")

    def test_local_only_folders_never_leave_this_computer(self):
        rules = phone_vault.stignore_rules(False)
        for top in phone_vault.LOCAL_TOP:
            self.assertTrue(ignored(rules, f"{top}/x.md"), top)


if __name__ == "__main__":
    unittest.main()
