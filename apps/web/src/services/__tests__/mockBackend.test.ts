/**
 * End to end against the mock backend (mock-server/, random port): real WebSockets (jsdom) and
 * real HTTP, driven only through `@/services`. Includes the W-06 DoD "headless demo" that logs a
 * full turn, and fixture scenarios replayed through the real SessionRuntime, including a dropped
 * socket with in-memory replay and with `replay_overflow` → REST reload.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import NodeWebSocket from 'ws';
import type { Fixture } from '../../../mock-server/scenarios.mjs';
import { startMockServer, type MockServer } from '../../../mock-server/server.mjs';
import { normalise } from '../../protocol/__tests__/harness';
import type { Conversation, Entry } from '@/protocol';
import { catalogStore, tabsStore } from '@/stores';
import {
  configureServices,
  openArchie,
  openSession,
  refreshSessionList,
  startServices,
  type ArchieRuntime,
  type ReconnectPolicy,
  type SessionRuntime,
} from '@/services';
import { setupServices, teardownServices } from './fakes';

let server: MockServer;
const fastReconnect: ReconnectPolicy = { delayMs: () => 20 };

beforeAll(async () => {
  server = await startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true });
});
afterAll(async () => {
  await server.close();
});
beforeEach(() => {
  setupServices();
  configureServices({
    baseUrl: server.url,
    // Node's `ws` client (jsdom's global WebSocket is Node's own, which rejects jsdom's Event).
    WebSocket: NodeWebSocket as never,
    fetch: (...a: Parameters<typeof fetch>) => globalThis.fetch(...a),
  });
});
afterEach(() => teardownServices());

async function until(pred: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!pred()) {
    if (Date.now() > end) throw new Error(`timeout waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

function summary(e: Entry): string {
  if (e.kind === 'user') return `user(${e.origin}): ${e.text}`;
  if (e.kind === 'notice') return `notice: ${e.notice}`;
  return `assistant: ${e.blocks.map((b) => (b.type === 'tool' ? `[${b.tool_name} ${b.status}]` : b.type === 'text' ? JSON.stringify(b.text.slice(0, 40)) : b.type)).join(' ')}`;
}

describe('headless demo (W-06 DoD)', () => {
  it('opens an agent session, sends a prompt and logs the full turn', async () => {
    startServices({ skipInitialSync: true, reconnectPolicy: fastReconnect });
    const rt = openSession({ kind: 'agent', focus: true }) as SessionRuntime;
    await until(() => rt.conv.conn === 'subscribed', 'session_started');
    rt.send('please use a tool');
    await until(() => !!rt.conv.ref.sdkId && rt.conv.status === 'idle', 'turn end');
    const lines = rt.conv.entries.map(summary);
    // the demo log (spec 13 W-06 DoD)
    process.stdout.write(`\n[W-06 demo] session ${rt.localId} → sdk ${String(rt.conv.ref.sdkId)}\n${lines.map((l) => `  ${l}`).join('\n')}\n`);
    expect(lines[0]).toBe('user(local): please use a tool');
    expect(lines[1]).toMatch(/^assistant: ".*" \[Bash done\] \[Read done\] ".*"$/);
    expect(rt.conv.counters.turns).toBeGreaterThan(0);
    await refreshSessionList();
    expect(catalogStore.getState().sessions.items.some((s) => s.session_id === rt.conv.ref.sdkId)).toBe(true);
  });

  it('Archie: attach over the single orchestrator socket, a tool turn, models, and pool sync of another device', async () => {
    startServices({ skipInitialSync: true, reconnectPolicy: fastReconnect });
    const r = await openArchie({ focus: true });
    const archie = r.runtime as ArchieRuntime;
    await until(() => archie.conv.conn === 'subscribed', 'archie subscribed');
    expect(archie.conv.ref.sdkId).toBe(archie.localId); // ID-4 jsonl_id
    archie.send('tool time');
    await until(() => archie.conv.status === 'idle' && archie.conv.entries.length >= 2, 'archie turn');
    archie.requestModels();
    await until(() => archie.handle.store.getState().models !== null, 'models_list');
    // another device opens an agent session: it arrives as a background tab through the watcher
    const other = new NodeWebSocket(`ws://127.0.0.1:${server.port}/api/sessions/chat`);
    await new Promise((res) => (other.onopen = res));
    other.send(JSON.stringify({ type: 'start', local_id: 'other-device-1' }));
    await until(() => tabsStore.getState().tabs.some((t) => t.id === 'other-device-1'), 'background tab');
    expect(tabsStore.getState().activeId).toBe(archie.localId); // P-6
    other.close();
  });
});

const SCENARIOS = ['plain_text_turn', 'text_tool_interleaving', 'termination_banner', 'replay_after_reconnect_overlap', 'replay_overflow_rest_reload'];

describe('fixture scenarios through the real SessionRuntime', () => {
  it.each(SCENARIOS)('%s', async (name) => {
    startServices({ skipInitialSync: true, reconnectPolicy: fastReconnect });
    const fx = server.engine.scenarios.get(name) as Fixture;
    const localId = `${name}:${Math.random().toString(36).slice(2)}`;
    const rt = openSession({
      kind: 'agent',
      localId,
      sdkId: fx.session.sdk_id,
      provider: fx.session.provider,
      liveStatus: fx.session.live_status ?? null,
      focus: true,
    }) as SessionRuntime;
    await until(() => rt.conv.conn === 'subscribed' && !rt.conv.reloading, 'subscribed');
    for (const a of fx.client_actions ?? []) if (a.type === 'send') rt.send(String(a.text));
    await until(() => {
      const run = server.engine.runs.get(localId);
      return !!run && run.i >= run.steps.length && !run.timer && !run.waitingFor && !rt.conv.reloading;
    }, `${name} replay`);
    await new Promise((r) => setTimeout(r, 50));
    const keys = Object.keys(fx.expected.state ?? {}).filter((k) => k !== 'last_start' && k !== 'local_id');
    const actual = normalise(rt.conv as Conversation, keys);
    expect(actual.entries).toStrictEqual(fx.expected.entries);
    const expState = Object.fromEntries(Object.entries(fx.expected.state ?? {}).filter(([k]) => keys.indexOf(k) >= 0));
    expect(actual.state).toStrictEqual(expState);
  });
});
