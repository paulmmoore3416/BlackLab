import datetime as dt
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import chat_memory  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


class MemoryTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "Chat History" / "memory.md"
        self.writes = []
        self.mem = chat_memory.Memory(self.path, on_write=self.writes.append, max_facts=4, today=lambda: dt.date(2026, 11, 28))

    def tearDown(self):
        self.dir.cleanup()

    def test_add_stamps_dedupes_and_is_private(self):
        self.assertEqual(["The chiller vendor is Trane"], self.mem.add(["The chiller vendor is Trane."]))
        self.assertEqual([], self.mem.add(["the chiller vendor is trane"]))  # same fact, other case
        self.assertEqual(["- The chiller vendor is Trane _(added 2026-11-28)_"], self.mem.facts())
        self.assertEqual(["The chiller vendor is Trane"], self.mem.clean())
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        self.assertEqual([self.path], self.writes)

    def test_one_call_with_duplicates_adds_once(self):
        self.assertEqual(["A fact"], self.mem.add(["A fact", "a fact"]))

    def test_cap_drops_oldest_learned_first(self):
        self.mem.add(["kept one"])
        self.mem.add(["learned one"], "learned")
        self.mem.add(["kept two", "kept three"])
        self.mem.add(["kept four"])
        self.assertEqual(["kept one", "kept two", "kept three", "kept four"], self.mem.clean())

    def test_forget_needs_every_word(self):
        self.mem.add(["Boiler 2 is serviced by Hurst", "Boiler 1 is gas"])
        self.assertEqual([], self.mem.forget("the"))  # only stopwords: nothing
        self.assertEqual(1, len(self.mem.forget("hurst boiler")))
        self.assertEqual(["Boiler 1 is gas"], self.mem.clean())

    def test_prompt_and_rev(self):
        self.assertEqual("", self.mem.prompt())
        self.assertEqual(0, self.mem.rev())
        self.mem.add(["Prefers short answers"])
        self.assertIn("- Prefers short answers", self.mem.prompt())
        self.assertNotIn("_(added", self.mem.prompt())
        self.assertGreater(self.mem.rev(), 0)


class ThreadTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.th = chat_memory.Thread(per_origin=3, shared=8, window=900, handoff=600, clock=self.clock)

    def test_own_turns_win(self):
        self.th.remember("Desktop", "d1", "r1", "Desktop")
        self.th.remember("Phone", "p1", "r2", "Phone")
        self.assertEqual([("p1", "r2")], self.th.recent("Phone"))

    def test_handoff_to_another_device(self):
        self.th.remember("Desktop", "what's on the RTU-4 PM?", "Filters and belts.", "Desktop")
        self.assertEqual([("what's on the RTU-4 PM?", "Filters and belts.")], self.th.recent("Phone"))
        self.clock.t += 601  # past the hand-off window
        self.assertEqual([], self.th.recent("Phone"))
        self.assertEqual([("what's on the RTU-4 PM?", "Filters and belts.")], self.th.recent("Desktop"))  # own window is 15 min

    def test_own_turns_expire(self):
        self.th.remember("Desktop", "a", "b", "Desktop")
        self.clock.t += 901
        self.assertEqual([], self.th.recent("Desktop"))

    def test_shared_is_capped_and_trimmed(self):
        for i in range(10):
            self.th.remember("Phone", f"q{i}", "x" * 3000, "Phone")
        shared = self.th.shared(limit=20)
        self.assertEqual(8, len(shared))
        self.assertEqual("q9", shared[-1]["you"])
        self.assertEqual(1500, len(shared[-1]["ai"]))


class PayloadTest(unittest.TestCase):
    def test_payload_filters_unsafe_text(self):
        with tempfile.TemporaryDirectory() as d:
            mem = chat_memory.Memory(Path(d) / "memory.md")
            mem.add(["Likes the laid-back voice", "SECRET thing"])
            th = chat_memory.Thread(clock=lambda: 5.0)
            th.remember("Desktop", "hello", "hi there", "Desktop")
            th.remember("Desktop", "SECRET question", "ok", "Desktop")
            p = chat_memory.payload(mem, th, safe=lambda t: "SECRET" not in t)
            self.assertTrue(p["ok"])
            self.assertEqual(["Likes the laid-back voice"], p["facts"])
            self.assertEqual([{"at": 5000, "surface": "Desktop", "you": "hello", "ai": "hi there"}], p["recent"])


if __name__ == "__main__":
    unittest.main()
