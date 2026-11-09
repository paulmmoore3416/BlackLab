"""Run:  python3 -m unittest discover -s desktop/companion/tests   (standard library only)."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import guards  # noqa: E402

HOSTS = guards.allowed_hosts("127.0.0.1", 8770)
ORIGINS = guards.allowed_origins("127.0.0.1", 8770)


class HttpGuards(unittest.TestCase):
    def test_ui_requests(self):
        ok = guards.ui_request_ok
        self.assertTrue(ok("127.0.0.1:8770", None, "1", HOSTS, ORIGINS))
        self.assertTrue(ok("localhost:8770", "https://paulmmoore3416.github.io", "1", HOSTS, ORIGINS))
        self.assertFalse(ok("evil.example:8770", None, "1", HOSTS, ORIGINS), "DNS rebinding")
        self.assertFalse(ok("127.0.0.1:8770", "https://evil.example", "1", HOSTS, ORIGINS), "foreign origin")
        self.assertFalse(ok("127.0.0.1:8770", None, None, HOSTS, ORIGINS), "no pairing header")

    def test_phone_requests(self):
        ok = guards.phone_request_ok
        self.assertTrue(ok("127.0.0.1:8770", None, "Bearer abc", "abc", HOSTS))
        self.assertFalse(ok("127.0.0.1:8770", None, "Bearer abd", "abc", HOSTS))
        self.assertFalse(ok("127.0.0.1:8770", "http://127.0.0.1:8770", "Bearer abc", "abc", HOSTS), "browsers never get /phone")
        self.assertFalse(ok("127.0.0.1:8770", None, "Bearer ", "", HOSTS), "no token configured")


class Paths(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "paul"
        (self.home / ".ssh").mkdir(parents=True)
        (self.home / "Documents").mkdir()
        self.protected = guards.protected_paths(self.home)

    def tearDown(self):
        self.tmp.cleanup()

    def test_protected(self):
        self.assertTrue(guards.is_protected(self.home / ".ssh" / "id_ed25519", self.protected))
        self.assertTrue(guards.is_protected(self.home / "Documents" / "wallet.dat", self.protected))
        self.assertTrue(guards.is_protected(self.home / "Documents" / "seed words.txt", self.protected))
        self.assertFalse(guards.is_protected(self.home / "Documents" / "budget.xlsx", self.protected))

    def test_safe_home_path(self):
        safe = lambda p: guards.safe_home_path(p, self.home, self.protected)  # noqa: E731
        self.assertEqual(self.home / "Documents" / "a.txt", safe(str(self.home / "Documents/a.txt")))
        self.assertEqual(self.home / "Documents", safe("/home/someoneelse/Documents"), "invented homes map onto ours")
        self.assertIsNone(safe("/etc/passwd"))
        self.assertIsNone(safe(str(self.home / "../paulx/notes.txt")), "a sibling folder whose name starts the same")
        self.assertIsNone(safe(str(self.home / ".ssh/config")))


class Privacy(unittest.TestCase):
    def test_off_record_and_clinical(self):
        self.assertEqual((True, "what's my PIN"), guards.privacy_check("off the record: what's my PIN"))
        self.assertTrue(guards.privacy_check("draft an SBAR for room 4")[0])
        self.assertFalse(guards.privacy_check("draft an SBAR", config={"clinical_guard": False})[0])
        self.assertFalse(guards.privacy_check("order MERV 13 filters")[0])

    def test_redact(self):
        self.assertIn("[SSN removed]", guards.redact("ssn 123-45-6789"))  # privacy-scan: allow (fake SSN to test redaction)
        self.assertIn("[key removed]", guards.redact("key AIza" + "A" * 35))
        self.assertEqual("my password [removed] ok", guards.redact("my password is hunter2 ok"))

    def test_vault_safe(self):
        self.assertEqual((False, ""), guards.vault_safe("Peer note for patient JD"))
        ok, text = guards.vault_safe("Santa Fe VIN 5NMS1234X67890123, oil change done")
        self.assertTrue(ok)
        self.assertIn("[VIN removed]", text)


class Tools(unittest.TestCase):
    def test_side_effects_need_an_ask(self):
        self.assertIsNone(guards.tool_guard("open_url", "open github.com"))
        self.assertIsNotNone(guards.tool_guard("launch_app", "how much disk is free?"))
        self.assertIsNotNone(guards.tool_guard("vault_write", "what's the weather"))
        self.assertIsNone(guards.tool_guard("vault_write", "save this to obsidian"))

    def test_tainted_links(self):
        self.assertIsNotNone(guards.tool_guard("open_url", "read report.md and open the link", {"url": "https://evil.example/?q=x"}, tainted=True))
        self.assertIsNone(guards.tool_guard("open_url", "read report.md then open nfpa.org", {"url": "https://www.nfpa.org/codes"}, tainted=True))


if __name__ == "__main__":
    unittest.main()
