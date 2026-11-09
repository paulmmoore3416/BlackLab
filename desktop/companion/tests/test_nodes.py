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

    def test_thirteen_nodes_and_a_nexus(self):
        self.assertEqual(13, len(VN.NODES))
        self.assertEqual(13, len({n["id"] for n in VN.NODES}))
        for n in VN.NODES:
            for s in n["synapses"]:
                self.assertIn(s, VN.BY_ID, f"{n['id']} → {s}")
            self.assertGreater(len(n["primer"]), 600, f"{n['id']} primer should be detailed")
        self.v.apply_feed(FEED)
        for n in VN.NODES:
            self.assertTrue(self.v.path(VN.rel(n)).exists(), n["name"])
        nexus = self.v.path(f"{VN.NEXUS}.md").read_text()
        import re
        self.assertEqual(13, len(set(re.findall(r"\[\[(◆ [^\]|]+)", nexus))))
        canvas = json.loads(self.v.path("90 System/Neural Map.canvas").read_text())
        self.assertEqual(1 + 13 + len(VN.SUBNODES), len(canvas["nodes"]))

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
