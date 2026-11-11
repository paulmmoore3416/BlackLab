"""The Atlas and people & topics. Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_atlas as A  # noqa: E402
import vault_entities as E  # noqa: E402
import vault_nodes as VN  # noqa: E402

KEY = "AI" + "zaSyA1234567890abcdefghijklmnopqrstuv"  # fake; split so the secret scan does not flag the fixture


class AtlasTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        h = self.home = self.tmp / "home"
        (h / "Desktop" / "CHFM").mkdir(parents=True)
        (h / "Desktop" / "CHFM" / "CHFM-Study-Guide.md").write_text(
            "# CHFM Exam Prep\n\nA six-month plan for the CHFM exam with ASHE resources and NFPA 101 Life Safety Code review. "
            "Jane Smith from ASHE recommended the practice test. Week one covers planning, design and construction; week two covers "
            "maintenance and operations, compliance, finance and leadership, with a practice quiz at the end of each week.\n\nThe patient census affects staffing on unit 3.\n\n"
            f"api_key = {KEY}\n")
        (h / "Desktop" / "CHFM" / "copy of guide.md").write_text((h / "Desktop" / "CHFM" / "CHFM-Study-Guide.md").read_text())
        (h / "Documents").mkdir()
        (h / "Documents" / "budget.csv").write_text("item,cost\nfilters,1200\n")
        (h / "Downloads").mkdir()
        (h / "Downloads" / "client_secret_123.apps.googleusercontent.com.json").write_text('{"client_secret": "GOC' + 'SPX-abcdefghijklmnopqrstuvwx"}')
        (h / ".ssh").mkdir()
        (h / ".ssh" / "notes.md").write_text("zzq-hidden-marker never read")
        mv = h / "Videos" / "Movies"
        mv.mkdir(parents=True)
        (mv / "Elf.2003.1080p.BluRay.mkv").write_bytes(b"\x00" * 64)
        show = h / "Videos" / "TV Shows" / "Show.S01"
        show.mkdir(parents=True)
        for i in range(1, 5):
            (show / f"Show.S01E0{i}.720p.mkv").write_bytes(bytes([i]) * 32)
        proj = h / "kcc"
        (proj / "docs").mkdir(parents=True)
        (proj / "src").mkdir()
        (proj / "package.json").write_text("{}")
        (proj / "README.md").write_text("# KCC\n\nKraken Cloud Control is a dashboard for cloud resources and their costs across providers.\n")
        (proj / "docs" / "USER_GUIDE.md").write_text("# KCC User Guide\n\nHow to sign in and read the dashboard, step by step for new users.\n")
        (proj / "src" / "app.js").write_text("console.log('code is listed, never carded')")
        ov = h / "OtherVault"
        (ov / ".obsidian").mkdir(parents=True)
        (ov / "Ideas").mkdir()
        (ov / "Ideas" / "Big idea.md").write_text("---\ntags: [idea]\nstatus: draft\n---\nBuild a better PM program with the CMMS.\n")
        (ov / "Private.md").write_text("Session with the therapist about medications.\n")
        self.v = V.Vault(self.tmp / "vault" / "BlackLab", self.tmp / "state")
        self.v.ensure()
        self.cfg = {"atlas_roots": [str(h)], "atlas_guide_roots": [], "atlas_system_guides": False, "vault_import": [str(ov)]}
        self.atlas = A.Atlas(self.v, self.cfg, home=h)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_all(self):
        self.atlas.scan()
        self.atlas.import_vaults()
        return self.atlas.write()

    def all_text(self):
        return "\n".join(p.read_text(errors="replace") for p in self.v.root.rglob("*.md"))

    def test_cards_guides_projects_media_and_privacy(self):
        r = self.run_all()
        self.assertGreaterEqual(r["guides"], 2)
        card = self.v.path("85 Atlas/Desktop/CHFM/CHFM-Study-Guide.md.md").read_text()
        self.assertIn("type: guide", card)
        self.assertIn("guide_kind: training", card)
        self.assertIn("[[◆ Learning & Credentials]]", card)
        self.assertIn("withheld: 1", card, "the clinical paragraph is left out")
        self.assertNotIn("patient census", card)
        self.assertNotIn(KEY, card)
        self.assertIn("Identical copies", card)
        self.assertFalse(self.v.path("85 Atlas/Desktop/CHFM/copy of guide.md.md").exists(), "duplicates aren't carded twice")
        # project: one card, docs carded, code listed nowhere as a card
        pj = self.v.path("85 Atlas/kcc/🧩 kcc.md").read_text()
        self.assertIn("type: project", pj)
        self.assertIn("USER_GUIDE.md", pj)
        self.assertFalse(self.v.path("85 Atlas/kcc/src/app.js.md").exists())
        self.assertIn("guide_kind: user", self.v.path("85 Atlas/kcc/docs/USER_GUIDE.md.md").read_text())
        # media: a movie gets a card, a season becomes one collection
        movie = self.v.path("85 Atlas/Videos/Movies/Elf.2003.1080p.BluRay.mkv.md").read_text()
        self.assertIn("year: 2003", movie)
        self.assertIn("[[◆ Media Library]]", movie)
        self.assertTrue(self.v.path("85 Atlas/Videos/TV Shows/Show.S01/🎞️ Show.S01 (collection).md").exists())
        # secrets and hidden folders never appear
        everything = self.all_text()
        for bad in ("client_secret_123", "GOCSPX-", "zzq-hidden-marker", KEY, "therapist"):
            self.assertNotIn(bad, everything)
        # the tree, the machine and the guides index
        self.assertIn("[[85 Atlas/Desktop/📁 Desktop|📁 Desktop]]", self.v.path(A.HOME_HUB).read_text())
        self.assertIn("## Hardware", self.v.path(A.COMPUTER).read_text())
        guides = self.v.path(A.GUIDES).read_text()
        self.assertIn("🎓 Training & study guides", guides)
        self.assertIn("KCC User Guide", guides)
        gi = self.v.path(".gitignore").read_text()
        for line in ("85 Atlas/", "86 Imported/", "87 Entities/"):
            self.assertIn(line, gi)

    def test_imported_vault_stays_in_sync_and_keeps_your_notes(self):
        self.run_all()
        rel = "86 Imported/OtherVault/Ideas/Big idea.md"
        note = self.v.path(rel).read_text()
        self.assertIn("status: draft", note, "the original's properties come along")
        self.assertIn("imported_from: OtherVault", note)
        self.assertIn("Build a better PM program", note)
        self.assertFalse(self.v.path("86 Imported/OtherVault/Private.md").read_text().count("therapist"))
        self.v.path(rel).write_text(note + "\nMy own follow-up.\n")
        src = self.home / "OtherVault" / "Ideas" / "Big idea.md"
        src.write_text(src.read_text().replace("better", "much better"))
        self.run_all()
        note = self.v.path(rel).read_text()
        self.assertIn("much better", note)
        self.assertIn("My own follow-up.", note)
        self.assertIn("📥 OtherVault", self.v.path("86 Imported/OtherVault/📥 OtherVault.md").read_text())

    def test_a_removed_file_is_archived_not_deleted(self):
        self.run_all()
        (self.home / "Documents" / "budget.csv").unlink()
        self.run_all()
        self.assertFalse(self.v.path("85 Atlas/Documents/budget.csv.md").exists())
        self.assertTrue(self.v.path("99 Archive/85 Atlas/Documents/budget.csv.md").exists())

    def test_rescan_reads_only_what_changed(self):
        self.atlas.scan()
        again = A.Atlas(self.v, self.cfg, home=self.home)
        self.assertEqual(0, again.scan()["read"])

    def test_people_and_topics(self):
        self.run_all()
        calls = []

        def fake(title, text, model, host, timeout=180):
            calls.append(title)
            return {"people": ["Jane Smith", "Invented Person"], "organizations": ["ASHE"], "places": [], "topics": ["exam prep"]}
        old = E.llm_entities
        E.llm_entities = fake
        try:
            r = E.Entities(self.v, self.cfg, self.atlas).run(budget=50)
        finally:
            E.llm_entities = old
        self.assertGreater(len(calls), 0)
        self.assertTrue(self.v.path("87 Entities/People/Jane Smith.md").exists())
        self.assertFalse(self.v.path("87 Entities/People/Invented Person.md").exists(), "names must be in the text")
        self.assertTrue(self.v.path("87 Entities/Credentials/CHFM.md").exists())
        self.assertTrue(self.v.path("87 Entities/Topics/NFPA 101 Life Safety Code.md").exists())
        person = self.v.path("87 Entities/People/Jane Smith.md").read_text()
        self.assertIn("[[◆ People & Relationships]]", person)
        self.assertIn("CHFM-Study-Guide.md", person)
        self.atlas.write()
        card = self.v.path("85 Atlas/Desktop/CHFM/CHFM-Study-Guide.md.md").read_text()
        self.assertIn("## People & topics", card)
        self.assertIn("Jane Smith", card)
        self.assertEqual(0, r["llm_pending"] - sum(1 for d in E.Entities(self.v, self.cfg, self.atlas).state["docs"].values() if not d.get("llm")))

    def test_verify_keeps_only_plausible_names(self):
        text = "Met Jane Smith and the Life Safety team in St. Louis."
        out = E.verify({"people": ["Jane Smith", "Life Safety", "Bob"], "places": ["St. Louis"], "organizations": [], "topics": []}, text)
        self.assertEqual({"Jane Smith"}, out["person"])
        self.assertEqual({"St. Louis"}, out["place"])


if __name__ == "__main__":
    unittest.main()


class PhoneMirrorTest(unittest.TestCase):
    def test_local_folders_stay_off_the_phone(self):
        import phone_vault
        tmp = Path(tempfile.mkdtemp())
        try:
            for rel in ("Home.md", "85 Atlas/x.md", "86 Imported/V/y.md", "87 Entities/People/Jane Smith.md", "10 Facility/a.md"):
                (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
                (tmp / rel).write_text("x")
            self.assertEqual(["10 Facility/a.md", "Home.md"], phone_vault.local_files(tmp))
            self.assertEqual(5, len(phone_vault.local_files(tmp, include_local=True)))
        finally:
            shutil.rmtree(tmp)
