/** Types for server.mjs (consumed from TypeScript tests). */
import type { Fixture } from './scenarios.mjs';

export interface MockServerOptions {
  port?: number;
  host?: string;
  /** No delays between replayed frames. */
  fast?: boolean;
  /** Delay multiplier (default 1). */
  speed?: number;
  /** Default scenario for sessions that do not pick one. */
  scenario?: string;
  fixturesDir?: string;
  /** Reject uploads > 1 MiB with nginx's HTML 413 (G-1). */
  nginx413?: boolean;
  quiet?: boolean;
  log?: (...args: unknown[]) => void;
}

export interface MockRun {
  localId: string;
  kind: 'agent' | 'orchestrator';
  fixture: Fixture | null;
  i: number;
  steps: unknown[];
  timer: unknown;
  waitingFor: string | null;
  busy: boolean;
  sdkId: string | null;
}

export interface MockServer {
  port: number;
  url: string;
  engine: { runs: Map<string, MockRun>; scenarios: Map<string, Fixture> };
  close(): Promise<void>;
}

export declare function startMockServer(opts?: MockServerOptions): Promise<MockServer>;
