"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402

FEED = {
    "facility": {"site": "Main campus", "assets": [
        {"id": "a1", "name": "AHU-2", "kind": "HVAC", "tag": "FAC-0042", "location": "B-wing", "make": "Trane", "model": "M", "serial": "T1",
         "pms": [{"title": "Filter change", "everyDays": 90, "standard": "ASHRAE 52.2", "lastDay": 20400, "dueDay": 20490, "status": "OK"}]}],
        "log": [{"id": "e1", "assetId": "a1", "day": 20400, "kind": "Inspection / PM", "result": "Pass", "text": "MERV 13 in", "by": "J. Rivera"}]},
    "tasks": [{"id": 1, "title": "Replace AHU-2 belt", "list": "Work Orders", "profile": "PROFESSIONAL", "workorder": True, "due": "2026-11-12", "priority": 2},
              {"id": 2, "title": "Buy milk", "list": "Errands", "profile": "PERSONAL"}],
    "notes": [{"id": 9, "title": "Gate code", "text": "Vendor gate code changed; ask Sam", "labels": ["work"]},
              {"id": 10, "title": "Rounds", "text": "Patient in room 4 needs q15 checks"}],
    "garage": {"vehicle": {"name": "2023 Hyundai Santa Fe", "odometer": 31000}, "services": [{"day": 20400, "miles": 30500, "title": "Oil change", "cost": 79.95, "shop": "Dealer"}]},
}


class VaultTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_layout_and_obsidian_settings(self):
        r = self.v.root
        for f in ("Home.md", "README.md", "AI Protocol.md", ".obsidian/app.json", ".obsidian/core-plugins.json", "90 System/BlackLab Map.canvas",
                  "90 System/Bases/Facility.base", "90 System/Templates/Daily.md", ".gitignore"):
            self.assertTrue((r / f).exists(), f)
        json.loads((r / ".obsidian/graph.json").read_text())
        canvas = json.loads((r / "90 System/BlackLab Map.canvas").read_text())
        self.assertTrue(canvas["nodes"] and canvas["edges"])
        if shutil.which("git"):
            self.assertTrue((r / ".git").exists())

    def test_feed_writes_linked_notes(self):
        counts = self.v.apply_feed(FEED)
        r = self.v.root
        asset = (r / "10 Facility/Assets/AHU-2.md").read_text()
        self.assertIn("type: asset", asset)
        self.assertIn("FAC-0042", asset)
        tasks = (r / "30 Work/Tasks.md").read_text()
        self.assertIn("[[AHU-2]]", tasks, "work orders link the asset they mention")
        self.assertTrue((r / "30 Work/Work Orders/Replace AHU-2 belt.md").exists())
        self.assertTrue((r / "50 Notes/Keep/Gate code.md").exists())
        self.assertFalse((r / "50 Notes/Keep/Rounds.md").exists(), "clinical notes never reach the vault")
        self.assertIn("Oil change", (r / "20 Garage/2023 Hyundai Santa Fe.md").read_text())
        self.assertGreater(counts["facility"], 0)

    def test_people_writing_survives_every_sync(self):
        self.v.apply_feed(FEED)
        p = self.v.root / "10 Facility/Assets/AHU-2.md"
        p.write_text(p.read_text() + "Belt squeals on cold mornings — Sam.\n")
        txt = p.read_text().replace("---\n", "---\nowner: Sam\n", 1)
        p.write_text(txt)
        feed = json.loads(json.dumps(FEED))
        feed["facility"]["assets"][0]["location"] = "C-wing"
        self.v.apply_feed(feed)
        after = p.read_text()
        self.assertIn("C-wing", after)
        self.assertIn("Belt squeals on cold mornings — Sam.", after)
        self.assertIn("owner: Sam", after)
        self.assertEqual(1, after.count(V.BEGIN))

    def test_removed_sources_are_archived_not_deleted(self):
        self.v.apply_feed(FEED)
        feed = json.loads(json.dumps(FEED))
        feed["notes"] = []
        self.v.apply_feed(feed)
        self.assertFalse((self.v.root / "50 Notes/Keep/Gate code.md").exists())
        self.assertTrue((self.v.root / "99 Archive/50 Notes/Keep/Gate code.md").exists())

    def test_timeline_and_privacy(self):
        self.assertTrue(self.v.log("task", "Ordered filters", "MERV 13 x 24"))
        self.assertFalse(self.v.log("note", "Peer note for patient JD"))
        day = (self.v.root / self.v.day_rel(self.v.now().date())).read_text()
        self.assertIn("Ordered filters", day)
        self.assertNotIn("patient", day)

    def test_registry(self):
        r = self.v.checkin("Claude Code", "claude-opus-5-5", "Build the vault", "Hi Paul — the vault is live!")
        self.assertTrue(r["ok"])
        reg = (self.v.root / "AI Registry.md").read_text()
        self.assertIn("Claude Code", reg)
        self.assertIn("Hi Paul", reg)
        self.assertIn(r["signature"], reg)

    def test_agents_cannot_edit_managed_blocks_or_settings(self):
        self.v.apply_feed(FEED)
        r = self.v.write_note("10 Facility/Assets/AHU-2.md", "overwrite!", mode="replace", author="Agent")
        self.assertTrue(r["ok"])
        txt = (self.v.root / "10 Facility/Assets/AHU-2.md").read_text()
        self.assertIn("FAC-0042", txt, "replace on a managed note becomes an append")
        self.assertIn("overwrite!", txt)
        self.assertFalse(self.v.write_note(".obsidian/app.json", "{}")["ok"])
        self.assertFalse(self.v.write_note("00 Inbox/x.md", "Patient diagnosis notes")["ok"])
        with self.assertRaises(ValueError):
            self.v.path("../outside.md")

    def test_secrets_and_vins_are_scrubbed(self):
        r = self.v.capture("Santa Fe VIN 5NMS1234X67890123 and the alarm password is 4471", "Car stuff")
        text = (self.v.root / r["path"]).read_text()
        self.assertNotIn("5NMS1234X67890123", text)
        self.assertNotIn("4471", text)

    def test_search(self):
        self.v.apply_feed(FEED)
        hits = self.v.search("filter change AHU-2")
        self.assertEqual("AHU-2", hits[0]["title"])

    @unittest.skipUnless(shutil.which("git"), "git not installed")
    def test_commits(self):
        self.v.apply_feed(FEED)
        self.assertTrue(self.v.commit("test", min_gap=0))
        self.assertGreaterEqual(self.v.status()["commits"], 2)


class McpTest(unittest.TestCase):
    def test_protocol_over_stdio(self):
        tmp = Path(tempfile.mkdtemp())
        try:
            env = dict(os.environ, BLACKLAB_VAULT_DIR=str(tmp / "Vault"), BLACKLAB_VAULT_STATE=str(tmp / "state"), BLACKLAB_DESKTOP_URL="http://127.0.0.1:9")
            msgs = [
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "0"}}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "vault_checkin", "arguments": {"agent": "Test AI", "note": "hello"}}},
                {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "vault_capture", "arguments": {"text": "An idea", "title": "Idea"}}},
                {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "vault_search", "arguments": {"query": "idea"}}},
            ]
            out = subprocess.run([sys.executable, str(Path(V.__file__).with_name("vault_mcp.py"))], input="\n".join(json.dumps(m) for m in msgs) + "\n",
                                 capture_output=True, text=True, env=env, timeout=60)
            replies = [json.loads(l) for l in out.stdout.splitlines()]
            self.assertEqual([1, 2, 3, 4, 5], [r["id"] for r in replies], out.stderr)
            self.assertEqual("blacklab-vault", replies[0]["result"]["serverInfo"]["name"])
            self.assertIn("vault_checkin", [t["name"] for t in replies[1]["result"]["tools"]])
            self.assertFalse(replies[2]["result"]["isError"])
            self.assertIn("Test AI", (tmp / "Vault" / "AI Registry.md").read_text())
            self.assertIn("Idea", replies[4]["result"]["content"][0]["text"])
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
