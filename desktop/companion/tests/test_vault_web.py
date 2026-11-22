"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import datetime as dt
import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_web as W  # noqa: E402

SYSTEM = {"host": "testbox", "cpus": 16, "load1": 4.0, "uptime": 3 * 86400 + 5, "mem_total": 48 * 2**30, "mem_avail": 30 * 2**30,
          "disks": [{"label": "System", "total": 2000 * 2**30, "used": 400 * 2**30}], "spinning": True,
          "gpus": [{"name": "NVIDIA GeForce GTX 1060 6GB", "used": 3 * 2**30, "total": 6 * 2**30, "busy": 20, "temp": 51},
                   {"name": "AMD Radeon 8 GB", "used": 4 * 2**30, "total": 8 * 2**30, "busy": 10, "temp": None}],
          "services": [{"name": "blacklab-desktop", "state": "active", "scope": "user"}, {"name": "ollama", "state": "active", "scope": "system"},
                       {"name": "kiwix library", "state": "inactive", "scope": "port"}],
          "models": [{"name": "qwen3:8b", "size": int(5.2 * 2**30), "loaded": True, "vram": int(6.2 * 2**30)},
                     {"name": "qwen2.5:14b", "size": 9 * 2**30, "loaded": False, "vram": 0}]}


def blocks(text: str) -> str:
    return text[text.index(V.BEGIN):text.index(V.END)]


class VaultWebTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()
        self.today = self.v.now().date()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def ctx(self):
        t = self.today.isoformat()
        return {"weather": {"summary": "Springfield: 41°F and cloudy.", "alerts": ["Freeze warning"]}, "received": 1795300000,
                "events": [{"title": "Day Start", "start": f"{t}T06:30", "end": f"{t}T08:30", "location": "Main campus"},
                           {"title": "Maintenance Time", "start": f"{t}T13:30", "end": f"{t}T15:30", "location": ""},
                           {"title": "Generator load test", "start": f"{(self.today + dt.timedelta(days=2)).isoformat()}T09:00", "location": ""}],
                "assignments": [{"title": "Essay 2 <script>", "due": (self.today + dt.timedelta(days=3)).isoformat(), "status": "OPEN", "course": "ENG 101"}],
                "exams": [], "flashcards_due": 7}

    def test_kit_escapes_and_uses_classes_not_styles(self):
        h = W.card("<b>x</b>", W.bar(42, "ok") + W.ring(101, "9", "left") + W.link("A & B", "<i>"))
        self.assertNotIn("<b>x</b>", h)
        self.assertIn("&lt;i&gt;", h)
        self.assertIn('data-href="A &amp; B"', h)
        self.assertIn("blw-p-40", h)   # 42 → 40
        self.assertIn("blw-p-100", h)  # clamped
        self.assertNotIn("style=", h)
        self.assertEqual(W.pct_class(-5), "blw-p-0")

    def test_pages_have_no_blank_lines_and_link_each_other(self):
        html = W.page("today", W.hero("Sunday", "Hi"), [W.tile("x", 1)], [W.card("a", "b\n\nc")])
        self.assertNotRegex(html, r"\n\s*\n")
        for pid, (name, *_rest) in W.PAGES.items():
            self.assertIn(f'data-href="{W.DASH_DIR}/{name}"', html)
        self.assertIn('class="internal-link blw-nav-link is-active"', html)

    def test_tasks_parse_and_bucket(self):
        t = self.today
        md = "\n".join(["## Professional · Tasks", f"- [ ] Replace AHU-2 belt ⏫ 📅 {(t - dt.timedelta(days=2)).isoformat()} ^t1",
                        f"- [ ] Submit timesheet 📅 {t.isoformat()} ^t2", "- [x] Done thing ^t3",
                        f"- [ ] Order filters 📅 {(t + dt.timedelta(days=3)).isoformat()} ^t4", "## Personal · Home", "- [ ] Mow the yard ^t5"])
        tasks = W.parse_tasks(md)
        self.assertEqual([x["title"] for x in tasks], ["Replace AHU-2 belt", "Submit timesheet", "Order filters", "Mow the yard"])
        self.assertTrue(tasks[0]["high"])
        self.assertEqual(tasks[3]["list"], "Personal · Home")
        b = W.bucket_tasks(tasks, t)
        self.assertEqual([x["title"] for x in b["now"]], ["Replace AHU-2 belt", "Submit timesheet"])
        self.assertEqual(b["overdue"], 1)
        self.assertEqual([x["title"] for x in b["next"]], ["Order filters"])
        self.assertEqual([x["title"] for x in b["later"]], ["Mow the yard"])

    def test_clinical_titles_never_reach_a_dashboard(self):
        md = "## Work\n- [ ] Patient J.D. behavioral health assessment follow-up 📅 2026-01-01\n- [ ] Change filters"
        titles = [x["title"] for x in W.parse_tasks(md)]
        self.assertEqual(titles, ["Change filters"])
        ev = W.events_on({"events": [{"title": "Patient intake — behavioral health", "start": f"{self.today}T09:00"}]}, self.today)
        self.assertEqual(ev, [])

    def test_today_page(self):
        self.v.write_managed("30 Work/Tasks.md", {"type": "tasks"}, f"## Work\n\n- [ ] Call elevator vendor ⏫ 📅 {self.today.isoformat()} ^t9\n")
        d = self.v.path("60 Meetings/Briefs")
        d.mkdir(parents=True, exist_ok=True)
        (d / f"{self.today.isoformat()} Morning brief.md").write_text("# Brief\n\n## Top 3 today\n1. **Freeze tonight** – drain the hose bibs.\n2. Two PMs overdue\n\n## Schedule\n- x\n")
        health = {"status": "warn", "title": "System check · Sun", "summary": "1 thing to look at", "issues": [{"area": "Security", "status": "warn", "title": "Funnel is on"}]}
        r = W.apply(self.v, ctx=self.ctx(), health=health, system=SYSTEM)
        self.assertTrue(all(v is True for v in r.values()), r)
        text = self.v.path(W.rel("today")).read_text()
        self.assertIn("cssclasses: [bl-web-page", text)
        self.assertIn('up: "[[◆ Time, Goals & Planning]]"', text)
        body = blocks(text)
        for needle in ("Springfield: 41°F", "Freeze warning", "Call elevator vendor", "Freeze tonight – drain the hose bibs.", "Day Start", "Main campus",
                       "System · WARN", "Funnel is on", "flashcards due"):
            self.assertIn(W.esc(needle), body, needle)
        self.assertIn('data-bl-widget="capture"', body)
        self.assertIn('data-bl-widget="ask"', body)
        self.assertIn(f'data-href="{self.v.day_rel(self.today).removesuffix(".md")}"', body)
        self.assertNotRegex(body, r"\n\s*\n")
        self.assertIn("## My notes", text)
        # the user's own notes below the block survive a refresh
        self.v.path(W.rel("today")).write_text(text + "my own line\n")
        W.apply(self.v, ctx=self.ctx(), health=health, system=SYSTEM, pages=("today",))
        self.assertIn("my own line", self.v.path(W.rel("today")).read_text())

    def test_unchanged_data_writes_nothing(self):
        W.apply(self.v, ctx=self.ctx(), health={}, system=SYSTEM)
        again = W.apply(self.v, ctx=self.ctx(), health={}, system=SYSTEM)
        self.assertEqual(set(again.values()), {False})

    def test_facility_countdowns(self):
        a = self.v.path("10 Facility/Assets")
        a.mkdir(parents=True, exist_ok=True)
        over = (self.today - dt.timedelta(days=5)).isoformat()
        soon = (self.today + dt.timedelta(days=10)).isoformat()
        (a / "Emergency generator.md").write_text(f"| Weekly inspection | weekly | x | y | {over} | 🔴 overdue |\n| Annual load bank | yearly | x | y | {soon} | 🟡 soon |\n")
        items = W.due_items(self.v)
        self.assertEqual([(i["what"], i["days"], i["over"]) for i in items], [("Weekly inspection", -5, True), ("Annual load bank", 10, False)])
        body = W.render_facility(self.v, self.ctx())
        self.assertIn("5 days overdue", body)
        self.assertIn("in 10 days", body)
        self.assertIn("Generator load test", body)  # this week's maintenance blocks come from the calendar

    def test_credential_time_left(self):
        t = dt.date(2026, 11, 22)
        self.assertEqual(W.credential_view({"never": True}, t)["tone"], "ok")
        self.assertEqual(W.credential_view({"renew": dt.date(2026, 1, 1), "issued": dt.date(2025, 1, 1)}, t)["tone"], "bad")
        soon = W.credential_view({"renew": t + dt.timedelta(days=30), "issued": t - dt.timedelta(days=335)}, t)
        self.assertEqual((soon["tone"], soon["value"]), ("warn", "30d"))
        self.assertAlmostEqual(soon["p"], 30 * 100 / 365, places=3)
        self.assertEqual(W.credential_view({}, t)["value"], "?")

    def test_learning_page_escapes_phone_text(self):
        body = W.render_learning(self.v, self.ctx())
        self.assertIn("Essay 2 &lt;script&gt;", body)
        self.assertNotIn("<script>", body)
        self.assertIn("blw-heatmap", body)
        self.assertEqual(body.count('class="blw-heat '), len(W.study_days(self.v, self.today)))

    def test_homelab_snapshot_is_coarse_and_live_is_exact(self):
        snap = W.homelab_live(SYSTEM, coarse=True)
        live = W.homelab_live(SYSTEM)
        self.assertIn("snapshot · live on the computer", snap)
        self.assertIn("25%", live)          # load 4 / 16 threads
        self.assertIn("<b>40%</b><small>Memory", snap)  # 37.5 % rounded to tens on disk
        self.assertIn("<b>30%</b><small>CPU", snap)     # 25 % → 30 (half rounds up)
        self.assertIn("2/3 services up", live)
        self.assertIn("51°C", live)
        self.assertNotIn("51°C", snap)
        page = W.render_homelab(self.v, SYSTEM, {"status": "ok"})
        self.assertIn('data-bl-live="homelab"', page)
        for a in W.ACTIONS:
            self.assertIn(f'data-bl-action="{a}"', page)
        self.assertIn("splits across GPUs", page)
        self.assertIn("HDD", page)

    def test_collect_system_with_fakes(self):
        def run(cmd, timeout=5):
            if cmd[:2] == ["systemctl", "--user"]:
                return "active\nfailed"
            if cmd[0] == "systemctl":
                return "active"
            if cmd[0] == "nvidia-smi":
                return "NVIDIA GeForce GTX 1060 6GB, 3000, 6144, 12, 49"
            if cmd[0] == "lsblk":
                return "loop0 1 \nsda   1 Some HDD"
            return ""
        s = W.collect_system({"keep_user_units": ["blacklab-desktop.service"], "keep_system_services": ["ollama.service"]}, run=run,
                             port=lambda p: p == 8770, get_json=lambda url: {"models": [{"name": "qwen3:8b", "size": 5, "size_vram": 5}]})
        states = {x["name"]: x["state"] for x in s["services"]}
        self.assertEqual(states["blacklab-desktop"], "active")
        self.assertEqual(states["familycraft"], "failed")
        self.assertEqual(states["ollama"], "active")
        self.assertEqual(states["companion UI"], "active")
        self.assertEqual(states["kiwix library"], "inactive")
        self.assertTrue(s["spinning"])
        self.assertEqual(s["gpus"][0]["busy"], 12)
        self.assertTrue(s["models"][0]["loaded"])

    def test_css_is_written_and_enabled_once(self):
        W.install_css(self.v)
        css = self.v.path(".obsidian/snippets/blacklab-web.css").read_text()
        self.assertIn(".bl-web .blw-p-55 { --p: 55; }", css)
        self.assertIn("@media (max-width: 900px)", css)
        app = json.loads(self.v.path(".obsidian/appearance.json").read_text())
        self.assertIn("blacklab-web", app["enabledCssSnippets"])
        app["enabledCssSnippets"] = []  # Paul turned it off: it stays off
        self.v.path(".obsidian/appearance.json").write_text(json.dumps(app))
        W.install_css(self.v)
        self.assertEqual(json.loads(self.v.path(".obsidian/appearance.json").read_text())["enabledCssSnippets"], [])

    def test_every_class_used_is_styled(self):
        html = "".join([W.render_homelab(self.v, SYSTEM, {}), W.render_learning(self.v, self.ctx()), W.render_facility(self.v, self.ctx()),
                        W.render_today(self.v, self.ctx(), {})])
        used = set(re.findall(r"\b(blw-[a-z0-9-]+)", " ".join(re.findall(r'class="([^"]+)"', html))))
        css = W.css()
        missing = sorted(c for c in used if f".{c}" not in css and not c.startswith("blw-p-"))
        self.assertEqual(missing, [])


if __name__ == "__main__":
    unittest.main()
