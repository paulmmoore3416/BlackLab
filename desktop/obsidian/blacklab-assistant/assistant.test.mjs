// node --test desktop/obsidian/blacklab-assistant/assistant.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { __test: T } = require("./main.js");

const CAT = [
  { id: "local:qwen3:8b", provider: "local", label: "qwen3:8b", available: true },
  { id: "auto", provider: "auto", label: "Auto", available: true },
  { id: "gemini:gemini-2.5-pro", provider: "gemini", label: "Gemini 2.5 Pro", available: false },
  { id: "claude:opus", provider: "claude", label: "Claude Opus 5.5", available: true },
  { id: "claude:sonnet", provider: "claude", label: "Claude Sonnet 5.5", available: true },
];

test("Claude and Gemini come first in the dropdown", () => {
  const g = T.groupModels(CAT);
  assert.deepEqual(g.map((x) => x.provider), ["claude", "gemini", "local", "auto"]);
  assert.equal(g[0].label, "★ Claude");
  assert.match(g[1].items[0].label, /unavailable/);
});
test("picks the saved model, else the first Claude", () => {
  assert.equal(T.pickModel(CAT, "local:qwen3:8b"), "local:qwen3:8b");
  assert.equal(T.pickModel(CAT, "gemini:gemini-2.5-pro"), "claude:opus");
  assert.equal(T.pickModel([], "x"), "auto");
});
test("history pairs skip errors and keep the newest", () => {
  const m = [{ role: "user", text: "a" }, { role: "ai", text: "A" }, { role: "user", text: "b" }, { role: "ai", text: "B", error: true }, { role: "user", text: "c" }, { role: "ai", text: "C" }];
  assert.deepEqual(T.historyPairs(m, 6), [["a", "A"], ["c", "C"]]);
  assert.deepEqual(T.historyPairs(m, 1), [["c", "C"]]);
});
test("note context drops frontmatter and BlackLab markers", () => {
  const t = T.noteContext("---\ntype: x\n---\n%% blacklab:begin %%\nHello\n%% blacklab:end %%\nMine", 1000);
  assert.equal(t, "Hello\n\nMine");
  assert.match(T.noteContext("x".repeat(50), 10), /trimmed/);
  assert.equal(T.providerOf("claude:opus"), "claude"); assert.equal(T.providerOf("??"), "auto");
});
