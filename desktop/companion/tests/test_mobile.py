"""The phone in the graph, with a fake adb. Run:  python3 -m unittest discover -s desktop/companion/tests"""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_mobile as M  # noqa: E402

LISTING = "\n".join([
    "1200|1760000000|./Documents/Notes/PM checklist.md",
    "900|1760000000|./Documents/MooreCore/Key🔐 Information.md",
    "800|1760000000|./Documents/BlackLab/Home.md",
    "50|1760000000|./BlackLab/.provision/keys.json",
    "3000000|1761000000|./DCIM/Camera/PXL_20261005_120000000.jpg",
    "2000000|1761000000|./DCIM/Camera/PXL_20261006_120000000.jpg",
    "5000000|1762000000|./Music/Artist/Album/01 Song.mp3",
])


def fake(cmd, timeout=300):
    if cmd.startswith("getprop ro.product.model"):
        return "Pixel 10 Pro XL\n"
    if cmd.startswith("getprop ro.product.manufacturer"):
        return "Google\n"
    if cmd.startswith("getprop"):
        return "17\n"
    if cmd.startswith("df"):
        return "/dev/block/dm-79 228G 184G 44G 81% /data/user/0\n"
    if cmd.startswith("dumpsys battery"):
        return "  level: 71\n"
    if cmd.startswith("pm list"):
        return "package:com.blacklab.app\npackage:md.obsidian\n"
    if cmd.startswith("cd /sdcard && find"):
        return LISTING
    return ""


class MobileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "vault", self.tmp / "state")
        self.v.ensure()
        local = self.tmp / "phone" / "SERIAL" / "Documents" / "Notes"
        local.mkdir(parents=True)
        (local / "PM checklist.md").write_text("# PM checklist\n\nChange AHU filters every 90 days and log the pressure drop across each bank.\n")
        self.m = M.Mobile(self.v, "adb", "SERIAL", {}, self.tmp / "phone", runner=fake)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_device_documents_and_collections(self):
        r = self.m.run()
        self.assertEqual(1, r["documents"])  # the key file, the vault mirror and provisioning are never read
        dev = self.v.path("89 Mobile/📱 Pixel 10 Pro XL.md").read_text()
        self.assertIn("Android 17", dev)
        self.assertIn("md.obsidian", dev)
        card = self.v.path("89 Mobile/Files/Documents/Notes/PM checklist.md.md").read_text()
        self.assertIn("[[◆ Mobile Device]]", card)
        self.assertIn("AHU filters", card)
        everything = "\n".join(p.read_text() for p in self.v.root.rglob("*.md"))
        self.assertNotIn("Key🔐", everything)
        self.assertNotIn("keys.json", everything)
        self.assertTrue(self.v.path("89 Mobile/Collections/DCIM › Camera · 2026-10.md").exists())
        self.assertTrue(list(self.v.root.glob("89 Mobile/Collections/Music › Artist › Album*.md")))


if __name__ == "__main__":
    unittest.main()
