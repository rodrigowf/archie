/**
 * Fixture / scenario loading and replay scripts, shared by the mock server (server.mjs), the
 * protocol conformance suite (src/protocol/__tests__) and later fake-socket tests (W-06).
 *
 * A scenario is any file with the apps/protocol-fixtures schema (README there):
 *   - every shared fixture (apps/protocol-fixtures/*.json, override with FIXTURES_DIR);
 *   - extra mock-only scenarios in mock-server/data/scenarios/*.json (same schema).
 *
 * `buildScript(fixture)` turns a fixture into replay steps for a server:
 *   { kind: 'emit', frame }            send this frame (binary) to the session's subscribers
 *   { kind: 'wait', for: <type>, at }  pause until the client sends a message of that type
 *   { kind: 'drop', at }               drop the socket (the client reconnects and re-sends start)
 *   { kind: 'rest', mode, response }   from now on serve this page for the session's history
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const MOCK_ROOT = HERE;
export const DATA_DIR = path.join(HERE, 'data');
export const SCENARIOS_DIR = path.join(DATA_DIR, 'scenarios');
export const DEFAULT_FIXTURES_DIR = path.resolve(HERE, '..', '..', 'protocol-fixtures');

/** The fixtures directory: `FIXTURES_DIR` (set by vitest.config.ts) or the repo default. */
export function fixturesDir() {
  return process.env.FIXTURES_DIR || DEFAULT_FIXTURES_DIR;
}

function readJsonDir(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((file) => ({ file, fixture: JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8')) }));
}

/** Every shared fixture, in file-name order. */
export function loadFixtures(dir = fixturesDir()) {
  return readJsonDir(dir);
}

/** Mock-only scenarios (same schema as the fixtures). */
export function loadMockScenarios(dir = SCENARIOS_DIR) {
  return readJsonDir(dir);
}

/** name → fixture, shared fixtures first; a mock scenario with the same name wins. */
export function loadScenarioMap(fixtures = fixturesDir(), mock = SCENARIOS_DIR) {
  const map = new Map();
  for (const { fixture } of loadFixtures(fixtures)) map.set(fixture.name, fixture);
  for (const { fixture } of loadMockScenarios(mock)) map.set(fixture.name, fixture);
  return map;
}

/** Client action types the server waits for (the client sends a message of that type). */
const WAIT_FOR = {
  send: 'send',
  send_audio: 'send_audio',
  inject: 'inject_text',
  interrupt: 'interrupt',
  compact: 'compact',
  stop: 'stop',
  permission_response: 'permission_response',
};

/** Fixture → replay steps (see the file header). Client-only actions are skipped. */
export function buildScript(fixture) {
  const events = Array.isArray(fixture.events) ? fixture.events : [];
  const actions = Array.isArray(fixture.client_actions) ? fixture.client_actions : [];
  const steps = [];
  for (let i = 0; i <= events.length; i++) {
    for (const a of actions) {
      if (a.at !== i) continue;
      if (WAIT_FOR[a.type]) steps.push({ kind: 'wait', for: WAIT_FOR[a.type], at: i });
      else if (a.type === 'ws_closed') steps.push({ kind: 'drop', at: i });
      else if (a.type === 'rest_page') steps.push({ kind: 'rest', mode: a.mode, response: a.response, at: i });
      // ws_open (the client reconnects by itself), datachannel_event, voice_local_end: client side
    }
    if (i < events.length) steps.push({ kind: 'emit', frame: events[i], at: i });
  }
  return steps;
}

/** First `(stream_id, seq)` stamped in the fixture's events (seeds `session_started.resume_state`). */
export function firstCursor(fixture) {
  for (const f of fixture.events ?? []) {
    if (typeof f.seq === 'number' && typeof f.stream_id === 'string') return { stream_id: f.stream_id, next_seq: f.seq };
  }
  return null;
}

/** Every sdk id a fixture refers to (its session and `turn_complete.session_id`s). */
export function sdkIdsOf(fixture) {
  const ids = new Set();
  if (fixture.session?.sdk_id) ids.add(fixture.session.sdk_id);
  for (const f of fixture.events ?? []) if (f.type === 'turn_complete' && typeof f.session_id === 'string') ids.add(f.session_id);
  return [...ids];
}
