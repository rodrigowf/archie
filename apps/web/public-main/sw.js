// Service worker for PWA installability (spec 13 §1.7, inv02 §1.10).
// Registered only by the production main build at '/' (scripts/vite-plugin-html-target.ts).
// Navigations: network-first with a cached index.html fallback. Everything else: network only.
// Notifications: the app posts its "agent session finished" notices through this registration
// (src/platform/notifications.ts; Android Chrome has no page Notification); a click focuses an
// open Archie page and tells it which session to open, or opens a new one with ?open_session=.
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

self.addEventListener('notificationclick', (event) => {
  const data = (event.notification && event.notification.data) || {};
  event.notification.close();
  if (data.kind !== 'agent-turn' || !data.localId) return;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      const pages = list.filter((c) => new URL(c.url).pathname.startsWith(SCOPE));
      const page = pages.find((c) => c.focused) || pages.find((c) => c.visibilityState === 'visible') || pages[0];
      const message = { type: 'archie:notification-click', kind: data.kind, localId: data.localId, sdkId: data.sdkId || null };
      if (page) {
        return page.focus().then(
          (focused) => (focused || page).postMessage(message),
          () => page.postMessage(message),
        );
      }
      const url = new URL(SCOPE, self.location.origin);
      url.searchParams.set('open_session', data.localId);
      if (data.sdkId) url.searchParams.set('open_sdk', data.sdkId);
      return self.clients.openWindow(url.href);
    }),
  );
});
