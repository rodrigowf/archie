/**
 * Property tests for the reducer invariants (spec 12 §4.1 I-1 … I-15) and RESULT-DELIVERY
 * (§4.5 R-1 … R-9). Inputs come from (a) a seeded random generator of plausible agent and
 * orchestrator streams, (b) random interleavings of every fixture's inputs (spec 12 §4.1), and
 * (c) a backend-faithful queue simulator (I-12). Seeds are fixed, so failures reproduce.
 */
import { describe, expect, it } from 'vitest';
import { loadFixtures } from '../../../mock-server/scenarios.mjs';
import type { Block, Conversation, ConversationInput, Entry, ServerFrame, ToolBlock } from '../index';
import { initialConversation, isDuplicateSeq, stepConversation } from '../index';
import { deepFreeze, fixtureInputs, fixtureStart, frameInput } from './harness';

// ───────────────────────────── seeded PRNG ─────────────────────────────

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Rng {
  next(): number;
  int(n: number): number;
  pick<T>(xs: readonly T[]): T;
  chance(p: number): boolean;
}

function rng(seed: number): Rng {
  const r = mulberry32(seed);
  return {
    next: r,
    int: (n) => Math.floor(r() * n),
    pick: (xs) => xs[Math.floor(r() * xs.length)] as (typeof xs)[number],
    chance: (p) => r() < p,
  };
}

// ───────────────────────────── helpers ─────────────────────────────

function blocks(c: Conversation): { b: Block; i: number }[] {
  const out: { b: Block; i: number }[] = [];
  c.entries.forEach((e, i) => {
    if (e.kind === 'assistant') for (const b of e.blocks) out.push({ b, i });
  });
  return out;
}

function tools(c: Conversation): ToolBlock[] {
  return blocks(c)
    .map((x) => x.b)
    .filter((b): b is ToolBlock => b.type === 'tool');
}

/** The frame of this input if it reaches the reducer now (not held pre-start / during a reload, not a seq duplicate). */
function reducedFrame(prev: Conversation, input: ConversationInput): ServerFrame | null {
  if (input.type !== 'frame') return null;
  const f = input.frame;
  if (f.type === 'voice_audio_out' || prev.reloading) return null;
  if (prev.awaitingSessionStarted && f.type !== 'session_started') return null;
  if (typeof f.seq === 'number' && f.stream_id && f.type !== 'session_stalled' && isDuplicateSeq(prev.checkpoint, f.stream_id, f.seq)) return null;
  return f;
}

const CONTENT_FRAMES = ['text_delta', 'text_complete', 'thinking_delta', 'thinking_complete', 'permission_request'];
const VOICE_CONTENT = [
  'response.output_audio_transcript.delta',
  'response.audio_transcript.delta',
  'response.output_text.delta',
  'response.text.delta',
  'response.output_audio_transcript.done',
  'response.audio_transcript.done',
  'response.output_text.done',
  'response.text.done',
];
const TRANSPORT_ERRORS = ['start_timeout', 'start_failed', 'orchestrator_active', 'invalid_json', 'unknown_type', 'voice_event_failed'];
const KNOWN_EFFECTS = ['send', 'reload', 'reconcile', 'learn_sdk_id', 'turn_ended', 'nested_event', 'watcher', 'protocol_error', 'retry_start'];

/** Is this input "assistant content" in the sense of I-1? */
function isContentInput(prev: Conversation, input: ConversationInput): boolean {
  const t = reducedFrame(prev, input)?.type ?? null;
  if (t && CONTENT_FRAMES.indexOf(t) >= 0) return true;
  if (t === 'tool_use' && input.type === 'frame' && input.frame.type === 'tool_use') {
    return !input.frame.tool_use_id || !tools(prev).some((b) => b.tool_use_id === (input.frame as { tool_use_id: string }).tool_use_id);
  }
  const ev = input.type === 'datachannel_event' ? input.event : t === 'voice_event' && input.type === 'frame' && input.frame.type === 'voice_event' ? input.frame.event : null;
  if (ev && typeof ev.type === 'string' && VOICE_CONTENT.indexOf(ev.type) >= 0) return true;
  return false;
}

/** All invariant violations of one step (empty = fine). */
function checkStep(prev: Conversation, input: ConversationInput, next: Conversation, effects: readonly { type: string }[]): string[] {
  const v: string[] = [];
  const f = reducedFrame(prev, input);
  const t = f?.type ?? null;

  // I-10 no empty runs
  next.entries.forEach((e, i) => {
    if (e.kind === 'assistant' && e.blocks.length === 0) v.push(`I-10 empty run at ${i}`);
  });

  // I-11 no duplicate tool cards / permission blocks; stable unique client ids
  const toolIds = new Set<string>();
  const reqIds = new Set<string>();
  const clientIds = new Set<string>();
  for (const e of next.entries) {
    if (clientIds.has(e.id)) v.push(`duplicate entry id ${e.id}`);
    clientIds.add(e.id);
    if (e.kind !== 'assistant') continue;
    for (const b of e.blocks) {
      if (clientIds.has(b.id)) v.push(`duplicate block id ${b.id}`);
      clientIds.add(b.id);
      if (b.type === 'tool' && b.tool_use_id) {
        if (toolIds.has(b.tool_use_id)) v.push(`I-11 duplicate tool ${b.tool_use_id}`);
        toolIds.add(b.tool_use_id);
      }
      if (b.type === 'permission') {
        if (reqIds.has(b.request_id)) v.push(`I-11 duplicate permission ${b.request_id}`);
        reqIds.add(b.request_id);
      }
    }
  }

  // I-1 / I-2 tail attachment: content never touches an earlier entry; nothing inserted before the tail
  if (isContentInput(prev, input)) {
    const keep = prev.entries.length - 1;
    for (let k = 0; k < keep; k++) {
      const pe = prev.entries[k] as Entry;
      if (pe.id === prev.openVoiceUserId) continue; // finalising the open Gemini transcript
      if (next.entries[k] !== pe) v.push(`I-1 content changed earlier entry ${k}`);
    }
  }

  // R-5 never downgrade, never back to running; R-8 results never create entries
  const prevTools = new Map(tools(prev).map((b) => [b.id, b]));
  for (const b of tools(next)) {
    const p = prevTools.get(b.id);
    if (!p) continue;
    if (p.output && !b.output) v.push(`R-5 output of ${b.tool_use_id} downgraded`);
    if (p.status !== 'running' && b.status === 'running') v.push(`R-5 ${b.tool_use_id} back to running`);
  }
  if (t === 'tool_result' || t === 'tool_executing' || t === 'tool_progress' || t === 'permission_resolved' || input.type === 'history_page' && input.mode === 'reconcile') {
    if (next.entries.length !== prev.entries.length) v.push(`R-8 ${t ?? 'reconcile'} changed the entry count`);
  }

  // R-4 an empty-id live result: inferred onto exactly one card, or unattributed
  if (f && f.type === 'tool_result' && f.tool_use_id === '') {
    const newlyInferred = tools(next).filter((b) => b.inferred && !(prevTools.get(b.id)?.inferred && prevTools.get(b.id)?.output === b.output)).length;
    const grew = next.unattributed.length - prev.unattributed.length;
    if (newlyInferred + grew !== 1) v.push(`R-4 empty-id result: inferred ${newlyInferred}, unattributed +${grew}`);
  }

  // I-7 no stuck running at a turn end / voice end / stop
  const turnEnded = prev.inTurn && !next.inTurn;
  const stopped = t === 'session_terminated' || (t === 'session_stopped' && !prev.expectStopAck); // our own ack is swallowed
  const voiceEnded =
    stopped ||
    t === 'voice_ended' ||
    t === 'voice_stopped' ||
    input.type === 'voice_local_end' ||
    (f !== null && f.type === 'voice_owner_active' && !f.active);
  for (const { b } of blocks(next)) {
    const check = (b.scope === 'turn' && (turnEnded || stopped)) || (b.scope === 'voice' && voiceEnded);
    if (!check) continue;
    if ((b.type === 'text' || b.type === 'thinking') && b.streaming) v.push(`I-7 ${b.scope} block still streaming`);
    if (b.type === 'tool' && b.status === 'running') v.push(`I-7 ${b.scope} tool ${b.tool_use_id} still running`);
    if (b.type === 'permission' && b.state === 'pending' && b.scope === 'turn') v.push('I-7 permission still pending');
  }

  // I-12 a queued prompt goes to the tray, not the timeline
  if (f && f.type === 'user_message' && f.queued === true && next.entries !== prev.entries)
    v.push('I-12 queued user_message touched the timeline');

  // I-14 no focus / navigation anywhere; I-15 transport errors are not content
  for (const e of effects) if (KNOWN_EFFECTS.indexOf(e.type) < 0) v.push(`I-14 unexpected effect ${e.type}`);
  const transportError =
    input.type === 'socket_closed' ||
    input.type === 'socket_open' ||
    input.type === 'reload_failed' && prev.reloadBuffer.length === 0 ||
    (input.type === 'frame' && input.frame.type === 'error' && TRANSPORT_ERRORS.indexOf(input.frame.error) >= 0 && prev.preStart.length === 0);
  if (transportError && next.entries !== prev.entries) v.push(`I-15 ${input.type} changed entries`);

  return v;
}

/**
 * I-3: within each run, live blocks keep arrival order. `ids`: client ids are minted in creation
 * order, so they must increase along a run (nothing inserted mid-run, nothing reordered).
 * `keys`: the generator embeds an increasing number in every content input; valid only for
 * streams without re-applied duplicates (no stream changes / checkpoint resets).
 */
function arrivalViolations(c: Conversation, by: 'ids' | 'keys'): string[] {
  const v: string[] = [];
  c.entries.forEach((e, i) => {
    if (e.kind !== 'assistant') return;
    let lastKey = -1;
    for (const b of e.blocks) {
      if (b.origin !== 'live') continue;
      const src = by === 'ids' ? b.id : b.type === 'tool' ? b.tool_use_id : b.type === 'permission' ? b.request_id : b.text;
      const m = /(\d+)/.exec(src);
      if (!m) continue;
      const key = Number(m[1]);
      if (key <= lastKey) v.push(`I-3 run ${i}: ${key} after ${lastKey}`);
      lastKey = key;
    }
  });
  return v;
}

function run(start: Conversation, inputs: readonly ConversationInput[], label: string): Conversation {
  let conv = deepFreeze(start);
  inputs.forEach((input, k) => {
    const r = stepConversation(conv, input);
    const v = checkStep(conv, input, r.state, r.effects);
    if (v.length) throw new Error(`${label} step ${k} (${JSON.stringify(input).slice(0, 160)}): ${v.join('; ')}`);
    conv = deepFreeze(r.state);
  });
  return conv;
}

// ───────────────────────────── generators ─────────────────────────────

interface GenState {
  n: number;
  seq: number;
  stream: string;
  tools: string[];
  perms: string[];
  sent: { input: ConversationInput; seqd: boolean }[];
}

function stamp(g: GenState, f: Record<string, unknown>, seqd: boolean): ConversationInput {
  if (seqd) {
    g.seq += 1;
    f.seq = g.seq;
    f.stream_id = g.stream;
  }
  const input = frameInput(f);
  g.sent.push({ input, seqd });
  return input;
}

interface GenOptions {
  /** Re-send earlier seq-stamped frames (replay overlap). */
  dupes?: boolean;
  /** session_stopped/terminated, session_started, new streams (they reset or re-seed the checkpoint). */
  resets?: boolean;
  /** socket_open / socket_closed (frames are held until session_started). */
  socket?: boolean;
}

const ALL: GenOptions = { dupes: true, resets: true, socket: true };

/** A plausible (not necessarily well-formed) Claude agent stream with local actions. */
function genAgent(r: Rng, len: number, opts: GenOptions = ALL): ConversationInput[] {
  const g: GenState = { n: 0, seq: 0, stream: 'L1:1', tools: [], perms: [], sent: [] };
  const out: ConversationInput[] = [];
  const k = () => ++g.n;
  for (let s = 0; s < len; s++) {
    let roll = r.int(30);
    if ((!opts.dupes && roll === 21) || (!opts.resets && (roll === 23 || roll === 26 || roll === 27)) || (!opts.socket && (roll === 25 || roll === 26)))
      roll = 2;
    let x: ConversationInput;
    switch (roll) {
      case 0:
        x = { type: 'local_send', text: `p${k()}` };
        break;
      case 1:
        x = stamp(g, { type: 'status', status: 'processing' }, false);
        break;
      case 2:
      case 3:
      case 4:
        x = stamp(g, { type: 'text_delta', text: `${k()};` }, true);
        break;
      case 5:
        x = stamp(g, { type: 'text_complete', text: `${k()};` }, true);
        break;
      case 6:
        x = stamp(g, { type: r.chance(0.5) ? 'thinking_delta' : 'thinking_complete', text: `${k()};` }, true);
        break;
      case 7:
      case 8: {
        const id = r.chance(0.15) && g.tools.length ? r.pick(g.tools) : `t${k()}`;
        g.tools.push(id);
        x = stamp(g, { type: 'tool_use', tool_use_id: id, tool_name: 'Bash', tool_input: { command: id } }, true);
        break;
      }
      case 9:
      case 10:
      case 11: {
        const pool = g.tools.length ? g.tools : ['tX'];
        const id = r.chance(0.15) ? '' : r.chance(0.15) ? `orphan${k()}` : r.pick(pool);
        x = stamp(g, { type: 'tool_result', tool_use_id: id, output: r.chance(0.3) ? '' : `out${k()}`, is_error: r.chance(0.1) }, true);
        break;
      }
      case 12: {
        const rid = r.chance(0.2) && g.perms.length ? r.pick(g.perms) : `r${k()}`;
        g.perms.push(rid);
        x = stamp(g, { type: 'permission_request', request_id: rid, tool_name: 'ExitPlanMode', tool_input: { plan: 'x' } }, true);
        break;
      }
      case 13:
        x = stamp(
          g,
          { type: 'permission_resolved', request_id: g.perms.length && r.chance(0.8) ? r.pick(g.perms) : 'rZ', decision: r.pick(['allow', 'deny']), responder: 'user', message: null },
          true,
        );
        break;
      case 14:
      case 15:
        x = stamp(g, { type: 'turn_complete', cost: 0.01, input_tokens: 100, num_turns: 1, session_id: 'sdk-1', is_error: r.chance(0.1), result: 'boom' }, true);
        break;
      case 16:
        x = stamp(g, { type: 'user_message', text: `p${k()}`, ...(r.chance(0.4) ? { queued: true } : {}) }, false);
        break;
      case 17:
        x = stamp(g, { type: 'status', status: r.pick(['interrupted', 'retrying', 'weird']) }, false);
        break;
      case 18:
        x = stamp(g, { type: 'compact_complete', trigger: 'auto', summary: `s${k()}` }, r.chance(0.5));
        break;
      case 19:
        x = stamp(g, { type: 'session_stalled', elapsed_seconds: 120, last_tool_name: 'Bash', last_tool_use_id: null }, false);
        break;
      case 20:
        x = stamp(g, { type: 'error', error: r.pick(['send_failed', 'upstream_wedged', 'start_failed', 'invalid_json']), detail: 'd' }, false);
        break;
      case 21: {
        const pick = g.sent.filter((e) => e.seqd);
        x = pick.length ? r.pick(pick).input : { type: 'local_interrupt' }; // a duplicate (replay) frame
        break;
      }
      case 22:
        x = r.pick<ConversationInput>([{ type: 'local_interrupt' }, { type: 'local_compact' }, { type: 'local_stop' }]);
        break;
      case 23:
        x = stamp(g, { type: r.pick(['session_stopped', 'session_terminated']), reason: 'subprocess_crashed', detail: null, sdk_session_id: 'sdk-1' }, false);
        break;
      case 24:
        x = stamp(g, { type: 'tool_executing', tool_use_id: g.tools.length ? r.pick(g.tools) : 'tY' }, false);
        break;
      case 25:
        x = r.pick<ConversationInput>([{ type: 'socket_closed' }, { type: 'socket_open' }, { type: 'dismiss_banner' }]);
        break;
      case 26:
        x = stamp(g, { type: 'session_started', session_id: 'L1', context_window: 200000, resume_state: { stream_id: g.stream, next_seq: g.seq + 1 } }, false);
        break;
      case 27:
        g.stream = `L1:${k()}`; // the CLI reconnected: a new stream (SEQ-4)
        x = stamp(g, { type: 'text_delta', text: `${k()};` }, true);
        break;
      default:
        x = stamp(g, { type: 'tool_progress', tool_use_id: g.tools.length ? r.pick(g.tools) : 'tY', elapsed_seconds: 5, message: 'm' }, false);
    }
    out.push(x);
  }
  return out;
}

/** A plausible orchestrator stream: text turns, voice events, tools, injects. */
function genOrchestrator(r: Rng, len: number): ConversationInput[] {
  const g: GenState = { n: 0, seq: 0, stream: '', tools: [], perms: [], sent: [] };
  const out: ConversationInput[] = [];
  const k = () => ++g.n;
  const ve = (event: Record<string, unknown>) => (r.chance(0.5) ? stamp(g, { type: 'voice_event', event }, false) : ({ type: 'datachannel_event', event } as ConversationInput));
  for (let s = 0; s < len; s++) {
    const roll = r.int(26);
    let x: ConversationInput;
    switch (roll) {
      case 0:
        x = { type: 'local_send', text: `p${k()}` };
        break;
      case 1:
        x = stamp(g, { type: 'status', status: 'streaming' }, false);
        break;
      case 2:
        x = stamp(g, { type: 'status', status: 'idle' }, false);
        break;
      case 3:
      case 4:
        x = stamp(g, { type: r.chance(0.7) ? 'text_delta' : 'text_complete', text: `${k()};` }, false);
        break;
      case 5:
      case 6: {
        const id = r.chance(0.1) && g.tools.length ? r.pick(g.tools) : `c${k()}`;
        g.tools.push(id);
        x = stamp(g, { type: 'tool_use', tool_use_id: id, tool_name: 'search_memory', tool_input: { q: id } }, false);
        break;
      }
      case 7:
      case 8: {
        const id = r.chance(0.2) ? `early${k()}` : g.tools.length ? r.pick(g.tools) : 'cX';
        x = stamp(g, { type: 'tool_result', tool_use_id: id, output: r.chance(0.3) ? { obj: k() } : `o${k()}`, is_error: false }, false);
        break;
      }
      case 9:
        x = ve({ type: 'input_audio_buffer.speech_started' });
        break;
      case 10:
        x = ve({ type: 'conversation.item.input_audio_transcription.completed', transcript: `u${k()}` });
        break;
      case 11:
      case 12:
        x = ve({ type: r.pick(['response.audio_transcript.delta', 'response.output_text.delta']), delta: `${k()};` });
        break;
      case 13:
        x = ve({ type: 'response.audio_transcript.done', transcript: `${k()};` });
        break;
      case 14:
        x = ve({ type: 'response.done' });
        break;
      case 15:
        x = stamp(g, { type: 'voice_event', event: { serverContent: { inputTranscription: { text: `g${k()} ` } } } }, false);
        break;
      case 16:
        x = stamp(g, { type: 'voice_event', event: { serverContent: { outputTranscription: { text: `${k()};` }, turnComplete: r.chance(0.3) } } }, false);
        break;
      case 17:
        x = stamp(g, { type: r.pick(['voice_ended', 'voice_stopped']) }, false);
        break;
      case 18:
        x = stamp(g, { type: 'voice_owner_active', active: r.chance(0.5) }, false);
        break;
      case 19:
        x = r.chance(0.5) ? { type: 'local_inject', text: `[shared text]\n${k()}` } : stamp(g, { type: 'user_message', text: `[shared text]\n${k()}`, source: 'shared_inject' }, false);
        break;
      case 20:
        x = stamp(g, { type: 'error', error: r.pick(['api_error', 'send_failed', 'interrupted', 'invalid_audio', 'voice_event_failed']), detail: 'e' }, false);
        break;
      case 21:
        x = stamp(g, { type: 'turn_complete', input_tokens: 500, output_tokens: 20 }, false);
        break;
      case 22:
        x = r.pick<ConversationInput>([{ type: 'local_send_audio' }, { type: 'voice_local_end' }, { type: 'local_compact' }]);
        break;
      case 23:
        x = stamp(g, { type: 'user_message', text: `p${k()}` }, false);
        break;
      case 24:
        x = stamp(g, { type: 'tool_executing', tool_use_id: g.tools.length ? r.pick(g.tools) : 'cY', tool_name: 'x' }, false);
        break;
      default:
        x = stamp(g, { type: 'status', status: 'interrupted' }, false);
    }
    out.push(x);
  }
  return out;
}

function agentStart(): Conversation {
  return initialConversation({ localId: 'L1', kind: 'agent', sdkId: 'sdk-1', provider: 'claude', subscribed: true });
}

function orchStart(voiceActive: boolean): Conversation {
  return initialConversation({ localId: 'O1', kind: 'orchestrator', sdkId: 'O1', voiceActive, subscribed: true });
}

const SEEDS = Array.from({ length: 60 }, (_, i) => 1000 + i * 7919);

// ───────────────────────────── tests ─────────────────────────────

describe('invariants on random agent streams', () => {
  it.each(SEEDS)('seed %i: I-1 I-2 I-3 I-7 I-10 I-11 I-12 I-14 I-15, R-4 R-5 R-8 hold at every step', (seed) => {
    const inputs = genAgent(rng(seed), 220);
    const end = run(agentStart(), inputs, `agent seed ${seed}`);
    expect(arrivalViolations(end, 'ids')).toEqual([]);
  });

  it.each(SEEDS)('seed %i: I-3 blocks of a run are in input order (no duplicates, resets or socket churn)', (seed) => {
    const inputs = genAgent(rng(seed), 220, {});
    expect(arrivalViolations(run(agentStart(), inputs, `agent-order seed ${seed}`), 'keys')).toEqual([]);
  });
});

describe('invariants on random orchestrator streams', () => {
  it.each(SEEDS)('seed %i: invariants hold at every step (text + voice)', (seed) => {
    const r = rng(seed);
    const inputs = genOrchestrator(r, 220);
    const end = run(orchStart(r.chance(0.5)), inputs, `orchestrator seed ${seed}`);
    expect(arrivalViolations(end, 'ids')).toEqual([]);
    expect(arrivalViolations(end, 'keys')).toEqual([]);
  });
});

describe('I-6 RESULT-DELIVERY: a tool result is never lost', () => {
  it.each(SEEDS.slice(0, 30))('seed %i: every id-matched result ends on its card or in orphanResults', (seed) => {
    const r = rng(seed);
    const agent = r.chance(0.5);
    const inputs = agent ? genAgent(r, 250, { dupes: true, resets: true, socket: false }) : genOrchestrator(r, 250);
    const end = run(agent ? agentStart() : orchStart(true), inputs, `R seed ${seed}`);
    const delivered = new Map<string, boolean>(); // id → some non-empty output was delivered
    for (const x of inputs) {
      if (x.type !== 'frame' || x.frame.type !== 'tool_result' || !x.frame.tool_use_id) continue;
      const out = x.frame.output;
      delivered.set(x.frame.tool_use_id, delivered.get(x.frame.tool_use_id) === true || !(out === '' || out === null));
    }
    for (const [id, nonEmpty] of delivered) {
      const card = tools(end).find((b) => b.tool_use_id === id);
      const orphan = end.orphanResults.find((o) => o.tool_use_id === id);
      expect(card || orphan, `result ${id} lost`).toBeTruthy();
      if (card) expect(card.status === 'done' || card.status === 'error' || card.inferred, `card ${id} status ${card.status}`).toBeTruthy();
      const output = card ? card.output : orphan?.output;
      if (nonEmpty) expect(output, `result ${id} output lost`).toBeTruthy();
    }
  });
});

describe('I-8 seq idempotence', () => {
  it.each(SEEDS.slice(0, 30))('seed %i: re-delivering seq-stamped frames later changes nothing', (seed) => {
    const r = rng(seed);
    // no checkpoint resets / new streams: those legitimately re-apply old frames (SEQ-4, terminate)
    const inputs = genAgent(r, 150, { dupes: false, resets: false, socket: false });
    const scheduled = new Map<number, ConversationInput[]>();
    inputs.forEach((x, i) => {
      if (x.type === 'frame' && typeof x.frame.seq === 'number' && x.frame.type !== 'session_stalled' && r.chance(0.4)) {
        const at = i + 1 + r.int(4);
        scheduled.set(at, (scheduled.get(at) ?? []).concat([x]));
      }
    });
    const withDupes: ConversationInput[] = [];
    for (let i = 0; i <= inputs.length; i++) {
      withDupes.push(...(scheduled.get(i) ?? []));
      if (i < inputs.length) withDupes.push(inputs[i] as ConversationInput);
    }
    for (const [at, xs] of scheduled) if (at > inputs.length) withDupes.push(...xs);
    expect(withDupes.length).toBeGreaterThan(inputs.length);
    expect(run(agentStart(), withDupes, 'twice')).toStrictEqual(run(agentStart(), inputs, 'once'));
  });
});

describe('I-13 determinism and structural sharing', () => {
  it.each(SEEDS.slice(0, 20))('seed %i: same inputs ⇒ same state (deep), and unchanged entries keep identity', (seed) => {
    const a = genOrchestrator(rng(seed), 200);
    const s1 = run(orchStart(true), a, 'a');
    const s2 = run(orchStart(true), a, 'b');
    expect(s1).toStrictEqual(s2);
    expect(JSON.parse(JSON.stringify(s1))).toStrictEqual(s1); // plain serializable data
  });

  it('a step that changes nothing returns the same object', () => {
    const c = agentStart();
    expect(stepConversation(c, frameInput({ type: 'ping' })).state).toBe(c);
    expect(stepConversation(c, frameInput({ type: 'status', status: 'nonsense' })).state).toBe(c);
  });
});

describe('random interleavings of every fixture (spec 12 §4.1)', () => {
  const files = loadFixtures();
  it.each(files.map((f) => [f.fixture.name, f.fixture] as const))('%s: 25 shuffles keep the invariants', (name, fx) => {
    const base = fixtureInputs(fx);
    const r = rng(name.length * 131 + base.length);
    for (let n = 0; n < 25; n++) {
      const shuffled = base.slice();
      for (let i = shuffled.length - 1; i > 0; i--) {
        const j = r.int(i + 1);
        const t = shuffled[i] as ConversationInput;
        shuffled[i] = shuffled[j] as ConversationInput;
        shuffled[j] = t;
      }
      run(fixtureStart(fx).conv, shuffled, `${name} shuffle ${n}`);
    }
  });
});

// ───────────────────────────── I-12 queue simulator ─────────────────────────────

/**
 * Drives one agent session the way api/pool.py does: prompts from this client ("sender") and
 * from another client ("observer" view), FIFO queue behind the running turn, `user_message`
 * echo excluding the sender, `queued:true` announcements, and the O-6 switch (no second echo).
 * Returns the view's input list and the prompts in dispatch order.
 */
function simulateQueue(r: Rng, prompts: number, o6: boolean): { inputs: ConversationInput[]; dispatched: string[] } {
  const inputs: ConversationInput[] = [];
  const queue: { text: string; mine: boolean; announced: boolean }[] = [];
  const dispatched: string[] = [];
  let running = false;
  let seq = 0;
  let made = 0;
  const frame = (f: Record<string, unknown>) => inputs.push(frameInput(f));
  const dispatch = (p: { text: string; mine: boolean; announced: boolean }) => {
    if (!p.mine && !(o6 && p.announced)) frame({ type: 'user_message', text: p.text });
    frame({ type: 'status', status: 'processing' });
    dispatched.push(p.text);
    running = true;
  };
  while (made < prompts || queue.length || running) {
    const canPrompt = made < prompts;
    if (canPrompt && (!running || r.chance(0.5))) {
      made += 1;
      const p = { text: `prompt ${made}`, mine: r.chance(0.5), announced: false };
      if (p.mine) inputs.push({ type: 'local_send', text: p.text });
      if (!running) dispatch(p);
      else {
        if (!p.mine) frame({ type: 'user_message', text: p.text, queued: true });
        p.announced = true;
        queue.push(p);
      }
      continue;
    }
    if (running) {
      seq += 1;
      frame({ type: 'text_complete', text: `answer ${seq}`, seq, stream_id: 'L1:1' });
      seq += 1;
      frame({ type: 'turn_complete', cost: 0, input_tokens: 10, num_turns: 1, session_id: 'sdk-1', is_error: false, result: '', seq, stream_id: 'L1:1' });
      running = false;
      const next = queue.shift();
      if (next) dispatch(next);
    }
  }
  return { inputs, dispatched };
}

describe('I-12 queue (backend-faithful simulation)', () => {
  for (const o6 of [true, false]) {
    it.each(SEEDS.slice(0, 25))(`seed %i, ${o6 ? 'O-6' : 'pre-O-6'} backend: every prompt enters the timeline exactly once, in dispatch order`, (seed) => {
      const { inputs, dispatched } = simulateQueue(rng(seed), 8, o6);
      const end = run(agentStart(), inputs, `queue seed ${seed}`);
      const users = end.entries.filter((e): e is Extract<Entry, { kind: 'user' }> => e.kind === 'user').map((e) => e.text);
      expect(users).toEqual(dispatched);
      expect(end.queue).toEqual([]);
      // each prompt is directly followed by its own answer run
      end.entries.forEach((e, i) => {
        if (e.kind === 'user') expect(end.entries[i + 1]?.kind).toBe('assistant');
      });
    });
  }
});
