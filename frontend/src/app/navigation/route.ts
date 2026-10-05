/**
 * Navigation state mirrored to the URL hash (spec 13 §4.5). No router library: the hash works
 * under any base path (`/`, `/compat/`, `/next/`) and needs no server fallback.
 *
 *   #/                     workspace (the active tab is not in the URL)
 *   #/history              Chats (expanded/medium: rail destination; compact: History screen)
 *   #/memory[/<path>]      Memory (compact: screen, a path opens the document screen)
 *   #/visuals[/<path>]     Visuals (same)
 *   #/settings[/<page>]    Settings screen
 *   #/dev/gallery          handled by main.tsx before the app mounts
 *
 * The route store is the source of truth. The URL is written with `replaceState` only; the
 * history entries that make Back close a screen belong to the overlay stack (`ScreenLayer`
 * registers each screen with `useOverlayLayer({history: true})`), so overlays and screens share
 * one ordered stack and the stack's silent-pop bookkeeping (no double pops). A `hashchange` on
 * an entry we did not create (a typed or pasted URL) navigates; on our own entries it only
 * re-syncs the URL.
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';

export type Route =
  | { readonly name: 'workspace' }
  | { readonly name: 'history' }
  | { readonly name: 'memory'; readonly path: string | null }
  | { readonly name: 'visuals'; readonly path: string | null }
  | { readonly name: 'settings'; readonly page: string | null };

export type RouteName = Route['name'];

export const WORKSPACE: Route = { name: 'workspace' };

const OVERLAY_STATE_KEY = '__archieOverlay';

function decodeSegments(rest: string[]): string | null {
  const parts = rest.filter(Boolean).map((p) => {
    try {
      return decodeURIComponent(p);
    } catch {
      return p;
    }
  });
  return parts.length ? parts.join('/') : null;
}

function encodeSegments(path: string): string {
  return path
    .split('/')
    .filter(Boolean)
    .map((p) => encodeURIComponent(p))
    .join('/');
}

/** `#/memory/a/b.md` → `{name: 'memory', path: 'a/b.md'}`. Unknown hashes → workspace. */
export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#/, '').replace(/^\//, '');
  const [head = '', ...rest] = raw.split('/');
  switch (head) {
    case 'history':
      return { name: 'history' };
    case 'memory':
      return { name: 'memory', path: decodeSegments(rest) };
    case 'visuals':
      return { name: 'visuals', path: decodeSegments(rest) };
    case 'settings':
      return { name: 'settings', page: decodeSegments(rest) };
    default:
      return WORKSPACE;
  }
}

export function formatRoute(r: Route): string {
  switch (r.name) {
    case 'workspace':
      return '#/';
    case 'history':
      return '#/history';
    case 'memory':
      return r.path ? `#/memory/${encodeSegments(r.path)}` : '#/memory';
    case 'visuals':
      return r.path ? `#/visuals/${encodeSegments(r.path)}` : '#/visuals';
    case 'settings':
      return r.page ? `#/settings/${encodeSegments(r.page)}` : '#/settings';
  }
}

export function sameRoute(a: Route, b: Route): boolean {
  return formatRoute(a) === formatRoute(b);
}

export const routeStore = createStore<{ route: Route }>(() => ({
  route: typeof location !== 'undefined' ? parseHash(location.hash) : WORKSPACE,
}));

function writeUrl(r: Route): void {
  if (typeof window === 'undefined') return;
  const hash = formatRoute(r);
  if (location.hash === hash || (hash === '#/' && (location.hash === '' || location.hash === '#'))) return;
  try {
    window.history.replaceState(window.history.state, '', location.pathname + location.search + hash);
  } catch {
    // Safari private mode / sandboxed frames: the in-memory route still works.
  }
}

/** Go to a route (no history entry of its own: screens push theirs through the overlay stack). */
export function navigate(r: Route): void {
  if (!sameRoute(routeStore.getState().route, r)) routeStore.setState({ route: r });
  writeUrl(r);
}

export function useRoute(): Route {
  return useStore(routeStore, (s) => s.route);
}

function isOwnEntry(state: unknown): boolean {
  return !!state && typeof state === 'object' && OVERLAY_STATE_KEY in state;
}

/**
 * Listen for typed/pasted hashes and keep the URL in step after Back. Returns an uninstall
 * function. Idempotent per call site (App installs it once).
 */
export function installHashSync(win: Window = window): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const resync = (): void => {
    timer = null;
    writeUrl(routeStore.getState().route);
  };
  const onHash = (): void => {
    if (isOwnEntry(win.history.state)) {
      // An overlay-stack traversal (silent pop or Back): the overlays decide; keep the URL true.
      if (timer === null) timer = setTimeout(resync, 0);
      return;
    }
    const next = parseHash(win.location.hash);
    if (!sameRoute(next, routeStore.getState().route)) routeStore.setState({ route: next });
  };
  const onPop = (): void => {
    if (timer === null) timer = setTimeout(resync, 0);
  };
  win.addEventListener('hashchange', onHash);
  win.addEventListener('popstate', onPop);
  writeUrl(routeStore.getState().route);
  return () => {
    win.removeEventListener('hashchange', onHash);
    win.removeEventListener('popstate', onPop);
    if (timer !== null) clearTimeout(timer);
  };
}

/** Tests. */
export function resetRoute(r: Route = WORKSPACE): void {
  routeStore.setState({ route: r });
}
