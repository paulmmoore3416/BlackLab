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
  fileUrl(path) { return `${this.url}/api/file?path=${encodeURIComponent(path)}&token=${encodeURIComponent(this.token)}`; },
  looping: false,
  async loop() {
    if (this.looping) return; this.looping = true;
    while (S.mode === 'phone') {
      try {
        const r = await this.req(`/api/changes?since=${S.rev}&wait=25`);
        if (r.rev > S.rev) { S.rev = r.rev; await loadFromPhone(); }
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
  S.values = values; S.snap = snap; S.rev = Math.max(S.rev, snap.info?.rev || 0); S.lastSync = Date.now();
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

const Cloud = {
  client: null,
  get clientId() { return LS.get('googleClientId', '') || CFG.googleClientId || ''; },
  get valid() { return !!S.google.token && Date.now() < S.google.exp; },
  init() {
    if (!window.google?.accounts?.oauth2 || !this.clientId) return false;
    this.client = google.accounts.oauth2.initTokenClient({ client_id: this.clientId, scope: DRIVE_SCOPE, callback: (r) => this.onToken(r) });
    return true;
  },
  signIn(prompt = '') {
    if (!this.clientId) { go('connect'); toast('Add your Google OAuth client ID under Connect first'); return; }
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
    if (r.error) { toast('Google sign-in: ' + r.error); return; }
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
      if (!r.applied.includes(key)) toast('The phone rejected that value'); else toast('Saved to phone');
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
  { id: 'assistant', label: 'Assistant', ico: '✦' },
  { id: 'tasks', label: 'To-Do', ico: '✓' },
  { id: 'calendar', label: 'Schedule', ico: '▦' },
  { id: 'documents', label: 'Documents', ico: '❏' },
  { id: 'learning', label: 'Education', ico: '◎' },
  { id: 'journal', label: 'Journal', ico: '✎' },
  { id: 'media', label: 'Recordings', ico: '◉' },
  { id: 'files', label: 'Phone files', ico: '▤' },
  { sep: true },
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
    <div class="grid g4" style="margin-bottom:16px">
      <div class="tile"><span class="k">Open tasks</span><span class="v">${open.length}</span><span class="s">${overdue.length ? `<span style="color:var(--danger)">${overdue.length} overdue</span>` : 'none overdue'} · ${dueToday.length} today</span></div>
      <div class="tile"><span class="k">Today</span><span class="v">${evToday.length}</span><span class="s">${next ? 'Next: ' + esc(next.title) + ' ' + relDay(next.start).toLowerCase() + ' ' + fmtTime(next.start) : 'Nothing upcoming'}</span></div>
      <div class="tile"><span class="k">Phone</span><span class="v">${i.battery >= 0 ? i.battery + '%' : '—'}</span><span class="s">${i.storageFree ? bytes(i.storageFree) + ' free' : 'not connected'}${i.charging ? ' · charging' : ''}</span></div>
      <div class="tile"><span class="k">Assistant</span><span class="v" style="font-size:20px">${esc(i.assistant?.name || 'BlackLab')}</span><span class="s">${i.assistant?.nano === 'AVAILABLE' ? 'Gemini Nano ready' : 'Rules engine'} · voice ${i.assistant ? (i.assistant.elevenCharsUsed || 0) + '/' + (i.assistant.elevenBudget || 0) : '—'}</span></div>
    </div>
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
  $$('[data-q]').forEach((c) => c.onclick = () => { go('assistant'); ask(c.dataset.q); });
  $('#quickAskBtn').onclick = () => { const v = $('#quickAsk').value; if (v.trim()) { go('assistant'); ask(v); } };
};

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
      <td>${S.mode === 'phone' && d.path ? `<a class="chip btn" href="${Phone.fileUrl(d.path)}" target="_blank" rel="noopener">Open</a>` : ''}</td></tr>`).join('') || '<tr><td colspan="5" class="empty">No documents.</td></tr>'}
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
  const link = (p) => S.mode === 'phone' && p ? `<a class="chip btn" href="${Phone.fileUrl(p)}" target="_blank" rel="noopener">Download</a>` : '';
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
      ${r.files.map((f) => `<tr><td>${f.dir ? `<a href="#/files/${encodeURIComponent(f.path)}">📁 ${esc(f.name)}</a>` : esc(f.name)}</td><td class="muted">${f.dir ? '' : bytes(f.size)}</td><td class="muted">${ago(f.modified)}</td><td>${f.dir ? '' : `<a class="chip btn" href="${Phone.fileUrl(f.path)}" target="_blank" rel="noopener">Download</a>`}</td></tr>`).join('') || '<tr><td colspan="4" class="empty">Empty folder.</td></tr>'}
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
        <button class="ghost" data-act="pull-backups">Pull phone backups to ~/BlackLab-Desktop</button>
        <button class="ghost" data-act="pull-documents">Pull documents</button>
        <button class="ghost" data-act="pull-photos">Pull photos</button>
        <button class="ghost" data-act="backup">Back up the phone now</button>
        <button class="ghost" data-act="open-folder">Open ~/BlackLab-Desktop</button></div></div>
      <div class="card"><h2>Ask the desktop</h2><div class="stack">
        ${['System status', 'What is using my disk space?', 'Find files named budget', 'Open my Downloads folder', 'Make a desktop note: call the roofer Monday', 'Android docs: CameraX extensions'].map((q) => `<span class="chip btn" data-q="${esc(q)}">${esc(q)}</span>`).join('')}
        <div class="small muted">These run on this computer through the companion. With Ollama they work fully offline.</div></div></div>
    </div></div>`;
};
Views.desktop.bind = () => {
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
      else if (a === 'open-folder') await Companion.req('/local/open', { path: '~/BlackLab-Desktop' });
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
      <details ${Cloud.clientId ? '' : 'open'}><summary class="small muted">OAuth client ID ${Cloud.clientId ? '(set)' : '(required once)'}</summary>
        <ol class="steps small"><li>Google Cloud Console › APIs &amp; Services › Credentials › <b>Create OAuth client ID › Web application</b> — in the same project as the phone's Android client.</li>
        <li>Authorized JavaScript origins: <code>${esc(location.origin)}</code>${onPages ? '' : ' and <code>https://paulmmoore3416.github.io</code>'}</li>
        <li>Enable the <b>Google Drive API</b>; add yourself as a test user on the OAuth consent screen.</li></ol>
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
Views.connect.bind = () => {
  $('#phConn').onclick = async () => {
    LS.set('phoneUrl', $('#phUrl').value.trim() || 'http://127.0.0.1:8765');
    LS.set('phoneToken', $('#phTok').value.replace(/\s+/g, '').toUpperCase());
    if (await connectPhone(false)) { toast('Connected to the phone'); go('dashboard'); }
  };
  const gi = $('#gIn'); if (gi) gi.onclick = () => Cloud.signIn('consent');
  const go_ = $('#gOut'); if (go_) go_.onclick = () => Cloud.signOut();
  const gl = $('#gLoad'); if (gl) gl.onclick = () => Cloud.load(true);
  $('#gSave').onclick = () => { LS.set('googleClientId', $('#gCid').value.trim()); Cloud.client = null; Cloud.init(); renderGsi(); toast('Saved'); };
  $('#sendKeys').onclick = async () => {
    const body = {}; ['elevenlabs', 'developer_knowledge'].forEach((k) => { const v = $('#key-' + k).value.trim(); if (v) body[k] = v; });
    if (!Object.keys(body).length) { toast('Paste a key first'); return; }
    try { const r = await Phone.req('/api/secrets', { body }); toast('Stored on phone: ' + r.set.join(', ')); await loadFromPhone(); } catch (e) { toast(e.message); }
  };
};

// ------------------------------------------------------------------ rendering
function render(soft) {
  readHash(); renderNav(); renderSyncState();
  const v = Views[S.view] || Views.dashboard;
  $('#crumb').textContent = NAV.find((n) => n.id === S.view)?.label || 'Dashboard';
  if (soft && S.view === 'assistant') { renderChatLog(); return; }
  if (soft && document.activeElement && $('#view').contains(document.activeElement) && /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && S.view !== 'settings') return;
  $('#view').innerHTML = v();
  v.bind?.();
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
  if (t === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
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
