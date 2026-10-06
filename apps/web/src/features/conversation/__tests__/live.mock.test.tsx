/**
 * End to end against the mock backend (mock-server/, random port): real WebSockets and HTTP
 * through `@/services`, rendered by the conversation view. Every published frame is checked:
 *
 * - R4: the DOM order equals the reducer order at every publish while the scenario streams.
 * - R7: tool outputs reach their cards live (by id, by position for an empty id, after the turn
 *   ended, and through the REST reconcile), and no card is left without output at the end.
 */
import { act, render } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import NodeWebSocket from 'ws';
import type { Fixture } from '../../../../mock-server/scenarios.mjs';
import { startMockServer, type MockServer } from '../../../../mock-server/server.mjs';
import { configureServices, openSession, startServices, type ReconnectPolicy, type SessionRuntime } from '@/services';
import { getSessionEntry } from '@/stores';
import { setupServices, teardownServices, type Harness } from '../../../services/__tests__/fakes';
import { ConversationPanel } from '../ConversationPanel';
import { preloadRich } from '../lazyRich';
import { convSequence, domSequence } from './helpers';

// The rich chunk (markdown, tool cards) loads lazily in the app; tests load it up front.
beforeAll(async () => {
  await preloadRich();
});

let server: MockServer;
let h: Harness;
const fastReconnect: ReconnectPolicy = { delayMs: () => 20 };

beforeAll(async () => {
  server = await startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true });
});
afterAll(async () => {
  await server.close();
});
beforeEach(() => {
  h = setupServices();
  configureServices({
    baseUrl: server.url,
    WebSocket: NodeWebSocket as never,
    fetch: (...a: Parameters<typeof fetch>) => globalThis.fetch(...a),
  });
});
afterEach(() => teardownServices());

async function tick(): Promise<void> {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 5));
    h.scheduler.flush();
    await Promise.resolve();
  });
}

interface Observed {
  checks: number;
  /** A card showed its output while the turn was still running. */
  liveOutput: boolean;
}

async function runScenario(name: string): Promise<{ rt: SessionRuntime; seen: Observed; fx: Fixture }> {
  startServices({ skipInitialSync: true, reconnectPolicy: fastReconnect });
  const fx = server.engine.scenarios.get(name) as Fixture;
  const localId = `${name}:${Math.random().toString(36).slice(2)}`;
  const rt = openSession({ kind: 'agent', localId, sdkId: fx.session.sdk_id, provider: fx.session.provider, focus: true }) as SessionRuntime;
  const { container } = render(<ConversationPanel localId={localId} hidden={false} />);
  const seen: Observed = { checks: 0, liveOutput: false };
  const check = (): void => {
    const store = getSessionEntry(rt.localId)?.handle.store;
    if (!store) return;
    const conv = store.getState().conv;
    expect(domSequence(container)).toEqual(convSequence(conv.entries));
    seen.checks += 1;
    if (conv.inTurn && container.querySelector('[data-tool][data-status="done"]')) seen.liveOutput = true;
  };
  const until = async (pred: () => boolean, what: string): Promise<void> => {
    const end = Date.now() + 5000;
    while (!pred()) {
      if (Date.now() > end) throw new Error(`timeout: ${what}`);
      await tick();
      check();
    }
  };
  await until(() => rt.conv.conn === 'subscribed' && !rt.conv.reloading, 'subscribed');
  for (const a of fx.client_actions ?? []) if (a.type === 'send') rt.send(String(a.text));
  await until(() => {
    const run = server.engine.runs.get(rt.localId) ?? server.engine.runs.get(localId);
    return !!run && run.i >= run.steps.length && !run.timer && !run.waitingFor && !rt.conv.reloading;
  }, `${name} replay`);
  for (let i = 0; i < 120; i++) await tick(); // reconcile debounce (500 ms) + REST
  check();
  return { rt, seen, fx };
}

function cardStatuses(): string[] {
  return Array.from(document.querySelectorAll('[data-tool]')).map((c) => c.getAttribute('data-status') ?? '');
}

describe('live against the mock backend', () => {
  it('R4 + R7: text_tool_interleaving streams in order and outputs appear while the turn runs', async () => {
    const { seen } = await runScenario('text_tool_interleaving');
    expect(seen.checks).toBeGreaterThan(5);
    expect(seen.liveOutput).toBe(true);
    expect(domSequence(document.body)).toEqual([
      'user:list and read',
      'text:Let me look.',
      'tool:Bash',
      'text:Found two files.',
      'tool:Read',
      'text:a.txt says hello.',
    ]);
    expect(cardStatuses()).toEqual(['done', 'done']);
  });

  it.each(['tool_result_string_shape_empty_id', 'tool_result_after_turn_ended', 'tool_result_empty_id_parallel_reconcile'])(
    'R7: %s ends with every card showing its output',
    async (name) => {
      const { fx } = await runScenario(name);
      const expected = fx.expected.entries.flatMap((e) =>
        (e as { kind: string }).kind === 'assistant'
          ? (e as { blocks: { type: string; status?: string }[] }).blocks.filter((b) => b.type === 'tool').map((b) => b.status ?? '')
          : [],
      );
      expect(cardStatuses()).toEqual(expected);
      expect(cardStatuses().every((s) => s === 'done' || s === 'error')).toBe(true);
    },
  );
});
