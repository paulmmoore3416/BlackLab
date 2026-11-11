"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_nodes as VN  # noqa: E402
from test_vault import FEED  # noqa: E402


class NodesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_built_in_nodes_and_a_nexus(self):
        self.assertEqual(18, len(VN.BUILTIN))
        self.assertEqual(len(VN.NODES), len({n["id"] for n in VN.NODES}))
        for n in VN.NODES:
            for s in n["synapses"]:
                self.assertIn(s, VN.BY_ID, f"{n['id']} → {s}")
            self.assertGreater(len(n["primer"]), 600, f"{n['id']} primer should be detailed")
        self.v.apply_feed(FEED)
        for n in VN.NODES:
            self.assertTrue(self.v.path(VN.rel(n)).exists(), n["name"])
        nexus = self.v.path(f"{VN.NEXUS}.md").read_text()
        import re
        self.assertEqual(18, len(set(re.findall(r"\[\[(◆ [^\]|]+)", nexus))))
        canvas = json.loads(self.v.path("90 System/Neural Map.canvas").read_text())
        self.assertEqual(1 + 18 + len(VN.SUBNODES), len(canvas["nodes"]))

    def test_notes_wire_themselves_into_nodes(self):
        self.v.apply_feed(FEED)
        asset = self.v.path("10 Facility/Assets/AHU-2.md").read_text()
        self.assertIn('up: ["[[◆ Facility Operations]]"]', asset)
        fac = self.v.path(VN.rel(VN.BY_ID["facility"])).read_text()
        self.assertIn("[[AHU-2]]", fac)
        self.assertIn("### Operating model", fac, "the primer is seeded")

    def test_node_knowledge_grows_and_survives_rebuilds(self):
        self.v.apply_feed(FEED)
        r = VN.add(self.v, "facility", "AHU-2 takes B-section belts, 2 per unit.", author="Test AI")
        self.assertTrue(r["ok"])
        VN.render(self.v)
        self.assertIn("B-section belts", self.v.path(VN.rel(VN.BY_ID["facility"])).read_text())
        self.assertFalse(VN.add(self.v, "nope", "x")["ok"])
        self.assertFalse(VN.add(self.v, "milieu", "patient in 4 refused meds")["ok"], "clinical text is refused, even for the milieu node")


class SubnodesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_survival_subnodes(self):
        self.assertEqual(17, len(VN.SUBNODES))
        for sn in VN.SUBNODES:
            self.assertIn(sn["parent"], VN.BY_ID)
            for r in sn["related"]:
                self.assertTrue(r in VN.BY_ID or r in VN.SUB_BY_ID, f"{sn['id']} → {r}")
            self.assertGreater(len(sn["primer"]), 500, sn["id"])
        VN.render(self.v)
        water = self.v.path(VN.subrel(VN.SUB_BY_ID["water"])).read_text()
        self.assertIn("rolling boil for 1 minute", water)
        self.assertIn('up: ["[[◆ Home, Family & Health]]"]', water)
        home = self.v.path(VN.rel(VN.BY_ID["home"])).read_text()
        self.assertIn("[[◇ Sourdough & Bread Baking]]", home)
        self.assertTrue(VN.add(self.v, "sourdough", "Rye starters ferment faster than white-flour ones.", "Test AI")["ok"])


if __name__ == "__main__":
    unittest.main()


class RegistryTest(unittest.TestCase):
    """Nodes beyond the built-in ones: no fixed limit, stored in the vault, safe text only."""
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp)
        VN.reload(self.v)  # back to the built-ins for the other tests

    def test_create_many_nodes_and_render_them(self):
        for i in range(25):
            r = VN.create(self.v, f"Topic Area {i}", f"Mission {i}", "✳️", synapses=["facility", "Learning & Credentials"], keywords=[f"kw{i}"], author="Test")
            self.assertTrue(r["ok"], r)
        self.assertEqual(18 + 25, len(VN.NODES))
        self.assertEqual(["facility", "learning"], VN.BY_ID["topic-area-3"]["synapses"])
        self.assertFalse(VN.create(self.v, "Topic Area 3", "dup")["ok"])
        VN.render(self.v)
        self.assertTrue(self.v.path("Nodes/◆ Topic Area 24.md").exists())
        nexus = self.v.path(f"{VN.NEXUS}.md").read_text()
        self.assertIn("## The 43 nodes", nexus)
        canvas = json.loads(self.v.path("90 System/Neural Map.canvas").read_text())
        self.assertEqual(1 + 43 + len(VN.SUBNODES), len(canvas["nodes"]))
        # a fresh process sees the same nodes (the registry lives in the vault)
        VN.NODES[:] = VN.BUILTIN
        VN.reload(self.v)
        self.assertIn("topic-area-24", VN.BY_ID)
        self.assertTrue(VN.add(self.v, "Topic Area 7", "Some knowledge.", author="Test")["ok"])

    def test_refuses_clinical_text_and_honours_the_cap(self):
        self.assertFalse(VN.create(self.v, "Patient tracking", "track patients")["ok"])
        self.v.config = {"vault_max_nodes": 19}
        self.assertTrue(VN.create(self.v, "One more", "ok")["ok"])
        self.assertFalse(VN.create(self.v, "Too many", "no")["ok"])

    def test_up_link_makes_membership_and_local_notes_are_counted_not_named(self):
        self.v.write_note("00 Inbox/Shared idea.md", "---\nup: [\"[[◆ Media Library]]\"]\n---\nA synced note.\n", mode="replace")
        self.v._write("85 Atlas/Videos/Secret Movie.mkv.md", "---\ntype: media\nup: [\"[[◆ Media Library]]\"]\n---\nlocal card\n")
        VN.render(self.v)
        node = self.v.path("Nodes/◆ Media Library.md").read_text()
        self.assertIn("[[Shared idea]]", node)
        self.assertNotIn("Secret Movie", node)
        self.assertIn("## On this computer", node)
        self.assertIn("local: 1", node)
