/**
 * The mock backend (mock-server/, random port) for W-11's protocol-sequence tests: real HTTP and
 * real WebSockets, with every client frame and REST call recorded in order.
 */
import NodeWebSocket from 'ws';
import { startMockServer, type MockServer } from '../../../../mock-server/server.mjs';
import { configureServices } from '@/services';
import { setFrameScheduler } from '@/stores';
import { setupServices, teardownServices } from '../../../services/__tests__/fakes';

export interface Recorded {
  /** `METHOD /path` of every REST call, in order. */
  readonly rest: string[];
  /** Every client frame, with the socket path. */
  readonly frames: { path: string; msg: Record<string, unknown> }[];
}

export const recorded: Recorded = { rest: [], frames: [] };

class RecordingSocket extends NodeWebSocket {
  private readonly path: string;
  constructor(url: string) {
    super(url);
    this.path = new URL(url).pathname;
  }
  override send(data: unknown, ...rest: unknown[]): void {
    try {
      recorded.frames.push({ path: this.path, msg: JSON.parse(String(data)) as Record<string, unknown> });
    } catch {
      // not JSON: not ours
    }
    (super.send as (...a: unknown[]) => void)(data, ...rest);
  }
}

export async function startMock(opts: { nginx413?: boolean } = {}): Promise<MockServer> {
  return startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true, ...opts });
}

export function useMock(server: MockServer): void {
  setupServices();
  // publish on a microtask so React sees updates (components under test)
  setFrameScheduler({
    schedule: (fn) => {
      let cancelled = false;
      void Promise.resolve().then(() => {
        if (!cancelled) fn();
      });
      return () => {
        cancelled = true;
      };
    },
  });
  recorded.rest.length = 0;
  recorded.frames.length = 0;
  configureServices({
    baseUrl: server.url,
    WebSocket: RecordingSocket as never,
    fetch: (input: RequestInfo | URL, init?: RequestInit) => {
      const u = new URL(String(input));
      recorded.rest.push(`${init?.method ?? 'GET'} ${u.pathname}`);
      return globalThis.fetch(input, init);
    },
  });
}

export function endMock(server: MockServer): void {
  teardownServices();
  // the mock keeps pool sessions alive across tests (like the backend): close them all
  const engine = server.engine as unknown as { runs: Map<string, unknown>; closeRun(id: string, o?: { silent?: boolean }): void };
  for (const id of Array.from(engine.runs.keys())) engine.closeRun(id, { silent: true });
}

export async function until(pred: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!pred()) {
    if (Date.now() > end) throw new Error(`timeout waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

/** Frames of one type sent on a socket path. */
export function framesOf(type: string, path?: string): Record<string, unknown>[] {
  return recorded.frames.filter((f) => f.msg.type === type && (!path || f.path === path)).map((f) => f.msg);
}

/** REST calls matching a prefix (e.g. `POST /api/sessions/`). */
export function restMatching(re: RegExp): string[] {
  return recorded.rest.filter((r) => re.test(r));
}
