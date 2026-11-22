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

test("dashboard buttons only run the known actions", () => {
  for (const a of ["refresh", "vault_pass", "health_scan", "atlas"]) assert.ok(T.isDashAction(a));
  for (const a of ["", "rm -rf", "restart", null, "refresh "]) assert.equal(T.isDashAction(a), false);
});
test("only the companion's own pages may load in a frame", () => {
  assert.ok(T.frameAllowed("http://127.0.0.1:8780/"));
  assert.ok(T.frameAllowed("http://127.0.0.1:8770/index.html"));
  for (const u of ["https://example.com/", "http://127.0.0.1:9000/", "http://127.0.0.1:8780.evil.com/", "javascript:alert(1)", ""]) assert.equal(T.frameAllowed(u), false);
});
test("ask answers list their source notes", () => {
  const t = T.answerText({ answer: "Filters every 90 days.", sources: ["10 Facility/Assets/AHU-2.md", "30 Work/Tasks.md"] });
  assert.match(t, /Filters every 90 days\./);
  assert.match(t, /\[\[AHU-2\]\] · \[\[Tasks\]\]/);
  assert.equal(T.answerText({ error: "kb-core is down" }), "⚠️ kb-core is down");
  assert.equal(T.answerText(null), "No answer.");
});
test("capture titles come from the first line", () => {
  assert.equal(T.captureTitle("# Call the elevator vendor\nabout the permit"), "Call the elevator vendor");
  assert.equal(T.captureTitle("   "), "Capture");
  assert.equal(T.captureTitle("x".repeat(80)).length, 58);
});
