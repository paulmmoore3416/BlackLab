"""Nightly system check hand-off (agents.health_report) and the report builder. Run:  python3 -m unittest discover -s desktop/companion/tests"""
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent.parent / "autopilot"))
import agents  # noqa: E402
import blacklab_health as H  # noqa: E402


def report(status="warn", title="System check · Sat Nov 21", text="# x", issues=None):
    return {"id": "health-2026-11-21", "date": "2026-11-21", "kind": "nightly", "status": status, "title": title,
            "summary": "1 thing to look at", "issues": issues if issues is not None else [{"area": "Data", "status": "warn", "title": "t", "detail": "", "fix": ""}],
            "counts": {"checks": 3, "fail": 0, "warn": 1}, "text": text}


class HealthHandoffTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        agents.C = types.SimpleNamespace(_private_dir=lambda *ps: [os.makedirs(p, exist_ok=True) for p in ps])
        agents.JOBS_FILE = Path(self.tmp.name) / "jobs.json"
        agents.JOBS.clear()

    def tearDown(self):
        agents.JOBS.clear()
        self.tmp.cleanup()

    def test_report_becomes_a_phone_job(self):
        j = agents.health_report(report())
        self.assertEqual(j["kind"], "health")
        self.assertTrue(j["origin"].startswith("Phone"))
        self.assertIn(j["id"], [p["id"] for p in agents.pending_for_phone()])
        self.assertEqual(j["result"]["status"], "warn")
        self.assertIn("Needs a look", j["result"]["notify"]["title"])

    def test_same_night_replaces_undelivered_copy(self):
        agents.health_report(report())
        agents.health_report(report(status="ok", issues=[]))
        health = [j for j in agents.JOBS.values() if j["kind"] == "health"]
        self.assertEqual(len(health), 1)
        self.assertEqual(health[0]["result"]["status"], "ok")

    def test_delivered_copy_is_kept(self):
        first = agents.health_report(report())
        agents.ack([first["id"]])
        agents.health_report(report(status="ok", issues=[]))
        self.assertEqual(len([j for j in agents.JOBS.values() if j["kind"] == "health"]), 2)

    def test_rejects_other_payloads(self):
        with self.assertRaises(ValueError):
            agents.health_report({"id": "job-1", "status": "ok"})
        with self.assertRaises(ValueError):
            agents.health_report(dict(report(), status="great"))

    def test_text_is_capped(self):
        j = agents.health_report(report(text="x" * 100_000))
        self.assertLessEqual(len(j["result"]["text"]), 60_000)


class ReportBuilderTest(unittest.TestCase):
    def test_healthy(self):
        s = H.Scan()
        s.add("Desktop AI", H.OK, "Ollama is running")
        r = H.build_report(s, "nightly")
        self.assertEqual(r["status"], "ok")
        self.assertEqual(r["issues"], [])
        self.assertTrue(r["id"].startswith("health-"))
        self.assertIn("All 1 checks passed", r["summary"])

    def test_worst_status_wins_and_fails_come_first(self):
        s = H.Scan()
        s.add("System", H.WARN, "Swap high")
        s.add("Data", H.FAIL, "Vault history damaged", fix="repair-vault-git.sh")
        s.add("System", H.INFO, "Up 3 h")
        r = H.build_report(s, "nightly")
        self.assertEqual(r["status"], "fail")
        self.assertEqual([i["title"] for i in r["issues"]], ["Vault history damaged", "Swap high"])
        self.assertIn("## Needs your attention", r["text"])
        self.assertIn("`repair-vault-git.sh`", r["text"])

    def test_broken_check_is_a_finding(self):
        s = H.Scan()

        def boom(_):
            raise RuntimeError("nope")
        s.guarded("System", boom)
        self.assertEqual(s.status, "warn")


if __name__ == "__main__":
    unittest.main()
