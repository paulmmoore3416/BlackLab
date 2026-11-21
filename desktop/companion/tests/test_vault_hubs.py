"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import datetime as dt
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_boards as B  # noqa: E402
import vault_hubs as H  # noqa: E402


class HubsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_daily_dashboard_and_command_center(self):
        today = self.v.now().date().isoformat()
        self.v.apply_feed({"tasks": [{"id": 1, "title": "Replace AHU-2 belt", "list": "Work Orders", "profile": "PROFESSIONAL", "workorder": True},
                                     {"id": 2, "title": "Submit timesheet", "list": "Admin", "profile": "PROFESSIONAL", "due": today}]})
        day = self.v.path(self.v.day_rel(self.v.now().date())).read_text()
        self.assertIn("Today at a glance", day)
        self.assertIn("Submit timesheet", day)
        self.assertIn("[[Replace AHU-2 belt]]", day)
        H.render_command_center(self.v)
        cc = self.v.path(H.COMMAND_REL).read_text()
        self.assertIn("Open work orders (1)", cc)
        self.assertIn("![[Open work.base]]", cc)
        self.assertNotIn("Today at a glance", self.v.path(self.v.day_rel(self.v.now().date() - dt.timedelta(days=3))).read_text()
                         if self.v.path(self.v.day_rel(self.v.now().date() - dt.timedelta(days=3))).exists() else "")

    def test_study_hub_and_decks(self):
        epoch = (self.v.now().date() - dt.date(1970, 1, 1)).days
        self.v.apply_feed({"learning": {"courses": [{"id": 7, "code": "FM 301", "title": "Facility Management"}],
                                        "assignments": [{"courseId": 7, "title": "Case study", "kind": "Paper", "status": "OPEN", "due": "2026-12-01"}],
                                        "certs": [{"name": "CHFM", "status": "active", "expires": "2027-06-30"}],
                                        "decks": [{"id": 1, "name": "OSHA 30", "cards": [{"front": "LOTO standard", "back": "29 CFR 1910.147", "dueDay": epoch},
                                                                                          {"front": "PEL", "back": "Permissible exposure limit", "dueDay": epoch + 5}]}]}})
        hub = self.v.path(H.STUDY_REL).read_text()
        self.assertIn("Case study", hub)
        self.assertIn("[[OSHA 30]] — 2 cards, 1 due", hub)
        deck = self.v.path(f"{H.DECKS_DIR}/OSHA 30.md").read_text()
        self.assertIn("- LOTO standard :: 29 CFR 1910.147", deck)
        self.assertIn("flashcards", deck)
        # an older phone without decks keeps the deck notes
        self.v.apply_feed({"learning": {"courses": [], "assignments": [], "certs": []}})
        self.assertTrue(self.v.path(f"{H.DECKS_DIR}/OSHA 30.md").exists())

    def test_project_board_two_way(self):
        pr = self.v.path("02 Projects/Chiller replacement.md")
        pr.parent.mkdir(parents=True, exist_ok=True)
        pr.write_text("---\ntype: project\nstatus: planning\n---\n# Chiller replacement\n")
        self.v.path("02 Projects/Veteran Initiative.md").write_text("---\ntype: project\nstatus: in progress\n---\n# VI\n")
        B.sync(self.v)
        board = self.v.path(B.BOARD_REL).read_text()
        self.assertIn("kanban-plugin: board", board)
        self.assertTrue(board.index("## 📐 Planning") < board.index("[[Chiller replacement]]") < board.index("## 🚀 Active") < board.index("[[Veteran Initiative]]"))
        # drag the card to Active, add a new card in Ideas
        moved = board.replace("- [ ] [[Chiller replacement]]\n", "").replace("## 🚀 Active\n\n", "## 🚀 Active\n\n- [ ] [[Chiller replacement]]\n")
        moved = moved.replace("## 💡 Ideas\n\n", "## 💡 Ideas\n\n- [ ] Solar on the garage\n")
        self.v.path(B.BOARD_REL).write_text(moved)
        r = B.sync(self.v)
        self.assertEqual(r["moved"], ["Chiller replacement → active"])
        self.assertEqual(r["created"], ["Solar on the garage"])
        self.assertIn("status: active", pr.read_text())
        self.assertIn("status: idea", self.v.path("02 Projects/Solar on the garage.md").read_text())
        # edit a note's status: the board follows
        pr.write_text(pr.read_text().replace("status: active", "status: blocked"))
        B.sync(self.v)
        self.assertEqual(B.read_board(self.v)["Chiller replacement"], "blocked")
        s = B.summary(self.v)
        self.assertEqual(s["projects"][0]["status"], "active")
        self.assertEqual(s["counts"]["blocked"], 1)


if __name__ == "__main__":
    unittest.main()
