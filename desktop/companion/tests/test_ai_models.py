"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import ai_models as A  # noqa: E402


class FakeRun:
    def __init__(self, stdout="", stderr="", code=0):
        self.calls = []
        self.out = (stdout, stderr, code)

    def __call__(self, args, **kw):
        self.calls.append((args, kw))
        return subprocess.CompletedProcess(args, self.out[2], self.out[0], self.out[1])


def ok_json(text, model="claude-opus-5-5"):
    return json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": text, "modelUsage": {model: {}}})


class ModelsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.exe = self.tmp / "claude"
        self.exe.write_text("#!/bin/sh\n")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def models(self, run=None, secrets=None, fetch=None, config=None):
        return A.Models(config or {}, secrets or {}, self.tmp / "cache", ollama_models=lambda: ["qwen3:8b"], run=run or FakeRun(ok_json("hi")),
                        fetch=fetch or (lambda *a: {}), claude_exe=str(self.exe))

    def test_ids_and_labels(self):
        self.assertEqual(A.provider_of("claude:opus"), "claude")
        self.assertEqual(A.provider_of("gemini:gemini-2.5-pro"), "gemini")
        self.assertEqual(A.provider_of("local:qwen3:8b"), "local")
        self.assertEqual(A.model_of("local:qwen3:8b"), "qwen3:8b")
        self.assertEqual(A.provider_of("weird"), "auto")
        self.assertEqual(A.provider_of(None), "auto")
        self.assertEqual(A.pretty_claude("claude-opus-5-5"), "Claude Opus 5.5")
        self.assertEqual(A.pretty_claude("claude-fable-5-2"), "Claude Fable 5.2")
        self.assertEqual(A.pretty_claude("claude-sonnet-4-5-20250929"), "Claude Sonnet 4.5")
        self.assertEqual(A.pretty_claude("claude-opus-6"), "Claude Opus 6")
        self.assertEqual(A.pretty_gemini("gemini-2.5-flash-lite"), "Gemini 2.5 Flash-Lite")
        self.assertEqual(A.pretty_gemini("gemini-3-pro"), "Gemini 3 Pro")

    def test_catalog_puts_claude_and_gemini_first_and_highlighted(self):
        cat = self.models().catalog()
        ids = [e["id"] for e in cat]
        self.assertEqual(ids[:4], ["claude:opus", "claude:sonnet", "claude:haiku", "claude:fable"])
        self.assertTrue(all(e["highlight"] for e in cat if e["provider"] in ("claude", "gemini")))
        self.assertIn("local:qwen3:8b", ids)
        self.assertEqual(ids[-1], "auto")
        gem = [e for e in cat if e["provider"] == "gemini"]
        self.assertTrue(gem and not any(e["available"] for e in gem))  # no key on this computer yet

    def test_claude_call_is_a_clean_headless_chat(self):
        run = FakeRun(ok_json("Hello Paul", "claude-opus-5-5"))
        m = self.models(run=run)
        r = m.chat("claude:opus", "hi", history=[("earlier q", "earlier a")], surface="Phone")
        self.assertEqual(r["text"], "Hello Paul")
        self.assertEqual(r["provider"], "claude")
        self.assertIn("Claude Opus 5.5", r["source"])
        args, kw = run.calls[0]
        for flag in ("-p", "--no-session-persistence", "--strict-mcp-config", "--setting-sources"):
            self.assertIn(flag, args)
        self.assertEqual(args[args.index("--tools") + 1], "")
        self.assertEqual(args[args.index("--model") + 1], "opus")
        self.assertIn("earlier q", kw["input"])
        self.assertNotIn("ANTHROPIC_API_KEY", kw["env"])  # the subscription, never a key
        self.assertNotIn("--mcp-config", args)  # no vault access unless opted in

    def test_advisor_pairing(self):
        self.assertEqual(A.advisor_for("opus", "fable"), "fable")
        self.assertEqual(A.advisor_for("sonnet", "opus"), "opus")
        self.assertEqual(A.advisor_for("claude-opus-5-5", "opus"), "opus")
        self.assertIsNone(A.advisor_for("opus", "sonnet"))  # below the main model
        self.assertIsNone(A.advisor_for("opus", "haiku"))
        self.assertIsNone(A.advisor_for("sonnet", ""))
        self.assertIsNone(A.advisor_for("sonnet", "off"))
        self.assertIsNone(A.advisor_for("sonnet", "--dangerously-skip-permissions"))

    def test_claude_advisor_flag_only_when_asked(self):
        run = FakeRun(ok_json("ok", "claude-sonnet-5-5"))
        m = self.models(run=run)
        r = m.chat("claude:sonnet", "long job", advisor="opus")
        args, _ = run.calls[0]
        self.assertEqual(args[args.index("--advisor") + 1], "opus")
        self.assertIn("advisor Opus", r["source"])
        m.chat("claude:sonnet", "quick question")
        self.assertNotIn("--advisor", run.calls[1][0])
        m.chat("claude:opus", "x", advisor="haiku")
        self.assertNotIn("--advisor", run.calls[2][0])

    def test_claude_vault_tools_are_opt_in_and_read_only(self):
        run = FakeRun(ok_json("ok"))
        m = self.models(run=run, config={"claude_vault_tools": True})
        m.chat("claude:sonnet", "what's in my vault about RTU-4?")
        args, _ = run.calls[0]
        self.assertIn("--mcp-config", args)
        allowed = args[args.index("--allowedTools") + 1:]
        self.assertTrue(allowed and all(t.startswith("mcp__blacklab-vault__vault_") for t in allowed))
        self.assertFalse(any("write" in t or "capture" in t or "node_add" in t for t in allowed))

    def test_learns_the_real_model_name(self):
        m = self.models(run=FakeRun(ok_json("x", "claude-opus-6-0")))
        m.chat("claude:opus", "hi")
        self.assertEqual(m.label("claude:opus"), "Claude Opus 6.0")

    def test_claude_errors_are_readable(self):
        t, c, e = A.parse_claude_output("", "Invalid API key · Please run /login", 1)
        self.assertIn("signed out", e)
        t, c, e = A.parse_claude_output(json.dumps({"is_error": True, "subtype": "success", "result": "Claude usage limit reached", "modelUsage": {}}), "", 1)
        self.assertIn("usage limit", e)
        t, c, e = A.parse_claude_output("", "", 1)
        self.assertIn("didn't answer", e)
        with self.assertRaises(RuntimeError):
            self.models(run=FakeRun("", "boom", 2)).chat("claude:haiku", "hi")
        with self.assertRaises(RuntimeError):
            self.models().claude("rm -rf", "hi", "sys")

    def test_gemini_list_and_call(self):
        listing = {"models": [
            {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash", "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/gemini-3-pro", "displayName": "Gemini 3 Pro", "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/gemini-2.5-flash-preview-tts", "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]},
            {"name": "models/gemini-2.0-flash-001", "supportedGenerationMethods": ["generateContent"]}]}
        sent = []

        def fetch(url, body, headers, *_):
            sent.append((url, body, headers))
            if url.endswith("pageSize=200"):
                return listing
            return {"candidates": [{"content": {"parts": [{"text": "thinking", "thought": True}, {"text": "Answer"}]}}]}
        m = self.models(secrets={"gemini": "k"}, fetch=fetch)
        names = [n for n, _ in m.gemini_models()]
        self.assertEqual(names, ["gemini-3-pro", "gemini-2.5-flash"])
        r = m.chat("gemini:gemini-3-pro", "hi", history=[("a", "b")])
        self.assertEqual(r["text"], "Answer")
        url, body, headers = sent[-1]
        self.assertIn("gemini-3-pro:generateContent", url)
        self.assertNotIn("k", url)  # the key goes in a header
        self.assertEqual(headers["x-goog-api-key"], "k")
        self.assertEqual([c["role"] for c in body["contents"]], ["user", "model", "user"])
        self.assertIn("systemInstruction", body)

    def test_transcript(self):
        self.assertEqual(A.transcript([], "hi"), "hi")
        t = A.transcript([("q1", "a1"), ("q2", "a2")], "q3")
        self.assertTrue(t.index("q1") < t.index("q2") < t.index("q3"))


if __name__ == "__main__":
    unittest.main()
