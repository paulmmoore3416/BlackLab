// The phone terminal's keyboard handling (app/src/main/assets/terminal/terminal.js). In 5.18.0 Gboard's composing
// textarea made every keystroke re-send the whole line typed so far; these tests replay that keyboard against the page
// with a stand-in xterm and check that the shell receives each character once.
//   node --test desktop/web/tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const pagePath = join(here, '..', '..', '..', 'app', 'src', 'main', 'assets', 'terminal', 'terminal.js');
const source = readFileSync(pagePath, 'utf8');
const { diff, lines, coast, velocity } = createRequire(import.meta.url)(pagePath);

test('diff: plain typing, corrections, deletions, emoji', () => {
  assert.deepEqual(diff('', 'a'), { del: 0, ins: 'a' });
  assert.deepEqual(diff('hel', 'hell'), { del: 0, ins: 'l' });
  assert.deepEqual(diff('nite', 'now'), { del: 3, ins: 'ow' });        // autocorrect rewrites a word
  assert.deepEqual(diff('abc', 'ab'), { del: 1, ins: '' });
  assert.deepEqual(diff('a😀', 'a😃'), { del: 1, ins: '😃' });         // one backspace for one emoji, never half of it
  assert.deepEqual(diff('same', 'same'), { del: 0, ins: '' });
});

/** Minimal DOM + xterm stand-in: one textarea inside the terminal box, with capture/target listener order. */
function page() {
  const sent = [];
  const listeners = (o) => (o.l = {}, o.addEventListener = (t, f, opt) => {
    const cap = opt === true || (opt && opt.capture);
    (o.l[t] ||= []).push({ f, cap });
  }, o);
  const host = listeners({ id: 't', clientHeight: 480 });  // 24 rows of 20 px
  const ta = listeners({ value: '', attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } });
  const xtermGot = [];
  // xterm's own textarea listeners: if any event reaches them, xterm would send its own (duplicate) data
  ['input', 'compositionstart', 'compositionupdate', 'compositionend', 'keydown'].forEach((t) => ta.addEventListener(t, (e) => xtermGot.push(t), true));
  function fire(type, props = {}) {
    let stopped = false;
    const e = { type, target: ta, ...props, stopImmediatePropagation() { stopped = true; } };
    for (const { f, cap } of host.l[type] || []) { if (cap) { f(e); if (stopped) return; } }
    for (const { f } of ta.l[type] || []) { f(e); if (stopped) return; }
  }
  class Terminal {
    constructor(o) {
      this.options = { ...o }; this.textarea = ta; this.cols = 80; this.rows = 24; this.modes = {};
      this.buffer = { active: { type: 'normal', viewportY: 50, baseY: 100 } }; this.element = { style: {} };
      this.scrolled = []; this.focused = 0; Terminal.last = this;
    }
    loadAddon() {} open() {} onData(f) { this.dataCb = f; } onResize() {} focus() { this.focused++; } write() {} paste(s) { sent.push('[paste]' + s); } clear() {}
    scrollLines(n) { this.scrolled.push(n); this.buffer.active.viewportY += n; }
  }
  const doc = listeners({ getElementById: () => host });
  const frames = [];
  const fonts = [];
  const ctx = {
    Terminal, FitAddon: { FitAddon: class { fit() {} } }, document: doc, location: { search: '?font=15' }, URLSearchParams,
    setTimeout: () => 0, clearTimeout: () => {}, requestAnimationFrame: (f) => frames.push(f), cancelAnimationFrame: () => { frames.length = 0; }, Math, Array, parseInt, atob: (s) => Buffer.from(s, 'base64').toString('binary'), Uint8Array,
    window: { addEventListener() {}, Android: { onData: (s) => sent.push(s), onReady() {}, onResize() {}, onCtrl() {}, onAlt() {}, onFont: (px) => fonts.push(px) } },
  };
  ctx.window.bl = undefined;
  vm.createContext(ctx);
  vm.runInContext(source.replace(/window\.bl =/, 'globalThis.bl = window.bl ='), ctx);
  /** A touch event on the terminal box; `pts` are [x, y] per finger still down. */
  function touch(type, t, ...pts) {
    const e = { type, timeStamp: t, cancelable: true, prevented: false, preventDefault() { this.prevented = true; },
      touches: pts.map(([clientX, clientY]) => ({ clientX, clientY })) };
    for (const { f } of host.l[type] || []) f(e);
    return e;
  }
  /** Run queued animation frames 16 ms apart (a flick coasting). */
  function run(t0) { let t = t0; for (let i = 0; i < 500 && frames.length; i++) { const f = frames.shift(); f(t += 16); } }
  Terminal.last.focused = 0;  // the page focuses once when it starts
  return { ta, fire, sent, xtermGot, bl: ctx.bl, ctx, touch, run, frames, fonts, term: Terminal.last };
}

test('Gboard composing the whole line: each letter reaches the shell once', () => {
  const p = page();
  const line = 'thank you';
  p.fire('compositionstart');
  for (let i = 1; i <= line.length; i++) {
    p.ta.value = line.slice(0, i);                     // the keyboard keeps the whole line in the textarea
    p.fire('keydown', { keyCode: 229 });
    p.fire('input', { isComposing: true, inputType: 'insertCompositionText', data: p.ta.value });
  }
  p.fire('compositionend');
  assert.equal(p.sent.join(''), line);
  assert.equal(p.ta.value, '', 'textarea is emptied once the keyboard is done');
  assert.deepEqual(p.xtermGot, [], 'xterm never sees the events, so it cannot send them again');
});

test('autocorrect while composing becomes backspaces plus the fix', () => {
  const p = page();
  p.fire('compositionstart');
  for (const v of ['n', 'ni', 'nit', 'nite', 'now']) { p.ta.value = v; p.fire('input', { isComposing: true }); }
  p.fire('compositionend');
  assert.equal(p.sent.join(''), 'nite\x7f\x7f\x7fow');
});

test('plain keyboard (no composing): one commit per key, textarea cleared each time', () => {
  const p = page();
  for (const ch of 'ls -la') { p.ta.value += ch; p.fire('keydown', { keyCode: 229 }); p.fire('input', { isComposing: false, inputType: 'insertText', data: ch }); }
  assert.equal(p.sent.join(''), 'ls -la');
  assert.equal(p.ta.value, '');
});

test('real keys (Enter, a hardware keyboard) still go to xterm', () => {
  const p = page();
  p.fire('keydown', { keyCode: 13 });
  assert.deepEqual(p.xtermGot, ['keydown']);
});

test('sticky Ctrl from the key bar applies to the next typed letter', () => {
  const p = page();
  p.bl.ctrl(true);
  p.ta.value = 'c'; p.fire('input', { isComposing: false });
  assert.equal(p.sent.join(''), '\x03');
});

test('compose box: one line is typed, several are pasted, then Return', () => {
  const p = page();
  p.bl.line('hello Claude', true);
  p.bl.line('a\nb', true);
  assert.deepEqual(p.sent, ['hello Claude', '\r', '[paste]a\nb', '\r']);
});

test('the saved text size comes from the page address', () => {
  const p = page();
  assert.equal(p.bl.font(15), 15);  // already 15 from ?font=15: no change
  assert.equal(p.bl.font(40), 28);  // clamped
});

test('touch math: leftover pixels carry over, so slow and fast drags go the same distance', () => {
  let acc = 0, n = 0;
  for (let i = 0; i < 20; i++) { const r = lines(acc, 3, 20); acc = r.acc; n += r.n; }  // 60 px in tiny steps
  assert.equal(n, 3);
  assert.equal(lines(0, 60, 20).n, 3);                                                   // 60 px at once
  assert.deepEqual(lines(0, -45, 20), { n: -2, acc: -5 });
  const c = coast(1, 325);                                                               // a flick slows evenly
  assert.ok(c.v > 0.36 && c.v < 0.37 && c.px > 205 && c.px < 206);
  assert.equal(velocity([{ t: 0, y: 0 }, { t: 50, y: 5 }]), 0, 'a slow lift does not fling');
  assert.equal(velocity([{ t: 0, y: 0 }, { t: 400, y: 9 }, { t: 450, y: 109 }]), 2, 'speed comes from the last 100 ms');
  assert.equal(velocity([{ t: 0, y: 0 }, { t: 10, y: 500 }]), 8, 'capped');
});

test('drag scrolls the scrollback 1:1 and never pops the keyboard', () => {
  const p = page();
  p.touch('touchstart', 0, [100, 300]);
  const e = p.touch('touchmove', 200, [100, 310]);                 // past the slop, going down = older text
  p.touch('touchmove', 400, [100, 360]);
  p.touch('touchend', 600);
  assert.ok(e.prevented, 'the browser does not pan as well');
  assert.equal(p.term.scrolled.reduce((a, b) => a + b, 0), -3);    // 60 px at 20 px a row
  assert.equal(p.term.focused, 0);
  p.run(600);
  assert.equal(p.term.scrolled.reduce((a, b) => a + b, 0), -3, 'a slow drag stops where the finger left it');
});

test('a flick coasts on and stops at the top of the scrollback', () => {
  const p = page();
  p.touch('touchstart', 0, [100, 100]);
  for (let i = 1; i <= 5; i++) p.touch('touchmove', i * 16, [100, 100 + i * 40]);  // 2.5 px/ms downwards
  p.touch('touchend', 90);
  assert.ok(p.frames.length, 'coasting');
  p.run(90);
  assert.equal(p.term.buffer.active.viewportY, 0, 'coasted all the way back and stopped at the top');
  assert.equal(p.frames.length, 0);
});

test('full-screen apps: arrow keys for less, no coasting', () => {
  const p = page();
  p.term.buffer.active.type = 'alternate';
  p.touch('touchstart', 0, [100, 300]);
  p.touch('touchmove', 16, [100, 260]);                            // finger up 40 px = 2 rows newer
  p.touch('touchend', 20);
  assert.equal(p.sent.join(''), '\x1b[B\x1b[B');
  assert.equal(p.frames.length, 0);
});

test('a tap focuses (keyboard); sideways drags are left alone', () => {
  const p = page();
  p.touch('touchstart', 0, [100, 300]); p.touch('touchend', 50);
  assert.equal(p.term.focused, 1);
  p.touch('touchstart', 100, [100, 300]);
  const e = p.touch('touchmove', 120, [160, 302]);
  p.touch('touchend', 140);
  assert.equal(e.prevented, false);
  assert.deepEqual(p.term.scrolled, []);
});

test('pinch previews with a scale and sets the size once, on release', () => {
  const p = page();                                                // starts at 15 px (?font=15)
  p.touch('touchstart', 0, [100, 100], [200, 100]);
  for (const x of [220, 250, 300]) p.touch('touchmove', x, [100, 100], [x, 100]);
  assert.equal(p.term.options.fontSize, 15, 'no reflow or SSH resize while the fingers move');
  assert.equal(p.term.element.style.transform, 'scale(' + 28 / 15 + ')');  // 2x, clamped to 28 px
  p.touch('touchend', 400, [100, 100]);                            // one finger still down
  assert.equal(p.term.options.fontSize, 28);
  assert.equal(p.term.element.style.transform, '');
  assert.deepEqual(p.fonts, [28], 'the app hears about it once');
  p.touch('touchmove', 420, [100, 200]); p.touch('touchend', 440);
  assert.deepEqual(p.term.scrolled, [], 'the finger left behind does not scroll');
});
