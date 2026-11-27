"""Agent Ops state that survives restarts, and the nightly review's own panel. Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import advisor as A  # noqa: E402
import agentops as O  # noqa: E402
import router as R  # noqa: E402

DAY = 86400


class Tmp(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.p = Path(self.dir.name) / "state.json"

    def tearDown(self):
        self.dir.cleanup()


class SaveLoadTest(Tmp):
    def test_round_trip_private_file(self):
        O.save(self.p, {"a": 1})
        self.assertEqual(O.load(self.p, {}), {"a": 1})
        self.assertEqual(os.stat(self.p).st_mode & 0o777, 0o600)
        self.assertEqual([x.name for x in self.p.parent.iterdir()], ["state.json"])  # no temp file left behind

    def test_missing_or_corrupt_gives_default(self):
        self.assertEqual(O.load(self.p, {"d": 1}), {"d": 1})
        self.p.write_text("{not json")
        self.assertEqual(O.load(self.p, {"d": 1}), {"d": 1})
        self.p.write_text("[1, 2]")  # valid JSON, wrong shape
        self.assertEqual(O.load(self.p, {"d": 1}), {"d": 1})

    def test_series_fills_quiet_days(self):
        now = time.time()
        out = O.series({O.day(now): {"sharp": 2}, O.day(now - 3 * DAY): {"split": 1}}, ("sharp", "split"), now, 5)
        self.assertEqual([x["day"] for x in out], [O.day(now - i * DAY) for i in (4, 3, 2, 1, 0)])
        self.assertEqual([x["sharp"] + x["split"] for x in out], [0, 1, 0, 0, 2])

    def test_keep_days(self):
        now = time.time()
        days = {O.day(now - i * DAY): {"n": i} for i in range(40)}
        kept = O.keep_days(days, 30)
        self.assertEqual(len(kept), 30)
        self.assertIn(O.day(now), kept)
        self.assertNotIn(O.day(now - 35 * DAY), kept)


class ForkStatsPersistTest(Tmp):
    def test_counts_survive_a_restart(self):
        s = R.Stats()
        s.attach(self.p)
        s.note("tool_choice", True, "find_files")
        s.note("tool_choice", False, "left to the model")
        s.note("repeat_call", True)
        again = R.Stats()  # the companion after the 04:00 restart
        again.attach(self.p)
        snap = again.snapshot()
        self.assertEqual(snap["total"], 3)
        self.assertEqual(snap["forks"]["tool_choice"], {"sharp": 1, "split": 1, "sharp_rate": 0.5})
        self.assertEqual(snap["today"], 3)
        self.assertEqual(snap["days"][-1], {"day": O.day(time.time()), "sharp": 2, "split": 1})
        self.assertEqual(len(snap["recent"]), 3)

    def test_tool_arguments_never_reach_the_file(self):
        s = R.Stats()
        s.attach(self.p)
        R.which_tool("find files named quarterly-budget-secret", s)
        self.assertGreater(s.snapshot()["total"], 0)
        self.assertNotIn("quarterly-budget-secret", self.p.read_text())

    def test_notes_before_attach_are_kept(self):
        O.save(self.p, {"counts": {"tool_choice": {"sharp": 5, "split": 0}}, "days": {}, "recent": []})
        s = R.Stats()
        s.note("tool_choice", True)
        s.attach(self.p)
        self.assertEqual(s.snapshot()["forks"]["tool_choice"]["sharp"], 6)

    def test_old_days_drop_off(self):
        s = R.Stats()
        s.attach(self.p)
        t0 = time.time()
        for i in range(40):
            s.clock = lambda i=i: t0 - (39 - i) * DAY
            s.note("tool_choice", True)
        saved = json.loads(self.p.read_text())
        self.assertEqual(len(saved["days"]), O.KEEP_DAYS)
        self.assertEqual(s.snapshot()["total"], 40)  # all-time totals stay
        self.assertEqual(len(s.snapshot()["days"]), 14)  # the view shows two weeks

    def test_garbage_file_starts_empty(self):
        self.p.write_text(json.dumps({"counts": {"x": "nope"}, "days": [], "recent": "?"}))
        s = R.Stats()
        s.attach(self.p)
        s.note("tool_choice", True)
        self.assertEqual(s.snapshot()["total"], 1)

    def test_no_path_writes_nothing(self):
        s = R.Stats()
        s.note("tool_choice", True)
        self.assertFalse(self.p.exists())


class AdvisorLogPersistTest(Tmp):
    def test_totals_and_feed_survive_a_restart(self):
        log = A.Log()
        log.attach(self.p)
        log.add({"at": time.time(), "stage": "plan", "model": "m", "seconds": 1.0, "advice": "x", "silent": False, "error": ""})
        log.add({"at": time.time(), "stage": "done", "model": "m", "seconds": 1.0, "advice": "", "silent": True, "error": ""})
        again = A.Log()
        again.attach(self.p)
        snap = again.snapshot()
        self.assertEqual(snap["total"], {"plan": 1, "repeat": 0, "done": 1})
        self.assertEqual(snap["today"], {"plan": 1, "repeat": 0, "done": 1})
        self.assertEqual(snap["days"][-1]["day"], O.day(time.time()))
        self.assertEqual(len(snap["recent"]), 2)

    def test_private_advice_is_never_written(self):
        log = A.Log()
        log.attach(self.p)
        adv = A.Advisor(lambda _p: "check the patient's chart first", "m", log=log, private=True)
        adv.consult("plan", "private request", [])
        self.assertNotIn("chart", self.p.read_text())


class ReviewPanelTest(unittest.TestCase):
    def test_setup_defaults(self):
        s = O.review_setup({})
        self.assertEqual((s["enabled"], s["model"], s["advisor"], s["time"]), (True, "sonnet", "opus", "02:30"))

    def test_setup_honours_config_and_drops_a_weaker_advisor(self):
        s = O.review_setup({"nightly_review": False, "review_model": "opus", "review_advisor": "haiku", "review_time": "01:15"})
        self.assertEqual((s["enabled"], s["model"], s["advisor"], s["time"]), (False, "opus", "", "01:15"))
        self.assertEqual(O.review_setup({"review_advisor": "off"})["advisor"], "")

    def test_health(self):
        now = time.time()
        on = O.review_setup({})
        self.assertEqual(O.review_health(O.review_setup({"nightly_review": False}), {}, now)["state"], "off")
        self.assertEqual(O.review_health(on, {}, now)["state"], "waiting")
        self.assertEqual(O.review_health(on, {}, now, installed_at=now - 2 * DAY)["state"], "missed")
        self.assertEqual(O.review_health(on, {"at": now - 3600, "status": "Skipped"}, now)["state"], "ok")
        self.assertEqual(O.review_health(on, {"at": now - 3600, "status": "Reviewed"}, now)["state"], "ok")
        self.assertEqual(O.review_health(on, {"at": now - 3600, "status": "Failed", "summary": "not logged in"}, now)["state"], "failed")
        missed = O.review_health(on, {"at": now - 2 * DAY, "status": "Reviewed"}, now)
        self.assertEqual(missed["state"], "missed")
        self.assertIn("2 days", missed["note"])


if __name__ == "__main__":
    unittest.main()
