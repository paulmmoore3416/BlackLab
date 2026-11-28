"""The desktop AI's tool loop with the advisor and the fork layer (companion 1.15), against a scripted fake Ollama.

Run:  python3 -m unittest discover -s desktop/companion/tests"""
import importlib.util
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
_spec = importlib.util.spec_from_file_location("blacklab_desktop_under_test", HERE / "blacklab_desktop.py")
D = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(D)
import advisor  # noqa: E402


def call(name, **args):
    return {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": name, "arguments": args}}]}


def say(text):
    return {"role": "assistant", "content": text}


class FakeOllama:
    """Worker turns come from `script` in order; advisor turns (system prompt = advisor.SYSTEM) from `advice`."""

    def __init__(self, script, advice=()):
        self.script, self.advice = list(script), list(advice)
        self.worker_msgs, self.advisor_prompts = [], []

    def __call__(self, url, body=None, *a, **kw):
        msgs = body["messages"]
        if msgs[0]["content"] == advisor.SYSTEM:
            self.advisor_prompts.append(msgs[-1]["content"])
            return {"message": {"content": self.advice.pop(0) if self.advice else "OK"}}
        self.worker_msgs.append([dict(m) for m in msgs])
        return {"message": self.script.pop(0)}


class ToolLoopTest(unittest.TestCase):
    def setUp(self):
        self.saved = {k: getattr(D, k) for k in ("http_json", "run_tool", "_skill_seed", "_reference_seed")}
        self.cfg = dict(D.CONFIG)
        D.CONFIG.clear()
        D.CONFIG.update({"autonomous_mode": True, "advisor": "auto"})
        D._skill_seed = lambda text: ()
        D._reference_seed = lambda text, seed=(): ()
        self.ran = []
        D.run_tool = lambda name, args, phone: self.ran.append((name, args)) or self.results.get(name, "ok")
        self.results = {}
        advisor.LOG.__init__()

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(D, k, v)
        D.CONFIG.clear()
        D.CONFIG.update(self.cfg)

    def chat(self, fake, text, **kw):
        D.http_json = fake
        return D.ollama_chat(text, "qwen3:14b", None, use_memory=False, **kw)

    def test_same_error_twice_asks_the_advisor(self):
        self.results = {"read_file": "read_file failed: No such file /x"}
        fake = FakeOllama([call("read_file", path="/a/1.txt"), call("read_file", path="/a/2.txt"), say("It isn't there.")],
                          advice=["- Use find_files first"])
        text, source = self.chat(fake, "read my roof report")
        self.assertEqual(text, "It isn't there.")
        self.assertEqual(len(fake.advisor_prompts), 1)
        self.assertIn("same failure twice", fake.advisor_prompts[0])
        self.assertIn("Use find_files first", fake.worker_msgs[-1][-1]["content"])  # handed to the worker
        self.assertIn("advisor ×1", source)

    def test_done_review_gets_one_more_round(self):
        fake = FakeOllama([call("vault_write", text="Chiller 2 PM done"), say("Saved."), say("Saved to the vault under Facility.")],
                          advice=["- Say where it was saved"])
        text, _ = self.chat(fake, "save to my vault: chiller 2 PM done")
        self.assertEqual(text, "Saved to the vault under Facility.")
        self.assertIn("DRAFT ANSWER:\nSaved.", fake.advisor_prompts[0])
        self.assertEqual(len(fake.advisor_prompts), 1)  # reviewed once, never again

    def test_ok_review_costs_no_round(self):
        fake = FakeOllama([call("vault_write", text="x"), say("Saved.")], advice=["OK"])
        self.assertEqual(self.chat(fake, "save x to my vault")[0], "Saved.")
        self.assertEqual(len(fake.worker_msgs), 2)

    def test_identical_call_is_stopped(self):
        fake = FakeOllama([call("find_files", query="budget"), call("find_files", query="budget"), say("Found it.")])
        self.chat(fake, "look for budget stuff")
        self.assertEqual(self.ran, [("find_files", {"query": "budget"})])
        self.assertIn("already ran", fake.worker_msgs[-1][-1]["content"])

    def test_router_seeds_a_plain_request(self):
        self.results = {"find_files": "~/Documents/budget.xlsx"}
        fake = FakeOllama([say("It's in Documents.")])
        self.chat(fake, "find files named budget")
        self.assertEqual(self.ran, [("find_files", {"query": "budget"})])
        self.assertTrue(any(m.get("tool_name") == "find_files" for m in fake.worker_msgs[0]))

    def test_plan_check_goes_into_the_system_prompt(self):
        fake = FakeOllama([say("Done.")], advice=["- Start with system_status"])
        self.chat(fake, "audit this computer", plan_check=True)
        self.assertIn("Start with system_status", fake.worker_msgs[0][0]["content"])

    def test_advisor_off(self):
        D.CONFIG["advisor"] = "off"
        fake = FakeOllama([call("vault_write", text="x"), say("Saved.")])
        self.chat(fake, "save x to my vault", plan_check=True)
        self.assertEqual(fake.advisor_prompts, [])

    def test_private_never_goes_to_claude(self):
        D.CONFIG["advisor"] = "claude:opus"
        asked = []
        saved = D.MODELS.claude
        D.MODELS.claude = lambda *a, **kw: asked.append(a) or ("- x", "claude-opus-5-5")
        try:
            fake = FakeOllama([call("vault_write", text="x"), say("Saved.")])
            self.chat(fake, "save x to my vault", private=True)
        finally:
            D.MODELS.claude = saved
        self.assertEqual(asked, [])
        self.assertEqual(len(fake.advisor_prompts), 1)  # the local advisor reviewed it instead


if __name__ == "__main__":
    unittest.main()
