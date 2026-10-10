/**
 * `fetch` wrapper (spec 13 §3.4): timeouts, typed errors, verbatim `detail`, nginx HTML 413,
 * SPA-fallback detection for probes (G-38: unknown paths answer `200 text/html` with the app
 * shell), and backend reachability for the connection indicator.
 */
import { markBackendOffline, markBackendOnline } from '@/stores';
import { getEnv, httpUrl } from '../env';
import { AbortedError, ApiError, NetworkError, TimeoutError, errorFromResponse } from './errors';

export type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  query?: Query;
  /** JSON body (sets `content-type: application/json`). */
  json?: unknown;
  /** Raw body (FormData, Blob, string). */
  body?: BodyInit;
  headers?: Record<string, string>;
  /** Default 15 s. `0` disables the timeout. */
  timeoutMs?: number;
  signal?: AbortSignal;
  /** How to read a 2xx body. Default `json` (`none` for 204 is automatic). */
  as?: 'json' | 'text' | 'none';
  /**
   * The endpoint may not exist on this backend: a `text/html` 2xx is the SPA fallback and becomes
   * `ApiError(404)` (G-38).
   */
  probe?: boolean;
  /** Read the headers of a 2xx answer (e.g. `pool/live`'s `X-Archie-Server-Id`, spec 12 SRV-1). */
  onHeaders?: (headers: Headers) => void;
}

export const DEFAULT_TIMEOUT_MS = 15_000;

export function buildQuery(q?: Query): string {
  if (!q) return '';
  const parts: string[] = [];
  for (const k of Object.keys(q)) {
    const v = q[k];
    if (v === undefined || v === null) continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** Encode each segment of a slash-separated path (memory files, visualizations; MEM-3, VZ-1). */
export function encodePath(path: string): string {
  return path
    .split('/')
    .map((seg) => encodeURIComponent(seg))
    .join('/');
}

interface AbortLike {
  signal: AbortSignal;
  abort(): void;
}

function makeController(): AbortLike | null {
  const Ctor = (globalThis as { AbortController?: new () => AbortLike }).AbortController;
  return Ctor ? new Ctor() : null;
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const env = getEnv();
  const url = httpUrl(path) + buildQuery(opts.query);
  const headers: Record<string, string> = { accept: 'application/json', ...opts.headers };
  let body: BodyInit | undefined = opts.body;
  if (opts.json !== undefined) {
    headers['content-type'] = 'application/json';
    body = JSON.stringify(opts.json);
  }
  const ctl = makeController();
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  let timedOut = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let detachOuter: (() => void) | null = null;
  if (opts.signal && ctl) {
    if (opts.signal.aborted) throw new AbortedError();
    const onAbort = (): void => ctl.abort();
    opts.signal.addEventListener('abort', onAbort);
    detachOuter = () => opts.signal?.removeEventListener('abort', onAbort);
  }

  const call = env.fetch(url, { method, headers, body, signal: ctl?.signal ?? opts.signal });
  const timeout =
    timeoutMs > 0
      ? new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            timedOut = true;
            ctl?.abort();
            reject(new TimeoutError());
          }, timeoutMs);
        })
      : null;

  let res: Response;
  try {
    res = await (timeout ? Promise.race([call, timeout]) : call);
  } catch (err) {
    if (timer) clearTimeout(timer);
    detachOuter?.();
    if (timedOut || err instanceof TimeoutError) throw new TimeoutError();
    if (opts.signal?.aborted) throw new AbortedError();
    const e = new NetworkError();
    markBackendOffline(e.message);
    throw e;
  }
  if (timer) clearTimeout(timer);
  detachOuter?.();
  markBackendOnline();

  const contentType = res.headers.get('content-type') ?? '';
  if (!res.ok) {
    let text = '';
    try {
      text = await res.text();
    } catch {
      text = '';
    }
    throw errorFromResponse(res.status, contentType, text);
  }
  if (opts.probe && /text\/html/i.test(contentType)) throw new ApiError(404, 'Not available on this server', null);
  opts.onHeaders?.(res.headers);
  if (res.status === 204 || opts.as === 'none') return undefined as T;
  if (opts.as === 'text') return (await res.text()) as T;
  const text = await res.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(res.status, 'The server sent an unreadable answer', text);
  }
}

export const http = {
  get: <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, opts),
  post: <T>(path: string, opts?: RequestOptions) => request<T>('POST', path, opts),
  put: <T>(path: string, opts?: RequestOptions) => request<T>('PUT', path, opts),
  patch: <T>(path: string, opts?: RequestOptions) => request<T>('PATCH', path, opts),
  del: <T>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, opts),
};

/** Run `fn`; a 404 resolves to `fallback` (rename/delete tolerate 404, inventory 02 F-19). */
export async function tolerate404<T>(fn: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return fallback;
    throw err;
  }
}
