// BlackLab web visual engine: the phone's palettes and light styles as CSS variables (with the same
// WCAG contrast rules), the aurora backdrop, frosted glass, a pointer-following glow and count-up
// numbers. Everything reads the settings the phone syncs, so the web looks like your phone.
(() => {
  'use strict';
  const DATA = window.BL_DATA || { palettes: [], lightStyles: [] };
  const root = document.documentElement;

  // ---------------------------------------------------------------- color math (mirrors ui/theme/Contrast.kt)
  const hexToRgb = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const rgbToHex = (r, g, b) => '#' + [r, g, b].map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
  const ch = (c) => { const s = c / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
  const lum = (h) => { const [r, g, b] = hexToRgb(h); return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b); };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  function toHsl(h) {
    let [r, g, b] = hexToRgb(h).map((v) => v / 255);
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    if (mx === mn) return [0, 0, l];
    const d = mx - mn, s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    let hh = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return [hh * 60, s, l];
  }
  function fromHsl(h, s, l) {
    const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = l - c / 2;
    const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
    return rgbToHex((r + m) * 255, (g + m) * 255, (b + m) * 255);
  }
  function ensure(fg, bg, target = 4.5) {
    if (ratio(fg, bg) >= target) return fg;
    const [h, s, l0] = toHsl(fg); const darken = lum(bg) > 0.18; let l = l0;
    for (let i = 0; i < 100; i++) { l += darken ? -0.01 : 0.01; if (l <= 0 || l >= 1) break; const c = fromHsl(h, s, l); if (ratio(c, bg) >= target) return c; }
    return darken ? '#000000' : '#FFFFFF';
  }
  const mix = (a, b, t) => { const x = hexToRgb(a), y = hexToRgb(b); return rgbToHex(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t); };
  const rgba = (h, a) => { const [r, g, b] = hexToRgb(h); return `rgba(${r},${g},${b},${a})`; };

  // ---------------------------------------------------------------- theme from the phone's settings
  const state = { dark: true, accent: '#2BD9A8', secondary: '#FF8A70', tertiary: '#8C9EFF', page: '#0E1014', glass: true, backdrop: 'AURORA', intensity: 0.6, motion: true };

  function resolveDark(values, uiTheme) {
    if (uiTheme === 'dark') return true;
    if (uiTheme === 'light') return false;
    const m = values.theme_mode;
    if (m === 'DARK') return true;
    if (m === 'LIGHT') return false;
    return !matchMedia('(prefers-color-scheme: light)').matches;
  }

  function apply(values = {}, ui = {}) {
    const dark = resolveDark(values, ui.theme);
    const prof = ui.profile === 'PROFESSIONAL' || (ui.profile !== 'PERSONAL' && values.active_profile === 'PROFESSIONAL') ? 'professional' : 'personal';
    const pal = DATA.palettes.find((p) => p.id === values['accent_' + prof]) || DATA.palettes.find((p) => p.id === (prof === 'professional' ? 'COBALT' : 'MINT')) || { dark: ['#2BD9A8', '#FF8A70', '#8C9EFF'], light: ['#00785A', '#C2410C', '#4F46E5'] };
    const secPick = values['secondary_' + prof];
    const secPal = secPick && secPick !== 'AUTO' ? DATA.palettes.find((p) => p.id === secPick) : null;
    const style = DATA.lightStyles.find((s) => s.id === values.light_style) || DATA.lightStyles[0] || { background: '#F2F3F6', card: '#FFFFFF', high: '#E9ECF0', ink: '#14161A', inkSoft: '#474D57', wash: 0.02 };
    const hc = values.high_contrast === true;
    const target = hc ? 7 : 4.5;
    const shade = dark ? pal.dark : pal.light;
    let page, v;
    if (dark) {
      page = hc ? '#07080A' : '#0E1014';
      const acc = ensure(shade[0], page, target), sec = ensure(secPal ? secPal.dark[0] : shade[1], page, target), ter = ensure(shade[2], page, target);
      v = { '--bg': page, '--bg-2': '#111419', '--surface': '#161A20', '--surface-2': '#1C2027', '--surface-3': '#242932', '--line': hc ? '#3A404A' : '#262B33',
        '--text': hc ? '#F7F8FA' : '#E9EBEF', '--muted': hc ? '#CDD2DA' : '#A6ADB8', '--accent': acc, '--accent-ink': mix(acc, '#000000', 0.85),
        '--accent-soft': rgba(acc, 0.16), '--accent-2': sec, '--accent-3': ter, '--pro': ensure('#4F8CFF', page, target),
        '--warn': ensure('#FFB74D', page, target), '--danger': ensure('#FF6B6B', page, target), '--ok': ensure('#4CD38A', page, target),
        '--glass': 'rgba(255,255,255,.055)', '--glass-bar': 'rgba(14,16,20,.62)', '--glass-line': 'rgba(255,255,255,.10)', '--glass-hi': 'rgba(255,255,255,.08)',
        '--glass-shadow': '0 12px 40px rgba(0,0,0,.35)', '--shadow': '0 10px 40px rgba(0,0,0,.35)' };
      state.accent = acc; state.secondary = sec; state.tertiary = ter;
    } else {
      page = mix(style.background, shade[0], style.wash);
      const acc = ensure(shade[0], page, target), sec = ensure(secPal ? secPal.light[0] : shade[1], page, target), ter = ensure(shade[2], page, target);
      v = { '--bg': page, '--bg-2': mix(page, '#FFFFFF', 0.4), '--surface': style.card, '--surface-2': mix(style.card, style.high, 0.5), '--surface-3': style.high,
        '--line': hc ? '#AEB4BD' : '#D3D7DE', '--text': hc ? '#050608' : style.ink, '--muted': hc ? mix(style.inkSoft, '#000000', 0.45) : style.inkSoft,
        '--accent': acc, '--accent-ink': '#FFFFFF', '--accent-soft': rgba(acc, 0.13), '--accent-2': sec, '--accent-3': ter, '--pro': ensure('#1D5FD6', page, target),
        '--warn': ensure('#B45309', page, target), '--danger': ensure('#C62828', page, target), '--ok': ensure('#2E7D32', page, target),
        '--glass': 'rgba(255,255,255,.62)', '--glass-bar': 'rgba(255,255,255,.66)', '--glass-line': 'rgba(15,23,42,.09)', '--glass-hi': 'rgba(255,255,255,.95)',
        '--glass-shadow': '0 10px 34px rgba(17,24,39,.08)', '--shadow': '0 10px 30px rgba(10,30,25,.10)' };
      state.accent = acc; state.secondary = sec; state.tertiary = ter;
    }
    for (const [k, val] of Object.entries(v)) root.style.setProperty(k, val);
    root.setAttribute('data-theme', dark ? 'dark' : 'light');
    root.style.colorScheme = dark ? 'dark' : 'light';
    state.dark = dark; state.page = page;
    state.glass = values.visual_style !== 'SOLID';
    state.backdrop = values.backdrop || 'AURORA';
    state.intensity = Math.max(0, Math.min(100, values.backdrop_intensity ?? 60)) / 100;
    state.motion = values.reduce_motion !== true && !matchMedia('(prefers-reduced-motion: reduce)').matches;
    root.setAttribute('data-style', state.glass ? 'glass' : 'solid');
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', page);
    drawOnce();
  }

  // ---------------------------------------------------------------- aurora backdrop
  const canvas = document.createElement('canvas');
  canvas.id = 'aurora'; canvas.setAttribute('aria-hidden', 'true');
  const ctx = canvas.getContext('2d');
  let w = 0, h = 0, dpr = 1, phase = 0.18, last = 0, raf = 0;
  function size() {
    dpr = Math.min(window.devicePixelRatio || 1, 1.5); // the field is soft; full resolution isn't needed
    w = canvas.width = Math.round(innerWidth * dpr); h = canvas.height = Math.round(innerHeight * dpr);
    canvas.style.width = innerWidth + 'px'; canvas.style.height = innerHeight + 'px';
    drawOnce();
  }
  function blob(c, a, cx, cy, r) {
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
    g.addColorStop(0, rgba(c, a)); g.addColorStop(1, rgba(c, 0));
    ctx.fillStyle = g; ctx.fillRect(cx - r, cy - r, r * 2, r * 2);
  }
  function draw() {
    ctx.fillStyle = state.page; ctx.fillRect(0, 0, w, h);
    if (!state.glass || state.backdrop === 'PLAIN' || state.intensity === 0) return;
    const k = state.intensity * (state.dark ? 0.34 : 0.42); const t = phase * Math.PI * 2; const r = Math.max(w, h);
    blob(state.accent, 1.0 * k, w * (0.18 + 0.22 * Math.sin(t)), h * (0.10 + 0.10 * Math.cos(t * 2)), r * (0.62 + 0.05 * Math.sin(t * 3)));
    blob(state.secondary, 0.8 * k, w * (0.92 + 0.10 * Math.cos(t)), h * (0.42 + 0.14 * Math.sin(t + 1.2)), r * (0.55 + 0.05 * Math.cos(t * 2)));
    blob(state.tertiary, 0.75 * k, w * (0.25 + 0.20 * Math.sin(t * 2 + 2.1)), h * (0.86 + 0.08 * Math.cos(t + 0.6)), r * (0.58 + 0.04 * Math.sin(t + 1)));
    blob(state.accent, 0.5 * k, w * (0.70 + 0.18 * Math.cos(t * 2 + 0.8)), h * (0.70 + 0.10 * Math.sin(t * 2)), r * 0.40);
    const veil = ctx.createLinearGradient(0, 0, 0, h);
    veil.addColorStop(0, rgba(state.page, 0)); veil.addColorStop(1, rgba(state.page, state.dark ? 0.25 : 0.35));
    ctx.fillStyle = veil; ctx.fillRect(0, 0, w, h);
  }
  function drawOnce() { if (w) draw(); }
  function loop(ts) {
    raf = requestAnimationFrame(loop);
    if (!state.glass || state.backdrop !== 'AURORA' || !state.motion || document.hidden) return;
    if (ts - last < 33) return; // ~30 fps is plenty for a slow drift
    if (last) phase = (phase + (ts - last) / 32000) % 1;
    last = ts; draw();
  }

  // ---------------------------------------------------------------- pointer glow + count-up
  document.addEventListener('pointermove', (e) => {
    const el = e.target.closest?.('.card, .tile, .swatch, .style-card');
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--mx', (e.clientX - r.left) + 'px'); el.style.setProperty('--my', (e.clientY - r.top) + 'px');
  }, { passive: true });

  function countUp(scope = document) {
    if (!state.motion) return;
    scope.querySelectorAll('[data-count], .tile .v').forEach((el) => {
      const m = /^(\D*?)(\d[\d,]*\.?\d*)(.*)$/.exec(el.textContent.trim());
      if (!m || el.dataset.counted) return;
      el.dataset.counted = '1';
      const target = parseFloat(m[2].replace(/,/g, '')); const dec = (m[2].split('.')[1] || '').length; const t0 = performance.now();
      const step = (ts) => {
        const p = Math.min(1, (ts - t0) / 700); const e = 1 - Math.pow(1 - p, 3);
        el.textContent = m[1] + (target * e).toFixed(dec) + m[3];
        if (p < 1) requestAnimationFrame(step); else el.textContent = m[1] + m[2] + m[3];
      };
      requestAnimationFrame(step);
    });
  }

  function start() {
    document.body.prepend(canvas);
    size(); addEventListener('resize', size);
    matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', () => window.BLFX.reapply?.());
    raf = requestAnimationFrame(loop);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();

  window.BLFX = { apply, countUp, ensure, ratio, state, palettes: DATA.palettes, lightStyles: DATA.lightStyles, reapply: null };
})();
