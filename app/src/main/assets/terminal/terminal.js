/* BlackLab Terminal page: xterm.js on one side, the app (window.Android) on the other.
 *   Android → page: bl.out(base64), bl.key(name), bl.text(s), bl.ctrl(on), bl.alt(on), bl.font(px), bl.focus(), bl.clear()
 *   page → Android: Android.onReady(cols, rows), onData(text), onResize(cols, rows), onCtrl(on), onAlt(on), onLink(url)
 * Shared with BlackLab Desktop's terminal view (desktop/web/terminal.js has the same keys). */
(function () {
  "use strict";
  var A = window.Android;
  var term = new Terminal({
    cursorBlink: true, fontSize: 13, scrollback: 6000, allowProposedApi: false, macOptionIsMeta: true,
    fontFamily: "'JetBrains Mono', 'Roboto Mono', 'Noto Sans Mono', monospace",
    theme: { background: "#0B0E13", foreground: "#E7ECF3", cursor: "#2BD9A8", cursorAccent: "#0B0E13", selectionBackground: "#2BD9A855",
             black: "#1B222C", red: "#FF5A5F", green: "#3DDC84", yellow: "#FFB020", blue: "#64B5F6", magenta: "#AB7CFF", cyan: "#2BD9A8", white: "#E7ECF3",
             brightBlack: "#5B6676", brightRed: "#FF7B80", brightGreen: "#6BF0A6", brightYellow: "#FFC85A", brightBlue: "#8AB4F8", brightMagenta: "#C3A2FF", brightCyan: "#6BEBC8", brightWhite: "#FFFFFF" }
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById("t"));
  var ctrl = false, alt = false;

  function send(s) { if (s) A.onData(s); }
  function refit() { try { fit.fit(); } catch (e) { /* not laid out yet */ } }

  term.onData(function (d) {
    if (ctrl && d.length === 1) {
      var c = d.toUpperCase().charCodeAt(0);
      if (c >= 64 && c <= 95) d = String.fromCharCode(c - 64);
      else if (d === " ") d = "\u0000";
      else if (d === "?") d = "\u007f";
      ctrl = false; A.onCtrl(false);
    }
    if (alt) { d = "\u001b" + d; alt = false; A.onAlt(false); }
    send(d);
  });
  term.onResize(function (s) { A.onResize(s.cols, s.rows); });
  if (window.ResizeObserver) new ResizeObserver(refit).observe(document.getElementById("t"));
  window.addEventListener("resize", refit);

  var KEYS = { esc: "\u001b", tab: "\t", enter: "\r", backspace: "\u007f", home: "\u001b[H", end: "\u001b[F", pgup: "\u001b[5~", pgdn: "\u001b[6~", del: "\u001b[3~" };
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
    ctrl: function (on) { ctrl = !!on; term.focus(); },
    alt: function (on) { alt = !!on; term.focus(); },
    font: function (px) { term.options.fontSize = Math.max(8, Math.min(28, px)); refit(); },
    focus: function () { term.focus(); },
    clear: function () { term.clear(); },
    size: function () { return term.cols + "x" + term.rows; }
  };
  // Tapping anywhere on the page focuses the terminal (and brings up the keyboard)
  document.addEventListener("touchend", function () { term.focus(); }, { passive: true });
  document.addEventListener("click", function () { term.focus(); });
  // Report a real size: wait until the page has a height (the app may still be laying out)
  (function ready(tries) {
    refit();
    if (term.rows < 4 && tries < 40) return setTimeout(function () { ready(tries + 1); }, 50);
    A.onReady(term.cols, term.rows);
    term.focus();
  })(0);
})();
