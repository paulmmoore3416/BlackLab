"""Run:  python3 -m unittest discover -s desktop/companion/tests  (no network, no Ollama: fakes for both)."""
import json
import shutil
import sys
import tempfile
import unittest
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_graph as G  # noqa: E402
import vault_nodes as VN  # noqa: E402


def no_network(url):
    raise OSError("offline in tests")


class GraphTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def note(self, rel, text):
        p = self.v.path(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def test_graph_profile_forces_colors_and_views(self):
        g = G.graph_config({"linkStrength": 0, "showTags": True, "scale": 0.3, "mine": 1})
        self.assertGreater(g["linkStrength"], 0.5, "links must pull or the nodes never cluster")
        self.assertFalse(g["showTags"])
        self.assertEqual(1, g["mine"], "keys we don't own are kept")
        queries = [c["query"] for c in g["colorGroups"]]
        self.assertTrue(queries[0].startswith('file:"◉ Nexus"'), "most specific first")
        for n in VN.NODES:
            self.assertTrue(any(VN.title(n) in q for q in queries), n["name"])
        views = G.graph_views()
        self.assertEqual("group", views["type"])
        self.assertEqual(len(VN.NODES) + 5, len(views["items"]))
        self.assertTrue(all(i["type"] == "graph" and i["options"]["search"] for i in views["items"]))

    def test_bookmarks_keep_the_users_own(self):
        self.v._write(".obsidian/bookmarks.json", json.dumps({"items": [{"type": "file", "path": "Mine.md"}]}))
        G.apply_bookmarks(self.v)
        G.apply_bookmarks(self.v)
        items = json.loads(self.v.path(".obsidian/bookmarks.json").read_text())["items"]
        self.assertEqual("Mine.md", items[0]["path"])
        self.assertEqual(1, sum(1 for i in items if i.get("title") == "🕸️ Graph views"), "applied twice, listed once")

    def test_plugins_are_verified_before_anything_is_written(self):
        with self.assertRaises(ValueError):
            G.fetch_plugin("breadcrumbs", lambda url: b"tampered")
        r = G.apply(self.v, fetch=lambda url: b"tampered")
        self.assertIn("checksum", r["plugins_error"])
        self.assertEqual([], json.loads(self.v.path(".obsidian/community-plugins.json").read_text()), "nothing enabled that isn't installed")
        self.assertFalse(self.v.path(".obsidian/plugins/breadcrumbs/main.js").exists())
        for pid, (repo, tag, files) in G.PLUGINS.items():
            self.assertRegex(repo, r"^[\w.-]+/[\w.-]+$")
            self.assertEqual({"main.js", "manifest.json", "styles.css"}, set(files))
            self.assertTrue(all(len(h) == 64 for h in files.values()))

    def test_settings_merge_deeply(self):
        merged = G.deep_merge({"a": {"x": 1, "y": 2}, "keep": True}, {"a": {"y": 3}})
        self.assertEqual({"a": {"x": 1, "y": 3}, "keep": True}, merged)
        bc = G.PLUGIN_SETTINGS["breadcrumbs"]
        fields = {f["label"] for f in bc["edge_fields"]}
        for group in bc["edge_field_groups"]:
            self.assertTrue(set(group["fields"]) <= fields)
        for rel in bc["implied_relations"]["transitive"]:
            self.assertIn(rel["close_field"], fields)
            self.assertTrue(all(c["field"] in fields for c in rel["chain"]))
        types = {c["type"] for c in G.PLUGIN_SETTINGS["extended-graph"]["interactiveSettings"]["link"]["colors"]}
        self.assertTrue({"up", "synapses", "related"} <= types)

    def test_health_finds_orphans_unresolved_and_hubs(self):
        self.note("50 Notes/A.md", "---\nup: [\"[[◆ Facility Operations]]\"]\n---\nSee [[B]] and [[Missing thing]].")
        self.note("50 Notes/B.md", "Links back to [[A]].")
        self.note("50 Notes/Lonely.md", "A chiller with a bad compressor and a cooling tower.")
        h = G.graph_health(G.scan(self.v))
        self.assertIn("50 Notes/Lonely.md", h["orphans"])
        self.assertNotIn("50 Notes/A.md", h["orphans"])
        self.assertIn(("Missing thing", 1), h["unresolved"])
        notes = G.scan(self.v)
        self.assertEqual(VN.title(VN.BY_ID["facility"]), G.suggest_home(notes["50 Notes/Lonely.md"]))
        G.render_health(self.v, notes)
        self.assertIn("Lonely", self.v.path("90 System/Graph Health.md").read_text())

    def test_related_picks_are_mutual_thresholded_and_skip_linked_pairs(self):
        names = ["chiller", "chiller2", "budget", "tower"]
        vecs = [[1, 0, 0], [0.97, 0.05, 0], [0, 1, 0], [0.8, 0.0, 0.6]]
        picks = G.pick_related(names, vecs, {}, k=2, floor=0.6, relative=0.7)
        self.assertEqual("chiller2", picks["chiller"][0][0])
        self.assertTrue(any(b == "chiller" for b, _ in picks["chiller2"]), "mutual")
        self.assertEqual([], picks["budget"], "nothing is close to the budget note")
        linked = G.pick_related(names, vecs, {"chiller": {"chiller2"}}, k=2, floor=0.6, relative=0.7)
        self.assertNotIn("chiller2", [b for b, _ in linked["chiller"]], "already linked: no duplicate edge")

    def test_adaptive_floor(self):
        self.assertEqual(G.FLOOR_RANGE[0], G.adaptive_floor(G.similarity_matrix([[1, 0], [0, 1]])), "too few pairs: the minimum bar")
        vecs = [[1, i / 40] for i in range(12)]  # all very alike: the bar rises, capped
        self.assertLessEqual(G.adaptive_floor(G.similarity_matrix(vecs)), G.FLOOR_RANGE[1])
        self.assertGreater(G.adaptive_floor(G.similarity_matrix(vecs)), 0.8)

    def test_related_links_are_sticky_near_the_bar(self):
        names, vecs = ["a", "b"], [[1, 0], [0.62, 0.785]]  # cosine ≈ 0.62
        self.assertEqual([], G.pick_related(names, vecs, {}, floor=0.64)["a"])
        self.assertEqual("b", G.pick_related(names, vecs, {}, floor=0.64, previous={"a": ["b"]})["a"][0][0])

    def test_update_related_writes_only_the_property_and_keeps_yours(self):
        body = "x" * 200
        self.note("50 Notes/Chiller A.md", f"---\ntags: [hvac]\nrelated: [\"[[My pick]]\"]\n---\nChiller notes {body}")
        self.note("50 Notes/Chiller B.md", f"Chiller B notes {body}")
        self.note("50 Notes/Budget.md", f"Budget {body}")
        fake = {"Chiller A": [1, 0, 0, 0], "Chiller B": [0.98, 0.1, 0, 0], "Budget": [0, 0, 1, 0]}
        embed = lambda texts: [fake.get(t.split("\n")[0], [0, 0, 0, 1]) for t in texts]  # noqa: E731  (README etc. elsewhere)
        state = {}
        r = G.update_related(self.v, G.scan(self.v), embed, state)
        a = self.v.path("50 Notes/Chiller A.md").read_text()
        self.assertIn("[[My pick]]", a, "your own related link stays")
        self.assertIn("[[Chiller B]]", a)
        self.assertIn("tags: [hvac]", a)
        self.assertTrue(a.rstrip().endswith(body))
        self.assertIn("related:", self.v.path("50 Notes/Chiller B.md").read_text())
        self.assertNotIn("related:", self.v.path("50 Notes/Budget.md").read_text())
        self.assertGreaterEqual(r["pairs"], 1)
        again = G.update_related(self.v, G.scan(self.v), embed, state)
        self.assertEqual(0, again["changed"])
        self.assertEqual(0, again["embedded"], "unchanged notes aren't embedded again")

    def test_same_titles_are_linked_by_path_and_never_paired(self):
        body = "y" * 200
        self.note("50 Notes/A/Untitled page.md", f"Pump notes {body}")
        self.note("50 Notes/B/Untitled page.md", f"Pump notes {body}")
        self.note("50 Notes/Pump log.md", f"Pump log {body}")
        embed = lambda texts: [[1, 0, 0, 0] if "Pump" in t else [0, 0, 0, 1] for t in texts]  # noqa: E731
        G.update_related(self.v, G.scan(self.v), embed, {})
        a = self.v.path("50 Notes/A/Untitled page.md").read_text()
        log = self.v.path("50 Notes/Pump log.md").read_text()
        self.assertNotIn("[[Untitled page]]", a + log, "an ambiguous title is never used as a link")
        self.assertNotIn("50 Notes/B/Untitled page", a, "copies of one template aren't paired")
        self.assertIn("[[50 Notes/A/Untitled page|Untitled page]]", log)

    def test_future_days_and_folders_are_not_unresolved(self):
        self.note("50 Notes/Plan.md", "See [[2099-01-01]], [[10 Facility|Facility]], [[{{date}}]] and [[Ghost]].")
        h = G.graph_health(G.scan(self.v), self.v.root)
        self.assertEqual([("Ghost", 1)], [u for u in h["unresolved"] if u[0] in ("Ghost", "2099-01-01", "10 Facility", "{{date}}")])
        self.assertGreaterEqual(h["future_days"], 1)

    def test_uris_and_finding_notes(self):
        u = G.local_graph_uri("BlackLab", "10 Facility/Assets/RTU-4.md")
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(u).query)
        self.assertTrue(u.startswith("obsidian://adv-uri?"))
        self.assertEqual(["10 Facility/Assets/RTU-4.md"], q["filepath"])
        self.assertEqual(["graph:open-local"], q["commandid"])
        self.note("10 Facility/Assets/RTU-4.md", "---\naliases: [\"Rooftop 4\"]\n---\nRTU")
        self.assertEqual("10 Facility/Assets/RTU-4.md", G.find_note(self.v, "rtu-4"))
        self.assertEqual("10 Facility/Assets/RTU-4.md", G.find_note(self.v, "Rooftop 4"))
        self.assertEqual(VN.rel(VN.BY_ID["facility"]), G.find_note(self.v, "the facility node"))

    def test_apply_is_idempotent_offline(self):
        r1 = G.apply(self.v, fetch=no_network)
        self.assertIn("plugins_error", r1)
        g = json.loads(self.v.path(".obsidian/graph.json").read_text())
        self.assertGreater(g["linkStrength"], 0)
        self.assertTrue(self.v.path("90 System/Graph Health.md").exists())
        self.assertIn(".obsidian/plugins/*/main.js", self.v.path(".gitignore").read_text())
        real = {}  # the pinned files, served from a fake "GitHub" that only knows the right bytes
        for pid, (repo, tag, files) in G.PLUGINS.items():
            for name in files:
                real[f"https://github.com/{repo}/releases/download/{tag}/{name}"] = None
        self.assertEqual(9, len(real))


if __name__ == "__main__":
    unittest.main()
