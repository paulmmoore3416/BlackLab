"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import datetime as dt
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import feeds as F  # noqa: E402
import phone_vault as P  # noqa: E402
import vault as V  # noqa: E402
import vault_recall as R  # noqa: E402

TODAY = dt.date(2026, 11, 22)


class FeedsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()
        self.urls = []

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def fake(self, routes):
        def get(url, timeout=30, accept="application/json"):
            self.urls.append(url)
            for key, val in routes.items():
                if key in url:
                    if isinstance(val, Exception):
                        raise val
                    return val
            return {}
        return get

    def test_weather_keeps_watched_counties_and_sends_no_location(self):
        alerts = {"features": [
            {"properties": {"event": "Winter Storm Warning", "severity": "Severe", "areaDesc": "St. Louis; St. Louis City", "headline": "Snow 6-10 in",
                            "instruction": "Avoid travel.", "onset": "2026-11-23T06:00:00-06:00", "ends": "2026-11-24T06:00:00-06:00"}},
            {"properties": {"event": "Flood Watch", "severity": "Moderate", "areaDesc": "Jasper; Newton"}}]}
        r = F.weather(self.v, "MO", ["St. Louis"], self.fake({"api.weather.gov": alerts}))
        self.assertEqual([a["event"] for a in r["alerts"]], ["Winter Storm Warning"])
        self.assertTrue(all("point=" not in u and "lat" not in u for u in self.urls))
        note = self.v.path("80 Knowledge/Feeds/🌩️ Weather Alerts.md").read_text()
        self.assertIn("Winter Storm Warning — Severe", note)
        self.assertIn("Avoid travel.", note)
        self.assertNotIn("Flood Watch", note)

    def test_regulatory_filters_topics_and_writes_notes(self):
        cms = {"results": [
            {"title": "Medicare Program; CY 2027 Inpatient Hospital Deductible", "abstract": "payment amounts", "type": "Rule", "document_number": "2026-1",
             "publication_date": "2026-11-19", "html_url": "https://x/1", "agencies": [{"name": "CMS"}]},
            {"title": "Hospital Conditions of Participation: Psychiatric Units", "abstract": "Updates physical environment requirements.", "type": "Proposed Rule",
             "document_number": "2026-2", "publication_date": "2026-11-18", "html_url": "https://x/2", "agencies": [{"name": "CMS"}], "comments_close_on": "2027-01-17"}]}
        osha = {"results": [{"title": "Heat Injury and Illness Prevention", "abstract": "", "type": "Rule", "document_number": "2026-3",
                             "publication_date": "2026-11-10", "html_url": "https://x/3", "agencies": [{"name": "OSHA"}]}]}
        r = F.regulatory(self.v, TODAY, self.fake({"centers-for-medicare": cms, "occupational-safety": osha, "energy-department": RuntimeError("timeout")}))
        self.assertEqual([d["title"] for d in r["documents"]], ["Hospital Conditions of Participation: Psychiatric Units", "Heat Injury and Illness Prevention"])
        self.assertEqual(len(r["errors"]), 1)
        notes = sorted(p.name for p in self.v.path("80 Knowledge/Regulatory").glob("*.md"))
        self.assertEqual(len(notes), 2)
        cop = self.v.path(f"80 Knowledge/Regulatory/{notes[1]}").read_text()
        self.assertIn('up: ["[[◆ Life Safety & Compliance]]"]', cop)
        self.assertIn("comments close 2027-01-17", cop)
        self.assertIn("Proposed rule", cop)
        watch = self.v.path("80 Knowledge/Feeds/📜 Regulatory Watch.md").read_text()
        self.assertIn("Couldn't check", watch)
        u = next(x for x in self.urls if "occupational" in x)
        self.assertIn("conditions%5Bpublication_date%5D%5Bgte%5D=2026-10-08", u)

    def test_security_flags_our_software(self):
        kev = {"catalogVersion": "2026.11.20", "vulnerabilities": [
            {"cveID": "CVE-2026-1", "vendorProject": "Linux", "product": "Kernel", "vulnerabilityName": "UAF", "dateAdded": "2026-11-20", "requiredAction": "Update."},
            {"cveID": "CVE-2026-2", "vendorProject": "Zyxel", "product": "Firewalls", "vulnerabilityName": "RCE", "dateAdded": "2026-11-19"},
            {"cveID": "CVE-2026-3", "vendorProject": "Gitea", "product": "Gitea", "vulnerabilityName": "x", "dateAdded": "2026-11-18"},
            {"cveID": "CVE-2020-9", "vendorProject": "Mozilla", "product": "Firefox", "vulnerabilityName": "old", "dateAdded": "2021-11-03"}]}
        r = F.security(self.v, TODAY, self.fake({"known_exploited": kev}))
        self.assertEqual(r["recent"], 3)
        self.assertEqual([x["cve"] for x in r["ours"]], ["CVE-2026-1"])  # "git" must not match "Gitea"
        self.assertEqual(F.matches_software({"vendorProject": "Google", "product": "Chromium V8"}), "chromium")

    def test_disasters_group_areas(self):
        rows = {"DisasterDeclarationsSummaries": [
            {"disasterNumber": 4800, "declarationTitle": "SEVERE STORMS", "incidentType": "Severe Storm", "declarationDate": "2026-06-01T00:00:00.000Z",
             "declarationType": "DR", "designatedArea": "St. Louis (County)"},
            {"disasterNumber": 4800, "declarationTitle": "SEVERE STORMS", "incidentType": "Severe Storm", "declarationDate": "2026-06-01T00:00:00.000Z",
             "declarationType": "DR", "designatedArea": "Jefferson (County)"}]}
        r = F.disasters(self.v, "MO", TODAY, self.fake({"fema.gov": rows}))
        self.assertEqual(r["declarations"][0]["areas"], ["St. Louis (County)", "Jefferson (County)"])

    def test_run_respects_pace_and_records_errors(self):
        get = self.fake({"api.weather.gov": {"features": []}, "federalregister": {"results": []}, "known_exploited": {"vulnerabilities": []},
                         "fema.gov": RuntimeError("403"), "ecfr.gov": {"content_versions": []}})
        now = 1_800_000_000.0
        r = F.run(self.v, {}, now=now, get=get)
        self.assertEqual(r["weather"], "ok")
        self.assertTrue(r["disasters"].startswith("error"))
        self.assertEqual(F.run(self.v, {}, now=now + 60, get=get), {})          # nothing due a minute later
        self.assertEqual(set(F.run(self.v, {}, now=now + 1900, get=get)), {"weather"})  # weather every 30 min


class ReferenceTest(unittest.TestCase):
    def test_html_text_and_parts(self):
        t = R.html_text("<html><script>x()</script><h2>§ 1910.37</h2><p>Exit routes&nbsp;must be <b>free</b>.</p><nav>menu</nav></html>")
        self.assertIn("## § 1910.37", t)
        self.assertIn("Exit routes must be free.", t)
        self.assertNotIn("x()", t)
        self.assertNotIn("menu", t)
        parts = R.split_parts("\n\n".join(f"para {i} " + "w" * 90 for i in range(50)), size=1000)
        self.assertTrue(all(len(p) <= 1000 for p in parts))
        self.assertEqual("".join(parts).count("para "), 50)
        big = R.split_parts("word " * 1000, size=300)
        self.assertTrue(all(len(p) <= 300 for p in big))

    def test_reference_files_and_ask_kinds(self):
        tmp = Path(tempfile.mkdtemp())
        try:
            v = V.Vault(tmp / "BlackLab", tmp / "state")
            v.ensure()
            f = v.path("95 Library/Life Safety & Compliance/Files/29 CFR 1910.html")
            f.parent.mkdir(parents=True)
            f.write_text("<p>x</p>")
            v.path("95 Library/Life Safety & Compliance/card.md").write_text("card")
            self.assertEqual(list(R.reference_files(v.root)), ["95 Library/Life Safety & Compliance/Files/29 CFR 1910.html"])
            orig = R._req
            R._req = lambda cfg, m, path, body=None, timeout=30: {"results": [
                {"source": "ref:95 Library/Life Safety & Compliance/Files/29 CFR 1910.html#3", "content": "Exit routes…", "score": 0.8},
                {"source": "vault:10 Facility/Assets/AHU-2.md", "content": "Belt changed", "score": 0.6}]}
            try:
                r = R.ask(v, "exit routes", config={})
            finally:
                R._req = orig
            self.assertEqual([(h["kind"], h["title"]) for h in r["results"]], [("reference", "29 CFR 1910"), ("note", "AHU-2")])
        finally:
            shutil.rmtree(tmp)


class PhoneSettingsTest(unittest.TestCase):
    def test_desktop_owns_shared_obsidian_settings(self):
        for rel in (".obsidian/plugins/dataview/main.js", ".obsidian/snippets/blacklab-web.css", ".obsidian/appearance.json",
                    ".obsidian/community-plugins.json", ".obsidian/graph.json"):
            self.assertTrue(P.shared_config(rel), rel)
        for rel in (".obsidian/workspace-mobile.json", ".obsidian/workspace.json", "Home.md"):
            self.assertFalse(P.shared_config(rel), rel)
        self.assertFalse(P.shared_config(".obsidian/appearance.json", mirror_settings=False))
        self.assertTrue(P.shared_config(".obsidian/graph.json", mirror_settings=False))
        rules = P.stignore_rules(include_local=True)
        self.assertNotIn("/85 Atlas", rules)
        self.assertIn("/.obsidian/workspace*", rules)


if __name__ == "__main__":
    unittest.main()
