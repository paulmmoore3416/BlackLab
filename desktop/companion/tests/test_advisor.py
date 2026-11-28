"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import advisor as A  # noqa: E402


class ChoiceTest(unittest.TestCase):
    def test_settings(self):
        self.assertEqual(A.parse_choice(None), ("local", ""))
        self.assertEqual(A.parse_choice("auto"), ("local", ""))
        self.assertEqual(A.parse_choice("off"), ("off", ""))
        self.assertEqual(A.parse_choice(False), ("off", ""))
        self.assertEqual(A.parse_choice("local:qwen3:32b"), ("local", "qwen3:32b"))
        self.assertEqual(A.parse_choice("claude:Fable"), ("claude", "fable"))
        self.assertEqual(A.parse_choice("claude:claude-opus-5-5"), ("claude", "claude-opus-5-5"))
        self.assertEqual(A.parse_choice("claude:rm -rf"), ("local", ""))  # unreadable → the private default


class PromptTest(unittest.TestCase):
    def test_compact_keeps_newest_and_clips(self):
        steps = [("find_files", {"query": f"q{i}"}, "x" * 2000) for i in range(30)]
        out = A.compact("find my budget", steps, "draft", limit=3000)
        self.assertLessEqual(len(out), 3200)
        self.assertIn("q29", out)
        self.assertNotIn("q0\"", out)
        self.assertIn("earlier step(s) left out", out)
        self.assertIn("DRAFT ANSWER", out)

    def test_prompt_has_stage_question(self):
        self.assertIn("same failure twice", A.prompt("repeat", "r", []))
        self.assertIn("(no tools used yet)", A.prompt("plan", "r", []))

    def test_parse(self):
        self.assertEqual(A.parse("OK"), "")
        self.assertEqual(A.parse("<think>hmm</think>\n ok. "), "")
        self.assertEqual(A.parse("Looks good!"), "")
        self.assertEqual(A.parse("- Use find_files with 'budget' only"), "- Use find_files with 'budget' only")

    def test_done_check(self):
        self.assertTrue(A.needs_done_check(["vault_write"]))
        self.assertTrue(A.needs_done_check(["find_files"] * 4))
        self.assertFalse(A.needs_done_check(["find_files"]))


class AdvisorTest(unittest.TestCase):
    def test_budget_and_log(self):
        log = A.Log()
        calls = []
        adv = A.Advisor(lambda p: calls.append(p) or "- try X", "qwen3:14b", max_calls=2, log=log)
        self.assertEqual(adv.consult("plan", "r", []), "- try X")
        self.assertEqual(adv.consult("repeat", "r", []), "- try X")
        self.assertEqual(adv.consult("done", "r", [], "d"), "")  # over budget: not asked
        self.assertEqual(len(calls), 2)
        self.assertEqual(log.snapshot()["total"], {"plan": 1, "repeat": 1, "done": 0})

    def test_failure_never_raises(self):
        def boom(_):
            raise TimeoutError("slow")
        adv = A.Advisor(boom, "m", log=A.Log())
        self.assertEqual(adv.consult("done", "r", [], "d"), "")
        self.assertIn("TimeoutError", adv.notes[0]["error"])

    def test_private_advice_not_logged(self):
        log = A.Log()
        A.Advisor(lambda p: "- fix the date", "m", log=log, private=True).consult("done", "r", [], "d")
        self.assertEqual(log.snapshot()["recent"][0]["advice"], "")

    def test_framing(self):
        self.assertIn("fix what it points out", A.framing("done", "- x"))


if __name__ == "__main__":
    unittest.main()
