"""The nightly code review (desktop/autopilot/nightly_review.py). Run:  python3 -m unittest discover -s desktop/companion/tests"""
import datetime as dt
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent.parent / "autopilot"))
import nightly_review as N  # noqa: E402


class NightlyReviewTest(unittest.TestCase):
    def test_read_only_tools_and_advisor(self):
        args = N.claude_args("/x/claude", "sonnet", "opus")
        self.assertEqual(args[args.index("--tools") + 1], "Read,Grep,Glob")
        self.assertEqual(args[args.index("--advisor") + 1], "opus")
        self.assertEqual(args[args.index("--setting-sources") + 1], "")  # no hooks, no user settings
        self.assertIn("--strict-mcp-config", args)
        self.assertNotIn("Bash", " ".join(args))
        self.assertNotIn("--advisor", N.claude_args("/x/claude", "sonnet", None))

    def test_prompt_redacts_and_clips(self):
        p = N.build_prompt("BlackLab", "abc fix", "+token = ghp_" + "a" * 40 + "\n" + "x" * (N.MAX_DIFF + 10), "24 hours ago")
        self.assertNotIn("ghp_" + "a" * 40, p)
        self.assertIn("first 90,000", p)
        self.assertIn("since 24 hours ago", p)

    def test_verdict(self):
        self.assertEqual(N.verdict("## Verdict\n\nNo findings.\n## Findings\n"), "No findings.")
        self.assertEqual(N.verdict("Looks fine overall."), "Looks fine overall.")

    def test_no_commits_skips_without_calling_claude(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            r = N.review(Path(d), {}, "24 hours ago", dry=False)
        self.assertEqual(r["status"], "Skipped")

    def test_a_failure_is_what_the_panel_shows(self):
        ok, bad, skip = {"repo": "a", "status": "Reviewed"}, {"repo": "b", "status": "Failed"}, {"repo": "c", "status": "Skipped"}
        self.assertIs(N.pick([ok, bad, skip]), bad)
        self.assertIs(N.pick([skip, ok]), ok)
        self.assertIs(N.pick([skip]), skip)
        self.assertEqual(N.pick([])["status"], "Skipped")

    def test_a_crash_is_recorded_not_lost(self):
        saved, sent = {}, []
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            orig = (N.load_conf, N.review, N.save_state, N.companion, sys.argv)
            N.load_conf = lambda: {"review_repos": [d]}
            N.review = lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom"))
            N.save_state = lambda path, **kw: saved.update(kw)
            N.companion = lambda path, body: sent.append((path, body))
            sys.argv = ["nightly_review.py"]
            try:
                rc = N.main()
            finally:
                N.load_conf, N.review, N.save_state, N.companion, sys.argv = orig
        self.assertEqual(rc, 1)
        self.assertEqual(saved["status"], "Failed")
        self.assertIn("boom", saved["summary"])
        self.assertEqual(sent[0][0], "/local/notify")
        self.assertIn("failed", sent[0][1]["title"])

    @staticmethod
    def repo(d, *msgs):
        subprocess.run(["git", "init", "-q", d], check=True)
        shas = []
        for i, m in enumerate(msgs):
            Path(d, f"f{i}.txt").write_text(m)
            subprocess.run(["git", "-C", d, "add", "."], check=True)
            subprocess.run(["git", "-C", d, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", m], check=True)
            shas.append(subprocess.run(["git", "-C", d, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip())
        return shas

    def test_on_demand_reviews_only_new_commits(self):
        with tempfile.TemporaryDirectory() as d:
            a, _b = self.repo(d, "first change", "second change")
            since, base = N.since_for(Path(d), {Path(d).name: a}, on_demand=True)
            log, _patch = N.day_changes(Path(d), since, base)
        self.assertIn("second change", log)
        self.assertNotIn("first change", log)

    def test_on_demand_without_a_recorded_head_uses_a_day(self):
        with tempfile.TemporaryDirectory() as d:
            self.repo(d, "only")
            self.assertEqual(N.since_for(Path(d), {}, on_demand=True), ("24 hours ago", None))
            self.assertEqual(N.since_for(Path(d), {Path(d).name: "0" * 40}, on_demand=True), ("24 hours ago", None))  # not an ancestor

    def test_nightly_skips_commits_already_reviewed(self):
        with tempfile.TemporaryDirectory() as d:
            a, _b = self.repo(d, "reviewed on demand", "new tonight")
            since, base = N.since_for(Path(d), {Path(d).name: a}, on_demand=False)
            log, _ = N.day_changes(Path(d), since, base)
        self.assertEqual(since, "24 hours ago")
        self.assertIn("new tonight", log)
        self.assertNotIn("reviewed on demand", log)

    def test_nothing_new_skips_without_claude(self):
        with tempfile.TemporaryDirectory() as d:
            (head,) = self.repo(d, "only")
            orig = N.ai_models.find_claude
            N.ai_models.find_claude = lambda *_a: (_ for _ in ()).throw(AssertionError("Claude must not be called"))
            try:
                r = N.review(Path(d), {}, *N.since_for(Path(d), {Path(d).name: head}, True), dry=False, on_demand=True)
            finally:
                N.ai_models.find_claude = orig
        self.assertEqual((r["status"], r["summary"], r["head"]), ("Skipped", "Nothing new since the last review", head))

    def test_on_demand_never_touches_nightly_state(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as cache:
            (head,) = self.repo(d, "only")
            nightly, now = Path(cache, "nightly-review.json"), Path(cache, "review-now.json")
            nightly.write_text(json.dumps({"status": "Reviewed", "summary": "keep me", "heads": {Path(d).name: head}}))
            orig = (N.load_conf, N.STATE, N.NOW_STATE, N.companion, sys.argv)
            N.load_conf, N.STATE, N.NOW_STATE = (lambda: {"review_repos": [d]}), nightly, now
            N.companion, sys.argv = (lambda *_a: None), ["nightly_review.py", "--on-demand"]
            try:
                N.main()
            finally:
                N.load_conf, N.STATE, N.NOW_STATE, N.companion, sys.argv = orig
            self.assertEqual(json.loads(nightly.read_text())["summary"], "keep me")
            saved = json.loads(now.read_text())
        self.assertEqual(saved["summary"], "Nothing new since the last review")
        self.assertEqual(saved["heads"][Path(d).name], head)

    def test_on_demand_nothing_new_still_notifies(self):
        sent = []
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as cache:
            (head,) = self.repo(d, "only")
            nightly, now = Path(cache, "nightly-review.json"), Path(cache, "review-now.json")
            nightly.write_text(json.dumps({"heads": {Path(d).name: head}}))
            orig = (N.load_conf, N.STATE, N.NOW_STATE, N.companion, sys.argv)
            N.load_conf, N.STATE, N.NOW_STATE = (lambda: {"review_repos": [d]}), nightly, now
            N.companion, sys.argv = (lambda path, body: sent.append((path, body))), ["nightly_review.py", "--on-demand"]
            try:
                N.main()
            finally:
                N.load_conf, N.STATE, N.NOW_STATE, N.companion, sys.argv = orig
        self.assertEqual([p for p, _ in sent], ["/local/notify"])  # the phone said "you'll get a notification"
        self.assertIn("Nothing new", sent[0][1]["text"])

    def test_heads_recorded_after_a_review(self):
        saved = {}
        with tempfile.TemporaryDirectory() as d:
            self.repo(d, "x")
            orig = (N.load_conf, N.review, N.save_state, N.companion, sys.argv)
            N.load_conf = lambda: {"review_repos": [d]}
            N.review = lambda repo, *a, **k: {"repo": repo.name, "status": "Reviewed", "summary": "No findings.", "head": "abc123"}
            N.save_state = lambda path, **kw: saved.update(kw, path=path)
            N.companion, sys.argv = (lambda *_a: None), ["nightly_review.py"]
            try:
                N.main()
            finally:
                N.load_conf, N.review, N.save_state, N.companion, sys.argv = orig
            name = Path(d).name
        self.assertEqual(saved["heads"][name], "abc123")
        self.assertEqual(saved["path"], N.STATE)

    def test_note_names(self):
        t = dt.datetime(2026, 11, 27, 14, 5)
        self.assertEqual(N.note_name("BlackLab", True, t), "2026-11-27 1405 BlackLab (on demand)")
        self.assertEqual(N.note_name("BlackLab", False, t), "2026-11-27 BlackLab")

    def test_binaries_and_keystores_excluded(self):
        for x in ("*.apk", "keystore", "releases", "local.properties"):
            self.assertIn(f":(exclude){x}", N.EXCLUDE)


if __name__ == "__main__":
    unittest.main()
