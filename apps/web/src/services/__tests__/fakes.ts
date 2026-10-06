/**
 * Test doubles for the services: a fake WebSocket (binary server frames like the backend, G-2),
 * a controllable visibility source, and a routed fake `fetch` that records every request.
 */
import { vi } from 'vitest';
import {
  clearSnackbars,
  createManualScheduler,
  resetCatalog,
  resetConnection,
  resetServerConfig,
  resetTabs,
  setFrameScheduler,
  type ManualScheduler,
} from '@/stores';
import { configureServices, resetServicesEnv, stopServices, type VisibilitySource } from '@/services';

export class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  binaryType = 'blob';
  readyState = 0;
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: { code?: number; reason?: string; wasClean?: boolean }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  sent: string[] = [];
  closedByClient = false;

  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  send(data: unknown): void {
    if (typeof data !== 'string') throw new Error('binary frame sent by the client (T-2)');
    if (this.readyState !== 1) throw new Error('send on a socket that is not open');
    this.sent.push(data);
  }

  close(): void {
    this.closedByClient = true;
    this.readyState = 3;
  }

  // ── server side ──
  open(): void {
    this.readyState = 1;
    this.onopen?.({});
  }

  /** Deliver a frame as the backend does: a binary UTF-8 JSON frame. */
  emit(frame: Record<string, unknown>): void {
    const bytes = new TextEncoder().encode(JSON.stringify(frame));
    this.onmessage?.({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
  }

  emitText(raw: string): void {
    this.onmessage?.({ data: raw });
  }

  drop(code = 1006): void {
    this.readyState = 3;
    this.onclose?.({ code, reason: '', wasClean: false });
  }

  messages(): Record<string, unknown>[] {
    return this.sent.map((s) => JSON.parse(s) as Record<string, unknown>);
  }

  types(): string[] {
    return this.messages().map((m) => String(m.type));
  }

  static last(path?: string): FakeWebSocket {
    const list = path ? FakeWebSocket.instances.filter((w) => w.url.endsWith(path)) : FakeWebSocket.instances;
    const ws = list[list.length - 1];
    if (!ws) throw new Error(`no socket${path ? ` for ${path}` : ''}`);
    return ws;
  }

  static all(path: string): FakeWebSocket[] {
    return FakeWebSocket.instances.filter((w) => w.url.endsWith(path));
  }

  /** Every client message on every socket. */
  static allSent(): Record<string, unknown>[] {
    return FakeWebSocket.instances.flatMap((w) => w.messages());
  }
}

export class FakeVisibility implements VisibilitySource {
  hidden = false;
  private fns = new Set<(hidden: boolean) => void>();
  isHidden(): boolean {
    return this.hidden;
  }
  subscribe(fn: (hidden: boolean) => void): () => void {
    this.fns.add(fn);
    return () => this.fns.delete(fn);
  }
  set(hidden: boolean): void {
    this.hidden = hidden;
    for (const fn of Array.from(this.fns)) fn(hidden);
  }
}

export interface RecordedRequest {
  method: string;
  path: string;
  url: string;
  body: unknown;
}

type Responder = (req: RecordedRequest) => Response | Promise<Response>;

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

export function textResponse(body: string, status = 200, contentType = 'text/plain'): Response {
  return new Response(body, { status, headers: { 'content-type': contentType } });
}

export class FakeFetch {
  requests: RecordedRequest[] = [];
  private routes: { method: string; match: RegExp | string; respond: Responder }[] = [];

  on(method: string, match: RegExp | string, respond: Responder | object): this {
    const fn: Responder = typeof respond === 'function' ? (respond as Responder) : () => jsonResponse(respond);
    this.routes.unshift({ method, match, respond: fn });
    return this;
  }

  readonly fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const u = new URL(url);
    let body: unknown = init?.body;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        // keep the raw string
      }
    }
    const req: RecordedRequest = { method: init?.method ?? 'GET', path: u.pathname + u.search, url, body };
    this.requests.push(req);
    const route = this.routes.find(
      (r) => r.method === req.method && (typeof r.match === 'string' ? u.pathname === r.match : r.match.test(u.pathname + u.search)),
    );
    if (!route) return jsonResponse({ detail: 'Not Found' }, 404);
    return route.respond(req);
  });

  calls(method: string, pathPrefix: string): RecordedRequest[] {
    return this.requests.filter((r) => r.method === method && r.path.startsWith(pathPrefix));
  }
}

export const EMPTY = { messages: [], total_count: 0, has_more: false, start_index: 0 };

export interface Harness {
  fetch: FakeFetch;
  visibility: FakeVisibility;
  scheduler: ManualScheduler;
}

/** Fresh environment for one test: fake socket, fetch, visibility, a manual frame scheduler. */
export function setupServices(): Harness {
  stopServices();
  FakeWebSocket.instances = [];
  const fetch = new FakeFetch();
  fetch.on('GET', '/api/sessions', []).on('GET', '/api/sessions/pool/live', []).on('GET', '/api/visualizations', []);
  const visibility = new FakeVisibility();
  const scheduler = createManualScheduler();
  setFrameScheduler(scheduler);
  configureServices({
    baseUrl: 'http://backend.test',
    WebSocket: FakeWebSocket as unknown as never,
    fetch: fetch.fetch as unknown as typeof globalThis.fetch,
    visibility,
    random: () => 0.5,
    log: () => undefined,
  });
  resetTabs();
  resetCatalog();
  resetConnection();
  resetServerConfig();
  clearSnackbars();
  return { fetch, visibility, scheduler };
}

export function teardownServices(): void {
  stopServices();
  resetServicesEnv();
  setFrameScheduler(null);
}

/** Let pending promise callbacks run (works with fake timers too). */
export async function flushPromises(times = 40): Promise<void> {
  for (let i = 0; i < times; i++) await Promise.resolve();
}
