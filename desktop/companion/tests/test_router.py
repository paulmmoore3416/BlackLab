"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import router as R  # noqa: E402


class WhichToolTest(unittest.TestCase):
    def setUp(self):
        self.stats = R.Stats()

    def pick(self, text):
        return R.which_tool(text, self.stats)

    def test_find_files_named(self):
        d = self.pick("find files named budget 2027")
        self.assertEqual((d["tool"], d["args"], d["sharp"]), ("find_files", {"query": "budget 2027"}, True))
        self.assertEqual(self.pick('Where are my documents called "roof bid"?')["args"], {"query": "roof bid"})

    def test_equipment_tags_normalized(self):
        self.assertEqual(self.pick("show me the history on rtu 4")["args"], {"query": "RTU-4"})
        self.assertEqual(self.pick("AHU2 nameplate please")["args"], {"query": "AHU-2"})
        self.assertEqual(self.pick("what refrigerant does chiller 2 use")["args"], {"query": "Chiller 2"})
        self.assertTrue(self.pick("work orders for EF-12")["sharp"])

    def test_recall_and_documents(self):
        self.assertEqual(self.pick("What did we talk about regarding the boiler bid?")["args"], {"query": "the boiler bid"})
        self.assertEqual(self.pick("search my documents for roof inspection")["tool"], "search_documents")

    def test_weak_cues_split(self):
        d = self.pick("in our last conversation you said something about chillers")
        self.assertEqual(d["tool"], "recall_chats")
        self.assertFalse(d["sharp"])

    def test_no_rule_and_side_effects_never_seeded(self):
        self.assertIsNone(self.pick("what's 12 times 7"))
        self.assertIsNone(self.pick("make a desktop note: call the roofer"))
        self.assertTrue(R.SEEDABLE.isdisjoint({"vault_write", "desktop_note", "execute_command", "open_url", "launch_app"}))

    def test_stats(self):
        self.pick("find files named x")
        self.pick("where did i put it")
        snap = self.stats.snapshot()
        self.assertEqual(snap["forks"]["which_tool"], {"sharp": 1, "split": 1, "sharp_rate": 0.5})
        self.assertEqual(snap["total"], 2)


class ForksTest(unittest.TestCase):
    def test_identical_call_stopped(self):
        f = R.Forks(R.Stats())
        self.assertIsNone(f.before("find_files", {"query": "a"}))
        self.assertEqual(f.after("find_files", {"query": "a"}, "3 files"), "ok")
        self.assertIn("already ran", f.before("find_files", {"query": "a"}))
        self.assertIsNone(f.before("find_files", {"query": "b"}))

    def test_same_error_twice_splits(self):
        f = R.Forks(R.Stats())
        self.assertEqual(f.after("read_file", {"path": "/a/1.txt"}, "read_file failed: No such file /a/1.txt"), "error")
        self.assertEqual(f.after("read_file", {"path": "/b/22.txt"}, "read_file failed: No such file /b/22.txt"), "split")

    def test_different_errors_dont_split(self):
        f = R.Forks(R.Stats())
        f.after("read_file", {"path": "a"}, "read_file failed: permission denied")
        self.assertEqual(f.after("find_files", {"query": "a"}, "find_files failed: timed out"), "error")

    def test_repeat_of_failed_call_says_so(self):
        f = R.Forks(R.Stats())
        f.after("read_file", {"path": "a"}, "read_file failed: nope")
        self.assertIn("failed the same way", f.before("read_file", {"path": "a"}))

    def test_is_error(self):
        self.assertTrue(R.is_error("Not run: the user didn't ask for open_url."))
        self.assertFalse(R.is_error("Found 3 files"))


if __name__ == "__main__":
    unittest.main()
