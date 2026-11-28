/* BlackLab Terminal page: xterm.js on one side, the app (window.Android) on the other.
 *   Android → page: bl.out(base64), bl.key(name), bl.text(s), bl.line(s, enter), bl.ctrl(on), bl.alt(on), bl.font(px), bl.focus(), bl.clear()
 *   page → Android: Android.onReady(cols, rows), onData(text), onResize(cols, rows), onCtrl(on), onAlt(on), onFont(px)
 * BlackLab Desktop's terminal view (desktop/web/terminal.js) uses the same colours and key names, but it talks to the
 * companion directly and has none of the phone-only parts here (bl.line, onFont, touch scrolling, the keyboard fix).
 *
 * Touch (5.18.1): xterm.js 6 has no touch scrolling of its own, so the browser panned its viewport and the rows lagged
 * or jumped. This page scrolls itself: the finger moves the text 1:1 (leftover pixels carry over, so slow and fast drags
 * go the same distance), a flick keeps coasting and slows down evenly, and a pinch only scales the picture until the
 * fingers lift, then sets the new size once (one resize for the SSH session instead of dozens).
 *
 * Phone keyboards (5.18.1): xterm.js works out what an Android keyboard typed by diffing its hidden textarea, and Gboard
 * keeps the whole line in that textarea, so every keystroke re-sent the line typed so far. This page reads the
 * keyboard's text itself instead: it sends only what changed (a correction becomes backspaces plus the new letters)
 * and empties the textarea after each finished input. The app also asks for a no-suggestions keyboard (TermWebView). */
var BLTermIme = (function () {
  "use strict";
  /** What to send to turn `typed` (what the keyboard had) into `now`: how many backspaces, then which text. */
  function diff(typed, now) {
    var p = 0, n = Math.min(typed.length, now.length);
    while (p < n && typed.charCodeAt(p) === now.charCodeAt(p)) p++;
    if (p > 0 && p < typed.length) {  // never split an emoji / surrogate pair
      var c = typed.charCodeAt(p - 1);
      if (c >= 0xD800 && c <= 0xDBFF) p--;
    }
    return { del: Array.from(typed.slice(p)).length, ins: now.slice(p) };
  }
  return { diff: diff };
})();

/** Touch-scroll arithmetic, kept apart from the page so it can be tested. Distances are CSS px, times ms. */
var BLTermTouch = (function () {
  "use strict";
  var TAU = 325;          // how long a flick coasts: its speed falls to 37% every TAU ms (the feel of Android lists)
  var MIN_FLING = 0.25;   // px/ms: slower than this on release, the text just stops where the finger left it
  var MAX_FLING = 8;      // px/ms: a hard flick still stays readable
  var WINDOW = 100;       // the speed on release is measured over the finger's last 100 ms
  /** Add `px` to the carried-over `acc` and say how many whole lines (`cell` px each) that is; the rest carries on. */
  function lines(acc, px, cell) {
    if (!(cell > 0)) cell = 16;
    acc += px;
    var n = acc >= 0 ? Math.floor(acc / cell) : Math.ceil(acc / cell);
    return { n: n, acc: acc - n * cell };
  }
  /** A coasting flick after `dt` ms: how far it moved and its new speed. */
  function coast(v, dt) {
    var k = Math.exp(-dt / TAU);
    return { px: v * TAU * (1 - k), v: v * k };
  }
  /** Finger speed (px/ms) over the last WINDOW ms of `samples` ([{t, y}], oldest first); 0 if it stopped. */
  function velocity(samples) {
    if (!samples || samples.length < 2) return 0;
    var last = samples[samples.length - 1], first = last;
    for (var i = samples.length - 1; i >= 0 && last.t - samples[i].t <= WINDOW; i--) first = samples[i];
    var dt = last.t - first.t;
    if (dt <= 0) return 0;
    var v = (last.y - first.y) / dt;
    if (Math.abs(v) < MIN_FLING) return 0;
    return Math.max(-MAX_FLING, Math.min(MAX_FLING, v));
  }
  return { lines: lines, coast: coast, velocity: velocity, TAU: TAU, MIN_FLING: MIN_FLING, MAX_FLING: MAX_FLING };
})();
if (typeof module !== "undefined") module.exports = { diff: BLTermIme.diff, lines: BLTermTouch.lines, coast: BLTermTouch.coast, velocity: BLTermTouch.velocity };

if (typeof Terminal !== "undefined") (function () {
  "use strict";
  var A = window.Android;
  var params = new URLSearchParams(location.search);
  var startFont = Math.max(8, Math.min(28, parseInt(params.get("font"), 10) || 14));
  var term = new Terminal({
    cursorBlink: true, fontSize: startFont, lineHeight: 1.15, scrollback: 6000, allowProposedApi: false, macOptionIsMeta: true,
    fontFamily: "'JetBrains Mono', 'Roboto Mono', 'Noto Sans Mono', monospace",
    theme: { background: "#0B0E13", foreground: "#E7ECF3", cursor: "#2BD9A8", cursorAccent: "#0B0E13", selectionBackground: "#2BD9A855",
             black: "#1B222C", red: "#FF5A5F", green: "#3DDC84", yellow: "#FFB020", blue: "#64B5F6", magenta: "#AB7CFF", cyan: "#2BD9A8", white: "#E7ECF3",
             brightBlack: "#5B6676", brightRed: "#FF7B80", brightGreen: "#6BF0A6", brightYellow: "#FFC85A", brightBlue: "#8AB4F8", brightMagenta: "#C3A2FF", brightCyan: "#6BEBC8", brightWhite: "#FFFFFF" }
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  var host = document.getElementById("t");
  term.open(host);
  var ctrl = false, alt = false;

  function send(s) { if (s) A.onData(s); }
  function refit() { try { fit.fit(); } catch (e) { /* not laid out yet */ } }
  // The keyboard sliding in resizes the page every frame; fit once it settles so the SSH session gets one resize.
  var refitTimer = 0;
  function refitSoon() { clearTimeout(refitTimer); refitTimer = setTimeout(refit, 60); }

  /** Typed text, with the sticky Ctrl / Alt keys from the key bar applied to it. */
  function emit(d) {
    if (!d) return;
    if (ctrl && d.length === 1) {
      var c = d.toUpperCase().charCodeAt(0);
      if (c >= 64 && c <= 95) d = String.fromCharCode(c - 64);
      else if (d === " ") d = "\u0000";
      else if (d === "?") d = "\u007f";
    }
    if (ctrl) { ctrl = false; A.onCtrl(false); }
    if (alt) { d = "\u001b" + d; alt = false; A.onAlt(false); }
    send(d);
  }

  term.onData(emit);
  term.onResize(function (s) { A.onResize(s.cols, s.rows); });
  if (window.ResizeObserver) new ResizeObserver(refitSoon).observe(host);
  window.addEventListener("resize", refitSoon);

  // ---- the phone keyboard (see the note at the top) -------------------------------------------------------------
  var ta = term.textarea;
  if (ta) {
    ["autocorrect", "autocapitalize", "autocomplete"].forEach(function (a) { ta.setAttribute(a, "off"); });
    ta.setAttribute("spellcheck", "false");
    var typed = "", composing = false;
    var sync = function () {
      var now = ta.value, d = BLTermIme.diff(typed, now);
      if (d.del) send(new Array(d.del + 1).join("\u007f"));
      if (d.ins) emit(d.ins.replace(/\r?\n/g, "\r"));
      typed = now;
      if (!composing && now) { ta.value = ""; typed = ""; }
    };
    // Listening on the terminal's box in the capture phase runs before xterm's own textarea listeners, and stopping
    // the event there keeps xterm from sending the same text a second time.
    var mine = function (e) { return e.target === ta; };
    host.addEventListener("compositionstart", function (e) { if (mine(e)) { composing = true; e.stopImmediatePropagation(); } }, true);
    host.addEventListener("compositionupdate", function (e) { if (mine(e)) e.stopImmediatePropagation(); }, true);
    host.addEventListener("compositionend", function (e) { if (mine(e)) { composing = false; e.stopImmediatePropagation(); sync(); } }, true);
    host.addEventListener("input", function (e) {
      if (!mine(e)) return;
      e.stopImmediatePropagation();
      if (e.isComposing) composing = true;
      sync();
    }, true);
    // Keyboard "unidentified" keys (229) are the keyboard editing text; the input event above covers them. Real keys
    // (Enter, Backspace on an empty line, a hardware keyboard) still go to xterm.
    host.addEventListener("keydown", function (e) {
      if (!mine(e)) return;
      if (e.keyCode === 229 || e.isComposing || composing) e.stopImmediatePropagation();
    }, true);
  }

  // ---- touch: drag / flick to scroll, pinch to change the text size, tap for the keyboard ------------------------
  var MIN_FONT = 8, MAX_FONT = 28, SLOP = 8;
  function clampFont(px) { return Math.max(MIN_FONT, Math.min(MAX_FONT, Math.round(px))); }
  function setFont(px) {
    px = clampFont(px);
    if (px === term.options.fontSize) return px;
    term.options.fontSize = px;
    refit();
    return px;
  }
  function gap(t) { var x = t[0].clientX - t[1].clientX, y = t[0].clientY - t[1].clientY; return Math.sqrt(x * x + y * y) || 1; }
  /** One terminal row in CSS px (fit makes the rows fill the box). */
  function cell() { return (host.clientHeight && term.rows) ? host.clientHeight / term.rows : 16; }
  function buf() { return term.buffer && term.buffer.active; }
  function mouseApp() { var m = term.modes && term.modes.mouseTrackingMode; return !!m && m !== "none"; }
  /** Where a scroll goes: the scrollback, a full-screen app that reads the mouse (tmux, htop…), or one that only reads
   *  the arrow keys (less, man) — the same choice xterm.js makes for a mouse wheel. */
  function scrollMode() {
    if (mouseApp()) return "wheel";
    var b = buf();
    return b && b.type === "alternate" ? "arrows" : "lines";
  }
  var lastX = 0, lastY = 0;
  /** Scroll `n` rows (positive = towards newer text). Returns false once the scrollback can't go further that way. */
  function scrollBy(n, mode) {
    if (!n) return true;
    if (mode === "lines") {
      var b = buf();
      if (b && (n < 0 ? b.viewportY <= 0 : b.viewportY >= b.baseY)) return false;
      term.scrollLines(n);
      return true;
    }
    var k = Math.min(Math.abs(n), 40);
    if (mode === "wheel") {
      var el = (term.element && term.element.querySelector(".xterm-screen")) || term.element;
      if (!el || typeof WheelEvent === "undefined") return false;
      for (var i = 0; i < k; i++) {
        el.dispatchEvent(new WheelEvent("wheel", { deltaY: n > 0 ? 1 : -1, deltaMode: 1, clientX: lastX, clientY: lastY, bubbles: true, cancelable: true }));
      }
      return true;
    }
    var app = term.modes && term.modes.applicationCursorKeysMode;
    send(new Array(k + 1).join((app ? "\u001bO" : "\u001b[") + (n > 0 ? "B" : "A")));
    return true;
  }

  var flingId = 0;
  function stopFling() { if (flingId && typeof cancelAnimationFrame !== "undefined") cancelAnimationFrame(flingId); flingId = 0; }
  /** Keep scrolling after a flick at `v` px/ms of text movement, slowing down evenly until it stops or hits an end. */
  function fling(v, acc, mode) {
    if (!v || typeof requestAnimationFrame === "undefined") return;
    var last = 0;
    var step = function (now) {
      if (!last) { last = now; flingId = requestAnimationFrame(step); return; }
      var c = BLTermTouch.coast(v, Math.min(now - last, 50));
      last = now; v = c.v;
      var r = BLTermTouch.lines(acc, c.px, cell());
      acc = r.acc;
      if (!scrollBy(r.n, mode) || Math.abs(v) < 0.02) { flingId = 0; return; }
      flingId = requestAnimationFrame(step);
    };
    flingId = requestAnimationFrame(step);
  }

  /** The pinch preview: scale the picture only (cheap, no reflow, no resize sent); the real size is set on release. */
  function preview(scale) {
    var el = term.element;
    if (!el || !el.style) return;
    el.style.transformOrigin = "0 0";
    el.style.transform = scale === 1 ? "" : "scale(" + scale + ")";
  }

  var movedAt = -1e9;  // when the last drag / pinch ended
  var g = null;  // the gesture under way: { mode: "tap" | "scroll" | "sideways" | "pinch", … }
  host.addEventListener("touchstart", function (e) {
    stopFling();
    var t = e.touches;
    if (t.length >= 2) {
      g = { mode: "pinch", d: gap(t), font: term.options.fontSize, scale: 1 };
    } else if (t.length === 1) {
      lastX = t[0].clientX; lastY = t[0].clientY;
      g = { mode: "tap", x0: lastX, y0: lastY, y: lastY, acc: 0, smode: scrollMode(), samples: [{ t: e.timeStamp, y: lastY }] };
    }
  }, { passive: true });
  host.addEventListener("touchmove", function (e) {
    if (!g) return;
    var t = e.touches;
    if (g.mode === "pinch") {
      if (e.cancelable) e.preventDefault();
      if (t.length >= 2) {
        g.scale = clampFont(g.font * gap(t) / g.d) / g.font;
        preview(g.scale);
      }
      return;
    }
    if (t.length !== 1) return;
    var x = t[0].clientX, y = t[0].clientY;
    lastX = x; lastY = y;
    if (g.mode === "tap") {
      var dx = Math.abs(x - g.x0), dy = Math.abs(y - g.y0);
      if (dx < SLOP && dy < SLOP) return;
      g.mode = dx > dy ? "sideways" : "scroll";  // sideways: a long line being read; leave it alone
    }
    if (g.mode !== "scroll") return;
    if (e.cancelable) e.preventDefault();
    var r = BLTermTouch.lines(g.acc, g.y - y, cell());  // finger up = newer text, like any list
    g.acc = r.acc; g.y = y;
    scrollBy(r.n, g.smode);
    g.samples.push({ t: e.timeStamp, y: y });
    if (g.samples.length > 12) g.samples.shift();
  }, { passive: false });
  var touchEnd = function (e) {
    if (!g) return;
    if (g.mode === "pinch") {
      if (e.touches.length >= 2) return;
      preview(1);
      var before = term.options.fontSize, now = setFont(g.font * g.scale);
      if (now !== before && A.onFont) A.onFont(now);
      movedAt = e.timeStamp;
      g = null;  // a finger left behind after a pinch doesn't start a scroll
      return;
    }
    if (e.touches.length) return;
    if (g.mode !== "tap") movedAt = e.timeStamp;
    if (g.mode === "tap" && e.type === "touchend") term.focus();  // a tap brings up the keyboard; a scroll doesn't
    // Flicks coast through the scrollback or a mouse-aware app; arrow-key apps (less) stop with the finger.
    if (g.mode === "scroll" && e.type === "touchend" && g.smode !== "arrows") fling(-BLTermTouch.velocity(g.samples), g.acc, g.smode);
    g = null;
  };
  host.addEventListener("touchend", touchEnd, { passive: true });
  host.addEventListener("touchcancel", touchEnd, { passive: true });
  // A mouse or a stylus; not the click a browser may add after a short drag (that would pop the keyboard up mid-scroll)
  document.addEventListener("click", function (e) { if (!(e.timeStamp - movedAt < 500)) term.focus(); });

  var KEYS = { esc: "\u001b", tab: "\t", enter: "\r", backspace: "\u007f", home: "\u001b[H", end: "\u001b[F", pgup: "\u001b[5~", pgdn: "\u001b[6~", del: "\u001b[3~",
               ctrlc: "\u0003", ctrld: "\u0004" };
  var ARROWS = { up: "A", down: "B", right: "C", left: "D" };

  window.bl = {
    out: function (b64) {
      var bin = atob(b64), u = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
      term.write(u);
    },
    key: function (name) {
      if (ARROWS[name]) {  // vim, less, top… switch the cursor keys to "application" mode
        var app = term.modes && term.modes.applicationCursorKeysMode;
        send((alt ? "\u001b" : "") + (app ? "\u001bO" : "\u001b[") + ARROWS[name]);
      } else if (KEYS[name]) {
        send((alt ? "\u001b" : "") + KEYS[name]);
      }
      if (alt) { alt = false; A.onAlt(false); }
      term.focus();
    },
    text: function (s) { term.paste(s); term.focus(); },
    /** A line from the app's compose box (full keyboard: suggestions, voice typing). One line goes as typing; several
     *  as a paste, so a shell or Claude doesn't run each line on its own. `enter` presses Return after it. */
    line: function (s, enter) {
      if (s) { if (/[\r\n]/.test(s)) term.paste(s); else send(s); }
      if (enter) send("\r");
    },
    ctrl: function (on) { ctrl = !!on; term.focus(); },
    alt: function (on) { alt = !!on; term.focus(); },
    font: function (px) { return setFont(px); },
    focus: function () { term.focus(); },
    clear: function () { term.clear(); },
    size: function () { return term.cols + "x" + term.rows; }
  };
  // Report a real size: wait until the page has a height (the app may still be laying out)
  (function ready(tries) {
    refit();
    if (term.rows < 4 && tries < 40) return setTimeout(function () { ready(tries + 1); }, 50);
    A.onReady(term.cols, term.rows);
    term.focus();
  })(0);
})();
