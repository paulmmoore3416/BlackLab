"""keep_warm: the desktop AI stays loaded after ./daddyshome until ./daddyshome --stop."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import keep_warm  # noqa: E402


class FakeOllama:
    def __init__(self, loaded=(), up=True):
        self.loaded = list(loaded)
        self.up = up
        self.calls = []

    def __call__(self, url, body=None, timeout=0):
        if not self.up:
            raise OSError("connection refused")
        self.calls.append((url.rsplit("/", 1)[-1], body))
        if url.endswith("/api/ps"):
            return {"models": [{"name": n} for n in self.loaded]}
        if url.endswith("/api/generate"):
            self.loaded.append(body["model"])
            return {"done": True}
        raise AssertionError(url)


def run(cfg, ollama, mining=False):
    return keep_warm.pass_once(cfg, ollama, lambda: mining, "http://o", model="qwen3:8b", num_ctx=8192)


class KeepWarmTest(unittest.TestCase):
    def test_pinned_model_missing_is_loaded_with_the_same_context(self):
        o = FakeOllama()
        self.assertEqual(run({"keep_alive": -1}, o), "loaded qwen3:8b")
        gen = [b for name, b in o.calls if name == "generate"][0]
        self.assertEqual(gen["keep_alive"], -1)
        self.assertEqual(gen["options"]["num_ctx"], 8192)  # a different size would force a reload on the next chat

    def test_already_loaded_does_nothing(self):
        o = FakeOllama(loaded=["qwen3:8b"])
        self.assertEqual(run({"keep_alive": -1}, o), "ok")
        self.assertFalse([c for c in o.calls if c[0] == "generate"])

    def test_after_stop_it_leaves_the_model_alone(self):
        o = FakeOllama()
        self.assertEqual(run({"keep_alive": "30m"}, o), "off")  # ./daddyshome --stop sets 30m
        self.assertEqual(o.calls, [])

    def test_string_minus_one_counts_as_pinned(self):
        self.assertEqual(run({"keep_alive": "-1"}, FakeOllama()), "loaded qwen3:8b")

    def test_waits_while_night_mining_owns_the_gpus(self):
        o = FakeOllama()
        self.assertEqual(run({"keep_alive": -1}, o, mining=True), "mining")
        self.assertEqual(o.calls, [])

    def test_ollama_down_is_reported_not_raised(self):
        self.assertTrue(run({"keep_alive": -1}, FakeOllama(up=False)).startswith("ollama down"))

    def test_tagless_name_matches_latest(self):
        o = FakeOllama(loaded=["llama3:latest"])
        r = keep_warm.pass_once({"keep_alive": -1}, o, lambda: False, "http://o", model="llama3", num_ctx=4096)
        self.assertEqual(r, "ok")


if __name__ == "__main__":
    unittest.main()
