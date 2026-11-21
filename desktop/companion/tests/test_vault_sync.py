"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import vault as V  # noqa: E402
import vault_sync as S  # noqa: E402

TASKS = [{"id": 1, "title": "Replace AHU-2 belt", "list": "Work Orders", "profile": "PROFESSIONAL", "workorder": True, "due": "2026-11-12", "priority": 2},
         {"id": 2, "title": "Buy milk", "list": "Errands", "profile": "PERSONAL"}]


class Q:
    def __init__(self):
        self.ops = []

    def __call__(self, op, key=None):
        self.ops.append(op)


def age(p: Path, seconds=60):
    t = time.time() - seconds
    os.utime(p, (t, t))


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.v = V.Vault(self.tmp / "BlackLab", self.tmp / "state")
        self.v.ensure()
        self.q = Q()
        self.v.queue_op = self.q

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def tasks_md(self) -> Path:
        return self.v.path(S.TASKS_REL)

    def test_checking_a_box_in_obsidian_completes_it_on_the_phone(self):
        self.v.apply_feed({"tasks": TASKS})
        p = self.tasks_md()
        text = p.read_text()
        self.assertIn("Buy milk ^t2", text)
        p.write_text(text.replace("- [ ] Buy milk ^t2", "- [x] Buy milk ^t2"))
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(self.q.ops, [{"op": "task.complete", "task_id": 2}])
        S.harvest_tasks(self.v, self.q)  # not twice
        self.assertEqual(len(self.q.ops), 1)
        # the phone hasn't applied it yet: its next feed still says open, but Obsidian keeps the check
        self.v.apply_feed({"tasks": TASKS})
        self.assertIn("- [x] Buy milk ^t2", p.read_text())
        # the phone confirms; the override is forgotten
        self.v.apply_feed({"tasks": [TASKS[0], dict(TASKS[1], completed=True)]})
        self.assertEqual(S.overrides(self.v), {})

    def test_new_line_under_a_list_is_added_on_the_phone(self):
        self.v.apply_feed({"tasks": TASKS})
        p = self.tasks_md()
        p.write_text(p.read_text().replace("- [ ] Buy milk ^t2", "- [ ] Buy milk ^t2\n- [ ] Pick up filters 📅 2026-11-25"))
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(self.q.ops, [{"op": "task.add", "title": "Pick up filters", "list": "Errands", "profile": "PERSONAL", "due": "2026-11-25"}])
        self.assertIn("Pick up filters 📅 2026-11-25 ⏳", p.read_text())
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(len(self.q.ops), 1)
        self.v.apply_feed({"tasks": TASKS})  # still waiting: shown with ⏳
        self.assertIn("- [ ] Pick up filters ⏳", p.read_text())
        self.v.apply_feed({"tasks": TASKS + [{"id": 3, "title": "Pick up filters", "list": "Errands", "profile": "PERSONAL"}]})
        self.assertNotIn("⏳", p.read_text())

    def test_work_order_status_edit(self):
        self.v.apply_feed({"tasks": TASKS})
        wo = self.v.path(f"{S.WO_DIR}/Replace AHU-2 belt.md")
        text = wo.read_text()
        self.assertIn('task_id: "1"', text)
        wo.write_text(text.replace("status: open", "status: done"))
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(self.q.ops, [{"op": "task.complete", "task_id": 1}])

    def note(self, rel: str, text: str) -> Path:
        p = self.v.path(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        age(p)
        return p

    def test_trigger_tags_send_once(self):
        S.scan_triggers(self.v, self.q)  # first pass only starts the clock
        p = self.note("50 Notes/Ideas.md", "# Ideas\n\n- [ ] call Trane about RTU-4 tomorrow 9am #task\nOrder salt #remind\n"
                                           "Roof leak over B-wing #workorder\nSafety committee Friday 2pm #event\n- [x] done already #task\n"
                                           "> Claude said #task is a tag\n")
        r = S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual(r["commands"], 4)
        self.assertEqual([o["text"] for o in self.q.ops], ["add task call Trane about RTU-4 tomorrow 9am", "remind me to Order salt",
                                                           "work order: Roof leak over B-wing", "schedule Safety committee Friday 2pm"])
        text = p.read_text()
        self.assertIn("#task/sent", text)
        self.assertIn("#workorder/sent", text)
        age(p)
        S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual(len(self.q.ops), 4)

    def test_managed_blocks_and_ai_folders_never_fire(self):
        self.v.apply_feed({"tasks": TASKS})  # Tasks.md has #workorder inside its managed block
        age(self.tasks_md())
        self.note("70 AI/Claude/x.md", "do this #task\n")
        S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual([o for o in self.q.ops if o["op"] == "command"], [])

    def test_clinical_text_stays_home(self):
        p = self.note("50 Notes/x.md", "check on the patient in room 4 #task\n")
        S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual(self.q.ops, [])
        self.assertIn("#task/private", p.read_text())

    def test_ask_tags_answer_in_place(self):
        asked = []

        def ask(model, prompt, rel):
            asked.append((model, prompt, rel))
            return "Use a 3/4 HP pump.\nCheck the head.", "Claude Sonnet 5.5"
        p = self.note("50 Notes/Pump.md", "# Pump\n\nWhat size condensate pump for a 5-ton coil? #ask/claude\n")
        r = S.scan_triggers(self.v, self.q, ask=ask, since=0)
        self.assertEqual(r["asks"], 1)
        self.assertEqual(asked[0][0], "claude:sonnet")
        self.assertIn("5-ton coil", asked[0][1])
        text = p.read_text()
        self.assertIn("#ask/answered", text)
        self.assertIn("> [!claude] Claude Sonnet 5.5", text)
        self.assertIn("> Check the head.", text)
        age(p)
        S.scan_triggers(self.v, self.q, ask=ask, since=0)
        self.assertEqual(len(asked), 1)
        self.note("50 Notes/Whole.md", "# Trip plan\n\nThree days in Austin, need a packing list.\n\n#ask/gemini/pro\n")
        S.scan_triggers(self.v, self.q, ask=ask, since=0)
        self.assertEqual(asked[1][0], "gemini:pro")
        self.assertIn("Three days in Austin", asked[1][1])

    def test_flashcards_and_inbox_notes(self):
        self.note("40 Learning/OSHA.md", "---\ntags: [flashcards]\ndeck: OSHA 30\n---\n# OSHA\n\nLockout/tagout standard :: 29 CFR 1910.147\n- PEL means :: Permissible exposure limit\n")
        self.note("00 Inbox/Call the elevator vendor.md", "---\nblacklab: task\n---\nabout the annual inspection\n")
        S.scan_triggers(self.v, self.q, since=0)
        cards = [o for o in self.q.ops if o["op"] == "card.add"]
        self.assertEqual([(c["deck"], c["front"]) for c in cards], [("OSHA 30", "Lockout/tagout standard"), ("OSHA 30", "PEL means")])
        cmds = [o for o in self.q.ops if o["op"] == "command"]
        self.assertTrue(cmds[0]["text"].startswith("add task Call the elevator vendor"))
        self.assertIn("blacklab: sent", self.v.path("00 Inbox/Call the elevator vendor.md").read_text())
        for f in ("40 Learning/OSHA.md", "00 Inbox/Call the elevator vendor.md"):
            age(self.v.path(f))
        S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual(len(self.q.ops), 3)

    def test_meeting_action_items_become_tasks(self):
        p = self.note("60 Meetings/Safety committee.md", "---\ntype: meeting\ndate: 2026-11-21\n---\n# Safety committee\n\n## Notes\n\n- [ ] not an action\n\n"
                                                        "## Action items\n\n- [ ] Order eyewash refills 📅 2026-11-28\n- [x] Done one\n- [ ] \n- [ ] Tagged already #task/sent\n")
        S.scan_triggers(self.v, self.q, since=0)
        adds = [o for o in self.q.ops if o["op"] == "task.add"]
        self.assertEqual(len(adds), 1)
        self.assertEqual((adds[0]["title"], adds[0]["due"], adds[0]["tags"]), ("Order eyewash refills", "2026-11-28", "meeting"))
        self.assertIn("Order eyewash refills 📅 2026-11-28 #action/sent", p.read_text())
        age(p)
        S.scan_triggers(self.v, self.q, since=0)
        self.assertEqual(len([o for o in self.q.ops if o["op"] == "task.add"]), 1)

    def test_an_old_tasks_note_without_ids_adds_nothing(self):
        # Tasks.md as companion 1.10 wrote it: no ^t ids. The first harvest after the upgrade must not re-add every task.
        p = self.tasks_md()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("---\ntype: tasks\n---\n%% blacklab:begin %%\n# ✅ Tasks\n\n## Personal · Errands\n\n- [ ] Buy milk\n- [ ] Hang the TV\n%% blacklab:end %%\n")
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(self.q.ops, [])
        self.assertNotIn("⏳", p.read_text())
        # after one render with ids, an existing title typed again is still not "new"
        self.v.apply_feed({"tasks": TASKS})
        p.write_text(p.read_text().replace("- [ ] Buy milk ^t2", "- [ ] Buy milk ^t2\n- [ ] Buy milk"))
        S.harvest_tasks(self.v, self.q)
        self.assertEqual(self.q.ops, [])


if __name__ == "__main__":
    unittest.main()
