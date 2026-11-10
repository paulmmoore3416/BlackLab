/* BlackLab Graph FX — three animations for Obsidian's graph view, drawn on a transparent layer above it.
 *
 *  1. ⚡ Synapse signals — sparks of light travel along the links, in the color of the note they leave. Notes edited
 *     recently fire more often, so the busy parts of your life visibly hum.
 *  2. 💓 Live pulse — when any note changes (you, the phone sync, an AI agent), its node sends out a ripple and a burst
 *     of signals to everything it links to. The open note breathes; notes changed today glow softly.
 *  3. 🎞️ Time-lapse — replays the vault being built: notes appear in the order they were created, their links drawing
 *     in, with the date running in the corner. Command: "Play vault time-lapse" (click or Esc to stop).
 *
 * Read-only: it never moves nodes or changes the graph; it reads positions and colors from the graph's renderer each
 * frame and draws on its own canvas (pointer-events: none). Runs only while a graph is visible; honors "reduce motion".
 * Installed and kept up to date by the BlackLab Desktop companion (desktop/obsidian/blacklab-graph-fx in the repo).
 */
"use strict";
let obsidian;
try { obsidian = require("obsidian"); } catch (_) { obsidian = { Plugin: class {}, PluginSettingTab: class {}, Setting: class {}, Platform: {}, Notice: class {} }; }
const { Plugin, PluginSettingTab, Setting, Platform, Notice } = obsidian;

const DEFAULTS = { signals: true, pulse: true, intensity: 1.0, maxParticles: 420, recentDays: 7, lapseSeconds: 24, respectReducedMotion: true };
const DAY = 86400000;

// ------------------------------------------------------------------ pure helpers (unit tested in tests/graph_fx.test.mjs)

function rgbToCss(rgb, a) {
  const r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
  return a === undefined ? `rgb(${r},${g},${b})` : `rgba(${r},${g},${b},${a})`;
}

/** The earliest believable date for a note: a date in its name or frontmatter, else its file times. */
function birthOf(path, stat, fm) {
  const cands = [];
  const m = /(\d{4})-(\d{2})-(\d{2})/.exec(path || "");
  if (m) cands.push(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  for (const k of ["created", "date", "start"]) {
    const v = fm && fm[k];
    if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}/.test(v)) cands.push(Date.parse(v.slice(0, 10)));
  }
  if (stat) { if (stat.ctime) cands.push(stat.ctime); if (stat.mtime) cands.push(stat.mtime); }
  const ok = cands.filter((t) => Number.isFinite(t) && t > Date.UTC(1990, 0, 1) && t < Date.now() + 365 * DAY);
  return ok.length ? Math.min(...ok) : Date.now();
}

/** Birth order for the time-lapse: [{id, t}] sorted, plus the time span. */
function schedule(items) {
  const s = items.filter((i) => Number.isFinite(i.t)).sort((a, b) => a.t - b.t || (a.id < b.id ? -1 : 1));
  return { order: s, t0: s.length ? s[0].t : 0, t1: s.length ? s[s.length - 1].t : 0 };
}

/** How far into the replay a note appears (0…1): by rank, so a quiet year doesn't leave the screen empty. */
function revealAt(rank, count) { return count <= 1 ? 0 : rank / (count - 1); }

/** Activity weight: notes edited in the last [days] fire more signals (1 … 6). */
function activity(mtime, now, days) {
  if (!mtime) return 1;
  const age = (now - mtime) / DAY;
  return age < 0 ? 6 : age >= days ? 1 : 1 + 5 * (1 - age / days);
}

/** Pick an index from cumulative weights with r in [0,1). */
function pickWeighted(cum, r) {
  const total = cum[cum.length - 1];
  let lo = 0, hi = cum.length - 1;
  const x = r * total;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cum[mid] > x) hi = mid; else lo = mid + 1; }
  return lo;
}

function easeOut(t) { return 1 - Math.pow(1 - t, 3); }

// ------------------------------------------------------------------ one overlay per visible graph

class Overlay {
  constructor(plugin, leaf) {
    this.plugin = plugin; this.leaf = leaf;
    this.canvas = document.createElement("canvas");
    this.canvas.className = "bl-graph-fx";
    this.ctx = this.canvas.getContext("2d");
    this.particles = []; this.ripples = []; this.pool = null; this.poolAt = 0; this.lapse = null;
    this.attach();
  }
  get renderer() { return this.leaf.view && this.leaf.view.renderer; }
  attach() {
    const r = this.renderer;
    const host = r && r.interactiveEl && r.interactiveEl.parentElement;
    if (!host || this.canvas.parentElement === host) return;
    host.appendChild(this.canvas);
    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(host);
    this.resize();
  }
  resize() {
    const host = this.canvas.parentElement;
    if (!host) return;
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, Math.round(host.clientWidth * dpr));
    this.canvas.height = Math.max(1, Math.round(host.clientHeight * dpr));
  }
  destroy() { if (this.ro) this.ro.disconnect(); this.canvas.remove(); }
  visible() { return !!this.canvas.parentElement && this.canvas.offsetParent !== null && !!this.renderer; }

  /** graph coordinates → this canvas's pixels */
  xf() {
    const r = this.renderer, el = r.interactiveEl, dpr = window.devicePixelRatio || 1;
    const f = (el.clientWidth / (r.width || el.width || 1)) * dpr;
    return { s: r.scale * f, ox: r.panX * f, oy: r.panY * f, ns: (r.nodeScale || 1) * f, w: this.canvas.width, h: this.canvas.height };
  }
  color(n) {
    try { const c = n.getFillColor ? n.getFillColor() : n.color; if (c && typeof c.rgb === "number") return c.rgb; } catch (_) {}
    return 0x2bd9a8;
  }

  /** Links with both ends on screen, weighted by how recently their source changed. Rebuilt every 1.5 s. */
  buildPool(now, T) {
    const r = this.renderer, links = r.links || [], pad = 40;
    const on = (n) => { const x = n.x * T.s + T.ox, y = n.y * T.s + T.oy; return x > -pad && y > -pad && x < T.w + pad && y < T.h + pad; };
    const picked = [], cum = [];
    let total = 0;
    const step = links.length > 20000 ? Math.ceil(links.length / 20000) : 1;  // big vaults: sample
    const days = this.plugin.settings.recentDays;
    for (let i = Math.floor(Math.random() * step); i < links.length; i += step) {
      const l = links[i];
      if (!l || !l.source || !l.target || !on(l.source) || !on(l.target)) continue;
      const w = activity(this.plugin.mtime(l.source.id), now, days) + activity(this.plugin.mtime(l.target.id), now, days) * 0.5;
      total += w; picked.push(l); cum.push(total);
    }
    this.pool = { links: picked, cum };
    this.poolAt = now;
  }

  spawn(link, speed, size, outward) {
    const rev = !outward && Math.random() < 0.35;
    this.particles.push({ a: rev ? link.target : link.source, b: rev ? link.source : link.target, t: 0,
      v: (0.004 + Math.random() * 0.01) * (speed || 1), size: size || 1, c: this.color(rev ? link.target : link.source) });
  }

  /** A note changed: ripple + a burst of signals to its neighbors. */
  burst(id) {
    const r = this.renderer;
    if (!r) return;
    const n = (r.nodeLookup && r.nodeLookup[id]) || (r.nodes || []).find((x) => x.id === id);
    if (!n) return;
    this.ripples.push({ n, t: 0, c: this.color(n), big: true });
    const out = [...Object.values(n.forward || {}), ...Object.values(n.reverse || {})].slice(0, 24);
    for (const l of out) this.spawn(l.source === n ? l : { source: n, target: l.source }, 1.6, 1.8, true);
  }

  frame(now, dt) {
    const S = this.plugin.settings, ctx = this.ctx, T = this.xf();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, T.w, T.h);
    if (this.lapse) return this.drawLapse(now, T);
    const k = Math.min(3, dt / 16.7), dpr = window.devicePixelRatio || 1;
    ctx.globalCompositeOperation = "lighter";
    if (S.signals) {
      if (!this.pool || now - this.poolAt > 1500) this.buildPool(now, T);
      const cap = Math.round(S.maxParticles * S.intensity * (Platform.isMobile ? 0.4 : 1));
      const want = Math.min(cap - this.particles.length, Math.ceil(cap / 90) + 1);
      if (this.pool.links.length) for (let i = 0; i < want; i++) this.spawn(this.pool.links[pickWeighted(this.pool.cum, Math.random())]);
    }
    const keep = [];
    for (const p of this.particles) {
      p.t += p.v * k;
      if (p.t >= 1) continue;
      const e = p.t;
      const x = (p.a.x + (p.b.x - p.a.x) * e) * T.s + T.ox, y = (p.a.y + (p.b.y - p.a.y) * e) * T.s + T.oy;
      const tail = Math.max(0, e - 0.14);
      const tx = (p.a.x + (p.b.x - p.a.x) * tail) * T.s + T.ox, ty = (p.a.y + (p.b.y - p.a.y) * tail) * T.s + T.oy;
      const fade = Math.sin(Math.PI * e);
      const rad = Math.max(2.2 * dpr, Math.min(6 * dpr, 2.4 * dpr * p.size * Math.sqrt(T.s * 4)));
      ctx.strokeStyle = rgbToCss(p.c, 0.45 * fade); ctx.lineWidth = rad * 1.1; ctx.lineCap = "round";
      ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(x, y); ctx.stroke();
      ctx.fillStyle = rgbToCss(p.c, 0.18 * fade);
      ctx.beginPath(); ctx.arc(x, y, rad * 2.6, 0, 6.2832); ctx.fill();
      ctx.fillStyle = rgbToCss(p.c, 0.95 * fade);
      ctx.beginPath(); ctx.arc(x, y, rad, 0, 6.2832); ctx.fill();
      keep.push(p);
    }
    this.particles = keep;
    if (S.pulse) this.drawPulse(now, T, k);
    ctx.globalCompositeOperation = "source-over";
  }

  drawPulse(now, T, k) {
    const ctx = this.ctx, r = this.renderer, dpr = window.devicePixelRatio || 1;
    // the open note breathes
    const f = this.plugin.app.workspace.getActiveFile && this.plugin.app.workspace.getActiveFile();
    const open = f && r.nodeLookup && r.nodeLookup[f.path];
    if (open) {
      const x = open.x * T.s + T.ox, y = open.y * T.s + T.oy, base = (open.getSize ? open.getSize() : 8) * T.ns;
      const b = 0.5 + 0.5 * Math.sin(now / 520);
      const g = ctx.createRadialGradient(x, y, base * 0.8, x, y, base * (2.2 + b));
      g.addColorStop(0, rgbToCss(this.color(open), 0.45)); g.addColorStop(1, rgbToCss(this.color(open), 0));
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, base * (2.2 + b), 0, 6.2832); ctx.fill();
    }
    // notes changed in the last day: a slow ambient ripple, staggered
    if (!this._recentAt || now - this._recentAt > 4000) { this._recent = this.plugin.recentNodes(r, now); this._recentAt = now; }
    for (const [i, n] of (this._recent || []).entries()) {
      if (((now / 2600) + i * 0.37) % 1 < 0.016 * k) this.ripples.push({ n, t: 0, c: this.color(n), big: false });
    }
    const keep = [];
    for (const rp of this.ripples) {
      rp.t += (rp.big ? 0.012 : 0.018) * k;
      if (rp.t >= 1) continue;
      const n = rp.n, x = n.x * T.s + T.ox, y = n.y * T.s + T.oy, base = (n.getSize ? n.getSize() : 8) * T.ns;
      const rad = base * (1 + easeOut(rp.t) * (rp.big ? 7 : 3.2));
      ctx.strokeStyle = rgbToCss(rp.c, (rp.big ? 0.9 : 0.5) * (1 - rp.t));
      ctx.lineWidth = (rp.big ? 3 : 1.6) * dpr * (1 - rp.t * 0.6);
      ctx.beginPath(); ctx.arc(x, y, rad, 0, 6.2832); ctx.stroke();
      keep.push(rp);
    }
    this.ripples = keep.slice(-300);
  }

  // ---------------------------------------------------------------- time-lapse
  startLapse() {
    const r = this.renderer;
    if (!r || !(r.nodes || []).length) return false;
    const items = r.nodes.map((n) => {
      const f = this.plugin.app.vault.getAbstractFileByPath(n.id);
      const fm = f && this.plugin.app.metadataCache.getFileCache(f);
      return { id: n.id, t: birthOf(n.id, f && f.stat, fm && fm.frontmatter), n };
    });
    const sc = schedule(items);
    const rank = new Map(sc.order.map((it, i) => [it.id, i]));
    this.lapse = { sc, rank, start: performance.now(), dur: this.plugin.settings.lapseSeconds * 1000 };
    this.particles = []; this.ripples = [];
    return true;
  }
  stopLapse() { this.lapse = null; }

  drawLapse(now, T) {
    const L = this.lapse, ctx = this.ctx, dpr = window.devicePixelRatio || 1, r = this.renderer;
    const p = Math.min(1, (performance.now() - L.start) / L.dur);
    const count = L.sc.order.length, born = Math.floor(p * (count - 1)) + 1;
    // veil: hide the real graph so the replay starts from nothing
    const dark = document.body.classList.contains("theme-dark");
    ctx.fillStyle = dark ? "rgba(14,17,22,0.97)" : "rgba(251,252,253,0.97)";
    ctx.fillRect(0, 0, T.w, T.h);
    ctx.globalCompositeOperation = dark ? "lighter" : "source-over";
    // links between born notes (sampled for huge vaults)
    const links = r.links || [], step = links.length > 12000 ? Math.ceil(links.length / 12000) : 1;
    ctx.lineWidth = Math.max(0.5, 0.8 * dpr);
    for (let i = 0; i < links.length; i += step) {
      const l = links[i];
      const ra = L.rank.get(l.source.id), rb = L.rank.get(l.target.id);
      if (ra === undefined || rb === undefined || ra >= born || rb >= born) continue;
      const age = born - Math.max(ra, rb);
      ctx.strokeStyle = rgbToCss(this.color(l.source), Math.min(0.28, 0.05 + age / 400));
      ctx.beginPath(); ctx.moveTo(l.source.x * T.s + T.ox, l.source.y * T.s + T.oy); ctx.lineTo(l.target.x * T.s + T.ox, l.target.y * T.s + T.oy); ctx.stroke();
    }
    for (let i = 0; i < born; i++) {
      const it = L.sc.order[i], n = it.n;
      const x = n.x * T.s + T.ox, y = n.y * T.s + T.oy;
      if (x < -20 || y < -20 || x > T.w + 20 || y > T.h + 20) continue;
      const base = Math.max(1.5 * dpr, (n.getSize ? n.getSize() : 8) * T.ns), fresh = born - i;
      const flash = fresh < 25 ? 1 + (25 - fresh) / 10 : 1;
      ctx.fillStyle = rgbToCss(this.color(n), fresh < 25 ? 1 : 0.85);
      ctx.beginPath(); ctx.arc(x, y, base * flash, 0, 6.2832); ctx.fill();
    }
    ctx.globalCompositeOperation = "source-over";
    // the date and a progress bar
    const cur = L.sc.order[born - 1];
    const label = cur ? new Date(cur.t).toISOString().slice(0, 10) : "";
    ctx.fillStyle = dark ? "#E6EDF3" : "#1F2328";
    ctx.font = `${600} ${Math.round(26 * dpr)}px Inter, system-ui, sans-serif`;
    ctx.fillText(label, 24 * dpr, 44 * dpr);
    ctx.font = `${Math.round(13 * dpr)}px Inter, system-ui, sans-serif`;
    ctx.fillText(`${born.toLocaleString()} of ${count.toLocaleString()} notes · click or Esc to stop`, 24 * dpr, 66 * dpr);
    ctx.fillStyle = "rgba(43,217,168,0.9)";
    ctx.fillRect(0, T.h - 4 * dpr, T.w * p, 4 * dpr);
    if (p >= 1 && performance.now() - L.start > L.dur + 2500) this.stopLapse();
  }
}

// ------------------------------------------------------------------ the plugin

class GraphFx extends Plugin {
  async onload() {
    this.settings = Object.assign({}, DEFAULTS, await this.loadData());
    this.overlays = new Map(); this.running = false; this._mt = new Map();
    this.reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    this.addSettingTab(new FxSettings(this.app, this));
    this.addRibbonIcon("activity", "Graph FX: signals on/off", () => this.toggle("signals"));
    this.addCommand({ id: "toggle-signals", name: "Toggle synapse signals", callback: () => this.toggle("signals") });
    this.addCommand({ id: "toggle-pulse", name: "Toggle live pulse", callback: () => this.toggle("pulse") });
    this.addCommand({ id: "play-timelapse", name: "Play vault time-lapse", callback: () => this.timelapse() });
    this.addCommand({ id: "stop-timelapse", name: "Stop vault time-lapse", callback: () => this.overlays.forEach((o) => o.stopLapse()) });
    const changed = (f) => { if (!f || !f.path) return; this._mt.set(f.path, Date.now()); this.overlays.forEach((o) => o.burst(f.path)); };
    this.registerEvent(this.app.vault.on("modify", changed));
    this.registerEvent(this.app.vault.on("create", (f) => setTimeout(() => changed(f), 1200)));  // after the graph adds it
    this.registerEvent(this.app.workspace.on("layout-change", () => this.sync()));
    this.registerEvent(this.app.workspace.on("active-leaf-change", () => this.sync()));
    this.registerDomEvent(document, "keydown", (e) => { if (e.key === "Escape") this.overlays.forEach((o) => o.stopLapse()); });
    this.registerDomEvent(document, "pointerdown", () => this.overlays.forEach((o) => o.lapse && o.stopLapse()), true);
    this.app.workspace.onLayoutReady(() => this.sync());
  }
  onunload() { this.running = false; this.overlays.forEach((o) => o.destroy()); this.overlays.clear(); }
  async save() { await this.saveData(this.settings); }

  mtime(path) {
    if (this._mt.has(path)) return this._mt.get(path);
    const f = this.app.vault.getAbstractFileByPath(path);
    const t = (f && f.stat && f.stat.mtime) || 0;
    this._mt.set(path, t);
    return t;
  }
  recentNodes(r, now) {
    const out = [];
    for (const n of r.nodes || []) { if (now - this.mtime(n.id) < DAY) { out.push(n); if (out.length >= 60) break; } }
    return out;
  }
  active() { return !(this.settings.respectReducedMotion && this.reduced); }

  sync() {
    const leaves = [...this.app.workspace.getLeavesOfType("graph"), ...this.app.workspace.getLeavesOfType("localgraph")];
    for (const [leaf, o] of this.overlays) if (!leaves.includes(leaf)) { o.destroy(); this.overlays.delete(leaf); }
    for (const leaf of leaves) {
      if (!leaf.view || !leaf.view.renderer) continue;
      if (!this.overlays.has(leaf)) this.overlays.set(leaf, new Overlay(this, leaf)); else this.overlays.get(leaf).attach();
    }
    if (this.overlays.size && !this.running) this.loop();
  }
  loop() {
    this.running = true;
    let last = performance.now();
    const tick = (now) => {
      if (!this.running) return;
      const dt = now - last; last = now;
      let any = false;
      for (const o of this.overlays.values()) {
        if (!o.visible()) continue;
        any = true;
        try { if (this.active() || o.lapse) o.frame(now, dt); else o.ctx.clearRect(0, 0, o.canvas.width, o.canvas.height); } catch (e) { console.warn("Graph FX", e); }
      }
      if (!this.overlays.size) { this.running = false; return; }
      // nothing visible: check again in a moment instead of every frame
      if (any) requestAnimationFrame(tick); else setTimeout(() => requestAnimationFrame(tick), 500);
    };
    requestAnimationFrame(tick);
  }
  async toggle(key) {
    this.settings[key] = !this.settings[key];
    await this.save();
    new Notice(`Graph FX: ${key === "signals" ? "synapse signals" : "live pulse"} ${this.settings[key] ? "on" : "off"}`);
  }
  timelapse() {
    this.sync();
    let o = [...this.overlays.values()].find((x) => x.visible());
    if (!o) { this.app.commands.executeCommandById("graph:open"); setTimeout(() => this.timelapse(), 1500); return; }
    if (!o.startLapse()) new Notice("Graph FX: the graph has no notes to replay yet.");
  }
}

class FxSettings extends PluginSettingTab {
  constructor(app, plugin) { super(app, plugin); this.plugin = plugin; }
  display() {
    const { containerEl } = this, p = this.plugin;
    containerEl.empty();
    const tog = (name, desc, key) => new Setting(containerEl).setName(name).setDesc(desc)
      .addToggle((t) => t.setValue(p.settings[key]).onChange(async (v) => { p.settings[key] = v; await p.save(); }));
    tog("⚡ Synapse signals", "Sparks travel the links in their note's color; recently edited notes fire more.", "signals");
    tog("💓 Live pulse", "A ripple and a burst of signals when a note changes; the open note breathes; today's notes glow.", "pulse");
    new Setting(containerEl).setName("Intensity").setDesc("How many signals at once (0.2 – 2).")
      .addSlider((s) => s.setLimits(0.2, 2, 0.1).setValue(p.settings.intensity).setDynamicTooltip().onChange(async (v) => { p.settings.intensity = v; await p.save(); }));
    new Setting(containerEl).setName("Recent means (days)").setDesc("Notes edited within this many days fire extra signals.")
      .addSlider((s) => s.setLimits(1, 60, 1).setValue(p.settings.recentDays).setDynamicTooltip().onChange(async (v) => { p.settings.recentDays = v; await p.save(); }));
    new Setting(containerEl).setName("🎞️ Time-lapse length (seconds)")
      .addSlider((s) => s.setLimits(8, 120, 2).setValue(p.settings.lapseSeconds).setDynamicTooltip().onChange(async (v) => { p.settings.lapseSeconds = v; await p.save(); }))
      .addButton((b) => b.setButtonText("Play now").onClick(() => p.timelapse()));
    tog("Respect “reduce motion”", "Pause signals and pulses when the system asks for less motion (the time-lapse still plays on request).", "respectReducedMotion");
  }
}

module.exports = GraphFx;
module.exports.__test = { rgbToCss, birthOf, schedule, revealAt, activity, pickWeighted, easeOut, DEFAULTS };
