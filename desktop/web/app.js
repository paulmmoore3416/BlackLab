/* BlackLab Desktop — control center for the BlackLab Android app.
 *
 * Talks to the phone three ways, picking the best available:
 *   1. Phone link   — the app's local HTTP API (USB via `adb forward`, or Wi-Fi), live long-poll sync.
 *   2. Cloud sync   — the hidden appDataFolder in your Google Drive (works from anywhere, e.g. GitHub Pages).
 *   3. Companion    — the local desktop helper (desktop/companion) for offline desktop tasks, Ollama, adb, voice.
 * No secrets live in this file or this site; pairing codes and tokens stay in this browser's storage.
 */
'use strict';

const CFG = window.BLACKLAB_CONFIG || {};
const SYNC_FILE = 'blacklab-sync.json';
const INBOX_FILE = 'blacklab-inbox.json';
const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.appdata openid email profile';

// ------------------------------------------------------------------ utilities
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const LS = {
  get(k, d) { try { const v = localStorage.getItem('bl.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('bl.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
  del(k) { try { localStorage.removeItem('bl.' + k); } catch { /* ignore */ } },
};
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
const argb = (c) => (c ? '#' + ((c >>> 0).toString(16).padStart(8, '0').slice(2)) : 'var(--accent)');
const DAY = 86400000;
const startOfDay = (t = Date.now()) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const fmtDate = (ms, opts) => new Date(ms).toLocaleDateString([], opts || { weekday: 'short', month: 'short', day: 'numeric' });
const fmtMinute = (m) => { const d = new Date(); d.setHours(Math.floor(m / 60), m % 60, 0, 0); return fmtTime(d); };
const relDay = (ms) => {
  const diff = Math.round((startOfDay(ms) - startOfDay()) / DAY);
  if (diff === 0) return 'Today'; if (diff === 1) return 'Tomorrow'; if (diff === -1) return 'Yesterday';
  if (diff > 1 && diff < 7) return fmtDate(ms, { weekday: 'long' });
  return fmtDate(ms);
};
const ago = (ms) => {
  if (!ms) return 'never'; const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return 'just now'; if (s < 3600) return Math.round(s / 60) + ' min ago'; if (s < 86400) return Math.round(s / 3600) + ' h ago';
  return Math.round(s / 86400) + ' d ago';
};
const bytes = (n) => { if (!n) return '0 B'; const u = ['B', 'KB', 'MB', 'GB', 'TB']; const i = Math.min(4, Math.floor(Math.log(n) / Math.log(1024))); return (n / 1024 ** i).toFixed(i ? 1 : 0) + ' ' + u[i]; };
const dur = (ms) => { const s = Math.round(ms / 1000); const h = Math.floor(s / 3600); const m = Math.floor(s % 3600 / 60); return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s % 60).padStart(2, '0'); };

function toast(msg, ms = 2600) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}

// ------------------------------------------------------------------ state
const S = {
  schema: LS.get('schema', null),
  values: LS.get('values', {}),
  snap: LS.get('snapshot', null),
  pending: LS.get('pending', {}),
  chat: LS.get('chat', []),
  lastSync: LS.get('lastSync', 0),
  mode: 'offline', // phone | cloud | offline
  rev: 0,
  companion: null,
  google: { token: null, exp: 0, email: LS.get('googleEmail', '') },
  cloudDoc: null,
  ui: Object.assign({ advanced: true, onlyModified: false, theme: 'auto', target: 'auto', speak: false, profile: 'ALL', model: '', refresh: 30 }, LS.get('ui', {})),
  view: 'dashboard', sub: '',
};
const saveUi = () => LS.set('ui', S.ui);
function persist() {
  LS.set('schema', S.schema); LS.set('values', S.values); LS.set('snapshot', S.snap); LS.set('lastSync', S.lastSync);
}

// ------------------------------------------------------------------ transports
const Phone = {
  get url() { return String(LS.get('phoneUrl', CFG.defaultPhoneUrl || 'http://127.0.0.1:8765')).replace(/\/$/, ''); },
  get token() { return LS.get('phoneToken', ''); },
  async req(path, opt = {}) {
    const r = await fetch(this.url + path, {
      method: opt.method || (opt.body ? 'POST' : 'GET'),
      headers: Object.assign({ Authorization: 'Bearer ' + this.token }, opt.body ? { 'Content-Type': 'application/json' } : {}),
      body: opt.body ? JSON.stringify(opt.body) : undefined,
      signal: opt.signal,
    });
    if (r.status === 401) throw new Error('Pairing code rejected — re-enter it under Connect.');
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
    return j;
  },
  async ping() {
    const c = new AbortController(); const t = setTimeout(() => c.abort(), 2500);
    try { const r = await fetch(this.url + '/api/ping', { signal: c.signal }); return r.ok; } catch { return false; } finally { clearTimeout(t); }
  },
  // Files download with the pairing code in a header (never in the URL, where it would land in history and logs)
  async download(path) {
    const r = await fetch(`${this.url}/api/file?path=${encodeURIComponent(path)}`, { headers: { Authorization: 'Bearer ' + this.token } });
    if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || 'HTTP ' + r.status); }
    const url = URL.createObjectURL(await r.blob());
    const a = Object.assign(document.createElement('a'), { href: url, download: path.split('/').pop() || 'file' });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  },
  looping: false,
  async loop() {
    if (this.looping) return; this.looping = true;
    while (S.mode === 'phone') {
      try {
        const r = await this.req(`/api/changes?since=${S.rev}&wait=25`);
        const plan = syncPlan(r, S.rev, S.snap);
        if (plan.kind === 'full') { await loadFromPhone(); S.rev = r.rev; }
        else if (plan.kind === 'partial') { await loadChanged(plan.sections); S.rev = r.rev; }
      } catch {
        await sleep(2500);
        if (!(await this.ping())) { setMode(S.google.token ? 'cloud' : 'offline'); toast('Phone link lost'); break; }
      }
    }
    this.looping = false;
  },
};

async function loadFromPhone() {
  const [values, snap] = await Promise.all([Phone.req('/api/settings'), Phone.req('/api/snapshot')]);
  if (!S.schema || !S.schemaFresh) { S.schema = (await Phone.req('/api/schema')).sections; S.schemaFresh = true; }
  // the phone's rev, not the higher of the two: after the phone app restarts its rev can be lower than ours
  S.values = values; S.snap = snap; S.rev = snap.info?.rev ?? S.rev; S.lastSync = Date.now();
  persist(); render(true);
}

/**
 * What to fetch after /api/changes answers. BlackLab 5.14+ says which sections changed, so only those
 * are rebuilt and sent; older phones (no `changed`), a rev from before the phone app started
 * (`changed: null`), a phone rev lower than ours (it restarted) or an empty cache mean a full reload.
 */
function syncPlan(r, rev, snap) {
  if (r.rev === rev) return { kind: 'none' };
  if (!snap || !Array.isArray(r.changed) || r.rev < rev) return { kind: 'full' };
  return r.changed.length ? { kind: 'partial', sections: r.changed } : { kind: 'none' };
}

/** Fetches just the changed sections (and settings values when they changed) and merges them in. */
async function loadChanged(sections) {
  const snapKeys = sections.filter((k) => k !== 'settings');
  const jobs = [];
  if (sections.includes('settings')) jobs.push(Phone.req('/api/settings').then((v) => { S.values = v; }));
  if (snapKeys.length) jobs.push(Phone.req('/api/snapshot?sections=' + encodeURIComponent(snapKeys.join(','))).then((part) => { S.snap = Object.assign({}, S.snap, part); }));
  await Promise.all(jobs);
  S.lastSync = Date.now();
  persist(); render(true);
}

async function connectPhone(silent) {
  if (!Phone.token) { if (!silent) toast('Enter the pairing code from the phone first'); return false; }
  if (Companion.ok && /127\.0\.0\.1|localhost/.test(Phone.url)) await Companion.req('/local/adb/forward', { port: Number(new URL(Phone.url).port || 8765) }).catch(() => {});
  if (!(await Phone.ping())) { if (!silent) toast('Phone not reachable at ' + Phone.url); return false; }
  try {
    await loadFromPhone();
    setMode('phone');
    if (Object.keys(S.pending).length) {
      const r = await Phone.req('/api/settings', { method: 'PUT', body: S.pending });
      S.pending = {}; LS.set('pending', {}); S.values = r.values; persist();
      toast(`Pushed ${r.applied.length} queued change(s) to the phone`);
    }
    Phone.loop();
    return true;
  } catch (e) { if (!silent) toast(e.message); return false; }
}

// A Web OAuth client ID looks like 123456789012-abc….apps.googleusercontent.com. API keys (AIza…) are a
// different credential: Google Identity Services rejects them, so sign-in and cloud sync would silently never work.
const CLIENT_ID_RE = /^\d+-[a-z0-9]+\.apps\.googleusercontent\.com$/i;
function clientIdProblem(v) {
  if (!v || CLIENT_ID_RE.test(v)) return '';
  return /^AIza/.test(v) ? 'That is an API key, not an OAuth client ID. Create a Web application OAuth client (steps above) and paste its client ID — it ends in .apps.googleusercontent.com.'
    : 'That doesn’t look like an OAuth client ID — it should end in .apps.googleusercontent.com.';
}
const Cloud = {
  client: null,
  get savedClientId() { return LS.get('googleClientId', '') || CFG.googleClientId || ''; },
  get clientId() { const v = this.savedClientId; return clientIdProblem(v) ? '' : v; },
  get valid() { return !!S.google.token && Date.now() < S.google.exp; },
  init() {
    if (!window.google?.accounts?.oauth2 || !this.clientId) return false;
    this.client = google.accounts.oauth2.initTokenClient({ client_id: this.clientId, scope: DRIVE_SCOPE, callback: (r) => this.onToken(r),
      // The popup itself failing (blocked, closed) never reaches callback
      error_callback: (e) => toast(e?.type === 'popup_failed_to_open' ? 'Google sign-in: the browser blocked the pop-up — allow pop-ups for this site and try again'
        : e?.type === 'popup_closed' ? 'Google sign-in was closed before it finished' : 'Google sign-in: ' + (e?.message || e?.type || 'failed'), 6000) });
    return true;
  },
  signIn(prompt = '') {
    if (!this.clientId) { go('connect'); toast(clientIdProblem(this.savedClientId) || 'Add your Google OAuth client ID under Connect first', 7000); return; }
    if (!this.client && !this.init()) { toast('Google sign-in is still loading — try again in a second'); return; }
    this.client.requestAccessToken({ prompt, login_hint: S.google.email || undefined });
  },
  signOut() {
    if (S.google.token && window.google?.accounts?.oauth2) google.accounts.oauth2.revoke(S.google.token, () => {});
    S.google = { token: null, exp: 0, email: '' }; LS.del('googleEmail');
    if (S.mode === 'cloud') setMode('offline');
    renderGsi(); render();
  },
  async onToken(r) {
    if (r.error) {
      const why = { access_denied: 'access was denied — add your account as a test user on the OAuth consent screen (or publish the app)',
        invalid_client: 'the OAuth client ID is wrong or deleted — check it under Connect',
        unauthorized_client: 'this client ID isn’t a Web application client',
        invalid_scope: 'the Google Drive API isn’t enabled in that Cloud project' }[r.error] || r.error;
      toast('Google sign-in: ' + why, 8000); go('connect'); return;
    }
    S.google.token = r.access_token; S.google.exp = Date.now() + (Number(r.expires_in || 3600) - 60) * 1000;
    try {
      const u = await fetch('https://openidconnect.googleapis.com/v1/userinfo', { headers: { Authorization: 'Bearer ' + r.access_token } }).then((x) => x.json());
      S.google.email = u.email || ''; S.google.name = u.name || ''; S.google.picture = u.picture || ''; LS.set('googleEmail', S.google.email);
    } catch { /* optional */ }
    renderGsi();
    await this.load(true);
    if (S.mode !== 'phone') setMode('cloud');
    this.poll();
  },
  async api(url, opt = {}) {
    if (!this.valid) throw new Error('Google sign-in expired');
    const r = await fetch(url, Object.assign({}, opt, { headers: Object.assign({ Authorization: 'Bearer ' + S.google.token }, opt.headers || {}) }));
    if (r.status === 401) { S.google.token = null; renderGsi(); throw new Error('Google sign-in expired'); }
    if (!r.ok) throw new Error('Drive ' + r.status);
    return r;
  },
  async find(name) {
    const q = encodeURIComponent(`name = '${name}'`);
    const r = await this.api(`https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id,name,modifiedTime)&q=${q}`);
    return (await r.json()).files?.[0] || null;
  },
  async read(name) {
    const f = await this.find(name); if (!f) return null;
    return (await this.api(`https://www.googleapis.com/drive/v3/files/${f.id}?alt=media`)).json();
  },
  async write(name, obj) {
    const f = await this.find(name);
    const body = JSON.stringify(obj);
    if (f) return this.api(`https://www.googleapis.com/upload/drive/v3/files/${f.id}?uploadType=media`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body });
    const b = 'bl' + uid();
    const multipart = `--${b}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify({ name, parents: ['appDataFolder'] })}\r\n--${b}\r\nContent-Type: application/json\r\n\r\n${body}\r\n--${b}--`;
    return this.api('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart', { method: 'POST', headers: { 'Content-Type': 'multipart/related; boundary=' + b }, body: multipart });
  },
  async load(loud) {
    try {
      const doc = await this.read(SYNC_FILE);
      if (!doc) { if (loud) toast('Signed in. No phone data in Drive yet — turn on Cloud sync on the phone (Settings › Assistant & desktop).', 6000); return; }
      S.cloudDoc = doc; S.schema = doc.schema; S.values = Object.assign({}, doc.settings, S.mode === 'cloud' ? this.queuedSettings || {} : {});
      S.snap = doc.snapshot; S.lastSync = doc.updatedAt || Date.now(); persist();
      // surface answers to commands queued from this browser
      const sent = LS.get('cloudSent', {});
      (doc.results || []).forEach((r) => {
        if (sent[r.id] && !sent[r.id].answered) { sent[r.id].answered = true; pushChat({ user: false, text: r.reply, source: 'Phone via cloud · ' + (r.source || '') }); }
      });
      LS.set('cloudSent', sent);
      render(true);
    } catch (e) { if (loud) toast(e.message); }
  },
  async inbox() {
    const inbox = (await this.read(INBOX_FILE).catch(() => null)) || { settings: {}, settingsTs: 0, commands: [] };
    const acks = S.cloudDoc?.acks || {};
    if ((acks.settingsTs || 0) >= (inbox.settingsTs || 0)) inbox.settings = {};
    const done = new Set(acks.commands || []);
    inbox.commands = (inbox.commands || []).filter((c) => !done.has(c.id));
    return inbox;
  },
  async queueSettings(changes) {
    const inbox = await this.inbox();
    inbox.settings = Object.assign(inbox.settings || {}, changes); inbox.settingsTs = Date.now();
    this.queuedSettings = Object.assign(this.queuedSettings || {}, changes);
    await this.write(INBOX_FILE, inbox);
  },
  async queueCommand(text) {
    const inbox = await this.inbox(); const id = uid();
    inbox.commands.push({ id, text, ts: Date.now() });
    await this.write(INBOX_FILE, inbox);
    const sent = LS.get('cloudSent', {}); sent[id] = { text, ts: Date.now() }; LS.set('cloudSent', sent);
    return id;
  },
  polling: false,
  poll() {
    if (this.polling) return; this.polling = true;
    setInterval(() => { if (S.mode === 'cloud' && this.valid) this.load(false); }, Math.max(15, S.ui.refresh) * 1000);
  },
};

const Companion = {
  base: null,
  get ok() { return !!this.base; },
  async detect() {
    const bases = [];
    if (/^https?:$/.test(location.protocol) && /^(127\.0\.0\.1|localhost)$/.test(location.hostname)) bases.push(location.origin);
    bases.push(CFG.companionUrl || 'http://127.0.0.1:8770');
    for (const b of [...new Set(bases)]) {
      try {
        const c = new AbortController(); const t = setTimeout(() => c.abort(), 1500);
        const r = await fetch(b + '/local/status', { headers: { 'X-BlackLab': '1' }, signal: c.signal });
        clearTimeout(t);
        if (r.ok) { this.base = b; S.companion = await r.json(); return true; }
      } catch { /* not running */ }
    }
    this.base = null; S.companion = null; return false;
  },
  async req(path, body) {
    const r = await fetch(this.base + path, {
      method: body ? 'POST' : 'GET',
      headers: Object.assign({ 'X-BlackLab': '1' }, body ? { 'Content-Type': 'application/json' } : {}),
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || 'Companion ' + r.status);
    return r.headers.get('content-type')?.includes('json') ? r.json() : r.blob();
  },
};

function setMode(m) {
  S.mode = m;
  const b = $('#connBadge');
  b.className = 'conn-badge' + (m === 'phone' ? ' on' : m === 'cloud' ? ' cloud' : '');
  b.textContent = m === 'phone' ? '● Live · phone link' : m === 'cloud' ? '● Cloud · Google Drive' : '○ Offline · cached data';
  $('#brandSub').textContent = S.companion ? 'Desktop · companion on' : (location.hostname.endsWith('github.io') ? 'Web' : 'Desktop');
  renderSyncState();
  render(true);
}
function renderSyncState() {
  const p = Object.keys(S.pending).length + Object.keys(Cloud.queuedSettings || {}).length;
  $('#syncState').textContent = (S.lastSync ? 'Synced ' + ago(S.lastSync) : 'Never synced') + (p ? ` · ${p} queued` : '');
}

// ------------------------------------------------------------------ settings writes
async function setSetting(key, value) {
  S.values[key] = value; persist();
  try {
    if (S.mode === 'phone') {
      const r = await Phone.req('/api/settings', { method: 'PUT', body: { [key]: value } });
      if ((r.refused || []).includes(key)) toast('Security and link settings change only over USB or on the phone');
      else if (!r.applied.includes(key)) toast('The phone rejected that value'); else toast('Saved to phone');
      S.values = r.values; persist();
    } else if (S.mode === 'cloud') {
      await Cloud.queueSettings({ [key]: value });
      toast('Queued in Drive — the phone applies it on its next sync');
    } else {
      S.pending[key] = value; LS.set('pending', S.pending);
      toast('Saved here — pushes to the phone when it connects');
    }
  } catch (e) { toast(e.message); }
  renderSyncState();
}

async function setMany(changes, label) {
  Object.assign(S.values, changes); persist();
  try {
    if (S.mode === 'phone') { const r = await Phone.req('/api/settings', { method: 'PUT', body: changes }); S.values = r.values; persist(); toast(`${label}: ${r.applied.length} setting(s) applied`); }
    else if (S.mode === 'cloud') { await Cloud.queueSettings(changes); toast(`${label}: queued in Drive`); }
    else { Object.assign(S.pending, changes); LS.set('pending', S.pending); toast(`${label}: saved here, will push on connect`); }
  } catch (e) { toast(e.message); }
  renderSyncState(); render(true);
}

// ------------------------------------------------------------------ assistant
function pushChat(m) {
  S.chat.push(Object.assign({ id: uid(), time: Date.now() }, m));
  S.chat = S.chat.slice(-300); LS.set('chat', S.chat);
  if (S.view === 'assistant') renderChatLog();
}

const LOCAL_HELP = `I'm running in your browser right now. Connect the phone (or sign in with Google) and I can do everything the phone assistant does. Locally I can:
• open any page — “open settings”, “open calendar”, “show camera settings”
• switch theme — “dark mode” / “light mode”
• find settings — “search settings for voice”`;

function localAssistant(text) {
  const t = text.toLowerCase().trim();
  if (/^(help|what can you do)/.test(t)) return LOCAL_HELP;
  const nav = t.match(/^(?:open|go to|show)(?: the| my)? ([\w-]+(?: files| tools)?)$/);
  if (nav) { const v = NAV.find((n) => n.id.startsWith(nav[1]) || n.label.toLowerCase().startsWith(nav[1])); if (v) { go(v.id); return 'Opening ' + v.label + '.'; } }
  const sec = t.match(/(?:show|open) (\w+) settings/);
  if (sec && S.schema) { const s = S.schema.find((x) => x.title.toLowerCase().includes(sec[1])); if (s) { go('settings', s.id); return 'Here are the ' + s.title + ' settings.'; } }
  if (/dark mode/.test(t)) { setTheme('dark'); return 'Dark mode on.'; }
  if (/light mode/.test(t)) { setTheme('light'); return 'Light mode on.'; }
  const ss = t.match(/search settings for (.+)/); if (ss) { go('settings'); setTimeout(() => { const i = $('#setSearch'); if (i) { i.value = ss[1]; i.dispatchEvent(new Event('input')); } }, 50); return 'Searching settings.'; }
  return null;
}

async function ask(text) {
  text = text.trim(); if (!text) return;
  pushChat({ user: true, text });
  const target = S.ui.target;
  // Pages of this UI are opened here rather than sent anywhere
  if (/^(open|go to|show)( the| my)? (dashboard|assistant|tasks|to-?do|schedule|calendar|documents|education|journal|recordings|phone files|settings|connect|desktop tools)$/i.test(text) ||
      /^(show|open) \w+ settings$|^search settings for /i.test(text)) {
    const local = localAssistant(text);
    if (local) { pushChat({ user: false, text: local, source: 'This browser' }); return; }
  }
  try {
    let r = null;
    if (Companion.ok && target !== 'phone') {
      r = await Companion.req('/local/assistant', { text, target, model: S.ui.model || undefined, phone: S.mode === 'phone' ? { url: Phone.url, token: Phone.token } : null });
    } else if (S.mode === 'phone') {
      r = await Phone.req('/api/assistant', { body: { text } });
    } else if (S.mode === 'cloud') {
      await Cloud.queueCommand(text);
      r = { text: 'Queued in your Drive. The phone runs it on its next cloud sync (open BlackLab or wait for the background sync) and the answer shows up here.', source: 'Cloud inbox' };
    } else {
      r = { text: localAssistant(text) || 'Not connected to the phone. Pair it under Connect, sign in with Google, or start the desktop companion.', source: 'This browser' };
    }
    pushChat({ user: false, text: r.text, source: r.source, links: r.links || [] });
    if (S.ui.speak) speak(r.text);
    if (S.mode === 'phone') setTimeout(() => loadFromPhone().catch(() => {}), 400);
  } catch (e) {
    pushChat({ user: false, text: 'Hit a snag: ' + e.message, source: 'Error' });
  }
}

let audio = null;
async function speak(text) {
  const clean = text.replace(/[•*_#`]/g, '').slice(0, 600);
  try {
    if (Companion.ok && S.companion?.eleven && S.ui.voiceEngine !== 'browser') {
      const blob = await Companion.req('/local/tts', { text: clean });
      if (blob instanceof Blob && blob.size > 0) { audio?.pause(); audio = new Audio(URL.createObjectURL(blob)); await audio.play(); return; }
    }
  } catch { /* fall back */ }
  if ('speechSynthesis' in window) {
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(clean); u.rate = 1; u.pitch = 0.9;
    const v = speechSynthesis.getVoices().find((x) => /en-US/i.test(x.lang) && /male|guy|david|alex|daniel/i.test(x.name)); if (v) u.voice = v;
    speechSynthesis.speak(u);
  }
}

// ------------------------------------------------------------------ navigation
const NAV = [
  { id: 'dashboard', label: 'Dashboard', ico: '◧' },
  { id: 'timeline', label: 'Timeline', ico: '◷' },
  { id: 'assistant', label: 'Assistant', ico: '✦' },
  { id: 'tasks', label: 'To-Do', ico: '✓' },
  { id: 'calendar', label: 'Schedule', ico: '▦' },
  { id: 'documents', label: 'Documents', ico: '❏' },
  { id: 'brainstorm', label: 'Brainstorm', ico: '✧' },
  { id: 'learning', label: 'Education', ico: '◎' },
  { id: 'knowledge', label: 'Knowledge', ico: '❖' },
  { id: 'summarize', label: 'Summarize', ico: '≡' },
  { id: 'journal', label: 'Journal', ico: '✎' },
  { id: 'media', label: 'Recordings', ico: '◉' },
  { id: 'files', label: 'Phone files', ico: '▤' },
  { id: 'bh', label: 'Behavioral Health', ico: '✚' },
  { id: 'facility', label: 'Facility', ico: '⌸' },
  { id: 'garage', label: 'Garage', ico: '⛭' },
  { id: 'vault', label: 'Obsidian vault', ico: '◈' },
  { sep: true },
  { id: 'health', label: 'Health', ico: '♡' },
  { id: 'appearance', label: 'Appearance', ico: '◐' },
  { id: 'settings', label: 'Settings', ico: '⚙' },
  { id: 'desktop', label: 'Desktop tools', ico: '⌂' },
  { id: 'connect', label: 'Connect', ico: '⇄' },
];

function go(view, sub = '') { location.hash = '#/' + view + (sub ? '/' + encodeURIComponent(sub) : ''); }
function readHash() {
  const [, v, s] = (location.hash || '#/dashboard').split('/');
  S.view = NAV.some((n) => n.id === v) ? v : 'dashboard'; S.sub = s ? decodeURIComponent(s) : '';
}
function renderNav() {
  $('#nav').innerHTML = NAV.map((n) => n.sep ? '<div class="sep"></div>' :
    `<a href="#/${n.id}" class="${S.view === n.id ? 'active' : ''}"><span class="ico">${n.ico}</span>${n.label}</a>`).join('');
}

// ------------------------------------------------------------------ data helpers
const D = {
  get info() { return S.snap?.info || {}; },
  prof(list) { return (list || []).filter((x) => S.ui.profile === 'ALL' || !x.profile || x.profile === S.ui.profile); },
  get tasks() { return this.prof(S.snap?.tasks); },
  get events() { return this.prof(S.snap?.events); },
  profileName(p) { return (this.info.profiles || {})[p] || (p === 'PERSONAL' ? 'Personal' : 'Professional'); },
};

function profileSwitch() {
  const opts = [['ALL', 'Both accounts'], ['PERSONAL', D.profileName('PERSONAL')], ['PROFESSIONAL', D.profileName('PROFESSIONAL')]];
  return `<select id="profSel" title="Account">${opts.map(([v, l]) => `<option value="${v}" ${S.ui.profile === v ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>`;
}
function bindProfile() { const s = $('#profSel'); if (s) s.onchange = () => { S.ui.profile = s.value; saveUi(); render(); }; }

function needData() {
  if (S.snap) return '';
  return `<div class="notice"><b>No phone data yet.</b> Pair the phone over USB/Wi-Fi or sign in with Google to load your tasks, calendar and settings. <a href="#/connect">Connect →</a></div>`;
}

// ------------------------------------------------------------------ views
const Views = {};

Views.dashboard = () => {
  const now = Date.now(); const today = startOfDay(); const i = D.info;
  const open = D.tasks.filter((t) => !t.completed);
  const overdue = open.filter((t) => t.overdue);
  const dueToday = open.filter((t) => t.dueDay != null && startOfDay(t.dueDay) === today);
  const evToday = D.events.filter((e) => e.start < today + DAY && e.end >= today).sort((a, b) => a.start - b.start);
  const next = D.events.filter((e) => e.start > now).sort((a, b) => a.start - b.start)[0];
  const deadlines = D.prof(S.snap?.assignments).filter((a) => a.dueAt && a.dueAt > now - DAY).slice(0, 6);
  const certs = D.prof(S.snap?.certifications).filter((c) => c.expiresDay && c.expiresDay - now < 120 * DAY && c.expiresDay > now - 30 * DAY);
  const docs = D.prof(S.snap?.documents).slice(0, 6);
  const name = (i.user?.name || '').split(' ')[0];
  const hr = new Date().getHours();
  return `<div class="view-inner">
    <div class="hero"><div><h1>${hr < 12 ? 'Good morning' : hr < 17 ? 'Good afternoon' : 'Good evening'}${name ? ', ' + esc(name) : ''}</h1>
      <div class="muted">${fmtDate(now, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })}${i.device ? ` · ${esc(i.device)} · Android ${esc(i.android)}` : ''}</div></div>
      <div class="spacer"></div>${profileSwitch()}</div>
    ${needData()}
    <div class="grid g4" style="margin-bottom:16px;grid-template-columns:repeat(auto-fit,minmax(200px,1fr))">
      <div class="tile"><span class="k">Open tasks</span><span class="v">${open.length}</span><span class="s">${overdue.length ? `<span style="color:var(--danger)">${overdue.length} overdue</span>` : 'none overdue'} · ${dueToday.length} today</span></div>
      <div class="tile"><span class="k">Today</span><span class="v">${evToday.length}</span><span class="s">${next ? 'Next: ' + esc(next.title) + ' ' + relDay(next.start).toLowerCase() + ' ' + fmtTime(next.start) : 'Nothing upcoming'}</span></div>
      <div class="tile"><span class="k">Phone</span><span class="v">${i.battery >= 0 ? i.battery + '%' : '—'}</span><span class="s">${i.storageFree ? bytes(i.storageFree) + ' free' : 'not connected'}${i.charging ? ' · charging' : ''}</span></div>
      ${i.weather ? `<div class="tile"><span class="k">Weather${i.weather.place ? ' · ' + esc(i.weather.place) : ''}</span><span class="v">${Math.round(i.weather.temp)}°</span><span class="s">${esc(i.weather.condition)} · H ${Math.round(i.weather.high)}° L ${Math.round(i.weather.low)}°${(i.weather.alerts || []).length ? ` · <span style="color:var(--warn)">⚠ ${esc(i.weather.alerts[0].title)}</span>` : ''}</span></div>` : ''}
      ${S.snap?.vehicle && !S.snap.vehicle.error ? (() => { const c = S.snap.vehicle; const n = (c.due || []).find((d) => d.status === 'OVERDUE' || d.status === 'DUE_SOON') || (c.due || []).find((d) => d.status === 'OK'); const f = (c.live || []).find((m) => m.key === 'fuel_pct');
        return `<a class="tile" href="#/garage" style="text-decoration:none;color:inherit"><span class="k">Car</span><span class="v" style="font-size:20px">${c.odometerEstimated ? '≈ ' : ''}${mi(c.odometer)} mi</span><span class="s">${f ? esc(f.value) + ' fuel · ' : ''}${n ? esc(n.name) + ' ' + esc(n.text) : 'nothing due'}</span></a>`; })() : ''}
      ${S.snap?.facility ? (() => { const c = S.snap.facility.counts || {}; return `<a class="tile" href="#/facility" style="text-decoration:none;color:inherit"><span class="k">Facility</span><span class="v" style="${c.overdue ? 'color:var(--danger)' : ''}">${c.overdue || 0}</span><span class="s">overdue · ${c.dueSoon || 0} due soon · ${(S.snap.tasks || []).filter((t) => !t.completed && isWorkOrder(t)).length} open WOs</span></a>`; })() : ''}
      ${S.snap?.health ? `<a class="tile" href="#/health" style="text-decoration:none;color:inherit"><span class="k">Health</span><span class="v" style="font-size:20px">${esc(S.snap.health.summary)}</span><span class="s">${(S.snap.health.checks || []).length} checks on the phone</span></a>` : ''}
      <div class="tile"><span class="k">Assistant</span><span class="v" style="font-size:20px">${esc(i.assistant?.name || 'BlackLab')}</span><span class="s">${i.assistant?.nano === 'AVAILABLE' ? 'Gemini Nano ready' : 'Rules engine'} · voice ${i.assistant ? (i.assistant.elevenCharsUsed || 0) + '/' + (i.assistant.elevenBudget || 0) : '—'}</span></div>
    </div>
    ${whatsNew()}
    <div class="grid g3">
      <div class="card span2"><h2>Today<span class="spacer"></span><a href="#/calendar" class="small">Schedule →</a></h2>
        ${evToday.length ? `<ul class="list">${evToday.map((e) => `<li><span class="bar" style="background:${argb(e.color)}"></span><span class="meta" style="width:74px">${e.allDay ? 'All day' : fmtTime(e.start)}</span><span class="t">${esc(e.title)}${e.location ? ` <span class="muted">· ${esc(e.location)}</span>` : ''}</span><span class="chip">${esc(e.calendar || '')}</span></li>`).join('')}</ul>` : '<div class="empty">Nothing on the calendar today.</div>'}
      </div>
      <div class="card"><h2>Ask</h2>
        <div class="stack"><textarea id="quickAsk" placeholder="Remind me to call the chiller vendor tomorrow 9am…"></textarea>
        <div class="row wrap">${['Brief me', "What's due?", 'Action items from my last meeting'].map((q) => `<span class="chip btn" data-q="${esc(q)}">${esc(q)}</span>`).join('')}</div>
        <button id="quickAskBtn">Send to assistant</button></div>
      </div>
      <div class="card"><h2>Focus<span class="spacer"></span><a href="#/tasks" class="small">To-Do →</a></h2>${taskList([...overdue, ...dueToday.filter((t) => !t.overdue)].slice(0, 8), 'No tasks due today.')}</div>
      <div class="card"><h2>Deadlines & renewals</h2>
        ${deadlines.length || certs.length ? `<ul class="list">${deadlines.map((a) => `<li><span class="t">${esc(a.title)} <span class="muted">· ${esc(a.course)}</span></span><span class="meta">${relDay(a.dueAt)}</span></li>`).join('')}
        ${certs.map((c) => `<li><span class="t">${esc(c.name)}</span><span class="chip warn">renews ${relDay(c.expiresDay).toLowerCase()}</span></li>`).join('')}</ul>` : '<div class="empty">Nothing coming up.</div>'}
      </div>
      <div class="card"><h2>Recent documents<span class="spacer"></span><a href="#/documents" class="small">All →</a></h2>
        ${docs.length ? `<ul class="list">${docs.map((d) => `<li><span class="t">${esc(d.title)}</span><span class="meta">${ago(d.updatedAt)}</span></li>`).join('')}</ul>` : '<div class="empty">No documents yet.</div>'}
      </div>
    </div></div>`;
};
Views.dashboard.bind = () => {
  bindProfile(); bindTaskChecks();
  const nd = $('#newDismiss'); if (nd) nd.onclick = () => { S.ui.seenNew = window.BL_DATA?.version || ''; saveUi(); render(); };
  $$('[data-q]').forEach((c) => c.onclick = () => { go('assistant'); ask(c.dataset.q); });
  $('#quickAskBtn').onclick = () => { const v = $('#quickAsk').value; if (v.trim()) { go('assistant'); ask(v); } };
};

function whatsNew() {
  if (S.ui.seenNew === (window.BL_DATA?.version || '')) return '';
  return `<div class="card" style="margin-bottom:16px"><h2>New in BlackLab ${esc(window.BL_DATA?.version || '')}<span class="spacer"></span><button class="ghost small" id="newDismiss">Got it</button></h2>
    <div class="grid g3">
      <div><h3>Glass, aurora & 17 palettes</h3><div class="muted small">Frosted glass over a moving backdrop in your palette colors, five light-mode styles, Stronger contrast, and eight new palettes — on the phone and here. <a href="#/appearance">Appearance →</a></div></div>
      <div><h3>Behavioral Health</h3><div class="muted small">MHT & Peer Support tools on the phone: shift hub, observation rounds, peer notes, groups, safety rounds. Reference library here. <a href="#/bh">Open →</a></div></div>
      <div><h3>Readable everywhere</h3><div class="muted small">Accent text is checked against WCAG AA (AAA with Stronger contrast) on every background, and color changes blend smoothly.</div></div>
    </div></div>`;
}

function taskList(list, empty) {
  if (!list.length) return `<div class="empty">${empty}</div>`;
  return `<ul class="list">${list.map((t) => `<li>
    <button class="check ${t.completed ? 'done' : ''}" data-task="${t.id}" data-done="${t.completed}" title="${t.completed ? 'Reopen' : 'Complete'}" aria-label="Toggle ${esc(t.title)}"></button>
    <span class="t" style="${t.completed ? 'text-decoration:line-through;opacity:.6' : ''}">${t.important ? '★ ' : ''}${esc(t.title)}</span>
    ${t.priority >= 3 ? `<span class="chip danger">${t.priority === 4 ? 'urgent' : 'high'}</span>` : ''}
    ${t.list ? `<span class="chip">${esc(t.list)}</span>` : ''}
    <span class="meta" style="${t.overdue ? 'color:var(--danger)' : ''}">${t.dueDay != null ? relDay(t.dueDay) + (t.dueMinute != null ? ' ' + fmtMinute(t.dueMinute) : '') : ''}</span></li>`).join('')}</ul>`;
}
function bindTaskChecks() {
  $$('[data-task]').forEach((b) => b.onclick = async () => {
    const id = b.dataset.task; const done = b.dataset.done === 'true';
    const t = S.snap.tasks.find((x) => String(x.id) === id);
    try {
      if (S.mode === 'phone') { await Phone.req(`/api/tasks/${id}/${done ? 'reopen' : 'complete'}`, { body: {} }); await loadFromPhone(); }
      else if (S.mode === 'cloud') { await Cloud.queueCommand((done ? 'reopen ' : 'complete ') + t.title); t.completed = !done; render(); toast('Queued for the phone'); }
      else toast('Connect the phone to change tasks');
    } catch (e) { toast(e.message); }
  });
}

// ---------------- facility, timeline, health (from the phone's snapshot — BlackLab 5.13.1+)
function olderPhone(section, name) {
  if (!S.snap || S.snap[section]) return '';
  return `<div class="notice">The phone didn't send ${name}. Update the phone to BlackLab 5.13.1 or later, then reconnect.</div>`;
}
const statusChip = (st) => st === 'OVERDUE' ? '<span class="chip danger">Overdue</span>' : st === 'DUE_SOON' ? '<span class="chip warn">Due soon</span>' : '<span class="chip">OK</span>';
// Same rule as the phone's DayTimeline.isWorkOrder.
const isWorkOrder = (t) => /work-?order/i.test(t.tags || '') || /^WO[ :]/.test(t.title || '');

Views.facility = () => {
  const f = S.snap?.facility;
  if (!f) return `<div class="view-inner"><div class="hero"><div><h1>Facility</h1></div></div>${needData()}${olderPhone('facility', 'facility data')}</div>`;
  const c = f.counts || {};
  const q = (S.ui.facQ || '').toLowerCase();
  const kind = S.ui.facKind || '';
  const kinds = [...new Set((f.assets || []).map((a) => a.kind))].sort();
  const assets = (f.assets || []).filter((a) => (!kind || a.kind === kind) && (!q || [a.name, a.tag, a.location, a.make, a.model, a.serial].join(' ').toLowerCase().includes(q)));
  const attention = (f.due || []).filter((d) => d.status !== 'OK');
  const upcoming = (f.due || []).filter((d) => d.status === 'OK').slice(0, 8);
  const wos = (S.snap.tasks || []).filter((t) => !t.completed && isWorkOrder(t)).sort((a, b) => (b.priority - a.priority) || ((a.dueDay ?? Infinity) - (b.dueDay ?? Infinity)));
  const worst = (a) => (a.pms || []).reduce((w, p) => (p.status === 'OVERDUE' ? 'OVERDUE' : w === 'OVERDUE' ? w : p.status === 'DUE_SOON' ? 'DUE_SOON' : w), 'OK');
  const next = (a) => (a.pms || []).slice().sort((x, y) => x.due - y.due)[0];
  return `<div class="view-inner">
    <div class="hero"><div><h1>Facility</h1><div class="muted">${esc(f.site || 'Assets, inspections and work orders from the phone')}</div></div></div>
    ${needData()}
    <div class="grid g4" style="margin-bottom:16px;grid-template-columns:repeat(auto-fit,minmax(170px,1fr))">
      <div class="tile"><span class="k">Assets</span><span class="v">${c.assets || 0}</span><span class="s">${kinds.length} kind${kinds.length === 1 ? '' : 's'}</span></div>
      <div class="tile"><span class="k">Overdue</span><span class="v" style="${c.overdue ? 'color:var(--danger)' : ''}">${c.overdue || 0}</span><span class="s">inspections / PMs</span></div>
      <div class="tile"><span class="k">Due soon</span><span class="v" style="${c.dueSoon ? 'color:var(--warn)' : ''}">${c.dueSoon || 0}</span><span class="s">within their warning window</span></div>
      <div class="tile"><span class="k">Open work orders</span><span class="v">${wos.length}</span><span class="s">${c.fails30 || 0} failed check${c.fails30 === 1 ? '' : 's'} in 30 days</span></div>
    </div>
    <div class="grid g2">
      <div class="card"><h2>Needs attention</h2>${attention.length ? `<ul class="list">${attention.map((d) => `<li><span class="t">${esc(d.pm)}<br><span class="muted small">${esc(d.asset)}${d.standard ? ' · ' + esc(d.standard) : ''} — ${esc(d.text)}</span></span><span class="meta">${fmtDate(d.due, { month: 'short', day: 'numeric' })}</span>${statusChip(d.status)}</li>`).join('')}</ul>` : '<div class="empty">Every inspection is current.</div>'}
        ${upcoming.length ? `<h3 class="small muted" style="margin:12px 0 4px">Coming up</h3><ul class="list">${upcoming.map((d) => `<li><span class="t">${esc(d.pm)} <span class="muted">· ${esc(d.asset)}</span></span><span class="meta">${esc(d.text)}</span></li>`).join('')}</ul>` : ''}</div>
      <div class="card"><h2>Open work orders<span class="spacer"></span><a href="#/tasks" class="small">To-Do →</a></h2>${wos.length ? `<ul class="list">${wos.slice(0, 15).map((t) => `<li><span class="t">${esc(t.title)}${t.notes ? `<br><span class="muted small">${esc(t.notes.slice(0, 140))}</span>` : ''}</span>${t.overdue ? '<span class="chip danger">overdue</span>' : t.dueDay ? `<span class="meta">${relDay(t.dueDay)}</span>` : ''}</li>`).join('')}</ul>` : '<div class="empty">No open work orders. Say “work order: …” to the assistant to add one.</div>'}</div>
      <div class="card span2"><h2>Assets<span class="spacer"></span>
          <select id="facKind" title="Kind" style="font-size:14px;font-weight:400"><option value="">All kinds</option>${kinds.map((k) => `<option ${k === kind ? 'selected' : ''}>${esc(k)}</option>`).join('')}</select>
          <input id="facQ" placeholder="Search name, tag, location…" value="${esc(S.ui.facQ || '')}" style="margin-left:8px;width:220px;font-size:14px;font-weight:400"></h2>
        ${assets.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Asset</th><th>Kind</th><th>Tag</th><th>Location</th><th>Next</th><th>Status</th></tr></thead><tbody>
          ${assets.map((a) => { const n = next(a); return `<tr><td>${esc(a.name)}${a.make || a.model ? `<br><span class="muted small">${esc([a.make, a.model].filter(Boolean).join(' '))}${a.serial ? ' · S/N ' + esc(a.serial) : ''}</span>` : ''}</td><td>${esc(a.kind)}</td><td class="mono">${esc(a.tag)}</td><td>${esc(a.location)}</td>
            <td>${n ? esc(n.title) + `<br><span class="muted small">${fmtDate(n.due, { month: 'short', day: 'numeric', year: 'numeric' })}</span>` : '<span class="muted">No schedule</span>'}</td><td>${(a.pms || []).length ? statusChip(worst(a)) : ''}</td></tr>`; }).join('')}</tbody></table></div>`
          : `<div class="empty">${(f.assets || []).length ? 'No assets match.' : 'No assets yet — add them on the phone (Facility).'}</div>`}</div>
      <div class="card span2"><h2>Log · last 90 days</h2>${(f.log || []).length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Day</th><th>Asset</th><th>Kind</th><th>Result</th><th>Notes</th><th>By</th></tr></thead><tbody>
        ${f.log.slice(0, 60).map((e) => `<tr><td>${fmtDate(e.day, { month: 'short', day: 'numeric' })}</td><td>${esc(e.asset)}${e.pmTitle ? `<br><span class="muted small">${esc(e.pmTitle)}</span>` : ''}</td><td>${esc(e.kindLabel)}</td>
          <td>${e.result === 'FAIL' ? '<span class="chip danger">Fail</span>' : e.result === 'PASS' ? '<span class="chip">Pass</span>' : e.result === 'NA' ? '<span class="chip">N/A</span>' : ''}</td><td>${esc(e.text)}</td><td>${esc(e.by)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">Nothing logged in the last 90 days.</div>'}</div>
    </div></div>`;
};
Views.facility.bind = () => {
  const k = $('#facKind'); if (k) k.onchange = () => { S.ui.facKind = k.value; saveUi(); render(); };
  const q = $('#facQ'); if (q) q.oninput = () => { S.ui.facQ = q.value; saveUi(); const pos = q.selectionStart; render(); const n = $('#facQ'); if (n) { n.focus(); n.setSelectionRange(pos, pos); } };
};

Views.timeline = () => {
  const t = S.snap?.timeline;
  if (!t) return `<div class="view-inner"><div class="hero"><div><h1>Timeline</h1></div></div>${needData()}${olderPhone('timeline', 'the day’s timeline')}</div>`;
  const items = t.items || [];
  const stale = S.snap.generatedAt && startOfDay(S.snap.generatedAt) !== startOfDay();
  return `<div class="view-inner">
    <div class="hero"><div><h1>Timeline</h1><div class="muted">${fmtDate(new Date(t.day + 'T12:00').getTime(), { weekday: 'long', month: 'long', day: 'numeric' })} · from the phone ${S.snap.generatedAt ? ago(S.snap.generatedAt) : ''}</div></div></div>
    ${stale ? '<div class="notice" style="margin-bottom:16px">This is an earlier day — reconnect the phone for today’s timeline.</div>' : ''}
    <div class="grid g2">
      <div class="card"><h2>The day</h2>${items.length ? `<ul class="list">${items.map((i) => `<li><span class="meta" style="width:74px">${fmtTime(i.time)}</span><span class="t">${esc(i.emoji)} ${esc(i.title)}${i.detail ? `<br><span class="muted small">${esc(i.detail)}</span>` : ''}</span><span class="chip">${esc(i.label)}</span></li>`).join('')}</ul>` : '<div class="empty">Nothing yet today.</div>'}</div>
      <div class="card"><h2>Ops handoff<span class="spacer"></span><button class="ghost small" id="copyHandoff">Copy</button></h2>
        <pre id="handoffTxt" style="white-space:pre-wrap;margin:0;font-family:inherit">${esc(t.handoff || '')}</pre>
        <div class="muted small" style="margin-top:8px">The same handoff the phone builds. It never includes Behavioral Health.</div></div>
    </div></div>`;
};
Views.timeline.bind = () => {
  const b = $('#copyHandoff'); if (b) b.onclick = async () => { try { await navigator.clipboard.writeText(S.snap.timeline.handoff || ''); toast('Handoff copied'); } catch { toast('Couldn’t copy — select the text instead'); } };
};

Views.health = () => {
  const h = S.snap?.health;
  if (!h) return `<div class="view-inner"><div class="hero"><div><h1>Health</h1></div></div>${needData()}${olderPhone('health', 'its health checks')}</div>`;
  const tone = { BAD: ['danger', 'Problem'], WARN: ['warn', 'Warning'], INFO: ['pro', 'Note'], OK: ['', 'OK'] };
  return `<div class="view-inner">
    <div class="hero"><div><h1>Health</h1><div class="muted">${esc(h.summary)} · checked on the phone ${S.snap.generatedAt ? ago(S.snap.generatedAt) : ''}</div></div></div>
    <div class="card"><ul class="list">${(h.checks || []).map((c) => `<li><span class="t">${esc(c.title)}<br><span class="muted small">${esc(c.detail)}${c.fix ? ` — on the phone: <b>${esc(c.fix)}</b>` : ''}</span></span><span class="chip ${tone[c.level]?.[0] || ''}">${tone[c.level]?.[1] || esc(c.level)}</span></li>`).join('')}</ul></div>
  </div>`;
};

// ---------------- garage (the car, from the phone's snapshot: profile, what's due, fuel, trips, live values, recalls)
const mi = (n) => (n == null ? '—' : Math.round(n).toLocaleString());
function svgBars(rows, opts) {
  // rows: [{label, parts:[{v, cls}], title}] — stacked bars, 4px rounded tops, 2px gaps, one dashed gridline
  const W = 560, H = 140, pad = 18, n = rows.length || 1, slot = (W - 8) / n, bw = slot * 0.56;
  const max = Math.max(1, ...rows.map((r) => r.parts.reduce((a, p) => a + p.v, 0)));
  const bars = rows.map((r, i) => {
    let y = H - pad; const x = 4 + i * slot + (slot - bw) / 2;
    const segs = r.parts.map((p, j) => {
      const h = p.v / max * (H - pad - 8); if (h < 0.5) return '';
      const top = j === r.parts.length - 1 || r.parts.slice(j + 1).every((q) => q.v <= 0);
      y -= h; const rr = top ? Math.min(4, h, bw / 2) : 0;
      const d = `M${x},${y + h} L${x},${y + rr} Q${x},${y} ${x + rr},${y} L${x + bw - rr},${y} Q${x + bw},${y} ${x + bw},${y + rr} L${x + bw},${y + h} Z`;
      const out = `<path d="${d}" class="${p.cls}"/>`; y -= 2; return out;
    }).join('');
    return `<g class="bar-g"><title>${esc(r.title)}</title><rect x="${4 + i * slot}" y="0" width="${slot}" height="${H}" fill="transparent"/>${segs}
      <text x="${4 + i * slot + slot / 2}" y="${H - 3}" text-anchor="middle" class="ax">${esc(i % 2 === (n - 1) % 2 ? r.label : '')}</text></g>`;
  }).join('');
  return `<svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="${esc(opts.aria)}"><line x1="0" x2="${W}" y1="8" y2="8" class="grid" stroke-dasharray="4 4"/>
    <line x1="0" x2="${W}" y1="${H - pad}" y2="${H - pad}" class="grid"/>${bars}</svg><div class="muted small">Top line: ${esc(opts.top(max))}</div>`;
}
function svgLine(pts, aria) {
  const W = 560, H = 120; if (pts.length < 2) return '';
  const lo = Math.min(...pts.map((p) => p[1])) - 2, hi = Math.max(...pts.map((p) => p[1])) + 2;
  const x = (i) => 10 + i * (W - 20) / (pts.length - 1), y = (v) => H - 8 - (v - lo) / (hi - lo) * (H - 16);
  const avg = pts.reduce((a, p) => a + p[1], 0) / pts.length;
  return `<svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="${esc(aria)}"><line x1="0" x2="${W}" y1="${y(avg)}" y2="${y(avg)}" class="grid" stroke-dasharray="4 4"/>
    <polyline points="${pts.map((p, i) => x(i) + ',' + y(p[1])).join(' ')}" class="ln"/>
    ${pts.map((p, i) => `<g><title>${esc(fmtDate(p[0]))}: ${p[1].toFixed(1)} mpg</title><circle cx="${x(i)}" cy="${y(p[1])}" r="12" fill="transparent"/><circle cx="${x(i)}" cy="${y(p[1])}" r="4" class="dot"/></g>`).join('')}</svg>
    <div class="muted small">Average ${avg.toFixed(1)} mpg (dashed) · best ${Math.max(...pts.map((p) => p[1])).toFixed(1)} · lowest ${Math.min(...pts.map((p) => p[1])).toFixed(1)}</div>`;
}
Views.garage = () => {
  const c = S.snap?.vehicle;
  if (!c || c.error) return `<div class="view-inner"><div class="hero"><div><h1>Garage</h1></div></div>${needData()}${c?.error ? `<div class="notice">The phone couldn't share car data: ${esc(c.error)}</div>` : ''}
    ${S.snap && !c ? '<div class="notice">Update the phone to BlackLab 5.8 to see your car here.</div>' : ''}</div>`;
  const t = c.totals || {}; const fs = c.fuelStats || {};
  const fuel = (c.live || []).find((m) => m.key === 'fuel_pct'); const range = (c.live || []).find((m) => m.key === 'range');
  const due = (c.due || []).filter((d) => d.status !== 'UNKNOWN');
  const tone = (st) => (st === 'OVERDUE' ? 'danger' : st === 'DUE_SOON' ? 'warn' : '');
  const groups = {}; (c.live || []).forEach((m) => { (groups[m.group || 'Other'] = groups[m.group || 'Other'] || []).push(m); });
  const openRecalls = (c.recalls || []).filter((r) => !r.resolved);
  const weekly = (c.weekly || []).map(([d, b, o]) => ({ label: fmtDate(d, { month: 'short', day: 'numeric' }), parts: [{ v: o, cls: 'b1' }, { v: b, cls: 'b2' }], title: `Week of ${fmtDate(d, { month: 'short', day: 'numeric' })}: ${Math.round(b + o)} mi${b > 0 ? ' · ' + Math.round(b) + ' business' : ''}` }));
  const hasBiz = (c.weekly || []).some((w) => w[1] > 0);
  return `<div class="view-inner">
    <div class="hero"><div><h1>${esc(c.name)}</h1><div class="muted">${[c.vin ? 'VIN ' + esc(c.vin) : '', c.engine ? esc(c.engine) : '', c.drive === 'FWD' ? 'Front-wheel drive' : c.drive === 'AWD' ? 'HTRAC AWD' : ''].filter(Boolean).join(' · ')}</div></div></div>
    ${needData()}
    <div class="grid g4" style="margin-bottom:16px;grid-template-columns:repeat(auto-fit,minmax(190px,1fr))">
      <div class="tile"><span class="k">Odometer${c.odometerEstimated ? ' (est.)' : ''}</span><span class="v">${c.odometerEstimated ? '≈ ' : ''}${mi(c.odometer)}</span><span class="s">${c.odometerAt ? 'reading ' + ago(c.odometerAt) : 'no reading yet'}${c.milesPerDay ? ` · ~${Math.round(c.milesPerDay)} mi/day` : ''}</span></div>
      <div class="tile"><span class="k">Fuel</span><span class="v">${fuel ? esc(fuel.value) : '—'}</span><span class="s">${range ? esc(range.value) + ' range' : 'from the car when connected'}</span>
        ${fuel?.num != null ? `<div class="meter" role="meter" aria-valuenow="${Math.round(fuel.num)}" aria-valuemin="0" aria-valuemax="100"><span style="width:${Math.max(0, Math.min(100, fuel.num))}%;${fuel.num <= 12 ? 'background:var(--danger)' : ''}"></span></div>` : ''}</div>
      <div class="tile"><span class="k">Avg MPG</span><span class="v">${fs.avgMpg ? fs.avgMpg.toFixed(1) : '—'}</span><span class="s">${fs.costPerMile ? (fs.costPerMile * 100).toFixed(1) + '¢ per mile in fuel' : 'log full fill-ups for MPG'}</span></div>
      <div class="tile"><span class="k">Driven this month</span><span class="v">${mi(t.month?.miles)} mi</span><span class="s">${t.month?.trips || 0} trips · ${mi(t.year?.business)} business mi this year${t.year?.deductible ? ` (~$${Math.round(t.year.deductible).toLocaleString()})` : ''}</span></div>
    </div>
    ${c.pendingFill ? `<div class="notice" style="margin-bottom:16px">⛽ <b>Filled up?</b> About ${c.pendingFill.gallons.toFixed(1)} gal — the fuel gauge went from ${Math.round(c.pendingFill.from)}% to ${Math.round(c.pendingFill.to)}% ${ago(c.pendingFill.time)}. Log it on the phone (Garage) with the price.</div>` : ''}
    <div class="grid g2">
      <div class="card"><h2>What's due</h2>${due.length ? `<ul class="list">${due.slice(0, 10).map((d) => `<li><span class="t">${esc(d.name)}<br><span class="muted small">${esc(d.text)}</span></span>${d.expected ? `<span class="meta">${fmtDate(d.expected, { month: 'short', day: 'numeric' })}</span>` : ''}<span class="chip ${tone(d.status)}">${d.status === 'OVERDUE' ? 'Overdue' : d.status === 'DUE_SOON' ? 'Due soon' : 'OK'}</span></li>`).join('')}</ul>` : '<div class="empty">Set the odometer on the phone to see what’s due.</div>'}</div>
      <div class="card"><h2>Recalls & safety<span class="spacer"></span>${c.vin ? `<a class="small" href="https://www.nhtsa.gov/recalls?vin=${encodeURIComponent(c.vin)}" target="_blank" rel="noopener">Check my VIN →</a>` : ''}</h2>
        ${(c.recalls || []).length ? `<ul class="list">${c.recalls.map((r) => `<li><span class="t">${esc(r.component.charAt(0) + r.component.slice(1).toLowerCase())}<br><span class="muted small">${esc(r.campaign)} · ${esc(r.date)} — ${esc(r.summary.slice(0, 180))}${r.summary.length > 180 ? '…' : ''}</span></span><span class="chip ${r.resolved ? '' : r.parkIt ? 'danger' : 'warn'}">${r.resolved ? 'Done / n.a.' : r.parkIt ? 'Park it' : 'Open'}</span></li>`).join('')}</ul>`
          : `<div class="empty">${c.recallsCheckedAt ? 'No recalls listed for this model year.' : 'Not checked yet — the phone checks NHTSA weekly.'}</div>`}
        <div class="muted small" style="margin-top:8px">${openRecalls.length} open · listed by year, make and model, so mark any that don't apply on the phone.${c.recallsCheckedAt ? ' Checked ' + ago(c.recallsCheckedAt) + '.' : ''}</div></div>
      <div class="card"><h2>Miles per week</h2>${hasBiz ? '<div class="legend"><span><i class="b1"></i>Personal & other</span><span><i class="b2"></i>Business</span></div>' : ''}
        ${weekly.some((w) => w.parts.some((p) => p.v > 0)) ? svgBars(weekly, { aria: 'Miles per week for the last 12 weeks', top: (m) => Math.round(m) + ' mi' }) : '<div class="empty">Trips appear after the first drive with the phone connected to the car.</div>'}</div>
      <div class="card"><h2>MPG per tank</h2>${(c.mpgSeries || []).length >= 2 ? svgLine(c.mpgSeries, 'MPG per full tank') : '<div class="empty">Two full fill-ups in a row give the first MPG.</div>'}</div>
      <div class="card"><h2>Recent trips</h2>${(c.trips || []).length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>When</th><th>From → To</th><th>Miles</th><th>MPG</th><th>Purpose</th></tr></thead><tbody>
        ${c.trips.slice(0, 15).map((x) => `<tr><td>${fmtDate(x.start, { month: 'short', day: 'numeric' })} ${fmtTime(x.start)}</td><td>${esc([x.from, x.to].filter(Boolean).map((p) => p.split(',')[0]).join(' → ') || '—')}</td><td class="mono">${x.miles.toFixed(1)}</td><td class="mono">${x.mpg ? x.mpg.toFixed(1) : '—'}</td><td>${x.sim ? '<span class="chip pro">Simulated</span>' : esc(x.purpose)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">No trips yet.</div>'}</div>
      <div class="card"><h2>From the car</h2>
        ${(c.codes || []).length ? `<div class="notice" style="border-color:var(--danger);margin-bottom:10px"><b style="color:var(--danger)">${c.codes.length} trouble code${c.codes.length > 1 ? 's' : ''}</b><br>${c.codes.map((d) => `${esc(d.code)} — ${esc(d.text)}`).join('<br>')}</div>` : ''}
        ${c.parked ? `<div class="muted small" style="margin-bottom:8px">Parked${c.parked.place ? ' at ' + esc(c.parked.place) : ''} · ${ago(c.parked.time)}</div>` : ''}
        ${Object.keys(groups).length ? Object.entries(groups).map(([g, ms]) => `<h3 class="small muted" style="margin:10px 0 4px">${esc(g)}</h3><ul class="list">${ms.map((m) => `<li><span class="t">${esc(m.label)}<br><span class="muted small">${esc(m.source)} · ${ago(m.time)}</span></span><span class="mono" style="${m.alert ? 'color:var(--danger)' : ''}">${esc(m.value)}</span></li>`).join('')}</ul>`).join('')
          : '<div class="empty">Live values arrive when the phone connects to the car (Android Auto, OBD-II adapter or Bluelink).</div>'}</div>
      ${(c.specs || []).length ? `<div class="card"><h2>From your VIN</h2><ul class="list">${c.specs.map(([k, v]) => `<li><span class="t muted">${esc(k)}</span><span>${esc(v)}</span></li>`).join('')}</ul><div class="muted small">Decoded by NHTSA.</div></div>` : ''}
    </div></div>`;
};

// ---------------- brainstorm (notebooks › sections › pages, and Keep-style notes)
const noteBg = (n) => { const dark = document.documentElement.getAttribute('data-theme') !== 'light'; const c = dark ? n.dark : n.light; return c ? `background:${c};${dark ? '' : 'color:#14161A'}` : ''; };
Views.brainstorm = () => {
  const B = S.snap?.brainstorm;
  const tab = S.sub === 'notes' || S.sub === 'tagged' ? S.sub : 'notebooks';
  const q = (S.ui.bsQuery || '').toLowerCase();
  const nbs = D.prof(B?.notebooks); const notes = D.prof(B?.notes);
  const tabs = [['notebooks', 'Notebooks'], ['notes', 'Notes'], ['tagged', 'Tagged']].map(([k, l]) => `<a href="#/brainstorm/${k}" class="chip btn ${tab === k ? 'accent' : ''}">${l}</a>`).join('');
  const head = `<div class="hero"><h1>Brainstorm</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="toolbar"><div class="row wrap">${tabs}</div><span class="spacer"></span><input type="search" id="bsQ" placeholder="Search pages and notes" value="${esc(S.ui.bsQuery || '')}"></div>`;
  if (!B) return `<div class="view-inner">${head}${S.snap ? '<div class="notice">Update the phone to BlackLab 5.8.1 to see Brainstorm here.</div>' : ''}</div>`;
  const allPages = nbs.flatMap((nb) => nb.sections.flatMap((sec) => sec.pages.map((p) => ({ ...p, nb, sec }))));
  const hit = (p) => !q || (p.title + ' ' + p.preview + ' ' + p.text).toLowerCase().includes(q);
  const noteHit = (n) => !q || (n.title + ' ' + n.body + ' ' + n.items.map((i) => i.t).join(' ') + ' ' + n.labels.join(' ')).toLowerCase().includes(q);
  if (tab === 'notes') {
    const shown = notes.filter((n) => !n.archived && noteHit(n));
    const live = S.mode === 'phone';
    const card = (n) => `<div class="note" style="${noteBg(n)}">${n.pinned ? '<span class="pin" title="Pinned">📌</span>' : ''}${n.title ? `<b>${esc(n.title)}</b>` : ''}
      ${n.checklist ? `<ul class="checks">${n.items.map((it, i) => `<li style="padding-left:${it.i * 16}px"><label><input type="checkbox" data-note="${n.id}" data-item="${i}" ${it.d ? 'checked' : ''} ${live ? '' : 'disabled'}> <span class="${it.d ? 'done' : ''}">${esc(it.t)}</span></label></li>`).join('')}</ul>` : `<div class="nb">${esc(n.body).slice(0, 900)}</div>`}
      <div class="small muted" style="margin-top:6px">${[...n.labels.map((l) => '#' + esc(l)), n.reminderAt ? '⏰ ' + relDay(n.reminderAt) + ' ' + fmtTime(n.reminderAt) : '', n.attachments ? '📎 ' + n.attachments : '', ago(n.updatedAt)].filter(Boolean).join(' · ')}</div></div>`;
    return `<div class="view-inner">${head}
      <div class="card" style="margin-bottom:16px"><div class="stack"><input id="nTitle" placeholder="Title">
        <textarea id="nBody" placeholder="Take a note… (one item per line for a checklist)"></textarea>
        <div class="row"><label class="row small"><label class="switch"><input type="checkbox" id="nList"><span></span></label> Checklist</label><span class="spacer"></span><button id="nAdd" ${live ? '' : 'disabled title="Needs the live phone link"'}>Add note</button></div>
        ${live ? '' : '<div class="muted small">Adding notes and ticking items needs the live phone link. <a href="#/connect">Connect →</a></div>'}</div></div>
      ${shown.length ? `<div class="notes">${shown.map(card).join('')}</div>` : '<div class="empty">No notes.</div>'}
      ${notes.some((n) => n.archived) ? `<details style="margin-top:16px"><summary class="muted">Archived (${notes.filter((n) => n.archived).length})</summary><div class="notes" style="margin-top:10px">${notes.filter((n) => n.archived && noteHit(n)).map(card).join('')}</div></details>` : ''}</div>`;
  }
  if (tab === 'tagged') {
    const tagged = allPages.flatMap((p) => (p.tags || []).map((t) => ({ ...t, p }))).filter((t) => !q || (t.tag + ' ' + t.text).toLowerCase().includes(q));
    const byTag = {}; tagged.forEach((t) => (byTag[t.tag] = byTag[t.tag] || []).push(t));
    return `<div class="view-inner">${head}${Object.keys(byTag).length ? `<div class="grid g2">${Object.entries(byTag).map(([tag, ts]) => `<div class="card"><h2>${esc(tag)} <span class="chip">${ts.filter((t) => !t.done).length} open</span></h2><ul class="list">
      ${ts.map((t) => `<li><span>${t.done ? '☑' : '☐'}</span><span class="t ${t.done ? 'muted' : ''}">${esc(t.text)}<br><span class="small muted">${esc(t.p.nb.name)} › ${esc(t.p.sec.name)} › ${esc(t.p.title || 'Untitled page')}</span></span></li>`).join('')}</ul></div>`).join('')}</div>`
      : '<div class="empty">No tagged paragraphs. Tag lines on a page (To Do, Important, Question…) and they gather here.</div>'}</div>`;
  }
  // notebooks
  const selSec = S.ui.bsSection;
  const sec = nbs.flatMap((nb) => nb.sections.map((x) => ({ ...x, nb }))).find((x) => x.id === selSec) || nbs[0]?.sections?.[0] && { ...nbs[0].sections[0], nb: nbs[0] };
  const pages = q ? allPages.filter(hit) : (sec?.pages || []).map((p) => ({ ...p, nb: sec.nb, sec }));
  const open = S.ui.bsPage;
  return `<div class="view-inner">${head}
    <div class="bs-layout">
      <div class="card bs-tree">${nbs.length ? nbs.map((nb) => `<div class="bs-nb"><div class="bs-nb-name"><span class="dot" style="background:${argb(nb.color)}"></span>${esc(nb.emoji ? nb.emoji + ' ' : '')}${esc(nb.name)}</div>
        ${nb.sections.map((x) => `<a class="bs-sec ${sec && x.id === sec.id && !q ? 'on' : ''}" data-sec="${x.id}"><span class="bar" style="background:${argb(x.color)}"></span>${x.group ? `<span class="muted small">${esc(x.group)} › </span>` : ''}${esc(x.name)}${x.locked ? ' 🔒' : ''}<span class="spacer"></span><span class="muted small">${x.pages.length}</span></a>`).join('') || '<div class="muted small" style="padding:4px 10px">No sections</div>'}</div>`).join('')
        : '<div class="empty">No notebooks yet.</div>'}</div>
      <div class="card bs-pages"><h2>${q ? `Pages matching “${esc(S.ui.bsQuery)}”` : sec ? esc(sec.nb.name) + ' › ' + esc(sec.name) : 'Pages'}</h2>
        ${sec?.locked && !q ? '<div class="notice">This section is password-protected on the phone, so only page titles are shared.</div>' : ''}
        ${pages.length ? `<ul class="list">${pages.map((p) => `<li class="bs-page" data-page="${p.id}" style="padding-left:${4 + p.level * 18}px;cursor:pointer;flex-wrap:wrap"><span class="t">${p.pinned ? '📌 ' : ''}<b>${esc(p.title || 'Untitled page')}</b>${q ? `<span class="muted small"> · ${esc(p.nb.name)} › ${esc(p.sec.name)}</span>` : ''}<br><span class="small muted">${esc(p.preview).slice(0, 160)}</span></span><span class="meta">${p.words} words · ${ago(p.updatedAt)}</span>
          ${open === p.id && p.text ? `<div class="bs-text">${esc(p.text)}${p.text.length >= 1500 ? '…' : ''}${(p.tags || []).length ? `<div style="margin-top:8px">${p.tags.map((t) => `<span class="chip">${t.done ? '☑' : '☐'} ${esc(t.tag)}: ${esc(t.text).slice(0, 80)}</span>`).join(' ')}</div>` : ''}</div>` : ''}</li>`).join('')}</ul>` : '<div class="empty">No pages.</div>'}
        <div class="muted small" style="margin-top:8px">Pages are drawn and edited on the phone (ink, pictures, audio). Here you can read and search their text.</div></div>
    </div></div>`;
};
Views.brainstorm.bind = () => {
  bindProfile();
  const qi = $('#bsQ'); qi.oninput = (e) => { S.ui.bsQuery = e.target.value; const p = e.target.selectionStart; render(); const i = $('#bsQ'); i.focus(); i.setSelectionRange(p, p); };
  $$('[data-sec]').forEach((a) => a.onclick = () => { S.ui.bsSection = Number(a.dataset.sec); S.ui.bsPage = null; S.ui.bsQuery = ''; saveUi(); render(); });
  $$('[data-page]').forEach((li) => li.onclick = () => { const id = Number(li.dataset.page); S.ui.bsPage = S.ui.bsPage === id ? null : id; render(); });
  $$('[data-note]').forEach((c) => c.onchange = async () => {
    try { await Phone.req(`/api/notes/${c.dataset.note}/item`, { body: { index: Number(c.dataset.item), done: c.checked } }); await loadFromPhone(); } catch (e) { toast(e.message); c.checked = !c.checked; }
  });
  const add = $('#nAdd'); if (add) add.onclick = async () => {
    const title = $('#nTitle').value.trim(); const body = $('#nBody').value; const list = $('#nList').checked;
    if (!title && !body.trim()) return;
    const profile = S.ui.profile === 'ALL' ? undefined : S.ui.profile;
    try {
      await Phone.req('/api/notes', { body: list ? { title, items: body.split('\n').map((x) => x.trim()).filter(Boolean), profile } : { title, body, profile } });
      toast('Note added on the phone'); await loadFromPhone();
    } catch (e) { toast(e.message); }
  };
};

// ---------------- knowledge (the app's expert and conversation knowledge bases, from knowledge.js)
let kbLoading = null;
function loadKb() {
  if (window.BL_KB || kbLoading) return kbLoading;
  kbLoading = new Promise((ok) => { const sc = document.createElement('script'); sc.src = 'knowledge.js'; sc.onload = () => { ok(); render(); }; sc.onerror = () => { ok(); toast('Knowledge bases didn’t load'); }; document.head.appendChild(sc); });
  return kbLoading;
}
Views.knowledge = () => {
  if (!window.BL_KB) { loadKb(); return '<div class="view-inner"><div class="hero"><h1>Knowledge</h1></div><div class="empty">Loading the knowledge bases…</div></div>'; }
  const K = window.BL_KB; const [kbId, entryId] = (S.sub || '').split(':');
  const q = (S.ui.kbQuery || '').toLowerCase();
  const base = K.find((b) => b.id === kbId);
  const para = (t) => t.split(/\n\s*\n/).map((x) => x.trim()).filter(Boolean).map((x) => x.split('\n').every((l) => /^\s*[-*] /.test(l))
    ? `<ul>${x.split('\n').map((l) => `<li>${esc(l.replace(/^\s*[-*] /, '').replace(/\*\*/g, ''))}</li>`).join('')}</ul>` : `<p>${esc(x.replace(/\*\*/g, '').replace(/\s*\n\s*/g, ' '))}</p>`).join('');
  const search = q ? K.flatMap((b) => b.entries.filter((e) => (e.title + ' ' + e.tags.join(' ') + ' ' + e.body).toLowerCase().includes(q)).map((e) => ({ e, b }))).slice(0, 60) : [];
  const head = `<div class="hero"><div><h1>${base ? esc(base.title) : 'Knowledge'}</h1><div class="muted">${base ? esc(base.summary) : `${K.length} knowledge bases · ${K.reduce((a, b) => a + b.entries.length, 0)} entries — the same ones the phone's assistant answers from`}</div></div></div>
    <div class="toolbar">${base ? '<a href="#/knowledge" class="chip btn">← All knowledge bases</a>' : ''}<span class="spacer"></span><input type="search" id="kbQ" placeholder="Search every entry" value="${esc(S.ui.kbQuery || '')}"></div>`;
  if (q) return `<div class="view-inner">${head}<div class="card">${search.length ? `<ul class="list">${search.map(({ e, b }) => `<li><a class="t" href="#/knowledge/${encodeURIComponent(b.id + ':' + e.id)}"><b>${esc(e.title)}</b><br><span class="small muted">${esc(b.title)} — ${esc(e.body.slice(0, 150))}…</span></a></li>`).join('')}</ul>` : '<div class="empty">Nothing matches.</div>'}</div></div>`;
  if (base && entryId) {
    const e = base.entries.find((x) => x.id === entryId);
    if (e) return `<div class="view-inner">${head}<div class="card kb-entry"><h2>${esc(e.title)}</h2>${e.tags.length ? `<div class="row wrap" style="margin-bottom:8px">${e.tags.slice(0, 10).map((t) => `<span class="chip">${esc(t)}</span>`).join('')}</div>` : ''}${para(e.body)}
      ${e.ask.length ? `<h3 style="margin-top:14px">Ask about it</h3><div class="row wrap">${e.ask.map((a) => `<span class="chip btn" data-ask="${esc(a)}">${esc(a)}</span>`).join('')}</div>` : ''}
      <div style="margin-top:14px"><a href="#/knowledge/${encodeURIComponent(base.id)}" class="small">← ${esc(base.title)}</a></div></div></div>`;
  }
  if (base) return `<div class="view-inner">${head}
    ${base.starters?.length ? `<div class="row wrap" style="margin-bottom:14px">${base.starters.map((a) => `<span class="chip btn" data-ask="${esc(a)}">${esc(a)}</span>`).join('')}</div>` : ''}
    <div class="card"><ul class="list">${base.entries.map((e) => `<li><a class="t" href="#/knowledge/${encodeURIComponent(base.id + ':' + e.id)}"><b>${esc(e.title)}</b><br><span class="small muted">${esc(e.body.replace(/\*\*/g, '').slice(0, 170))}…</span></a></li>`).join('')}</ul></div></div>`;
  const group = (kind, title, blurb) => `<h2 style="margin:18px 0 8px">${title}</h2><div class="muted small" style="margin-bottom:10px">${blurb}</div><div class="grid g3">${K.filter((b) => b.kind === kind).map((b) => `<a class="card kb-card" href="#/knowledge/${encodeURIComponent(b.id)}"><b>${esc(b.title)}</b><div class="small muted" style="margin-top:4px">${esc(b.summary)}</div><div class="small" style="margin-top:8px">${b.entries.length} entries</div></a>`).join('')}</div>`;
  return `<div class="view-inner">${head}${group('DOMAIN', 'Expert knowledge', 'Reference you can read here or ask the assistant about.')}${group('CONVERSATION', 'Conversations', 'Topics to talk through with the assistant — tap a starter to begin.')}</div>`;
};
Views.knowledge.bind = () => {
  const qi = $('#kbQ'); if (qi) qi.oninput = (e) => { S.ui.kbQuery = e.target.value; const p = e.target.selectionStart; render(); const i = $('#kbQ'); i.focus(); i.setSelectionRange(p, p); };
  $$('[data-ask]').forEach((c) => c.onclick = () => { go('assistant'); ask(c.dataset.ask); });
};

// ---------------- summarize (the phone's summaries: pick what and how)
Views.summarize = () => {
  const what = [['today', 'Today'], ['tomorrow', 'Tomorrow'], ['this week', 'This week'], ['my open tasks', 'Open tasks'], ['my recent notes', 'Recent notes'], ['my journal', 'Journal'], ['my last meeting', 'Last meeting']];
  const how = [['a quick brief', 'Quick brief'], ['action items', 'Action items'], ['bullet points', 'Bullets'], ['an email to my boss', 'Email to my boss'], ['a status report', 'Status report'], ['talking points', 'Talking points']];
  const w = S.ui.sumWhat || 'this week'; const h = S.ui.sumHow || 'action items';
  return `<div class="view-inner"><div class="hero"><div><h1>Summarize</h1><div class="muted">Summaries of your own stuff, written by the phone's assistant (or the desktop's AI when it's connected).</div></div></div>
    <div class="grid g2">
      <div class="card"><h2>What</h2><div class="row wrap">${what.map(([k, l]) => `<span class="chip btn ${w === k ? 'accent' : ''}" data-sw="${esc(k)}">${l}</span>`).join('')}</div>
        <h2 style="margin-top:16px">As</h2><div class="row wrap">${how.map(([k, l]) => `<span class="chip btn ${h === k ? 'accent' : ''}" data-sh="${esc(k)}">${l}</span>`).join('')}</div>
        <div class="row" style="margin-top:16px"><button id="sumGo">Summarize ${esc(w)} as ${esc(h)}</button></div></div>
      <div class="card"><h2>Or paste anything</h2><textarea id="sumText" placeholder="Paste text — an email thread, notes, an article…" style="min-height:160px"></textarea>
        <div class="row" style="margin-top:10px"><button class="ghost" id="sumPaste">Summarize this as ${esc(h)}</button></div></div>
    </div></div>`;
};
Views.summarize.bind = () => {
  $$('[data-sw]').forEach((c) => c.onclick = () => { S.ui.sumWhat = c.dataset.sw; saveUi(); render(); });
  $$('[data-sh]').forEach((c) => c.onclick = () => { S.ui.sumHow = c.dataset.sh; saveUi(); render(); });
  $('#sumGo').onclick = () => { go('assistant'); ask(`summarize ${S.ui.sumWhat || 'this week'} as ${S.ui.sumHow || 'action items'}`); };
  $('#sumPaste').onclick = () => { const t = $('#sumText').value.trim(); if (t) { go('assistant'); ask(`summarize this as ${S.ui.sumHow || 'action items'}: ${t.replace(/\s*\n+\s*/g, ' ¶ ')}`); } };
};

Views.tasks = () => {
  const all = D.tasks; const lists = D.prof(S.snap?.lists);
  const f = S.sub || 'open';
  const pick = { open: all.filter((t) => !t.completed), today: all.filter((t) => !t.completed && t.dueDay != null && startOfDay(t.dueDay) <= startOfDay()), important: all.filter((t) => !t.completed && t.important), planned: all.filter((t) => !t.completed && t.dueDay != null), done: all.filter((t) => t.completed) };
  let list = pick[f] || all.filter((t) => !t.completed && t.list === f);
  list = list.sort((a, b) => (a.dueDay ?? 9e15) - (b.dueDay ?? 9e15) || b.priority - a.priority);
  const tabs = [['open', 'All open'], ['today', 'Today & overdue'], ['important', 'Important'], ['planned', 'Planned'], ['done', 'Completed (14 d)']];
  return `<div class="view-inner"><div class="hero"><h1>To-Do</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="card" style="margin-bottom:16px"><div class="row"><input id="newTask" style="flex:1" placeholder="Add a task — e.g. Inspect boiler room tomorrow 9am !high #hvac every week @Inspections"><button id="addTask">Add</button></div>
    <div class="small muted" style="margin-top:6px">Natural language works the same as on the phone: dates, times, <code>!priority</code>, <code>#tags</code>, <code>@list</code>, “every week”.</div></div>
    <div class="row wrap" style="margin-bottom:12px">${tabs.map(([k, l]) => `<a class="chip ${f === k ? 'accent' : 'btn'}" href="#/tasks/${k}">${l} · ${pick[k].length}</a>`).join('')}
      ${lists.map((l) => `<a class="chip ${f === l.name ? 'accent' : 'btn'}" href="#/tasks/${encodeURIComponent(l.name)}">${esc(l.emoji)} ${esc(l.name)}</a>`).join('')}</div>
    <div class="card">${taskList(list, 'Nothing here.')}</div></div>`;
};
Views.tasks.bind = () => {
  bindProfile(); bindTaskChecks();
  const add = async () => {
    const v = $('#newTask').value.trim(); if (!v) return;
    try {
      if (S.mode === 'phone') { await Phone.req('/api/tasks', { body: { text: v, profile: S.ui.profile === 'ALL' ? '' : S.ui.profile } }); await loadFromPhone(); toast('Task added'); }
      else if (S.mode === 'cloud') { await Cloud.queueCommand('add task ' + v); toast('Queued — appears after the phone syncs'); }
      else { toast('Connect the phone first'); return; }
      $('#newTask').value = '';
    } catch (e) { toast(e.message); }
  };
  $('#addTask').onclick = add; $('#newTask').onkeydown = (e) => { if (e.key === 'Enter') add(); };
};

Views.calendar = () => {
  const days = Number(S.sub) || 14; const start = startOfDay();
  const ev = D.events.filter((e) => e.end >= start && e.start < start + days * DAY).sort((a, b) => a.start - b.start);
  const due = D.prof(S.snap?.assignments).filter((a) => a.dueAt && a.dueAt >= start && a.dueAt < start + days * DAY);
  const tasks = D.tasks.filter((t) => !t.completed && t.dueDay != null && t.dueDay >= start && t.dueDay < start + days * DAY);
  let html = '';
  for (let d = 0; d < days; d++) {
    const s = start + d * DAY; const e = s + DAY;
    const items = [
      ...ev.filter((x) => x.start < e && x.end >= s).map((x) => ({ t: x.allDay ? -1 : x.start, html: `<span class="bar" style="background:${argb(x.color)}"></span><span class="meta" style="width:110px">${x.allDay ? 'All day' : fmtTime(x.start) + '–' + fmtTime(x.end)}</span><span class="t">${esc(x.title)}${x.location ? ` <span class="muted">· ${esc(x.location)}</span>` : ''}</span><span class="chip">${esc(x.calendar)}</span>` })),
      ...due.filter((x) => x.dueAt >= s && x.dueAt < e).map((x) => ({ t: x.dueAt, html: `<span class="bar" style="background:var(--warn)"></span><span class="meta" style="width:110px">due ${fmtTime(x.dueAt)}</span><span class="t">${esc(x.title)} <span class="muted">· ${esc(x.course)}</span></span><span class="chip warn">${esc(x.kind)}</span>` })),
      ...tasks.filter((x) => x.dueDay >= s && x.dueDay < e).map((x) => ({ t: s + (x.dueMinute ?? 1439) * 60000, html: `<span class="bar" style="background:var(--muted)"></span><span class="meta" style="width:110px">${x.dueMinute != null ? fmtMinute(x.dueMinute) : 'task'}</span><span class="t">☐ ${esc(x.title)}</span>` })),
    ].sort((a, b) => a.t - b.t);
    if (!items.length && d > 1) continue;
    html += `<div class="day-head">${relDay(s)} · ${fmtDate(s, { month: 'short', day: 'numeric' })}</div><div class="card" style="padding:6px 14px">${items.length ? `<ul class="list">${items.map((x) => `<li>${x.html}</li>`).join('')}</ul>` : '<div class="empty">Free day.</div>'}</div>`;
  }
  return `<div class="view-inner"><div class="hero"><h1>Schedule</h1><div class="spacer"></div>
    <div class="row">${[7, 14, 30, 60].map((n) => `<a class="chip ${days === n ? 'accent' : 'btn'}" href="#/calendar/${n}">${n} days</a>`).join('')}</div>${profileSwitch()}</div>
    ${needData()}
    <div class="card" style="margin-bottom:8px"><div class="row"><input id="newEvent" style="flex:1" placeholder="Schedule safety walkthrough Friday at 2pm for 90 minutes"><button id="addEvent">Schedule</button></div></div>
    ${html}</div>`;
};
Views.calendar.bind = () => {
  bindProfile();
  const add = () => { const v = $('#newEvent').value.trim(); if (!v) return; go('assistant'); ask(/^(schedule|book|add|create|put|plan)\b/i.test(v) ? v : 'schedule ' + v); };
  $('#addEvent').onclick = add; $('#newEvent').onkeydown = (e) => { if (e.key === 'Enter') add(); };
};

Views.documents = () => {
  const q = (S.ui.docQuery || '').toLowerCase();
  const docs = D.prof(S.snap?.documents).filter((d) => !q || (d.title + ' ' + d.preview + ' ' + d.folder).toLowerCase().includes(q));
  return `<div class="view-inner"><div class="hero"><h1>Documents</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="toolbar"><input type="search" id="docQ" placeholder="Filter documents" value="${esc(S.ui.docQuery || '')}"><button class="ghost" id="askDocs">Ask my files…</button></div>
    <div class="card"><table class="table"><thead><tr><th>Title</th><th>Folder</th><th>Words</th><th>Modified</th><th></th></tr></thead><tbody>
    ${docs.map((d) => `<tr><td><b>${d.pinned ? '📌 ' : ''}${esc(d.title)}</b><div class="small muted">${esc(d.preview).slice(0, 140)}</div></td><td>${esc(d.folder || '—')}</td><td>${d.words}</td><td class="muted">${ago(d.updatedAt)}</td>
      <td>${S.mode === 'phone' && d.path ? `<a class="chip btn" href="#" data-dl="${esc(d.path)}">Open</a>` : ''}</td></tr>`).join('') || '<tr><td colspan="5" class="empty">No documents.</td></tr>'}
    </tbody></table></div></div>`;
};
Views.documents.bind = () => {
  bindProfile();
  $('#docQ').oninput = (e) => { S.ui.docQuery = e.target.value; const p = e.target.selectionStart; render(); const i = $('#docQ'); i.focus(); i.setSelectionRange(p, p); };
  $('#askDocs').onclick = () => { const q = prompt('Ask your files (searched on the phone, offline):'); if (q) { go('assistant'); ask('search my files for ' + q); } };
};

Views.learning = () => {
  const courses = D.prof(S.snap?.courses); const certs = D.prof(S.snap?.certifications); const asg = D.prof(S.snap?.assignments);
  const now = Date.now();
  return `<div class="view-inner"><div class="hero"><h1>Education</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="grid g2">
      <div class="card"><h2>Courses</h2>${courses.length ? `<ul class="list">${courses.map((c) => `<li><span class="dot" style="background:${argb(c.color)}"></span><span class="t"><b>${esc(c.code)}</b> ${esc(c.title)}</span><span class="chip">${esc(c.kind.toLowerCase())}</span><span class="chip ${c.status === 'COMPLETED' ? 'accent' : ''}">${esc(c.status.replace('_', ' ').toLowerCase())}${c.finalGrade ? ' · ' + esc(c.finalGrade) : ''}</span></li>`).join('')}</ul>` : '<div class="empty">No courses.</div>'}</div>
      <div class="card"><h2>Certifications</h2>${certs.length ? `<ul class="list">${certs.map((c) => { const days = c.expiresDay ? Math.round((c.expiresDay - now) / DAY) : null; return `<li><span class="t"><b>${esc(c.name)}</b> <span class="muted">${esc(c.issuer)}</span></span><span class="chip">${esc(c.status.replace('_', ' ').toLowerCase())}</span>${days != null ? `<span class="chip ${days < 45 ? 'warn' : ''}">${days >= 0 ? 'renews in ' + days + ' d' : 'expired'}</span>` : ''}${c.examAt ? `<span class="chip pro">exam ${relDay(c.examAt).toLowerCase()}</span>` : ''}</li>`; }).join('')}</ul>` : '<div class="empty">No certifications.</div>'}</div>
      <div class="card span2"><h2>Flashcards</h2>${(() => { const dk = D.prof(S.snap?.decks); return dk.length ? `<table class="table"><thead><tr><th>Deck</th><th>Course</th><th>Cards</th><th>Learned</th><th>Due today</th></tr></thead><tbody>
        ${dk.map((d) => `<tr><td><b>${esc(d.name)}</b></td><td>${esc(d.course || '—')}</td><td>${d.cards}</td><td>${d.cards ? Math.round(d.learned / d.cards * 100) + '%' : '—'}</td><td>${d.due ? `<span class="chip accent">${d.due} due</span>` : '<span class="muted">none</span>'}</td></tr>`).join('')}</tbody></table>
        <div class="muted small" style="margin-top:6px">Review on the phone (Education › Flashcards); spaced repetition schedules each card.</div>` : '<div class="empty">No flashcard decks yet — make one on the phone in Education.</div>'; })()}</div>
      <div class="card span2"><h2>Assignments & exams</h2><table class="table"><thead><tr><th>Due</th><th>Title</th><th>Course</th><th>Type</th><th>Status</th></tr></thead><tbody>
        ${asg.map((a) => `<tr><td style="${a.dueAt && a.dueAt < now ? 'color:var(--danger)' : ''}">${a.dueAt ? relDay(a.dueAt) + ' ' + fmtTime(a.dueAt) : '—'}</td><td>${esc(a.title)}</td><td>${esc(a.course)}</td><td>${esc(a.kind)}</td><td><span class="chip">${esc(a.status.toLowerCase().replace('_', ' '))}</span></td></tr>`).join('') || '<tr><td colspan="5" class="empty">Nothing due.</td></tr>'}
      </tbody></table></div>
    </div>
    <div class="row wrap" style="margin-top:14px">${["What's my GPA?", 'Start focus', 'Log 1 CEU for '].map((q) => `<span class="chip btn" data-q="${esc(q)}">${esc(q)}</span>`).join('')}</div></div>`;
};
Views.learning.bind = () => { bindProfile(); $$('[data-q]').forEach((c) => c.onclick = () => { if (c.dataset.q.endsWith(' ')) { go('assistant'); setTimeout(() => { $('#chatIn').value = c.dataset.q; $('#chatIn').focus(); }, 30); } else { go('assistant'); ask(c.dataset.q); } }); };

Views.journal = () => {
  const j = D.prof(S.snap?.journal); const moods = ['', '😞', '🙁', '😐', '🙂', '😄'];
  return `<div class="view-inner"><div class="hero"><h1>Journal</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="card" style="margin-bottom:16px"><div class="row"><input id="jIn" style="flex:1" placeholder="Write a quick journal entry — it lands in today's page on the phone"><button id="jAdd">Add</button></div></div>
    <div class="card">${j.length ? `<ul class="list">${j.map((e) => `<li><span class="meta" style="width:110px">${fmtDate(e.day)}</span><span>${moods[e.mood] || ''}</span><span class="t">${e.favorite ? '★ ' : ''}<b>${esc(e.title || '')}</b> ${esc(e.preview)}</span><span class="meta">${e.words} words</span></li>`).join('')}</ul>` : '<div class="empty">No entries.</div>'}</div></div>`;
};
Views.journal.bind = () => { bindProfile(); const add = () => { const v = $('#jIn').value.trim(); if (v) { go('assistant'); ask('journal: ' + v); } }; $('#jAdd').onclick = add; $('#jIn').onkeydown = (e) => { if (e.key === 'Enter') add(); }; };

Views.media = () => {
  const rec = D.prof(S.snap?.recordings); const dic = D.prof(S.snap?.dictations);
  const link = (p) => S.mode === 'phone' && p ? `<a class="chip btn" href="#" data-dl="${esc(p)}">Download</a>` : '';
  return `<div class="view-inner"><div class="hero"><h1>Recordings & dictation</h1><div class="spacer"></div>${profileSwitch()}</div>${needData()}
    <div class="row wrap" style="margin-bottom:14px">${['Start recording', 'Start dictation', 'Stop recording', 'Action items from my last meeting'].map((q) => `<span class="chip btn" data-q="${q}">${q}</span>`).join('')}</div>
    <div class="grid g2"><div class="card"><h2>Recordings</h2>${rec.length ? `<ul class="list">${rec.map((r) => `<li><span class="t">${esc(r.title)}</span>${r.hasTranscript ? '<span class="chip accent">transcript</span>' : ''}<span class="meta">${dur(r.durationMs)} · ${relDay(r.createdAt)}</span>${link(r.path)}</li>`).join('')}</ul>` : '<div class="empty">None.</div>'}</div>
    <div class="card"><h2>Dictation sessions</h2>${dic.length ? `<ul class="list">${dic.map((d) => `<li><span class="t">${esc(d.title)}</span><span class="meta">${d.words} words · ${dur(d.durationMs)} · ${relDay(d.startedAt)}</span></li>`).join('')}</ul>` : '<div class="empty">None.</div>'}</div></div></div>`;
};
Views.media.bind = () => { bindProfile(); $$('[data-q]').forEach((c) => c.onclick = () => { go('assistant'); ask(c.dataset.q); }); };

Views.files = () => {
  if (S.mode !== 'phone') return `<div class="view-inner"><h1>Phone files</h1><div class="notice" style="margin-top:16px">Browsing the BlackLab folder needs a <b>live phone link</b> (USB or Wi-Fi). <a href="#/connect">Connect →</a></div></div>`;
  return `<div class="view-inner"><div class="hero"><h1>Phone files</h1><span class="muted mono" id="fpath">/</span></div><div class="card" id="fileList"><div class="empty">Loading…</div></div></div>`;
};
Views.files.bind = async () => {
  if (S.mode !== 'phone') return;
  const path = S.sub || '';
  try {
    const r = await Phone.req('/api/files?path=' + encodeURIComponent(path));
    $('#fpath').textContent = 'BlackLab/' + (r.path === '.' ? '' : r.path);
    const up = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
    $('#fileList').innerHTML = `<table class="table"><thead><tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead><tbody>
      ${path ? `<tr><td colspan="4"><a href="#/files/${encodeURIComponent(up)}">↑ Up</a></td></tr>` : ''}
      ${r.files.map((f) => `<tr><td>${f.dir ? `<a href="#/files/${encodeURIComponent(f.path)}">📁 ${esc(f.name)}</a>` : esc(f.name)}</td><td class="muted">${f.dir ? '' : bytes(f.size)}</td><td class="muted">${ago(f.modified)}</td><td>${f.dir ? '' : `<a class="chip btn" href="#" data-dl="${esc(f.path)}">Download</a>`}</td></tr>`).join('') || '<tr><td colspan="4" class="empty">Empty folder.</td></tr>'}
    </tbody></table>`;
  } catch (e) { $('#fileList').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
};

// ---------------- assistant view
Views.assistant = () => `<div class="chat">
  <div class="target">
    <span class="muted small" style="align-self:center">Send to:</span>
    ${[['auto', 'Auto'], ['phone', 'Phone'], ['desktop', 'Desktop']].map(([k, l]) => `<span class="chip ${S.ui.target === k ? 'accent' : 'btn'}" data-target="${k}">${l}</span>`).join('')}
    <span class="spacer"></span>
    <label class="row small muted"><span class="switch"><input type="checkbox" id="speakT" ${S.ui.speak ? 'checked' : ''}><span></span></span>Speak replies</label>
    <button class="ghost small" id="clearChat">Clear</button>
  </div>
  <div class="chat-log" id="chatLog"></div>
  <div class="row wrap" style="margin:8px 0">${['Brief me', "What's due?", "What's on my calendar this week?", 'Search my files for ', 'Check email', 'System status', 'Pull phone backups'].map((q) => `<span class="chip btn" data-q="${esc(q)}">${esc(q.trim())}</span>`).join('')}</div>
  <div class="composer"><textarea id="chatIn" rows="1" placeholder="Ask BlackLab… (Enter to send, Shift+Enter for a new line)"></textarea>
    ${('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) ? '<button class="ghost mic" id="micBtn" title="Talk">🎙</button>' : ''}
    <button id="sendBtn">Send</button></div>
  <div class="small muted" style="margin-top:6px">${Companion.ok ? 'Desktop companion is running — desktop tasks run locally' + (S.companion?.ollama?.length ? ' with ' + esc(S.ui.model || S.companion.model || S.companion.ollama[0]) + ' (offline)' : '') + '; app tasks go to the phone.' : S.mode === 'phone' ? 'Connected live to the phone assistant.' : S.mode === 'cloud' ? 'Commands are delivered through your Drive.' : 'Not connected — only local navigation commands work.'}</div>
</div>`;
function renderChatLog() {
  const log = $('#chatLog'); if (!log) return;
  const msgs = S.chat.length ? S.chat : [{ user: false, text: `Hey${D.info.user?.name ? ' ' + D.info.user.name.split(' ')[0] : ''}! Ask me anything — I can run your phone's BlackLab (tasks, calendar, notes, recorder, files, email) and, with the desktop companion, this computer too.`, source: '' }];
  log.innerHTML = msgs.map((m) => `<div class="msg ${m.user ? 'user' : 'bot'}">${esc(m.text)}${!m.user && m.source ? `<span class="src">${esc(m.source)}</span>` : ''}${(m.links || []).filter((l) => l.url).length ? `<div class="links">${m.links.filter((l) => l.url).map((l) => `<a class="chip btn" href="${esc(l.url)}" target="_blank" rel="noopener">${esc(l.label)}</a>`).join('')}</div>` : ''}</div>`).join('');
  log.scrollTop = log.scrollHeight;
}
Views.assistant.bind = () => {
  renderChatLog();
  const input = $('#chatIn');
  const send = () => { const v = input.value; input.value = ''; ask(v); };
  $('#sendBtn').onclick = send;
  input.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } };
  input.focus();
  $$('[data-target]').forEach((c) => c.onclick = () => { S.ui.target = c.dataset.target; saveUi(); render(); });
  $$('[data-q]').forEach((c) => c.onclick = () => { if (c.dataset.q.endsWith(' ')) { input.value = c.dataset.q; input.focus(); } else ask(c.dataset.q); });
  $('#speakT').onchange = (e) => { S.ui.speak = e.target.checked; saveUi(); };
  $('#clearChat').onclick = async () => { S.chat = []; LS.set('chat', []); if (S.mode === 'phone') await Phone.req('/api/assistant/clear', { body: {} }).catch(() => {}); renderChatLog(); };
  const mic = $('#micBtn');
  if (mic) mic.onclick = () => {
    const R = window.SpeechRecognition || window.webkitSpeechRecognition; const r = new R();
    r.lang = 'en-US'; r.interimResults = true; mic.classList.add('on');
    r.onresult = (e) => { input.value = [...e.results].map((x) => x[0].transcript).join(''); if (e.results[e.results.length - 1].isFinal) { mic.classList.remove('on'); send(); } };
    r.onerror = r.onend = () => mic.classList.remove('on');
    r.start();
  };
};

// ---------------- settings view (every section, from the phone's schema)
const LOCAL_SECTION = {
  id: 'desktop-ui', title: 'This desktop (local)', icon: 'desktop', description: 'Preferences for this browser only. Not synced to the phone.',
  items: [
    { key: 'theme', label: 'Theme', help: 'Desktop UI theme.', ui: 'choice', options: [{ value: 'auto', label: 'Follow system' }, { value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }], default: 'auto' },
    { key: 'speak', label: 'Speak assistant replies', help: 'Uses ElevenLabs through the companion when available, otherwise the browser voice.', ui: 'bool', default: false },
    { key: 'voiceEngine', label: 'Desktop voice', help: 'Voice for spoken replies on this computer.', ui: 'choice', options: [{ value: 'auto', label: 'ElevenLabs if available' }, { value: 'browser', label: 'Browser voice (offline)' }], default: 'auto' },
    { key: 'target', label: 'Default assistant target', help: 'Where commands go first.', ui: 'choice', options: [{ value: 'auto', label: 'Auto' }, { value: 'phone', label: 'Phone' }, { value: 'desktop', label: 'Desktop' }], default: 'auto' },
    { key: 'model', label: 'Desktop AI model (Ollama)', help: 'Offline model the companion uses for desktop requests.', ui: 'model', default: '' },
    { key: 'refresh', label: 'Cloud refresh', help: 'Seconds between Drive checks when not linked live.', ui: 'int', min: 15, max: 600, step: 15, unit: 's', default: 30 },
    { key: 'advanced', label: 'Show advanced settings', help: 'Reveal tuning options marked advanced.', ui: 'bool', default: true },
    { key: 'profile', label: 'Account shown', help: 'Which account dashboards show.', ui: 'choice', options: [{ value: 'ALL', label: 'Both' }, { value: 'PERSONAL', label: 'Personal' }, { value: 'PROFESSIONAL', label: 'Professional' }], default: 'ALL' },
  ],
};

function controlFor(def, value, local) {
  const id = 'set-' + def.key; const ro = def.readOnly || (!local && def.ui === 'set');
  const v = value ?? def.default;
  switch (def.ui) {
    case 'bool': return `<label class="switch"><input type="checkbox" id="${id}" ${v ? 'checked' : ''} ${ro ? 'disabled' : ''}><span></span></label>`;
    case 'choice': case 'weekday': return `<select id="${id}" ${ro ? 'disabled' : ''}>${(def.options || []).map((o) => `<option value="${esc(o.value)}" ${String(o.value) === String(v) ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`;
    case 'model': { const ms = S.companion?.ollama || []; return `<select id="${id}"><option value="">${ms.length ? 'Companion default' : 'Start the companion to list models'}</option>${ms.map((m) => `<option ${m === v ? 'selected' : ''}>${esc(m)}</option>`).join('')}</select>`; }
    case 'time': { const h = String(Math.floor(v / 60)).padStart(2, '0'); const m = String(v % 60).padStart(2, '0'); return `<input type="time" id="${id}" value="${h}:${m}" ${ro ? 'disabled' : ''}>`; }
    case 'percent': case 'int': case 'float': {
      const step = def.step || (def.ui === 'float' ? 0.5 : 1); const span = (def.max ?? 100) - (def.min ?? 0);
      const range = span / step <= 400 ? `<input type="range" id="${id}-r" min="${def.min ?? 0}" max="${def.max ?? 100}" step="${step}" value="${v}" ${ro ? 'disabled' : ''}>` : '';
      return `${range}<input type="number" class="num" id="${id}" min="${def.min ?? ''}" max="${def.max ?? ''}" step="${step}" value="${v}" ${ro ? 'disabled' : ''}><span class="muted small">${esc(def.unit || '')}</span>`;
    }
    case 'set': return `<span class="muted small">${(v || []).length} item(s)</span>`;
    default: return `<input type="text" id="${id}" value="${esc(v)}" ${ro ? 'disabled' : ''}>`;
  }
}
function readControl(def) {
  const el = $('#set-' + def.key); if (!el) return undefined;
  switch (def.ui) {
    case 'bool': return el.checked;
    case 'time': { const [h, m] = el.value.split(':').map(Number); return h * 60 + m; }
    case 'percent': case 'int': return Math.round(Number(el.value));
    case 'float': return Number(el.value);
    case 'choice': case 'weekday': return def.store === 'int' ? Number(el.value) : el.value;
    default: return el.value;
  }
}
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

Views.settings = () => {
  const sections = [...(S.schema || []), LOCAL_SECTION];
  const q = (S.ui.setQuery || '').toLowerCase().trim();
  const match = (d, s) => !q || (d.label + ' ' + d.help + ' ' + s.title + ' ' + (d.options || []).map((o) => o.label).join(' ')).toLowerCase().includes(q);
  const visible = (d, s, local) => (S.ui.advanced || !d.advanced) && match(d, s) && (!S.ui.onlyModified || (!local && !same(S.values[d.key], d.default)));
  const current = q ? null : (sections.find((s) => s.id === S.sub) || sections[0]);
  const shown = current ? [current] : sections;
  const total = (S.schema || []).reduce((n, s) => n + s.items.length, 0);
  const modified = (S.schema || []).reduce((n, s) => n + s.items.filter((d) => !same(S.values[d.key], d.default)).length, 0);
  return `<div class="view-inner">
    <div class="hero"><div><h1>Settings</h1><div class="muted">${total} phone settings in ${(S.schema || []).length} sections · ${modified} changed from default · ${S.mode === 'phone' ? 'changes apply instantly' : S.mode === 'cloud' ? 'changes queue through Drive' : 'changes queue until the phone connects'}</div></div></div>
    ${S.schema ? '' : '<div class="notice" style="margin-bottom:16px"><b>Phone settings not loaded.</b> Connect once and every BlackLab setting appears here, grouped by section. <a href="#/connect">Connect →</a></div>'}
    <div class="toolbar">
      <input type="search" id="setSearch" placeholder="Search every setting (e.g. “voice”, “4K”, “reminder”)" value="${esc(S.ui.setQuery || '')}">
      <label class="row small"><span class="switch"><input type="checkbox" id="advT" ${S.ui.advanced ? 'checked' : ''}><span></span></span>Advanced</label>
      <label class="row small"><span class="switch"><input type="checkbox" id="modT" ${S.ui.onlyModified ? 'checked' : ''}><span></span></span>Only changed</label>
      <button class="ghost small" id="expSet">Export</button><button class="ghost small" id="impSet">Import</button>
      <input type="file" id="impFile" accept="application/json" class="hidden">
    </div>
    <div class="settings">
      <div class="settings-nav">${sections.map((s) => `<a href="#/settings/${s.id}" class="${current && current.id === s.id ? 'active' : ''}">${esc(s.title)}<span class="count">${s.items.length}</span></a>`).join('')}</div>
      <div class="stack">${shown.map((s) => {
        const local = s === LOCAL_SECTION;
        const items = s.items.filter((d) => visible(d, s, local));
        if (!items.length) return q || S.ui.onlyModified ? '' : `<div class="card"><h2>${esc(s.title)}</h2><div class="empty">No settings to show.</div></div>`;
        return `<div class="card" data-section="${s.id}"><h2>${esc(s.title)}<span class="spacer"></span>${local ? '' : `<button class="ghost small" data-reset-section="${s.id}">Reset section</button>`}</h2>
          <div class="muted small" style="margin:-6px 0 6px">${esc(s.description || '')}</div>
          ${items.map((d) => {
            const val = local ? S.ui[d.key] : S.values[d.key];
            const mod = !local && !same(val, d.default);
            const pend = !local && (d.key in S.pending || d.key in (Cloud.queuedSettings || {}));
            const defLabel = d.options ? (d.options.find((o) => String(o.value) === String(d.default))?.label ?? d.default) : d.ui === 'time' ? fmtMinute(d.default) : d.ui === 'bool' ? (d.default ? 'On' : 'Off') : (d.default === '' ? '(empty)' : d.default);
            return `<div class="setting ${mod ? 'modified' : ''} ${pend ? 'pending' : ''}" data-key="${esc(d.key)}">
              <div><div class="label">${esc(d.label)}${d.advanced ? '<span class="chip">advanced</span>' : ''}${d.readOnly ? '<span class="chip">read-only</span>' : ''}${pend ? '<span class="chip pro">queued</span>' : ''}</div>
                <div class="help">${esc(d.help)}${!local ? ` <span class="muted">· Default: ${esc(defLabel)}${q ? ' · ' + esc(s.title) : ''}</span>` : ''}</div></div>
              <div class="ctrl">${controlFor(d, val, local)}${mod && !d.readOnly ? `<button class="icon-btn" style="width:30px;height:30px;font-size:13px" data-reset="${esc(d.key)}" title="Reset to default">↺</button>` : ''}</div></div>`;
          }).join('')}</div>`;
      }).join('') || '<div class="card"><div class="empty">No settings match.</div></div>'}</div>
    </div></div>`;
};
Views.settings.bind = () => {
  const s = $('#setSearch');
  s.oninput = () => { S.ui.setQuery = s.value; saveUi(); const p = s.selectionStart; render(); const n = $('#setSearch'); n.focus(); n.setSelectionRange(p, p); };
  $('#advT').onchange = (e) => { S.ui.advanced = e.target.checked; saveUi(); render(); };
  $('#modT').onchange = (e) => { S.ui.onlyModified = e.target.checked; saveUi(); render(); };
  const defs = Object.fromEntries((S.schema || []).flatMap((sec) => sec.items.map((d) => [d.key, d])));
  $$('.setting').forEach((row) => {
    const key = row.dataset.key; const local = LOCAL_SECTION.items.find((d) => d.key === key) && !defs[key];
    const def = local ? LOCAL_SECTION.items.find((d) => d.key === key) : defs[key];
    if (!def) return;
    const commit = () => {
      const v = readControl(def); if (v === undefined || (typeof v === 'number' && Number.isNaN(v))) return;
      if (local) { S.ui[key] = v; saveUi(); if (key === 'theme') setTheme(v); toast('Saved on this desktop'); render(); return; }
      if (def.min != null && v < def.min || def.max != null && v > def.max) { toast(`${def.label}: ${def.min}–${def.max}`); return; }
      setSetting(key, v).then(() => render(true));
    };
    const el = $('#set-' + key); if (el) el.onchange = commit;
    const r = $('#set-' + key + '-r'); if (r && el) { r.oninput = () => { el.value = r.value; }; r.onchange = commit; }
  });
  $$('[data-reset]').forEach((b) => b.onclick = () => { const d = defs[b.dataset.reset]; setSetting(d.key, d.default).then(() => render(true)); });
  $$('[data-reset-section]').forEach((b) => b.onclick = () => {
    const sec = S.schema.find((x) => x.id === b.dataset.resetSection);
    const changes = Object.fromEntries(sec.items.filter((d) => !d.readOnly && d.ui !== 'set' && !same(S.values[d.key], d.default)).map((d) => [d.key, d.default]));
    if (!Object.keys(changes).length) { toast('Already at defaults'); return; }
    if (confirm(`Reset ${Object.keys(changes).length} ${sec.title} setting(s) to defaults?`)) setMany(changes, 'Reset');
  });
  $('#expSet').onclick = () => {
    const blob = new Blob([JSON.stringify({ app: 'BlackLab', exportedAt: new Date().toISOString(), settings: S.values }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `blacklab-settings-${new Date().toISOString().slice(0, 10)}.json`; a.click();
  };
  $('#impSet').onclick = () => $('#impFile').click();
  $('#impFile').onchange = async (e) => {
    try {
      const j = JSON.parse(await e.target.files[0].text()); const incoming = j.settings || j;
      const changes = Object.fromEntries(Object.entries(incoming).filter(([k, v]) => defs[k] && !defs[k].readOnly && !same(S.values[k], v)));
      if (!Object.keys(changes).length) { toast('Nothing to change'); return; }
      if (confirm(`Apply ${Object.keys(changes).length} setting change(s)?`)) setMany(changes, 'Import');
    } catch (err) { toast('Not a BlackLab settings file'); }
  };
};

// ---------------- desktop tools (companion)
// ---------------------------------------------------------------- Obsidian vault (the companion's second brain)
Views.vault = () => {
  const c = S.companion;
  if (!c) return `<div class="view-inner"><h1>Obsidian vault</h1><div class="card" style="margin-top:16px"><p class="muted">The vault lives on this computer and is run by the desktop companion. Start it, then reload.</p></div></div>`;
  const v = c.vault;
  if (!v) return `<div class="view-inner"><h1>Obsidian vault</h1><div class="card" style="margin-top:16px"><p class="muted">The vault is turned off (config.json <span class="mono">"vault": false</span>).</p></div></div>`;
  const nodes = S.vaultNodes || [];
  const when = (t) => t ? new Date(t * 1000).toLocaleString() : 'not yet';
  return `<div class="view-inner"><div class="hero"><h1>Obsidian vault</h1><span class="chip accent">${esc(v.name)}</span></div>
    <div class="grid g3">
      <div class="tile"><span class="k">Notes</span><span class="v">${v.notes}</span><span class="s">${v.today} logged today</span></div>
      <div class="tile"><span class="k">Saved versions</span><span class="v">${v.commits}</span><span class="s">${v.remote ? 'pushed to ' + esc(v.remote) : 'local git (add a private remote to back up off-site)'}</span></div>
      <div class="tile"><span class="k">AI registry</span><span class="v">${v.registry}</span><span class="s">${v.last_visitor ? esc(v.last_visitor.agent) + ' · ' + esc(v.last_visitor.when) : 'no visitors yet'}</span></div>
    </div>
    <div class="grid g2" style="margin-top:16px">
      <div class="card"><h2>Run it</h2><div class="stack">
        <button data-vact="open">Open in Obsidian</button>
        <button class="ghost" data-vact="sync">Sync now (meetings, briefs, task logs, projects, files, nodes)</button>
        <div class="small muted">Phone: last sync ${esc(when(v.last_sync?.phone))} · desktop pass ${esc(when(v.last_sync?.desktop))}</div>
        <div class="small muted mono">${esc(v.path)}</div>
        ${(v.errors || []).length ? `<div class="small" style="color:var(--danger)">${v.errors.map(esc).join('<br>')}</div>` : ''}</div></div>
      <div class="card"><h2>Capture & search</h2><div class="stack">
        <textarea id="vText" rows="3" placeholder="A note for 00 Inbox — or pick a node below to add knowledge to it"></textarea>
        <div class="row"><select id="vNode"><option value="">00 Inbox</option>${nodes.map((n) => `<option value="${esc(n.id)}">◆ ${esc(n.name)}</option>`).join('')}</select>
        <button data-vact="save">Save</button></div>
        <div class="row"><input id="vQuery" placeholder="Ask your notes, find a skill, or search the vault or library"><button data-vact="ask">Ask</button><button class="ghost" data-vact="search">Vault</button><button class="ghost" data-vact="skills">Skills</button><button class="ghost" data-vact="library">Library</button></div>
        <div id="vOut" class="small"></div></div></div>
    </div>
    <div class="card" style="margin-top:16px"><h2>Recall, skills & facility</h2><div class="grid g3">
      <div class="tile"><span class="k">Ask my notes</span><span class="v" style="font-size:18px">${S.recall ? (S.recall.kb_core ? S.recall.indexed + ' notes' : 'keyword only') : '…'}</span><span class="s">${S.recall ? (S.recall.kb_core ? 'kb-core · ' + esc(S.recall.embed_model || '') + (S.recall.pending ? ' · ' + S.recall.pending + ' waiting' : '') : 'start kb-core for semantic recall') : 'Loading…'}</span></div>
      <div class="tile"><span class="k">Agent skills</span><span class="v" style="font-size:18px">${S.skills && S.skills.skills ? S.skills.skills : '…'}</span><span class="s">${S.skills && S.skills.libraries ? Object.entries(S.skills.libraries).map(([k, n]) => esc(k) + ' ' + n).join(' · ') : 'Anthropic · Vercel · OpenAI · Google · Hugging Face'}</span></div>
      <div class="tile"><span class="k">Activity ledger</span><span class="v" style="font-size:18px">${S.ledger ? (S.ledger.ok ? 'Intact' : 'Broken') : '…'}</span><span class="s">${S.ledger ? S.ledger.entries + ' hash-chained entries' + (S.ledger.ok ? '' : ' · broken at ' + S.ledger.broken_at) : 'Loading…'}</span></div></div>
      <div class="row" style="margin-top:12px"><button class="ghost" data-vact="shift">Shift prep</button><button class="ghost" data-vact="round">Start EOC rounds</button><input id="vPanel" placeholder="Room, load or panel — e.g. 214, AHU-5"><button class="ghost" data-vact="panel">Breaker</button><button class="ghost" data-vact="creds">Credentials</button></div>
      <div id="vOps" class="small" style="margin-top:8px"></div></div>
    <div class="card" style="margin-top:16px"><h2>Offline library</h2>${S.libStatus ? `<div class="grid g3">
      <div class="tile"><span class="k">On disk</span><span class="v" style="font-size:18px">${S.libStatus.gb_on_disk} / ${S.libStatus.gb_planned} GB</span><span class="s">${S.libStatus.books_ok} of ${S.libStatus.books_planned} books · ${S.libStatus.docs_ok} documents</span></div>
      <div class="tile"><span class="k">Budget</span><span class="v" style="font-size:18px">${S.libStatus.nodes_gb} + ${S.libStatus.core_gb} GB</span><span class="s">nodes & subnodes + core (Wikipedia)</span></div>
      <div class="tile"><span class="k">Reader</span><span class="v" style="font-size:18px">${S.libStatus.serving ? 'Running' : 'Not installed'}</span><span class="s">${S.libStatus.serving ? `<a href="http://127.0.0.1:${S.libStatus.port}" target="_blank" rel="noopener">open the library</a>` : 'install kiwix-tools to read & search offline'}</span></div></div>` : '<span class="muted">Loading…</span>'}</div>
    <div class="card" style="margin-top:16px"><h2>Graph</h2>${S.vaultGraph ? `<div class="grid g3">
      <div class="tile"><span class="k">Links</span><span class="v" style="font-size:18px">${S.vaultGraph.edges}</span><span class="s">${S.vaultGraph.with_up} of ${S.vaultGraph.notes} notes wired to a node · ${S.vaultGraph.orphans} orphans · ${S.vaultGraph.unresolved} unresolved</span></div>
      <div class="tile"><span class="k">Related (local AI)</span><span class="v" style="font-size:18px">${S.vaultGraph.related ? S.vaultGraph.related.pairs + ' pairs' : '—'}</span><span class="s">${S.vaultGraph.related ? 'between ' + S.vaultGraph.related.notes + ' notes · bar ' + S.vaultGraph.related.threshold + ' · ' + esc(S.vaultGraph.related.at) : 'computed on the next sync'}</span></div>
      <div class="tile"><span class="k">Plugins</span><span class="v" style="font-size:18px">${S.vaultGraph.plugins.filter((p) => p.installed).length} / ${S.vaultGraph.plugins.length}</span><span class="s">${S.vaultGraph.plugins.map((p) => esc(p.id) + ' ' + esc(p.version) + (p.installed ? ' ✓' : ' ✗')).join(' · ')}</span></div></div>
      <div class="row" style="margin-top:12px"><button data-vact="graph">Open the graph</button><input id="vShow" placeholder="Show a note in the graph — e.g. RTU-4, Facility"><button class="ghost" data-vact="show">Show</button></div>
      <div class="small muted" style="margin-top:6px">Hubs: ${S.vaultGraph.hubs.map(esc).join(' · ')} · Bookmarks › Graph views has a saved graph per node · 90 System/Graph Health lists orphans with suggested homes.</div>` : '<span class="muted">Loading…</span>'}</div>
    <div class="card" style="margin-top:16px"><h2>The 13 nodes</h2><p class="muted small">Every node serves one function — turn what happens into better decisions and actions. Members are notes that belong to the node; signals are timeline entries from the last 14 days.</p>
      <div class="grid g3">${nodes.map((n) => `<div class="tile"><span class="k">◆ ${esc(n.name)}</span><span class="v" style="font-size:18px">${n.members} notes</span><span class="s">${n.signals} signals · ${esc(n.mission.slice(0, 90))}… <a href="#" data-vshow="${esc(n.name)}">graph ↗</a></span></div>`).join('') || '<span class="muted">Loading…</span>'}</div></div>
    <div class="card" style="margin-top:16px"><h2>Recent versions</h2>${(v.recent_commits || []).map((h) => `<div class="small mono">${esc(h.hash)} · ${esc(h.when)} · ${esc(h.message)}</div>`).join('') || '<span class="muted">none yet</span>'}</div>
  </div>`;
};
Views.vault.bind = () => {
  if (!S.vaultNodes && Companion.ok) Companion.req('/local/vault/nodes', {}).then((r) => { S.vaultNodes = r.nodes; render(); }).catch(() => {});
  if (!S.libStatus && Companion.ok) Companion.req('/local/vault/library_status', {}).then((r) => { S.libStatus = r; render(); }).catch(() => {});
  if (!S.vaultGraph && Companion.ok) Companion.req('/local/vault/graph', {}).then((r) => { S.vaultGraph = r; render(); }).catch(() => {});
  if (!S.recall && Companion.ok) Companion.req('/local/vault/recall_status', {}).then((r) => { S.recall = r; render(); }).catch(() => {});
  if (!S.skills && Companion.ok) Companion.req('/local/vault/skills', {}).then((r) => { S.skills = r; render(); }).catch(() => {});
  if (!S.ledger && Companion.ok) Companion.req('/local/vault/ledger', {}).then((r) => { S.ledger = r; render(); }).catch(() => {});
  document.querySelectorAll('[data-vshow]').forEach((a) => a.onclick = async (e) => {
    e.preventDefault();
    try { const r = await Companion.req('/local/vault/show', { query: a.dataset.vshow }); toast(r.message || 'Opening'); } catch (err) { toast(err.message); }
  });
  const out = (h) => { const o = $('#vOut'); if (o) o.innerHTML = h; };
  document.querySelectorAll('[data-vact]').forEach((b) => b.onclick = async () => {
    const a = b.dataset.vact;
    try {
      if (a === 'open') { await Companion.req('/local/vault/open', {}); toast('Opening Obsidian'); }
      if (a === 'graph') { const r = await Companion.req('/local/vault/show', { query: '' }); toast(r.message || 'Opening the graph'); }
      if (a === 'show') { const q = $('#vShow').value.trim(); if (q) { const r = await Companion.req('/local/vault/show', { query: q }); out(esc(r.message || '')); } }
      if (a === 'sync') { await Companion.req('/local/vault/sync', {}); toast('Syncing the vault'); setTimeout(async () => { await Companion.detect(); S.vaultNodes = null; S.vaultGraph = null; render(); }, 6000); }
      if (a === 'save') {
        const text = $('#vText').value.trim(); const node = $('#vNode').value; if (!text) return;
        const r = node ? await Companion.req('/local/vault/node_add', { node, content: text, author: 'BlackLab Desktop' })
                       : await Companion.req('/local/vault/capture', { text, source: 'BlackLab Desktop' });
        out(r.ok === false ? esc(r.error || 'Not saved') : 'Saved ' + esc(r.path || ''));
        if (r.ok !== false) $('#vText').value = '';
      }
      if (a === 'library') {
        const r = await Companion.req('/local/vault/library', { query: $('#vQuery').value, limit: 8 });
        out((r.results || []).map((h) => h.error ? esc(h.error) : `<div><a href="${esc(h.url)}" target="_blank" rel="noopener"><b>${esc(h.title)}</b></a> <span class="muted">${esc(h.book)}</span><br>${esc(h.snippet.slice(0, 220))}</div>`).join('<hr>') || 'No matches.');
      }
      const ops = (html) => { const el = $('#vOps'); if (el) el.innerHTML = html; };
      if (a === 'ask') {
        out('<span class="muted">Thinking with your notes…</span>');
        const r = await Companion.req('/local/vault/ask', { query: $('#vQuery').value, answer: true });
        out(r.ok === false ? esc(r.error || 'No answer') : `<div style="white-space:pre-wrap">${esc(r.answer || '')}</div><div class="muted" style="margin-top:6px">${esc(r.engine || '')} · ${(r.sources || []).map(esc).join(' · ')}</div>`);
      }
      if (a === 'skills') {
        const r = await Companion.req('/local/vault/skills', { query: $('#vQuery').value });
        out((r.results || []).map((h) => `<div><a href="${esc(h.url)}" target="_blank" rel="noopener"><b>${esc(h.name)}</b></a> <span class="muted">${esc(h.library)}</span><br>${esc(h.description.slice(0, 220))}</div>`).join('<hr>') || esc(r.message || r.error || 'No skills match.'));
      }
      if (a === 'shift') { const r = await Companion.req('/local/vault/shift_prep', {}); ops(`<div style="white-space:pre-wrap">${esc(r.message || r.error || '')}</div>`); }
      if (a === 'round') { const r = await Companion.req('/local/vault/round', { kind: 'eoc', source: 'BlackLab Desktop' }); ops(esc(r.message || r.error || '')); }
      if (a === 'panel') { const r = await Companion.req('/local/vault/panel', { query: $('#vPanel').value }); ops(esc(r.message || r.error || '')); }
      if (a === 'creds') { const r = await Companion.req('/local/vault/credentials', {}); ops(esc(r.message || r.error || '')); }
      if (a === 'search') {
        const r = await Companion.req('/local/vault/search', { query: $('#vQuery').value, limit: 8 });
        out((r.results || []).map((h) => `<div><b>${esc(h.title)}</b> <span class="muted">${esc(h.path)}</span><br>${esc(h.snippet.slice(0, 220))}</div>`).join('<hr>') || 'No matches.');
      }
    } catch (e) { out(esc(e.message)); }
  });
};

Views.desktop = () => {
  const c = S.companion;
  if (!c) return `<div class="view-inner"><h1>Desktop tools</h1>
    <div class="card" style="margin-top:16px"><h2>Start the desktop companion</h2>
    <p class="muted">The companion is a small local program (Python, no installs) that gives this UI desktop powers: an <b>offline desktop assistant</b> on your Ollama models, USB pairing via adb, pulling files and backups from the phone, ElevenLabs voice, and developer-docs search. It only listens on 127.0.0.1.</p>
    <pre class="mono card" style="background:var(--surface-2)">cd ~/Desktop/BlackLab/desktop && ./blacklab-desktop.sh</pre>
    <p class="muted small">Then reload this page. The installer also adds “BlackLab Desktop” to your app launcher.</p>
    <button id="recheck">Check again</button></div></div>`;
  return `<div class="view-inner"><div class="hero"><h1>Desktop tools</h1><span class="chip accent">companion ${esc(c.version)}</span></div>
    <div class="grid g3">
      <div class="tile"><span class="k">Desktop AI</span><span class="v" style="font-size:18px">${esc(S.ui.model || c.model || '—')}</span><span class="s">${c.ollama?.length ? c.ollama.length + ' Ollama model(s), offline' : 'Ollama not running'}</span></div>
      <div class="tile"><span class="k">USB</span><span class="v" style="font-size:18px">${c.adb?.devices?.length ? esc(c.adb.devices[0].model || c.adb.devices[0].serial) : 'No phone'}</span><span class="s">${c.adb?.devices?.length ? esc(c.adb.devices[0].state) : (c.adb?.available ? 'plug in & allow USB debugging' : 'adb not found')}</span></div>
      <div class="tile"><span class="k">Voice & docs</span><span class="v" style="font-size:18px">${c.eleven ? 'ElevenLabs' : 'Browser voice'}</span><span class="s">${c.devdocs ? 'Developer docs search on' : 'No docs key'}</span></div>
    </div>
    <div class="grid g2" style="margin-top:16px">
      <div class="card"><h2>Phone ⇄ desktop</h2><div class="stack">
        <button class="ghost" data-act="forward">Set up USB link (adb forward)</button>
        <button class="ghost" data-act="pull-backups">Pull phone backups to Desktop › BlackLab AI › Downloads</button>
        <button class="ghost" data-act="pull-documents">Pull documents</button>
        <button class="ghost" data-act="pull-photos">Pull photos</button>
        <button class="ghost" data-act="backup">Back up the phone now</button>
        <button class="ghost" data-act="open-folder">Open the BlackLab AI folder</button></div></div>
      <div class="card"><h2>Ask the desktop</h2><div class="stack">
        ${['System status', 'What is using my disk space?', 'Find files named budget', 'Open my Downloads folder', 'Make a desktop note: call the roofer Monday', 'Search my documents for roof inspection', 'Remember that Trane is our chiller vendor', 'Show chat history for chiller', 'Android docs: CameraX extensions'].map((q) => `<span class="chip btn" data-q="${esc(q)}">${esc(q)}</span>`).join('')}
        <div class="small muted">These run on this computer through the companion. With Ollama they work fully offline.</div></div></div>
    </div>
    <div class="grid g2" style="margin-top:16px">
      <div class="card"><h2>Background jobs</h2><div class="stack">
        <div id="jobs" class="small muted">Loading…</div>
        <button class="ghost" data-act="job-brief">Write the morning brief now</button>
        <button class="ghost" data-act="job-weekly">Write the weekly summary now</button>
        <div class="small muted">Recordings and nameplate photos from the phone run here too. Results go back to the phone as notifications and land in Desktop › BlackLab AI › Documents.</div></div></div>
      <div class="card"><h2>Equipment</h2><div class="stack">
        <pre id="equipment" class="small" style="white-space:pre-wrap;margin:0">Loading…</pre>
        <div class="small muted">Built from nameplate photos on the phone. Ask “history on RTU-4” or “which units use R-22?”.</div></div></div>
    </div></div>`;
};
async function loadJobs() {
  const el = $('#jobs'); if (!el) return;
  try {
    const r = await Companion.req('/local/jobs');
    const label = { queued: 'Waiting', running: 'Working', done: 'Done', failed: 'Failed' };
    el.innerHTML = r.jobs.length ? r.jobs.slice(0, 8).map((j) => `<div>• <b>${esc(j.title)}</b> · ${esc(j.kind)} · ${esc(j.status === 'running' ? j.progress : label[j.status] || j.status)}${j.error ? ' · ' + esc(j.error) : ''}</div>`).join('')
      : 'No jobs yet.' + (r.whisper ? '' : ' Transcription needs ./daddyshome --install-whisper.');
    const eq = await Companion.req('/local/equipment'); const pre = $('#equipment'); if (pre) pre.textContent = eq.text;
    if (r.jobs.some((j) => j.status === 'running' || j.status === 'queued')) setTimeout(loadJobs, 4000);
  } catch (e) { el.textContent = e.message; }
}

Views.desktop.bind = () => {
  if (Companion.ok) loadJobs();
  const re = $('#recheck'); if (re) re.onclick = async () => { await Companion.detect(); render(); toast(Companion.ok ? 'Companion found' : 'Still not running'); };
  $$('[data-q]').forEach((c) => c.onclick = () => { S.ui.target = 'desktop'; saveUi(); go('assistant'); ask(c.dataset.q); });
  $$('[data-act]').forEach((b) => b.onclick = async () => {
    const a = b.dataset.act; b.disabled = true;
    try {
      if (a === 'forward') { const r = await Companion.req('/local/adb/forward', { port: 8765 }); toast(r.message); await connectPhone(true); }
      else if (a.startsWith('pull-')) {
        if (S.mode !== 'phone') throw new Error('Needs a live phone link');
        const r = await Companion.req('/local/pull', { kind: a.slice(5), phone: { url: Phone.url, token: Phone.token } }); toast(r.message, 5000);
      } else if (a === 'backup') { if (S.mode !== 'phone') throw new Error('Needs a live phone link'); const r = await Phone.req('/api/backup', { body: {} }); toast('Backed up: ' + r.file); }
      else if (a === 'open-folder') await Companion.req('/local/open', { path: 'blacklab ai' });
      else if (a === 'job-brief' || a === 'job-weekly') {
        await Companion.req('/local/jobs', { kind: a.slice(4), title: a === 'job-brief' ? 'Morning brief' : 'Weekly summary' });
        toast('Started. It takes about a minute.'); loadJobs();
      }
    } catch (e) { toast(e.message); }
    b.disabled = false;
  });
};

// ---------------- connect
Views.connect = () => {
  const i = D.info;
  const onPages = location.hostname.endsWith('github.io');
  return `<div class="view-inner"><h1>Connect</h1><div class="muted" style="margin-bottom:18px">Three ways to reach your phone. Use whichever fits where you are.</div>
  <div class="grid g2">
    <div class="card"><h2>1 · Live phone link <span class="chip ${S.mode === 'phone' ? 'accent' : ''}">${S.mode === 'phone' ? 'connected' : 'USB / Wi-Fi'}</span></h2>
      <ol class="steps"><li>On the phone: Settings › <b>Assistant &amp; desktop</b> › turn on <b>Desktop link</b>.</li>
      <li>USB: plug in and run the companion (it sets up <code>adb forward</code>), or Wi-Fi: turn on “Allow Wi-Fi” and use the phone's address.</li>
      <li>Enter the 8-character pairing code shown on the phone.</li></ol>
      ${onPages ? '<div class="notice small" style="margin:10px 0">On GitHub Pages, browsers only allow the USB address (127.0.0.1). For Wi-Fi, open the UI from the companion instead.</div>' : ''}
      <div class="stack" style="margin-top:10px">
        <label class="small muted">Phone address<input id="phUrl" value="${esc(Phone.url)}" style="width:100%;margin-top:4px"></label>
        <label class="small muted">Pairing code<input id="phTok" value="${esc(Phone.token)}" placeholder="ABCD 2345" style="width:100%;margin-top:4px;font-family:var(--mono);letter-spacing:.12em" autocomplete="off"></label>
        <div class="row"><button id="phConn">${S.mode === 'phone' ? 'Reconnect' : 'Connect'}</button>${S.mode === 'phone' ? `<span class="muted small">${esc(i.device || '')} · BlackLab ${esc(i.version || '')}</span>` : ''}</div>
      </div></div>
    <div class="card"><h2>2 · Google account <span class="chip ${S.mode === 'cloud' ? 'pro' : ''}">${S.google.token ? 'signed in' : 'from anywhere'}</span></h2>
      <p class="muted">Signs in with Google and reads the sync file your phone keeps in your Drive's hidden app folder. Settings and assistant commands travel back the same way.</p>
      <div class="row" style="margin:10px 0">${S.google.token ? `<span>${esc(S.google.email)}</span><span class="spacer"></span><button class="ghost" id="gOut">Sign out</button><button class="ghost" id="gLoad">Load now</button>` : '<button id="gIn">Sign in with Google</button>'}</div>
      ${phoneCloudNotice()}
      ${clientIdProblem(Cloud.savedClientId) ? `<div class="notice small" style="margin:10px 0;border-color:var(--danger)"><b style="color:var(--danger)">Saved client ID won't work.</b> ${esc(clientIdProblem(Cloud.savedClientId))}</div>` : ''}
      <details ${Cloud.clientId ? '' : 'open'}><summary class="small muted">OAuth client ID ${Cloud.clientId ? '(set)' : Cloud.savedClientId ? '(invalid — replace it)' : '(required once)'}</summary>
        <ol class="steps small"><li>Google Cloud Console › APIs &amp; Services › Credentials › <b>Create OAuth client ID › Web application</b> — in the same project as the phone's Android client.</li>
        <li>Authorized JavaScript origins: <code>${esc(location.origin)}</code>${onPages ? '' : ' and <code>https://paulmmoore3416.github.io</code>'}</li>
        <li>Enable the <b>Google Drive API</b>; add yourself as a test user on the OAuth consent screen.</li>
        <li>If Google's pop-up says <b>Error 400: origin_mismatch</b> or <b>redirect_uri_mismatch</b>, the origin in step 2 is missing from the client. If it says <b>access_denied</b> / “has not completed the Google verification process”, add your account under Test users.</li>
        <li>The client ID is saved in this browser only; another browser or address (for example the companion at <code>127.0.0.1:8770</code>) needs it saved again, and its origin authorized.</li></ol>
        <div class="row" style="margin-top:8px"><input id="gCid" style="flex:1" placeholder="xxxxxxxx.apps.googleusercontent.com" value="${esc(LS.get('googleClientId', ''))}"><button class="ghost" id="gSave">Save</button></div></details>
    </div>
    <div class="card"><h2>3 · Desktop companion <span class="chip ${Companion.ok ? 'accent' : ''}">${Companion.ok ? 'running' : 'off'}</span></h2>
      <p class="muted">Local helper for desktop tasks, offline AI (Ollama), adb and voice. <a href="#/desktop">Desktop tools →</a></p></div>
    <div class="card"><h2>API keys on the phone</h2>
      <p class="muted small">Send keys to the phone's encrypted keystore over the live link. They are never stored in this browser or this site.</p>
      ${['elevenlabs', 'developer_knowledge'].map((k) => `<div class="row" style="margin:6px 0"><span style="width:170px">${k === 'elevenlabs' ? 'ElevenLabs' : 'Developer Knowledge'}</span><span class="chip ${i.keys?.[k] ? 'accent' : ''}">${i.keys?.[k] ? 'set' : 'not set'}</span><input type="password" id="key-${k}" placeholder="paste to replace" style="flex:1" autocomplete="off"></div>`).join('')}
      <button class="ghost" id="sendKeys" ${S.mode === 'phone' ? '' : 'disabled'}>Send to phone</button></div>
  </div></div>`;
};
/** What the phone says about its side of cloud sync (from the snapshot): the web copy only updates when the phone uploads. */
function phoneCloudNotice() {
  const c = D.info.cloud; if (!S.snap || !c) return '';
  const signedIn = !!D.info.google?.account;
  let msg = '';
  if (!c.enabled) msg = 'Cloud sync is <b>off</b> on the phone (Settings › Assistant &amp; desktop › Cloud sync), so Drive never gets new data.';
  else if (!signedIn) msg = 'Cloud sync is on, but the phone <b>isn’t signed in to Google</b>, so nothing reaches Drive. On the phone: Settings › Assistant &amp; desktop › Google account (needs the Android OAuth client in Google Cloud).';
  else if (c.error) msg = 'The phone’s last cloud sync failed: ' + esc(c.error);
  else if (c.lastSync) return `<div class="muted small" style="margin:6px 0">Phone uploaded to Drive ${ago(c.lastSync)}${D.info.google.account ? ' as ' + esc(D.info.google.account) : ''}.</div>`;
  else return '';
  return `<div class="notice small" style="margin:10px 0;border-color:var(--warn)">${msg}</div>`;
}
Views.connect.bind = () => {
  $('#phConn').onclick = async () => {
    LS.set('phoneUrl', $('#phUrl').value.trim() || 'http://127.0.0.1:8765');
    LS.set('phoneToken', $('#phTok').value.replace(/\s+/g, '').toUpperCase());
    if (await connectPhone(false)) { toast('Connected to the phone'); go('dashboard'); }
  };
  const gi = $('#gIn'); if (gi) gi.onclick = () => Cloud.signIn('consent');
  const go_ = $('#gOut'); if (go_) go_.onclick = () => Cloud.signOut();
  const gl = $('#gLoad'); if (gl) gl.onclick = () => Cloud.load(true);
  $('#gSave').onclick = () => {
    const v = $('#gCid').value.trim(); const bad = clientIdProblem(v);
    if (bad) { toast(bad, 8000); return; }
    LS.set('googleClientId', v); Cloud.client = null; Cloud.init(); renderGsi(); render(); toast('Saved');
  };
  $('#sendKeys').onclick = async () => {
    const body = {}; ['elevenlabs', 'developer_knowledge'].forEach((k) => { const v = $('#key-' + k).value.trim(); if (v) body[k] = v; });
    if (!Object.keys(body).length) { toast('Paste a key first'); return; }
    try { const r = await Phone.req('/api/secrets', { body }); toast('Stored on phone: ' + r.set.join(', ')); await loadFromPhone(); } catch (e) { toast(e.message); }
  };
};


// ---------------- appearance (writes the phone's color settings; the phone applies them on sync)
const APP_KEYS = ['theme_mode', 'dynamic_color', 'accent_personal', 'accent_professional', 'secondary_personal', 'secondary_professional', 'light_style', 'high_contrast', 'visual_style', 'backdrop', 'backdrop_intensity', 'reduce_motion',
  'dark_style', 'corner_style', 'ui_scale', 'font_family', 'reading_font', 'reading_size'];
Views.appearance = () => {
  const V = S.values; const P = window.BL_DATA?.palettes || []; const L = window.BL_DATA?.lightStyles || []; const DS = window.BL_DATA?.darkStyles || [];
  const acct = S.ui.appAcct || (V.active_profile === 'PROFESSIONAL' ? 'professional' : 'personal');
  const cur = V['accent_' + acct] || (acct === 'professional' ? 'COBALT' : 'MINT');
  const sec = V['secondary_' + acct] || 'AUTO';
  const dark = document.documentElement.getAttribute('data-theme') !== 'light';
  const conic = (p) => { const c = dark ? p.dark : p.light; return `conic-gradient(from 200deg, ${c[0]} 0 62%, ${c[1]} 62% 87%, ${c[2]} 87% 100%)`; };
  const seg = (key, opts) => `<div class="seg">${opts.map(([v, l]) => `<button data-set="${key}" data-val="${esc(v)}" class="${String(V[key] ?? '') === String(v) ? 'on' : ''}">${esc(l)}</button>`).join('')}</div>`;
  return `<div class="view-inner">
    <div class="hero"><div><h1>Appearance</h1><div class="muted">Changes here go to your phone — instantly over the phone link, or through Google Drive on its next sync. This page previews them right away.</div></div></div>
    ${S.snap || S.schema ? '' : '<div class="notice">Connect your phone or sign in with Google so changes reach the phone. Until then they’re saved here and pushed when it connects.</div>'}
    <div class="grid g2">
      <div class="card"><h2>Theme</h2>
        <div class="stack">
          ${seg('theme_mode', [['DARK', 'Dark'], ['LIGHT', 'Light'], ['SYSTEM', 'System'], ['AUTO', 'Sunset to sunrise']])}
          <div class="row wrap"><span class="muted small" style="width:110px">Visual style</span>${seg('visual_style', [['GLASS', 'Glass'], ['SOLID', 'Solid']])}</div>
          <div class="row wrap"><span class="muted small" style="width:110px">Backdrop</span>${seg('backdrop', [['AURORA', 'Aurora · moving'], ['GRADIENT', 'Gradient · still'], ['PLAIN', 'Plain']])}</div>
          <div class="row wrap"><span class="muted small" style="width:110px">Corners</span>${seg('corner_style', [['ROUND', 'Round'], ['SOFT', 'Soft'], ['SQUARE', 'Square']])}</div>
          <div class="row wrap"><span class="muted small" style="width:110px">Font</span>${seg('font_family', [['INTER', 'Inter'], ['SYSTEM', 'System'], ['SERIF', 'Serif']])}</div>
          <div class="row wrap"><span class="muted small" style="width:110px">Phone size</span>${seg('ui_scale', [[100, 'Comfortable'], [92, 'Compact'], [85, 'Dense']])}</div>
          <div class="row wrap"><span class="muted small" style="width:110px">Reading font</span>${seg('reading_font', [['SAME', 'Same'], ['SERIF', 'Serif'], ['SANS', 'Inter'], ['MONO', 'Mono']])}</div>
          <div class="row"><span class="muted small" style="width:110px">Intensity</span><input type="range" id="apInt" min="0" max="100" step="5" value="${V.backdrop_intensity ?? 60}" style="flex:1"><span class="mono" id="apIntV">${V.backdrop_intensity ?? 60}%</span></div>
          <label class="row"><label class="switch"><input type="checkbox" id="apHc" ${V.high_contrast ? 'checked' : ''}><span></span></label> Stronger contrast <span class="muted small">— WCAG AAA text and deeper accents</span></label>
          <label class="row"><label class="switch"><input type="checkbox" id="apRm" ${V.reduce_motion ? 'checked' : ''}><span></span></label> Reduce motion <span class="muted small">— the aurora holds still</span></label>
          <label class="row"><label class="switch"><input type="checkbox" id="apDyn" ${V.dynamic_color ? 'checked' : ''}><span></span></label> Material You <span class="muted small">— wallpaper colors on the phone instead of a palette</span></label>
        </div>
      </div>
      <div class="card"><h2>Preview</h2>
        <div class="preview" style="background:linear-gradient(135deg, color-mix(in srgb, var(--accent) 22%, var(--bg)), color-mix(in srgb, var(--accent-2) 16%, var(--bg)) 60%, color-mix(in srgb, var(--accent-3) 18%, var(--bg)))">
          <div class="tile" style="position:relative;max-width:260px"><span class="k">Open tasks</span><span class="v">12</span><span class="s">3 today · <span style="color:var(--accent)">link text</span></span></div>
          <div class="row" style="position:relative;margin-top:12px"><button>Primary</button><span class="chip accent">Selected</span><span class="chip" style="color:var(--accent-2)">Secondary</span></div>
        </div>
      </div>
    </div>
    <div class="card" style="margin-top:16px"><h2>Palette<span class="spacer"></span>
        <div class="seg"><button data-acct="personal" class="${acct === 'personal' ? 'on' : ''}">${esc(D.profileName('PERSONAL'))}</button><button data-acct="professional" class="${acct === 'professional' ? 'on' : ''}">${esc(D.profileName('PROFESSIONAL'))}</button></div></h2>
      <div class="muted small" style="margin-bottom:10px">Main color with its complementary secondary and a tertiary, tuned for light and dark.</div>
      <div class="swatches">${P.map((p) => `<button class="swatch ${p.id === cur && !V.dynamic_color ? 'sel' : ''}" data-pal="${p.id}" title="${esc(p.label)}"><span class="dot" style="background:${conic(p)}"></span>${esc(p.label)}</button>`).join('')}</div>
      <h3 style="margin:18px 0 10px">Secondary color</h3>
      <div class="swatches"><button class="swatch ${sec === 'AUTO' ? 'sel' : ''}" data-sec="AUTO"><span class="dot" style="background:${(P.find((p) => p.id === cur) || P[0])?.[dark ? 'dark' : 'light'][1]}"></span>Auto</button>
        ${P.filter((p) => p.id !== cur).map((p) => `<button class="swatch ${sec === p.id ? 'sel' : ''}" data-sec="${p.id}"><span class="dot" style="background:${dark ? p.dark[0] : p.light[0]}"></span>${esc(p.label)}</button>`).join('')}</div>
    </div>
    <div class="card" style="margin-top:16px"><h2>Dark mode style</h2>
      <div class="style-cards">${DS.map((st) => `<button class="style-card ${String(V.dark_style || 'GRAPHITE') === st.id ? 'sel' : ''}" data-dstyle="${st.id}">
        <div class="page" style="background:${st.background}"><div class="mini" style="background:${st.card};border:1px solid ${st.line}"><span class="bar" style="background:#E9EBEF;width:80%"></span><span class="bar" style="background:#A6ADB8;width:55%"></span><span class="bar" style="background:var(--accent)"></span></div></div>
        <div style="margin-top:8px;font-weight:650">${esc(st.label)}</div><div class="muted small">${esc(st.blurb)}</div></button>`).join('')}</div>
    </div>
    <div class="card" style="margin-top:16px"><h2>Light mode style</h2>
      <div class="style-cards">${L.map((st) => `<button class="style-card ${String(V.light_style || 'CLEAN') === st.id ? 'sel' : ''}" data-style="${st.id}">
        <div class="page" style="background:${st.background}"><div class="mini" style="background:${st.card}"><span class="bar" style="background:${st.ink};width:80%"></span><span class="bar" style="background:${st.inkSoft};width:55%"></span><span class="bar" style="background:var(--accent)"></span></div></div>
        <div style="margin-top:8px;font-weight:650">${esc(st.label)}</div><div class="muted small">${esc(st.blurb)}</div></button>`).join('')}</div>
    </div>
  </div>`;
};
Views.appearance.bind = () => {
  const acct = S.ui.appAcct || (S.values.active_profile === 'PROFESSIONAL' ? 'professional' : 'personal');
  $$('[data-set]').forEach((b) => b.onclick = () => setMany({ [b.dataset.set]: /^\d+$/.test(b.dataset.val) ? Number(b.dataset.val) : b.dataset.val }, 'Appearance'));
  $$('[data-acct]').forEach((b) => b.onclick = () => { S.ui.appAcct = b.dataset.acct; saveUi(); render(); });
  $$('[data-pal]').forEach((b) => b.onclick = () => setMany({ ['accent_' + acct]: b.dataset.pal, dynamic_color: false }, 'Palette'));
  $$('[data-sec]').forEach((b) => b.onclick = () => setMany({ ['secondary_' + acct]: b.dataset.sec, dynamic_color: false }, 'Secondary color'));
  $$('[data-style]').forEach((b) => b.onclick = () => setMany({ light_style: b.dataset.style }, 'Light style'));
  $$('[data-dstyle]').forEach((b) => b.onclick = () => setMany({ dark_style: b.dataset.dstyle }, 'Dark style'));
  const r = $('#apInt'); r.oninput = () => { $('#apIntV').textContent = r.value + '%'; S.values.backdrop_intensity = Number(r.value); window.BLFX?.apply(S.values, S.ui); };
  r.onchange = () => setMany({ backdrop_intensity: Number(r.value) }, 'Backdrop');
  $('#apHc').onchange = (e) => setMany({ high_contrast: e.target.checked }, 'Contrast');
  $('#apRm').onchange = (e) => setMany({ reduce_motion: e.target.checked }, 'Motion');
  $('#apDyn').onchange = (e) => setMany({ dynamic_color: e.target.checked }, 'Material You');
};

// ---------------- behavioral health reference (public reference content only — the workspace never leaves the phone)
const BH_TABS = [['resources', 'Crisis & resources'], ['deescalation', 'De-escalation'], ['grounding', 'Grounding'], ['groups', 'Group library'], ['safety', 'Safety rounds'], ['shift', 'Shift checklist'], ['privacy', 'Privacy']];
Views.bh = () => {
  const B = window.BL_DATA?.bh; if (!B) return '<div class="view-inner"><div class="notice">Reference data didn’t load.</div></div>';
  const tab = BH_TABS.some(([k]) => k === S.sub) ? S.sub : 'resources';
  const tabs = `<div class="tabs">${BH_TABS.map(([k, l]) => `<a href="#/bh/${k}" class="${tab === k ? 'on' : ''}">${l}</a>`).join('')}</div>`;
  const phaseColor = ['var(--warn)', '#FF7A59', 'var(--danger)', 'var(--pro)', 'var(--accent)'];
  let body = '';
  if (tab === 'resources') {
    const q = (S.ui.bhQ || '').toLowerCase(); const cat = S.ui.bhCat || '';
    const list = B.resources.filter((r) => (!cat || r.category === cat) && (!q || (r.name + ' ' + r.notes + ' ' + r.category).toLowerCase().includes(q)));
    body = `<div class="row wrap no-print" style="margin-bottom:12px"><input id="bhQ" placeholder="Search resources" value="${esc(S.ui.bhQ || '')}" style="flex:1;min-width:200px">
        <select id="bhCat"><option value="">All categories</option>${B.resourceCategories.map((c) => `<option ${c === cat ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select>
        <button class="ghost small" onclick="window.print()">Print</button></div>
      <div class="grid g3">${list.map((r) => `<div class="card" style="${r.category === 'Crisis' ? 'border-color:color-mix(in srgb,var(--danger) 45%,var(--glass-line))' : ''}"><h3>${esc(r.name)}</h3>
        <div class="muted small" style="margin:2px 0 8px">${esc(r.category)} · ${esc(r.hours)}</div>
        ${r.phone ? `<div>☎ <a href="tel:${esc(r.phone.replace(/[^\d+]/g, ''))}">${esc(r.phone)}</a></div>` : ''}${r.text ? `<div>✉ ${esc(r.text)}</div>` : ''}
        ${r.url ? `<div><a href="${esc(r.url)}" target="_blank" rel="noopener noreferrer">${esc(r.url.replace(/^https?:\/\//, ''))}</a></div>` : ''}
        ${r.notes ? `<div class="muted small" style="margin-top:6px">${esc(r.notes)}</div>` : ''}</div>`).join('')}</div>
      <div class="muted small" style="margin-top:12px">National numbers change occasionally — confirm before relying on one. Your local resources live in the app on your phone.</div>`;
  } else if (tab === 'deescalation') {
    body = `<div class="stack">${B.phases.map((p, i) => `<div class="card phase" style="--c:${phaseColor[i]}"><h3>${esc(p.name)}</h3><div class="muted small" style="margin-bottom:10px">${p.looks.map(esc).join(' · ')}</div>
        <div class="kv"><div><b>Respond</b><ul>${p.respond.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div><div><b>Say</b><ul>${p.say.map((x) => `<li class="say">${esc(x)}</li>`).join('')}</ul><b style="color:var(--danger)">Avoid</b><ul>${p.avoid.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div></div></div>`).join('')}
      <div class="card"><h2>Ground rules</h2><ul>${B.tips.map((t) => `<li>${esc(t)}</li>`).join('')}</ul></div>
      <div class="card"><h2>Trauma-informed principles (SAMHSA)</h2><div class="grid g3">${B.traumaInformed.map((t) => `<div><h3>${esc(t.name)}</h3><div class="muted small">${esc(t.how)}</div></div>`).join('')}</div></div>
      <div class="muted small">General reference — follow your facility's policies, your crisis-prevention training and the nurse in charge.</div></div>`;
  } else if (tab === 'grounding') {
    body = `<div class="grid g2"><div class="card" style="text-align:center"><h2>Box breathing</h2><div class="breath"></div><div class="muted" id="breathTxt">In 4 · hold 4 · out 4 · hold 4</div></div>
      <div class="card"><h2>Techniques</h2><ul class="list">${B.grounding.map((g) => `<li><span class="t"><b>${esc(g.name)}</b><br><span class="muted small">${esc(g.how)}</span></span></li>`).join('')}</ul></div></div>`;
  } else if (tab === 'groups') {
    const cat = S.ui.bhGCat || ''; const list = B.groups.filter((g) => !cat || g.category === cat); const open = Number(S.ui.bhOpen ?? -1);
    body = `<div class="row wrap no-print" style="margin-bottom:12px"><select id="bhGCat"><option value="">All ${B.groups.length} plans</option>${B.groupCategories.map((c) => `<option ${c === cat ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select><span class="spacer"></span><button class="ghost small" onclick="window.print()">Print</button></div>
      <div class="stack">${list.map((g) => { const i = B.groups.indexOf(g); const on = open === i; return `<div class="card"><h2 style="cursor:pointer" data-open="${i}">${esc(g.title)}<span class="spacer"></span><span class="chip accent">${esc(g.category)}</span><span class="chip">${g.minutes} min</span></h2>
        <div class="muted">${esc(g.objective)}</div>
        ${on ? `<div class="kv" style="margin-top:12px"><div><b>Opening</b><p>${esc(g.opener)}</p><b>Activity</b><p>${esc(g.activity)}</p><b>Closing</b><p>${esc(g.closing)}</p></div>
          <div><b>Discussion</b><ul>${g.discussion.map((d) => `<li>${esc(d)}</li>`).join('')}</ul>${g.materials ? `<b>Materials</b><p>${esc(g.materials)}</p>` : ''}${g.adaptations ? `<b>Adaptations</b><p>${esc(g.adaptations)}</p>` : ''}</div></div>` : ''}</div>`; }).join('')}</div>`;
  } else if (tab === 'safety') {
    const area = S.ui.bhArea || B.eoc[0].area; const a = B.eoc.find((x) => x.area === area) || B.eoc[0];
    const groups = [...new Set(a.items.map((i) => i.group))];
    body = `<div class="row wrap no-print" style="margin-bottom:12px"><select id="bhArea">${B.eoc.map((x) => `<option ${x.area === a.area ? 'selected' : ''}>${esc(x.area)}</option>`).join('')}</select>
        <span class="muted small">${B.eoc.reduce((n, x) => n + x.items.length, 0)} checks across ${B.eoc.length} area types</span><span class="spacer"></span><button class="ghost small" onclick="window.print()">Print blank round sheet</button></div>
      <div class="card"><h2>${esc(a.area)} — ligature risk & environment of care</h2>
        <div class="print-only">Unit ________________ Room / location ________________ Date ________ Inspector ________________</div>
        <table class="check-table"><thead><tr><th>Check</th><th>OK</th><th>Issue</th><th>N/A</th><th style="width:34%">Notes</th></tr></thead><tbody>
        ${groups.map((gr) => `<tr><th colspan="5" class="muted small">${esc(gr.toUpperCase())}</th></tr>${a.items.filter((i) => i.group === gr).map((i) => `<tr><td>${esc(i.text)}</td><td class="box"><span></span></td><td class="box"><span></span></td><td class="box"><span></span></td><td></td></tr>`).join('')}`).join('')}
        </tbody></table>
        <div class="kv" style="margin-top:12px"><div><b>Risk level</b><div class="muted small">${B.riskLevels.map(esc).join(' · ')}</div></div><div><b>Mitigation</b><div class="muted small">${B.mitigations.map(esc).join(' · ')}</div></div></div>
      </div><div class="muted small" style="margin-top:10px">Run the round in the app to log findings and send work orders to To-Do. Record official findings in your facility's system.</div>`;
  } else if (tab === 'shift') {
    const phases = [...new Set(B.checklist.map((c) => c.phase))];
    body = `<div class="row no-print" style="margin-bottom:12px"><span class="spacer"></span><button class="ghost small" onclick="window.print()">Print</button></div><div class="grid g3">${phases.map((ph) => `<div class="card"><h2>${esc(ph)} of shift</h2><table class="check-table"><tbody>${B.checklist.filter((c) => c.phase === ph).map((c) => `<tr><td class="box"><span></span></td><td>${esc(c.text)}</td></tr>`).join('')}</tbody></table></div>`).join('')}</div>
      <div class="card" style="margin-top:16px"><h2>Precautions</h2><div class="row wrap">${B.precautions.map((p) => `<span class="chip">${esc(p)}</span>`).join('')}</div></div>`;
  } else {
    body = `<div class="card"><h2>Why your workspace isn't here</h2>
      <p>The Behavioral Health workspace on your phone — census, observation rounds, peer notes, participants, incident drafts and check-ins — is encrypted with a key that never leaves the phone. It is never backed up, never synced to Google Drive or BlackLab Desktop, never searched by “Ask my files” and never read aloud in the car.</p>
      <p>That's deliberate: it keeps information about the people you support off shared and cloud systems. This page shows only public reference content generated from the same library the app uses, so it's always current with your phone's version (${esc(window.BL_DATA?.version || '')}).</p>
      <p class="muted small">Your facility's EHR is always the record of care. Follow its policy on personal devices.</p></div>`;
  }
  return `<div class="view-inner"><div class="hero"><div><h1>Behavioral Health</h1><div class="muted">MHT & Peer Support reference — crisis lines, de-escalation, groups and safety rounds. Printable.</div></div>
    <div class="spacer"></div><a class="chip btn" href="tel:988" style="color:var(--danger)">☎ 988</a></div>${tabs}${body}</div>`;
};
Views.bh.bind = () => {
  const q = $('#bhQ'); if (q) q.oninput = () => { S.ui.bhQ = q.value; saveUi(); clearTimeout(q._t); q._t = setTimeout(() => { render(); const n = $('#bhQ'); n.focus(); n.setSelectionRange(n.value.length, n.value.length); }, 250); };
  const c = $('#bhCat'); if (c) c.onchange = () => { S.ui.bhCat = c.value; saveUi(); render(); };
  const g = $('#bhGCat'); if (g) g.onchange = () => { S.ui.bhGCat = g.value; saveUi(); render(); };
  const a = $('#bhArea'); if (a) a.onchange = () => { S.ui.bhArea = a.value; saveUi(); render(); };
  $$('[data-open]').forEach((h) => h.onclick = () => { S.ui.bhOpen = Number(S.ui.bhOpen) === Number(h.dataset.open) ? -1 : Number(h.dataset.open); saveUi(); render(); });
  const bt = $('#breathTxt');
  if (bt && !(S.values.reduce_motion || matchMedia('(prefers-reduced-motion: reduce)').matches)) {
    const steps = ['Breathe in…', 'Hold…', 'Breathe out…', 'Hold…']; let i = 0; bt.textContent = steps[0];
    const timer = setInterval(() => { if (!document.body.contains(bt)) return clearInterval(timer); i = (i + 1) % 4; bt.textContent = steps[i]; }, 4000);
  }
};

// ------------------------------------------------------------------ rendering
let lastView = '';
function render(soft) {
  readHash(); renderNav(); renderSyncState();
  window.BLFX?.apply(S.values, S.ui);
  const v = Views[S.view] || Views.dashboard;
  $('#crumb').textContent = NAV.find((n) => n.id === S.view)?.label || 'Dashboard';
  if (soft && S.view === 'assistant') { renderChatLog(); return; }
  if (soft && document.activeElement && $('#view').contains(document.activeElement) && /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && S.view !== 'settings') return;
  $('#view').innerHTML = v();
  v.bind?.();
  if (S.view + S.sub !== lastView) { lastView = S.view + S.sub; window.BLFX?.countUp($('#view')); }
  $('#sidebar').classList.remove('open');
}

function renderGsi() {
  const slot = $('#gsiSlot');
  if (S.google.token) {
    slot.innerHTML = `<button class="ghost small" id="gsiBtn" title="${esc(S.google.email)}">${S.google.picture ? `<img src="${esc(S.google.picture)}" width="20" height="20" style="border-radius:50%" alt="">` : 'G'} ${esc((S.google.email || '').split('@')[0])}</button>`;
    $('#gsiBtn').onclick = () => go('connect');
  } else {
    slot.innerHTML = `<button class="ghost small" id="gsiBtn"><svg width="16" height="16" viewBox="0 0 48 48" aria-hidden="true"><path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/><path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/></svg> Sign in with Google</button>`;
    $('#gsiBtn').onclick = () => Cloud.signIn(S.google.email ? '' : 'consent');
  }
}

function setTheme(t) {
  S.ui.theme = t; saveUi();
  if (window.BLFX) window.BLFX.apply(S.values, S.ui);
  else if (t === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
}

// ------------------------------------------------------------------ command palette
function paletteItems(q) {
  const items = [
    ...NAV.filter((n) => !n.sep).map((n) => ({ label: 'Go to ' + n.label, k: 'view', run: () => go(n.id) })),
    ...(S.schema || []).map((s) => ({ label: 'Settings › ' + s.title, k: s.items.length + ' settings', run: () => go('settings', s.id) })),
    ...(S.schema || []).flatMap((s) => s.items.map((d) => ({ label: d.label, k: s.title, run: () => { S.ui.setQuery = d.label; saveUi(); go('settings'); render(); } }))),
    { label: 'Brief me', k: 'assistant', run: () => { go('assistant'); ask('Brief me'); } },
    { label: "What's due?", k: 'assistant', run: () => { go('assistant'); ask("What's due?"); } },
    { label: 'Toggle theme', k: 'T', run: () => setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light') },
    { label: 'Refresh from phone', k: 'R', run: () => refresh() },
    { label: 'Sign in with Google', k: 'account', run: () => Cloud.signIn() },
  ];
  q = q.toLowerCase().trim();
  if (q.startsWith('>')) return [{ label: 'Ask: ' + q.slice(1).trim(), k: 'assistant', run: () => { go('assistant'); ask(q.slice(1)); } }];
  const words = q.split(/\s+/).filter(Boolean);
  const hits = items.filter((it) => words.every((w) => (it.label + ' ' + it.k).toLowerCase().includes(w))).slice(0, 40);
  if (q) hits.push({ label: 'Ask the assistant: “' + q + '”', k: 'Enter', run: () => { go('assistant'); ask(q); } });
  return hits;
}
let palSel = 0; let palList = [];
function openPalette() {
  $('#palette').classList.remove('hidden'); const i = $('#paletteInput'); i.value = ''; drawPalette(); i.focus();
}
function closePalette() { $('#palette').classList.add('hidden'); }
function drawPalette() {
  palList = paletteItems($('#paletteInput').value); palSel = Math.min(palSel, Math.max(0, palList.length - 1));
  $('#paletteList').innerHTML = palList.map((it, n) => `<li class="${n === palSel ? 'sel' : ''}" data-n="${n}"><span>${esc(it.label)}</span><span class="k">${esc(it.k)}</span></li>`).join('');
  $$('#paletteList li').forEach((li) => li.onclick = () => { closePalette(); palList[Number(li.dataset.n)].run(); });
}

// ------------------------------------------------------------------ boot
async function refresh() {
  if (S.mode === 'phone') { await loadFromPhone().catch((e) => toast(e.message)); toast('Refreshed'); }
  else if (S.mode === 'cloud') { await Cloud.load(true); toast('Loaded from Drive'); }
  else if (!(await connectPhone(true))) toast('Not connected — showing cached data');
}

function keys(e) {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName);
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); $('#palette').classList.contains('hidden') ? openPalette() : closePalette(); return; }
  if (!$('#palette').classList.contains('hidden')) {
    if (e.key === 'Escape') closePalette();
    else if (e.key === 'ArrowDown') { palSel = Math.min(palSel + 1, palList.length - 1); drawPalette(); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { palSel = Math.max(palSel - 1, 0); drawPalette(); e.preventDefault(); }
    else if (e.key === 'Enter') { const it = palList[palSel]; closePalette(); it?.run(); }
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  const map = { d: 'dashboard', a: 'assistant', t: 'tasks', c: 'calendar', s: 'settings', o: 'documents', e: 'learning', j: 'journal', f: 'files', k: 'desktop' };
  if (keys.g && map[e.key]) { go(map[e.key]); keys.g = false; return; }
  keys.g = e.key === 'g';
  if (e.key === '/') { e.preventDefault(); if (S.view !== 'settings') go('settings'); setTimeout(() => $('#setSearch')?.focus(), 30); }
  if (e.key === 'r') refresh();
  if (e.key === '?') { toast('Ctrl+K palette · g then d/a/t/c/s/o/e/j/f/k to jump · / search settings · r refresh', 6000); }
}

async function boot() {
  setTheme(S.ui.theme);
  window.addEventListener('hashchange', () => render());
  document.addEventListener('keydown', keys);
  $('#paletteInput').addEventListener('input', () => { palSel = 0; drawPalette(); });
  $('#palette').addEventListener('click', (e) => { if (e.target.id === 'palette') closePalette(); });
  $('#paletteBtn').onclick = openPalette;
  $('#refreshBtn').onclick = refresh;
  $('#themeBtn').onclick = () => setTheme((document.documentElement.getAttribute('data-theme') || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark')) === 'light' ? 'dark' : 'light');
  if (window.BLFX) window.BLFX.reapply = () => window.BLFX.apply(S.values, S.ui);
  $('#menuBtn').onclick = () => $('#sidebar').classList.toggle('open');
  renderGsi(); setMode('offline'); render();
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
  await Companion.detect();
  if (await connectPhone(true)) return;
  setMode(S.google.token ? 'cloud' : 'offline');
  const waitGsi = setInterval(() => { if (window.google?.accounts?.oauth2) { clearInterval(waitGsi); Cloud.init(); } }, 300);
  setTimeout(() => clearInterval(waitGsi), 15000);
  // keep trying the phone in the background (e.g. cable plugged in later)
  setInterval(async () => { if (S.mode !== 'phone' && Phone.token) { if (Companion.ok) await Companion.detect(); connectPhone(true); } }, 20000);
}
boot();

// Phone file downloads (see Phone.download)
document.addEventListener('click', (e) => {
  const a = e.target.closest && e.target.closest('[data-dl]');
  if (!a) return;
  e.preventDefault();
  Phone.download(a.dataset.dl).catch((err) => toast(err.message));
});
