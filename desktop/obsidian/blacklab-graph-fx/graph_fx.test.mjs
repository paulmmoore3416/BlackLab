// node --test desktop/obsidian/blacklab-graph-fx/graph_fx.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { __test: T } = require("./main.js");

test("colors", () => { assert.equal(T.rgbToCss(0x2bd9a8), "rgb(43,217,168)"); assert.equal(T.rgbToCss(0xff0000, 0.5), "rgba(255,0,0,0.5)"); });
test("birth uses the earliest believable date", () => {
  const t = T.birthOf("01 Daily/2026-11-03.md", { ctime: Date.UTC(2026, 10, 9), mtime: Date.UTC(2026, 10, 10) }, {});
  assert.equal(new Date(t).toISOString().slice(0, 10), "2026-11-03");
  const u = T.birthOf("x.md", { ctime: Date.UTC(2026, 9, 1), mtime: Date.UTC(2026, 9, 5) }, { created: "2025-02-01" });
  assert.equal(new Date(u).toISOString().slice(0, 10), "2025-02-01");
  const w = T.birthOf("x.md", { ctime: 5, mtime: Date.UTC(2026, 0, 1) }, null);  // nonsense ctime ignored
  assert.equal(new Date(w).toISOString().slice(0, 10), "2026-01-01");
});
test("schedule sorts and reveals by rank", () => {
  const s = T.schedule([{ id: "b", t: 2 }, { id: "a", t: 1 }, { id: "c", t: 2 }, { id: "z", t: NaN }]);
  assert.deepEqual(s.order.map((i) => i.id), ["a", "b", "c"]);
  assert.equal(T.revealAt(0, 3), 0); assert.equal(T.revealAt(2, 3), 1); assert.equal(T.revealAt(0, 1), 0);
});
test("activity favors recent edits", () => {
  const now = Date.UTC(2026, 10, 10);
  assert.equal(T.activity(now, now, 7), 6);
  assert.equal(T.activity(now - 30 * 864e5, now, 7), 1);
  assert.ok(T.activity(now - 3 * 864e5, now, 7) > 3);
  assert.equal(T.activity(0, now, 7), 1);
});
test("weighted pick", () => {
  const cum = [1, 1, 11];  // index 1 has zero weight
  const counts = [0, 0, 0];
  for (let i = 0; i < 1000; i++) counts[T.pickWeighted(cum, i / 1000)]++;
  assert.equal(counts[1], 0); assert.ok(counts[2] > 850);
});
