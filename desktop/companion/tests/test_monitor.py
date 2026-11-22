"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import re
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import monitor as M  # noqa: E402

GB = 2**30
BASE = {"host": "box", "cpus": 16, "load1": 1.0, "uptime": 90000, "mem_total": 48 * GB, "mem_avail": 30 * GB,
        "disks": [{"label": "System", "total": 2000 * GB, "used": 400 * GB}],
        "gpus": [{"name": "NVIDIA GeForce GTX 1060 6GB", "used": 3 * GB, "total": 6 * GB, "busy": 10, "temp": 50}],
        "services": [{"name": "blacklab-desktop", "state": "active", "scope": "user"}, {"name": "ollama", "state": "active", "scope": "system"}]}


def snap(**kw):
    system = dict(BASE, **kw.pop("system", {}))
    now = time.time()
    s = M.snapshot(system, kw.pop("health", {"status": "ok", "summary": "all good", "generated": now - 3600}), chat_model="qwen3:8b",
                   recall=kw.pop("recall", {"kb_core": True, "indexed": 10, "reference": {"indexed": 3}}), now=now,
                   get=kw.pop("get", lambda url: {"models": [{"name": "qwen3:8b"}]}), **kw)
    return s


class MonitorTest(unittest.TestCase):
    def test_all_good(self):
        s = snap()
        self.assertEqual(M.problems(s), [])
        r = M.report(s)
        self.assertTrue(r.startswith("🟢 All good — box"))
        self.assertIn("AI: loaded qwen3:8b", r)
        self.assertIn("10 notes + 3 reference docs", r)

    def test_problems_are_found_and_ranked(self):
        s = snap(system={"services": BASE["services"] + [{"name": "blacklab-syncthing", "state": "failed", "scope": "user"},
                                                         {"name": "kiwix library", "state": "inactive", "scope": "port"}],
                         "disks": [{"label": "System", "total": 100, "used": 95}], "mem_avail": 1 * GB,
                         "gpus": [dict(BASE["gpus"][0], temp=88)]},
                 recall={"kb_core": False}, health={"status": "fail", "summary": "2 failures", "generated": time.time() - 40 * 3600},
                 get=lambda url: None)
        keys = {p["key"]: p["severity"] for p in M.problems(s)}
        self.assertEqual(keys["service:blacklab-syncthing"], "bad")
        self.assertEqual(keys["service:kiwix library"], "warn")
        self.assertEqual(keys["ollama"], "bad")
        self.assertEqual(keys["disk:System"], "bad")
        for k in ("memory", "gpu:NVIDIA GeForce GTX 1060 6GB", "kb-core", "nightly-stale"):
            self.assertEqual(keys[k], "warn", k)
        self.assertEqual(keys["nightly"], "bad")
        r = M.report(s)
        self.assertTrue(r.startswith("🔴 Needs attention"))
        self.assertIn("Ollama DOWN", r)

    def test_model_not_loaded_is_a_warning(self):
        s = snap(get=lambda url: {"models": [{"name": "nomic-embed-text:latest"}]})
        self.assertEqual([p["key"] for p in M.problems(s)], ["model"])

    def test_alerts_only_on_change_and_warnings_debounced(self):
        st = {}
        bad = {"key": "ollama", "severity": "bad", "text": "Ollama down"}
        warn = {"key": "disk:System", "severity": "warn", "text": "disk 85%"}
        new, cleared = M.changes(st, [bad, warn])
        self.assertEqual([p["key"] for p in new], ["ollama"])   # bad: at once; warning: wait one more check
        new, cleared = M.changes(st, [bad, warn])
        self.assertEqual([p["key"] for p in new], ["disk:System"])
        new, cleared = M.changes(st, [bad, warn])
        self.assertEqual((new, cleared), ([], []))              # never a repeat
        new, cleared = M.changes(st, [warn])
        self.assertEqual((new, cleared), ([], ["ollama"]))      # recovery
        new, cleared = M.changes(st, [])
        self.assertEqual(cleared, ["disk:System"])
        blip = {"key": "memory", "severity": "warn", "text": "low"}
        M.changes(st, [blip])
        new, cleared = M.changes(st, [])
        self.assertEqual((new, cleared), ([], []))              # a one-check blip never buzzes

    def test_status_phrases(self):
        for q in ("status report", "system check", "run a full system check", "is everything ok at home?", "how is my computer doing",
                  "how's everything", "computer status", "any problems?", "what's wrong with the computer", "homelab health", "give me a status update"):
            self.assertRegex(q, M.STATUS_WORDS, q)
        for q in ("check the status of work order 12", "what is wrong with RTU-4", "report the leak in B-wing", "status of the veteran initiative"):
            self.assertIsNone(re.match(M.STATUS_WORDS, q), q)

    def test_syncthing_status_reads_config_and_api(self):
        import tempfile
        d = Path(tempfile.mkdtemp())
        (d / "config.xml").write_text('<configuration><gui><address>127.0.0.1:8384</address><apikey>k</apikey></gui>'
                                      '<folder id="blacklab-vault"><device id="ME"/><device id="PHONE"/></folder><device id="ME"/><device id="PHONE"/></configuration>')
        api = {"/rest/system/status": {"myID": "ME"}, "/rest/system/connections": {"connections": {"PHONE": {"connected": False}}},
               "/rest/stats/device": {"PHONE": {"lastSeen": "2026-11-22T10:00:00-06:00"}}}
        st = M.syncthing_status(d, get=api.get)
        self.assertTrue(st["phone_configured"])
        self.assertFalse(st["phone_connected"])
        self.assertGreater(st["phone_last_seen"], 0)
        self.assertIsNone(M.syncthing_status(d / "nope"))


if __name__ == "__main__":
    unittest.main()
