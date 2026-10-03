/**
 * Injected environment of the services (spec 12 L-1 spirit: I/O behind small interfaces so the
 * runtimes run under test with fake sockets, fake fetch and fake timers).
 *
 * Base URL: the app may be served under `/`, `/compat/`, `/next/` or `/next-compat/`, but every
 * API path is at the origin root (`/api/…`, `/memory/…`, `/uploads/…`), so the default base is
 * `location.origin` and WebSockets use `ws:`/`wss:` from the page scheme (inventory 01 §1.1).
 */
import { exposeDebug } from '@/platform';

export interface WebSocketLike {
  binaryType: string;
  readonly readyState: number;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: { code?: number; reason?: string; wasClean?: boolean }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type WebSocketCtor = new (url: string) => WebSocketLike;

export interface VisibilitySource {
  isHidden(): boolean;
  /** Called with `true` when the page becomes hidden, `false` when visible. Returns an unsubscribe. */
  subscribe(fn: (hidden: boolean) => void): () => void;
}

export interface ServicesEnv {
  /** Origin (and optional path prefix) of the backend, no trailing slash. */
  baseUrl: string;
  WebSocket: WebSocketCtor;
  fetch: typeof fetch;
  XMLHttpRequest: typeof XMLHttpRequest | null;
  visibility: VisibilitySource;
  /** Random in [0,1) for reconnect jitter. */
  random: () => number;
  log: (level: 'debug' | 'info' | 'warn' | 'error', ...args: unknown[]) => void;
}

export function documentVisibility(doc: Document | undefined = typeof document !== 'undefined' ? document : undefined): VisibilitySource {
  return {
    isHidden: () => !!doc && doc.hidden === true,
    subscribe(fn) {
      if (!doc) return () => undefined;
      const h = (): void => fn(doc.hidden === true);
      doc.addEventListener('visibilitychange', h);
      return () => doc.removeEventListener('visibilitychange', h);
    },
  };
}

function defaultBaseUrl(): string {
  if (typeof location === 'undefined' || !location.origin || location.origin === 'null') return '';
  return location.origin;
}

function missingWebSocket(): never {
  throw new Error('WebSocket is not available');
}

function defaultEnv(): ServicesEnv {
  const g = globalThis as unknown as {
    WebSocket?: WebSocketCtor;
    fetch?: typeof fetch;
    XMLHttpRequest?: typeof XMLHttpRequest;
  };
  return {
    baseUrl: defaultBaseUrl(),
    WebSocket: g.WebSocket ?? (missingWebSocket as unknown as WebSocketCtor),
    fetch: (...args: Parameters<typeof fetch>) => (g.fetch as typeof fetch)(...args),
    XMLHttpRequest: g.XMLHttpRequest ?? null,
    visibility: documentVisibility(),
    random: Math.random,
    log: (level, ...args) => {
      if (level === 'debug') return;
      (level === 'error' ? console.error : level === 'warn' ? console.warn : console.info)('[services]', ...args);
    },
  };
}

let env: ServicesEnv | null = null;

export function getEnv(): ServicesEnv {
  if (!env) env = defaultEnv();
  return env;
}

/** Override parts of the environment (tests; a non-origin backend in dev tools). */
export function configureServices(partial: Partial<ServicesEnv>): ServicesEnv {
  env = { ...getEnv(), ...partial };
  exposeDebug('services', { baseUrl: env.baseUrl });
  return env;
}

export function resetServicesEnv(): void {
  env = null;
}

export function httpUrl(path: string): string {
  return getEnv().baseUrl + path;
}

/** `wss:` when the base is https, else `ws:` (inventory 01 §1.1). */
export function wsUrl(path: string): string {
  const base = getEnv().baseUrl;
  if (base) return base.replace(/^http/, 'ws') + path;
  if (typeof location !== 'undefined') return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`;
  return path;
}
