/**
 * Fixture runner and normaliser (shared/protocol-fixtures/README.md "Runner algorithm",
 * "Normalisation"). Every event goes through the real decoder as a binary frame first (T-1).
 */
import type { Fixture, FixtureAction } from '../../../mock-server/scenarios.mjs';
import type { Conversation, ConversationInput, Effect, Entry, MessagesPage } from '../index';
import { decodeFrame, initialConversation, stepConversation } from '../index';

export interface RunResult {
  conv: Conversation;
  effects: Effect[];
  /** Every intermediate state, oldest first (state after each input). */
  trace: { input: ConversationInput; prev: Conversation; next: Conversation; effects: readonly Effect[] }[];
}

/** Encode an object the way the backend sends it: UTF-8 JSON in a binary frame (G-2). */
export function binaryFrame(obj: unknown): ArrayBuffer {
  const bytes = new TextEncoder().encode(JSON.stringify(obj));
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

export function frameInput(obj: unknown): ConversationInput {
  const d = decodeFrame(binaryFrame(obj));
  if (!d.ok) throw new Error(`fixture frame did not decode: ${d.reason}`);
  return { type: 'frame', frame: d.frame };
}

/** The fixture `client_actions` vocabulary → reducer inputs (null = no reducer effect). */
export function actionInput(a: FixtureAction): ConversationInput | null {
  switch (a.type) {
    case 'send':
      return { type: 'local_send', text: String(a.text) };
    case 'send_audio':
      return { type: 'local_send_audio' };
    case 'inject':
      return { type: 'local_inject', text: String(a.text) };
    case 'interrupt':
      return { type: 'local_interrupt' };
    case 'compact':
      return { type: 'local_compact' };
    case 'stop':
      return { type: 'local_stop' };
    case 'permission_response':
      return null; // the resolve comes from the server
    case 'datachannel_event':
      return { type: 'datachannel_event', event: a.event as Record<string, unknown> };
    case 'voice_local_end':
      return { type: 'voice_local_end' };
    case 'ws_closed':
      return { type: 'socket_closed' };
    case 'ws_open':
      return { type: 'socket_open' };
    case 'rest_page':
      return { type: 'history_page', mode: a.mode as 'replace' | 'prepend' | 'reconcile', response: a.response as MessagesPage };
    default:
      throw new Error(`unknown client action ${a.type}`);
  }
}

/** The fixture's initial conversation (README: socket already subscribed, history applied, loaded). */
export function fixtureStart(fx: Fixture): { conv: Conversation; effects: Effect[] } {
  let conv = initialConversation({
    localId: fx.session.local_id,
    kind: fx.session.kind,
    sdkId: fx.session.sdk_id,
    provider: fx.session.provider,
    liveStatus: fx.session.live_status ?? null,
    voiceActive: fx.session.voice_active === true,
    subscribed: true,
  });
  const effects: Effect[] = [];
  if (fx.initial_history) {
    const r = stepConversation(conv, { type: 'history_page', mode: 'replace', response: fx.initial_history as MessagesPage });
    conv = r.state;
    effects.push(...r.effects);
  }
  conv = { ...conv, history: { ...conv.history, loaded: true } };
  return { conv, effects };
}

/** The ordered input list of a fixture (actions at index i run before event i). */
export function fixtureInputs(fx: Fixture): ConversationInput[] {
  const out: ConversationInput[] = [];
  const actions = fx.client_actions ?? [];
  for (let i = 0; i <= fx.events.length; i++) {
    for (const a of actions) {
      if (a.at !== i) continue;
      const inp = actionInput(a);
      if (inp) out.push(inp);
    }
    if (i < fx.events.length) out.push(frameInput(fx.events[i]));
  }
  return out;
}

export function runInputs(start: Conversation, inputs: readonly ConversationInput[]): RunResult {
  let conv = start;
  const effects: Effect[] = [];
  const trace: RunResult['trace'] = [];
  for (const input of inputs) {
    const r = stepConversation(conv, input);
    trace.push({ input, prev: conv, next: r.state, effects: r.effects });
    effects.push(...r.effects);
    conv = r.state;
  }
  return { conv, effects, trace };
}

export function runFixture(fx: Fixture): RunResult {
  const s = fixtureStart(fx);
  const r = runInputs(s.conv, fixtureInputs(fx));
  return { ...r, effects: [...s.effects, ...r.effects] };
}

// ───────────────────────────── normalisation ─────────────────────────────

export function normaliseEntry(e: Entry): Record<string, unknown> {
  if (e.kind === 'user') {
    const u: Record<string, unknown> = { kind: 'user', text: e.text, origin: e.origin, state: e.state };
    if (e.streaming === true) u.streaming = true;
    return u;
  }
  if (e.kind === 'notice') return { kind: 'notice', notice: e.notice, text: e.text };
  return {
    kind: 'assistant',
    blocks: e.blocks.map((b) => {
      if (b.type === 'text' || b.type === 'thinking') return { type: b.type, text: b.text, streaming: b.streaming };
      if (b.type === 'tool') {
        const t: Record<string, unknown> = {
          type: 'tool',
          tool_use_id: b.tool_use_id,
          tool_name: b.tool_name,
          tool_input: b.tool_input,
          status: b.status,
          output: b.output,
        };
        if (b.inferred === true) t.inferred = true;
        return t;
      }
      return {
        type: 'permission',
        request_id: b.request_id,
        tool_name: b.tool_name,
        state: b.state,
        responder: b.responder,
        message: b.message,
      };
    }),
  };
}

export const STATE_KEYS: Record<string, (c: Conversation) => unknown> = {
  status: (c) => c.status,
  in_turn: (c) => c.inTurn,
  stall: (c) => c.stall,
  termination: (c) => c.termination,
  checkpoint: (c) => c.checkpoint,
  context_tokens: (c) => c.counters.contextTokens,
  cost: (c) => c.counters.cost,
  turns: (c) => c.counters.turns,
  sdk_id: (c) => c.ref.sdkId,
  local_id: (c) => c.ref.localId,
  voice_active: (c) => c.voiceActive,
  gap_possible: (c) => c.gapPossible,
  reloading: (c) => c.reloading,
  history_has_more: (c) => c.history.hasMore,
  history_start_index: (c) => c.history.startIndex,
  last_start: (c) => c.startRequest,
};

export interface Normalised {
  entries: unknown[];
  orphan_results: unknown[];
  unattributed_results: unknown[];
  queue: unknown[];
  state: Record<string, unknown>;
}

export function normalise(c: Conversation, stateKeys: readonly string[] = Object.keys(STATE_KEYS)): Normalised {
  const state: Record<string, unknown> = {};
  for (const k of stateKeys) {
    const get = STATE_KEYS[k];
    if (!get) throw new Error(`unknown expected.state key "${k}"`);
    state[k] = get(c);
  }
  return {
    entries: c.entries.map(normaliseEntry),
    orphan_results: c.orphanResults.map((o) => ({ tool_use_id: o.tool_use_id, output: o.output, is_error: o.is_error })),
    unattributed_results: c.unattributed.map((u) => ({ output: u.output, is_error: u.is_error })),
    queue: c.queue.map((q) => ({ text: q.text, owner: q.owner })),
    state,
  };
}

/** Deep-freeze a state (tests: any write by the reducer to a previous state throws). */
export function deepFreeze<T>(x: T): T {
  if (x && typeof x === 'object' && !Object.isFrozen(x)) {
    Object.freeze(x);
    for (const v of Object.values(x as Record<string, unknown>)) deepFreeze(v);
  }
  return x;
}
