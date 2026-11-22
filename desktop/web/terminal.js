/* BlackLab Desktop — Terminal view (companion 1.14). The companion runs OpenSSH in a pseudo-terminal and streams it
 * (desktop/companion/terminal.py); this page is xterm.js 6.0.0 (vendor/xterm, MIT) talking to it over
 * /local/term/*. Works only when this page is served by the companion on this computer — the github.io copy can't
 * open a terminal (the companion refuses other origins). Sessions keep running when you leave the view and can be
 * re-attached (the last 1 MB of output is replayed). */
(function () {
  'use strict';
  const T = { term: null, fit: null, id: null, since: 0, reading: false, pending: '', flushTimer: null, hosts: null, open: [], error: '' };
  window.BLTerm = T;

  function b64ToBytes(b64) { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; }
  function strToB64(s) { const u = new TextEncoder().encode(s); let bin = ''; for (let i = 0; i < u.length; i++) bin += String.fromCharCode(u[i]); return btoa(bin); }

  async function loadHosts() {
    try { const r = await Companion.req('/local/term/hosts'); T.hosts = r.hosts || []; T.open = (r.open || []).filter((s) => !s.closed); T.error = ''; }
    catch (e) { T.hosts = []; T.error = e.message; }
  }

  function send(data) {
    T.pending += data;
    if (T.flushTimer) return;
    T.flushTimer = setTimeout(async () => {
      const d = T.pending; T.pending = ''; T.flushTimer = null;
      if (!T.id || !d) return;
      try { await Companion.req('/local/term/write', { id: T.id, data: strToB64(d) }); } catch (e) { T.term?.write('\r\n\x1b[33m' + e.message + '\x1b[0m\r\n'); }
    }, 8);
  }

  async function readLoop(id) {
    if (T.reading) return; T.reading = true;
    try {
      while (T.id === id && document.getElementById('termBox')) {
        let r;
        try { r = await Companion.req(`/local/term/read?id=${encodeURIComponent(id)}&since=${T.since}`); }
        catch (e) { T.term?.write('\r\n\x1b[33mLost the companion: ' + e.message + '\x1b[0m\r\n'); break; }
        if (r.data) T.term.write(b64ToBytes(r.data));
        T.since = r.next;
        if (r.closed) { T.term.write('\r\n\x1b[38;5;79m' + (r.why || 'Session ended.') + '\x1b[0m\r\n'); T.id = null; paintBar(); break; }
      }
    } finally { T.reading = false; }
  }

  function mountTerm() {
    const box = document.getElementById('termBox');
    if (!box || typeof Terminal === 'undefined') return;
    T.term?.dispose();
    T.term = new Terminal({ cursorBlink: true, fontSize: 14, scrollback: 10000, fontFamily: "'JetBrains Mono', 'IBM Plex Mono', ui-monospace, monospace",
      theme: { background: '#0B0E13', foreground: '#E7ECF3', cursor: '#2BD9A8', selectionBackground: '#2BD9A855', cyan: '#2BD9A8', green: '#3DDC84', yellow: '#FFB020', red: '#FF5A5F', blue: '#64B5F6', magenta: '#AB7CFF' } });
    T.fit = new FitAddon.FitAddon(); T.term.loadAddon(T.fit); T.term.open(box);
    try { T.fit.fit(); } catch { /* hidden */ }
    T.term.onData((d) => send(d));
    T.term.onResize(({ cols, rows }) => { if (T.id) Companion.req('/local/term/resize', { id: T.id, cols, rows }).catch(() => {}); });
    new ResizeObserver(() => { try { T.fit.fit(); } catch { /* */ } }).observe(box);
    T.term.writeln('\x1b[38;5;79mPick a host above and Connect. Sessions go over SSH from this computer; Tailscale hosts sign in with Tailscale SSH.\x1b[0m');
  }

  async function connect(body) {
    if (!T.term) mountTerm();
    T.term.reset(); T.since = 0;
    try {
      const r = await Companion.req('/local/term/open', Object.assign({ cols: T.term.cols, rows: T.term.rows }, body));
      T.id = r.id; T.term.writeln('\x1b[38;5;79mConnecting to ' + r.target + '…\x1b[0m'); paintBar(); T.term.focus(); readLoop(r.id);
    } catch (e) { T.term.writeln('\x1b[33m' + e.message + '\x1b[0m'); }
  }

  function attach(id) { if (!T.term) mountTerm(); T.term.reset(); T.id = id; T.since = 0; paintBar(); T.term.focus(); readLoop(id); }

  function paintBar() {
    const bar = document.getElementById('termState');
    if (bar) bar.innerHTML = T.id ? `<span class="pill" style="color:var(--ok, #3DDC84)">● connected</span> <button class="btn ghost" id="termClose">Disconnect</button>` : '<span class="muted">not connected</span>';
    const c = document.getElementById('termClose');
    if (c) c.onclick = async () => { const id = T.id; T.id = null; await Companion.req('/local/term/close', { id }).catch(() => {}); T.term.writeln('\r\n\x1b[38;5;79mDisconnected.\x1b[0m'); paintBar(); };
  }

  Views.terminal = () => {
    if (!Companion.ok) return `<div class="view-inner"><div class="notice"><b>The terminal runs through BlackLab Desktop on this computer.</b>
      Open <code>http://127.0.0.1:8770</code> here (or over an SSH tunnel) — the web copy of the app can't open SSH sessions.</div></div>`;
    const hosts = T.hosts || [];
    return `<div class="view-inner">
      <div class="hero"><div><h1>Terminal</h1><div class="muted">SSH from this computer · Tailscale hosts need no password (Tailscale SSH)</div></div><div class="spacer"></div><div id="termState"></div></div>
      ${T.error ? `<div class="notice">${esc(T.error)}</div>` : ''}
      <div class="row" style="gap:8px;flex-wrap:wrap;margin-bottom:10px">
        ${hosts.map((h) => `<button class="btn" data-host="${esc(h.id)}">${esc(h.name)} <span style="opacity:.8;font-weight:400">${esc(h.user)}@${esc(h.host)}${h.port !== 22 ? ":" + h.port : ""}</span></button>`).join('')}
        <input id="termTarget" placeholder="user@host or user@host:port" style="min-width:240px">
        <button class="btn primary" id="termGo">Connect</button>
        ${T.open.filter((s) => s.id !== T.id).map((s) => `<button class="btn ghost" data-attach="${esc(s.id)}">↺ ${esc(s.target)}</button>`).join('')}
      </div>
      <div id="termBox" style="height:calc(100vh - 250px);min-height:320px;background:#0B0E13;border-radius:12px;padding:8px;border:1px solid var(--line, #232D3B)"></div>
      <p class="muted" style="font-size:12px;margin-top:8px">Saved hosts: <code>ssh_hosts</code> in ~/.config/blacklab-desktop/config.json · new host keys are remembered, a changed key is refused.</p>
    </div>`;
  };

  Views.terminal.bind = () => {
    if (!Companion.ok) return;
    mountTerm(); paintBar();
    if (T.id) attach(T.id);
    if (T.hosts === null) loadHosts().then(() => { if (S.view === 'terminal') render(); });
    document.querySelectorAll('[data-host]').forEach((b) => { b.onclick = () => connect({ host_id: b.dataset.host }); });
    document.querySelectorAll('[data-attach]').forEach((b) => { b.onclick = () => attach(b.dataset.attach); });
    const go = document.getElementById('termGo'); const inp = document.getElementById('termTarget');
    if (go) go.onclick = () => { if (inp.value.trim()) connect({ target: inp.value.trim() }); };
    if (inp) inp.onkeydown = (e) => { if (e.key === 'Enter') go.click(); };
  };
})();
