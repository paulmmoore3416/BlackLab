"""Paul's private drive: routing, privacy, queue. Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import drive_private as D  # noqa: E402
import vault as V  # noqa: E402

FOLDERS = {p: f"id-{i}" for i, p in enumerate([r for r, _ in D.ROUTES] + [D.DEFAULT, D.NOTES_FALLBACK])}


class RoutingTest(unittest.TestCase):
    def test_words_pick_the_folder(self):
        cases = {
            "AHU-2 work order: replace the supply fan belt": "10 Facility & Work/Work Orders & Maintenance",
            "Joint Commission survey prep list": "10 Facility & Work/Inspections & Compliance",
            "oil change on the car at 41,000 miles": "50 Vehicles/Service & Repairs",
            "W-2 arrives late January": "40 Finance & Legal/Taxes",
            "CHFM exam date moved to April": "20 Learning & Credentials/Certifications & CEUs",
            "idea: a QR code on every asset tag": "70 Ideas & Notes/Ideas",
            "buy milk": D.NOTES_FALLBACK,
        }
        for text, want in cases.items():
            self.assertEqual(want, D.folder_for(text, None, FOLDERS), text)

    def test_named_folder_wins(self):
        self.assertEqual("40 Finance & Legal/Taxes", D.folder_for("anything", "taxes", FOLDERS))
        self.assertEqual("70 Ideas & Notes/Journal", D.folder_for("work order stuff", "journal", FOLDERS))
        self.assertEqual("50 Vehicles/Service & Repairs", D.folder_for("x", "car stuff", FOLDERS))

    def test_parse_the_spoken_command(self):
        self.assertEqual((None, "renew the boiler license"), D.parse("save to my private drive: renew the boiler license"))
        self.assertEqual(("taxes", "W-2 late"), D.parse("Save this to my private drive under taxes: W-2 late"))
        self.assertIsNone(D.parse("save to my drive: x"))


class SaveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "map.json").write_text(json.dumps({"private": {"id": "p"}, "folders": FOLDERS}))
        self.v = V.Vault(self.tmp / "vault", self.tmp / "state")
        self.v.ensure()
        self.sent = []

        def up(title, text, folder_id):
            self.sent.append((title, text, folder_id))
            return True, "newfileid1234567890123456789"
        self.d = D.PrivateDrive(self.v, {}, self.tmp / "map.json", self.tmp / "local", uploader=up)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_save_files_uploads_and_remembers(self):
        r = self.d.save("Oil change on the car, next due at 46,000 miles", upload_now=False)
        self.assertTrue(r["ok"])
        self.assertEqual("50 Vehicles/Service & Repairs", r["folder"])
        self.assertTrue(Path(r["local"]).exists())
        self.assertEqual({"uploaded": 1, "failed": 0}, self.d.flush())
        self.assertEqual(FOLDERS["50 Vehicles/Service & Repairs"], self.sent[0][2])
        self.assertEqual(1, self.d.status().get("uploaded"))
        cards = list(self.v.root.glob("88 Drive/Paul's Private/50 Vehicles/Service & Repairs/*.md"))
        self.assertEqual(1, len(cards))

    def test_privacy(self):
        self.assertFalse(self.d.save("patient in room 4 refused meds")["ok"])
        r = self.d.save("wifi password: hunter2hunter2", upload_now=False)
        self.assertTrue(r["scrubbed"])
        self.assertNotIn("hunter2", Path(r["local"]).read_text())

    def test_failed_upload_waits_for_the_next_pass(self):
        d = D.PrivateDrive(self.v, {}, self.tmp / "map.json", self.tmp / "local", uploader=lambda *a: (False, "offline"))
        d.save("an idea for later", upload_now=False)
        self.assertEqual(1, d.flush()["failed"])
        self.assertEqual(1, d.status().get("retry"))
        self.d.flush()
        self.assertEqual(1, self.d.status().get("uploaded"))


if __name__ == "__main__":
    unittest.main()
