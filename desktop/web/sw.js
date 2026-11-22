// Offline shell: the UI opens without a connection and shows the last synced snapshot.
const CACHE = 'blacklab-desktop-v9';
const SHELL = ['./', 'index.html', 'app.css', 'app.js', 'config.js', 'data.js', 'knowledge.js', 'fx.js', 'terminal.js', 'vendor/xterm/xterm.js', 'vendor/xterm/addon-fit.js', 'vendor/xterm/xterm.css', 'icon.png', 'icon-192.png', 'icon-maskable.png', 'manifest.webmanifest'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return; // never cache phone, Google or companion traffic
  if (/^\/(local|phone|api)\//.test(url.pathname)) return;                // …nor the companion's own API (terminal output, status)
  e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r; })
    .catch(() => caches.match(e.request).then((r) => r || caches.match('index.html'))));
});
