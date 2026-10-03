/** Types for scenarios.mjs (consumed from TypeScript tests). */

export interface FixtureSession {
  kind: 'agent' | 'orchestrator';
  provider: 'claude' | 'qwen' | 'gemini' | null;
  local_id: string;
  sdk_id: string | null;
  live_status?: 'idle' | 'streaming' | 'tool_use' | 'thinking' | 'interrupted' | 'disconnected';
  voice_active?: boolean;
}

export interface FixtureAction {
  at: number;
  type: string;
  [field: string]: unknown;
}

export interface Fixture {
  name: string;
  description: string;
  source: 'synthetic' | 'recorded';
  session: FixtureSession;
  initial_history?: unknown;
  events: Record<string, unknown>[];
  client_actions?: FixtureAction[];
  expected: {
    entries: unknown[];
    orphan_results?: unknown[];
    unattributed_results?: unknown[];
    queue?: unknown[];
    state?: Record<string, unknown>;
    controller?: Record<string, unknown>;
  };
}

export interface FixtureFile {
  file: string;
  fixture: Fixture;
}

export type ScriptStep =
  | { kind: 'emit'; frame: Record<string, unknown>; at: number }
  | { kind: 'wait'; for: string; at: number }
  | { kind: 'drop'; at: number }
  | { kind: 'rest'; mode: string; response: unknown; at: number };

export declare const MOCK_ROOT: string;
export declare const DATA_DIR: string;
export declare const SCENARIOS_DIR: string;
export declare const DEFAULT_FIXTURES_DIR: string;
export declare function fixturesDir(): string;
export declare function loadFixtures(dir?: string): FixtureFile[];
export declare function loadMockScenarios(dir?: string): FixtureFile[];
export declare function loadScenarioMap(fixtures?: string, mock?: string): Map<string, Fixture>;
export declare function buildScript(fixture: Fixture): ScriptStep[];
export declare function firstCursor(fixture: Fixture): { stream_id: string; next_seq: number } | null;
export declare function sdkIdsOf(fixture: Fixture): string[];
