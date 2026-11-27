"""The nightly code review (desktop/autopilot/nightly_review.py). Run:  python3 -m unittest discover -s desktop/companion/tests"""
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

    def test_binaries_and_keystores_excluded(self):
        for x in ("*.apk", "keystore", "releases", "local.properties"):
            self.assertIn(f":(exclude){x}", N.EXCLUDE)


if __name__ == "__main__":
    unittest.main()
