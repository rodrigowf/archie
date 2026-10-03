// Service worker for PWA installability (spec 13 §1.7, inv02 §1.10).
// Registered only by the production main build at '/' (scripts/vite-plugin-html-target.ts).
// Navigations: network-first with a cached index.html fallback. Everything else: network only.
// Cache 'assistant-v2' replaces 'assistant-v1' (the old app); activate deletes every other cache.

const CACHE_NAME = 'assistant-v2';
const SCOPE = new URL(self.registration.scope).pathname; // '/' in production
const INDEX = SCOPE + 'index.html';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll([SCOPE, INDEX])));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match(INDEX)));
  }
  // Other requests are not intercepted (network only).
});
