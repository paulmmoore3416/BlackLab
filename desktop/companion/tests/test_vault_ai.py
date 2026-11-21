"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import datetime as dt
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import phone_ops  # noqa: E402
import vault as V  # noqa: E402
import vault_ai as AI  # noqa: E402
import vault_colors  # noqa: E402


class VaultAiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()
        self.when = dt.datetime(2026, 11, 21, 14, 45, tzinfo=self.v.tz)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_claude_turns_land_in_a_glowing_day_note(self):
        r = AI.record(self.v, "claude", "claude-opus-5-5", "Phone", "How do I size a condensate pump?", "Start from the coil's GPH…", self.when, "Claude Opus 5.5")
        self.assertTrue(r["ok"], r)
        AI.record(self.v, "claude", "claude-haiku-5-5", "Obsidian", "Second question", "Second answer", self.when + dt.timedelta(minutes=5), "Claude Haiku 5.5")
        rel = "70 AI/Claude/Conversations/2026-11/Claude · 2026-11-21.md"
        text = (self.v.root / rel).read_text()
        self.assertIn("ai_provider: claude", text)
        self.assertIn("ai/claude", text)
        self.assertIn("bl-ai-claude", text)
        self.assertIn("exchanges: 2", text)
        self.assertIn("claude-haiku-5-5", text)
        self.assertEqual(text.count("---\n"), 2 + 2)  # frontmatter fences + one rule after each exchange
        self.assertTrue(text.index("condensate pump") < text.index("Second question"))
        hub = (self.v.root / "70 AI/Claude/🟠 Claude.md").read_text()
        self.assertIn("2 exchanges", hub)
        self.assertIn("[[Claude · 2026-11-21|", hub)
        day = self.v.path(self.v.day_rel(self.when.date()))
        self.assertTrue(day.exists() and "Claude" in day.read_text())

    def test_gemini_nano_counts_as_gemini_and_local_is_separate(self):
        self.assertEqual(AI.provider_for("nano", "gemini-nano"), "gemini")
        self.assertEqual(AI.provider_for("local", "qwen3:8b"), "local")
        AI.record(self.v, "nano", "gemini-nano", "Phone", "q", "a", self.when)
        self.assertTrue((self.v.root / "70 AI/Gemini/Conversations/2026-11/Gemini · 2026-11-21.md").exists())

    def test_clinical_and_secrets_never_saved(self):
        r = AI.record(self.v, "claude", "m", "Phone", "The patient in room 4 needs q15 checks", "ok", self.when)
        self.assertFalse(r["ok"])
        fake = "AI" + "za" + "SyA1234567890abcdefghijklmnopqrstuv"  # built at runtime so the repo's secret scan doesn't see a key
        AI.record(self.v, "gemini", "m", "Phone", f"my api_key = {fake}", "noted", self.when)
        text = (self.v.root / "70 AI/Gemini/Conversations/2026-11/Gemini · 2026-11-21.md").read_text()
        self.assertNotIn("AIzaSyA1234567890", text)

    def test_local_ai_finds_them(self):
        AI.record(self.v, "claude", "claude-opus-5-5", "Phone", "Chiller approach temperature too high", "Check the condenser tubes…", self.when)
        AI.record(self.v, "gemini", "gemini-2.5-pro", "Desktop", "Lesson plan for OSHA 10", "Day one…", self.when)
        hits = AI.search(self.v, "chiller approach", "", 5)
        self.assertEqual(hits[0]["provider"], "claude")
        self.assertIn("condenser tubes", AI.read_exchange(self.v, hits[0]["note"], hits[0]["anchor"]))
        self.assertEqual(AI.search(self.v, "osha", "gemini")[0]["provider"], "gemini")
        self.assertEqual(AI.search(self.v, "chiller", "gemini"), [])
        self.assertEqual(len(AI.today(self.v, self.when.date())), 2)

    def test_graph_groups_put_claude_and_gemini_first(self):
        g = vault_colors.graph_groups()
        self.assertIn("ai_provider", g[1]["query"])
        self.assertEqual(g[1]["color"]["rgb"], 0xFF8C1A)
        self.assertEqual(g[2]["color"]["rgb"], 0xFF2D3D)
        css = vault_colors.css()
        self.assertIn(".bl-ai-claude .inline-title", css)
        self.assertIn("text-shadow", css)


class PhoneOpsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.ops = phone_ops.Ops(self.tmp / "ops.json")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_queue_replace_ack(self):
        a = self.ops.queue({"op": "task.complete", "task_id": 3}, key="task:3")
        b = self.ops.queue({"op": "task.reopen", "task_id": 3}, key="task:3")  # changed my mind before the phone saw it
        self.ops.queue({"op": "task.add", "title": "Call vendor"})
        p = self.ops.pending()
        self.assertEqual([x["op"] for x in p], ["task.reopen", "task.add"])
        self.assertNotIn("key", p[0])
        self.assertEqual(self.ops.ack([b["id"], a["id"]]), 1)
        self.assertEqual(self.ops.count(), 1)
        with self.assertRaises(ValueError):
            self.ops.queue({"op": "rm -rf"})


if __name__ == "__main__":
    unittest.main()
