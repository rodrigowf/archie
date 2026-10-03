/** Reducer rules (spec 12 §4, §5) beyond what the fixtures pin down. */
import { describe, expect, it } from 'vitest';
import type { ConversationInput, MessagesPage, ToolBlock } from '../index';
import { stepConversation } from '../index';
import { agent, asstText, feed, runOf, orch, page, texts, toolResultLine, toolUseLine, userLine } from './util';

const proc = { type: 'status', status: 'processing' };
const tc = { type: 'turn_complete', session_id: 'sdk-1', is_error: false, num_turns: 1 };

describe('streaming text (I-4, I-5)', () => {
  it('a delta after a different block opens a new block; a complete with no deltas pushes a finished block', () => {
    const c = feed(agent(), proc, { type: 'thinking_delta', text: 'h' }, { type: 'text_delta', text: 'a' }, { type: 'text_delta', text: 'b' }).conv;
    expect(texts(c)).toEqual(['A[thinking:h|text:ab]']);
    const b = c.entries[0];
    expect(b?.kind === 'assistant' && b.blocks[0]).toMatchObject({ streaming: false, implicitlyClosed: true });
    expect(c.status).toBe('streaming');
  });

  it('I-5: a block split by an interleaved entry continues below it without repeating the shown prefix', () => {
    let c = feed(orch({ voiceActive: true }), { type: 'local_send', text: 'q' }, { type: 'status', status: 'streaming' }, { type: 'text_delta', text: 'Hello ' }).conv;
    c = feed(c, { type: 'user_message', text: '[shared text]\nx', source: 'shared_inject' }).conv;
    c = feed(c, { type: 'text_delta', text: 'world' }, { type: 'text_complete', text: 'Hello world' }).conv;
    expect(texts(c)).toEqual(['U(local):q', 'A[text:Hello ]', 'U(inject):[shared text]\nx', 'A[text:world]']);
  });

  it('I-5: a complete for a split block with no continuation deltas adds only the remainder (or nothing)', () => {
    const base = feed(orch(), { type: 'local_send', text: 'q' }, { type: 'status', status: 'streaming' }, { type: 'text_delta', text: 'Part one' }, { type: 'user_message', text: 'echo' }).conv;
    expect(texts(feed(base, { type: 'text_complete', text: 'Part one and two' }).conv)).toEqual(['U(local):q', 'A[text:Part one]', 'U(echo):echo', 'A[text: and two]']);
    expect(texts(feed(base, { type: 'text_complete', text: 'Part one' }).conv)).toEqual(['U(local):q', 'A[text:Part one]', 'U(echo):echo']);
  });

  it('a pending split is dropped when a block of another type is pushed', () => {
    const c = feed(orch(), { type: 'status', status: 'streaming' }, { type: 'text_delta', text: 'A' }, { type: 'user_message', text: 'u' }).conv;
    expect(c.pendingSplit).not.toBeNull();
    const after = feed(c, { type: 'tool_use', tool_use_id: 't', tool_name: 'x', tool_input: {} }).conv;
    expect(after.pendingSplit).toBeNull();
  });

  it('§5.4: a complete equal to an implicitly closed block of the tail run is a duplicate', () => {
    const c = feed(agent(), proc, { type: 'text_delta', text: 'Same' }, { type: 'tool_use', tool_use_id: 't', tool_name: 'x', tool_input: {} }, { type: 'text_complete', text: 'Same' }).conv;
    expect(texts(c)).toEqual(['A[text:Same|tool:t:running]']);
  });

  it('§5.4: dedupe only looks at the tail run', () => {
    const c = feed(agent(), page([asstText('Hi'), userLine('next')]), proc, { type: 'text_complete', text: 'Hi' }).conv;
    expect(texts(c)).toEqual(['A[text:Hi]', 'U(history):next', 'A[text:Hi]']);
  });
});

describe('tools (R-1 … R-6, TC)', () => {
  it('tool_executing / tool_progress update the card by id and never create one (R-3)', () => {
    let c = feed(orch(), { type: 'status', status: 'streaming' }, { type: 'text_complete', text: 'bg' }, { type: 'tool_executing', tool_use_id: 'zz' }, { type: 'tool_progress', tool_use_id: 'zz', elapsed_seconds: 1, message: 'm' }).conv;
    expect(texts(c)).toEqual(['N(background):', 'A[text:bg]']); // BG-1; executing/progress created nothing
    c = feed(c, { type: 'tool_use', tool_use_id: 'c1', tool_name: 'x', tool_input: {} }, { type: 'tool_executing', tool_use_id: 'c1', tool_name: 'x' }, { type: 'tool_progress', tool_use_id: 'c1', elapsed_seconds: 5, message: 'Still' }).conv;
    const tb = runOf(c.entries[1]).blocks[1] as ToolBlock;
    expect(tb.executing).toBe(true);
    expect(tb.progress).toEqual({ elapsed_seconds: 5, message: 'Still' });
    c = feed(c, { type: 'tool_result', tool_use_id: 'c1', output: 'ok', is_error: false }, { type: 'tool_progress', tool_use_id: 'c1', elapsed_seconds: 9, message: 'late' }).conv;
    const done = runOf(c.entries[1]).blocks[1] as ToolBlock;
    expect(done).toMatchObject({ status: 'done', executing: false, progress: { elapsed_seconds: 5 } });
  });

  it('R-5: an empty result never replaces output; a reconcile overrides inferred but not id-matched output', () => {
    let c = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: 'B', tool_input: {} }, { type: 'tool_result', tool_use_id: 'a', output: 'real' }, { type: 'tool_result', tool_use_id: 'a', output: '' }).conv;
    expect(texts(c)).toEqual(['A[tool:a:done]']);
    expect((runOf(c.entries[0]).blocks[0] as ToolBlock).output).toBe('real');
    c = feed(c, { type: 'history_page', mode: 'reconcile', response: { messages: [toolResultLine('a', 'rest')], start_index: 0, total_count: 1, has_more: false } }).conv;
    expect((runOf(c.entries[0]).blocks[0] as ToolBlock).output).toBe('real');
    let i = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'b', tool_name: 'B', tool_input: {} }, { type: 'tool_result', tool_use_id: '', output: 'guess' }).conv;
    i = feed(i, { type: 'history_page', mode: 'reconcile', response: { messages: [toolResultLine('b', 'truth', true), toolResultLine('', 'x')], start_index: 0, total_count: 1, has_more: false } }).conv;
    expect(runOf(i.entries[0]).blocks[0]).toMatchObject({ output: 'truth', status: 'error', inferred: false });
  });

  it('R-2: a newer orphan result replaces an older one unless it is empty', () => {
    const c = feed(orch(), { type: 'tool_result', tool_use_id: 'x', output: '' }, { type: 'tool_result', tool_use_id: 'x', output: 'one' }, { type: 'tool_result', tool_use_id: 'x', output: '' }).conv;
    expect(c.orphanResults).toEqual([{ tool_use_id: 'x', output: 'one', is_error: false, origin: 'live' }]);
  });

  it('R-7: reconcile is requested after a turn that ended with no_result, inferred, unattributed or live orphans', () => {
    const noRes = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: 'B', tool_input: {} }, tc);
    expect(noRes.effects.map((e) => e.type)).toEqual(['turn_ended', 'reconcile']);
    const clean = feed(agent(), proc, { type: 'text_complete', text: 'ok' }, tc);
    expect(clean.effects.map((e) => e.type)).toEqual(['turn_ended']);
    const orphan = feed(agent(), proc, { type: 'tool_result', tool_use_id: 'ghost', output: 'x' }, tc);
    expect(orphan.effects.map((e) => e.type)).toContain('reconcile');
    const noSdk = feed(agent({ sdkId: null }), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: 'B', tool_input: {} }, { ...tc, session_id: null });
    expect(noSdk.effects.map((e) => e.type)).not.toContain('reconcile');
  });

  it('a repeated tool_use fills a missing name and a richer input without a second card (I-11)', () => {
    const c = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: '', tool_input: {} }, { type: 'tool_use', tool_use_id: 'a', tool_name: 'Read', tool_input: { file_path: 'x' } }).conv;
    expect(runOf(c.entries[0]).blocks).toHaveLength(1);
    expect(runOf(c.entries[0]).blocks[0]).toMatchObject({ tool_name: 'Read', tool_input: { file_path: 'x' } });
    const same = stepConversation(c, { type: 'frame', frame: { type: 'tool_use', tool_use_id: 'a', tool_name: 'Read', tool_input: { file_path: 'x' } } });
    expect(same.state.entries).toBe(c.entries);
  });
});

describe('permissions (PM-1 … PM-4)', () => {
  it('a pending permission is expired as denied/system at the turn end (PM-4) and a duplicate request is ignored', () => {
    const c = feed(agent(), proc, { type: 'permission_request', request_id: 'r', tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } }, { type: 'permission_request', request_id: 'r', tool_name: 'ExitPlanMode', tool_input: {} }, tc).conv;
    expect(runOf(c.entries[0]).blocks).toEqual([
      expect.objectContaining({ type: 'permission', state: 'denied', responder: 'system', message: 'stream ended' }),
    ]);
  });

  it('nested_session_event: routed to the agent view, and listed as an orchestrator approval until resolved (§6.9)', () => {
    const req = { type: 'permission_request', request_id: 'r9', tool_name: 'ExitPlanMode', tool_input: { plan: 'x' }, seq: 3, stream_id: 'L5:1' };
    let r = feed(orch(), { type: 'nested_session_event', session_id: 'L5', event_type: 'permission_request', event_data: req });
    expect(r.effects).toEqual([{ type: 'nested_event', localId: 'L5', event: req }]);
    expect(r.conv.agentApprovals).toEqual([{ localId: 'L5', request_id: 'r9', tool_name: 'ExitPlanMode', tool_input: { plan: 'x' } }]);
    expect(r.conv.entries).toEqual([]);
    r = feed(r.conv, { type: 'nested_session_event', session_id: 'L5', event_type: 'permission_request', event_data: req });
    expect(r.conv.agentApprovals).toHaveLength(1);
    const resolved = feed(r.conv, { type: 'nested_session_event', session_id: 'L5', event_type: 'permission_resolved', event_data: { type: 'permission_resolved', request_id: 'r9', decision: 'allow' } });
    expect(resolved.conv.agentApprovals).toEqual([]);
    expect(feed(r.conv, { type: 'clear_agent_approvals', localId: 'L5' }).conv.agentApprovals).toEqual([]);
    expect(feed(r.conv, { type: 'clear_agent_approvals', localId: 'nope' }).conv).toBe(r.conv);
    const junk = feed(orch(), { type: 'nested_session_event', session_id: 'L5', event_type: 'x', event_data: { no: 'type' } });
    expect(junk.effects).toEqual([]);
    const onAgent = feed(agent(), { type: 'nested_session_event', session_id: 'L5', event_type: 'permission_request', event_data: req });
    expect(onAgent.conv.agentApprovals).toEqual([]);
  });
});

describe('turns, errors, compaction (TL-*, §4.4.4)', () => {
  it('agent turn failures add an error notice and end the turn; an is_error turn_complete shows its result', () => {
    const c = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: 'B', tool_input: {} }, { type: 'error', error: 'upstream_wedged', detail: 'Upstream did not respond' }).conv;
    expect(texts(c)).toEqual(['A[tool:a:no_result]', 'N(error):Upstream did not respond']);
    expect(c.status).toBe('idle');
    const e = feed(agent(), proc, { ...tc, is_error: true, result: 'Prompt is too long' }).conv;
    expect(texts(e)).toEqual(['N(error):Prompt is too long']);
    expect(feed(agent(), proc, { type: 'error', error: 'command_failed' }).conv.entries).toMatchObject([{ notice: 'error', text: 'command_failed' }]);
  });

  it('orchestrator: send_failed ends the turn itself (no idle follows); invalid_audio releases a pending local turn', () => {
    let c = feed(orch(), { type: 'local_send', text: 'hi' }, { type: 'status', status: 'streaming' }, { type: 'error', error: 'send_failed', detail: 'x' }).conv;
    expect(c.inTurn).toBe(false);
    c = feed(orch(), { type: 'local_send_audio' }, { type: 'error', error: 'invalid_audio', detail: 'bad' }).conv;
    expect(c.localTurnsPending).toBe(0);
    expect(texts(c)).toEqual(['U(audio):', 'N(error):bad']);
  });

  it('orchestrator interrupt: status and error interrupted give one notice; nested streaming depth (TL-4)', () => {
    let c = feed(orch(), { type: 'local_send', text: 'a' }, { type: 'status', status: 'streaming' }, { type: 'status', status: 'streaming' }).conv;
    expect(c.turnDepth).toBe(2);
    c = feed(c, { type: 'text_complete', text: 'x' }, { type: 'status', status: 'interrupted' }, { type: 'error', error: 'interrupted' }, { type: 'status', status: 'idle' }).conv;
    expect(c.inTurn).toBe(true);
    c = feed(c, { type: 'status', status: 'idle' }, { type: 'status', status: 'idle' }).conv;
    expect(c.inTurn).toBe(false);
    expect(c.turnDepth).toBe(0);
    expect(texts(c)).toEqual(['U(local):a', 'A[text:x]', 'N(interrupted):']);
    expect(feed(orch(), { type: 'error', error: 'interrupted' }, { type: 'status', status: 'interrupted' }).conv.entries).toEqual([]);
  });

  it('BG-1: no background notice after a visible prompt (echo, inject, transcript); one after a notice or a run', () => {
    const reply = [{ type: 'status', status: 'streaming' }, { type: 'text_complete', text: 'r' }];
    expect(texts(feed(orch(), { type: 'user_message', text: 'typed elsewhere' }, ...reply).conv)).toEqual(['U(echo):typed elsewhere', 'A[text:r]']);
    expect(texts(feed(orch({ voiceActive: true }), { type: 'user_message', text: 'f', source: 'shared_inject' }, ...reply).conv)).toEqual(['U(inject):f', 'A[text:r]']);
    expect(texts(feed(orch(), page([asstText('old')]), ...reply).conv)).toEqual(['A[text:old]', 'N(background):', 'A[text:r]']);
  });

  it('a second local send while a turn nests marks the turn local; connecting maps to connecting', () => {
    const c = feed(orch(), { type: 'status', status: 'streaming' }, { type: 'local_send', text: 'b' }, { type: 'status', status: 'streaming' }).conv;
    expect(c.turnIsLocal).toBe(true);
    expect(feed(orch(), { type: 'status', status: 'connecting' }).conv.status).toBe('connecting');
    expect(feed(agent(), { type: 'status', status: 'connecting' }).conv.status).toBe('connecting');
  });

  it('agent retrying is a distinct status (ST-1) and unknown statuses change nothing', () => {
    const c = feed(agent(), proc, { type: 'status', status: 'retrying', detail: 'upstream silent' }).conv;
    expect(c.status).toBe('retrying');
    expect(feed(c, { type: 'status', status: 'mystery' }).conv).toBe(c);
    expect(feed(agent(), { type: 'status', status: 'retrying' }).conv.status).toBe('idle');
  });

  it('orchestrator manual compaction: notice with token data, context tokens updated, idle after', () => {
    const c = feed(orch(), { type: 'local_compact' }, { type: 'status', status: 'streaming' }, { type: 'compact_complete', trigger: 'manual', tokens_before: 9000, tokens_after: 1200 }, { type: 'status', status: 'idle' }).conv;
    expect(c.entries).toMatchObject([{ kind: 'notice', notice: 'compaction', text: '', data: { trigger: 'manual', tokens_before: 9000, tokens_after: 1200 } }]);
    expect(c.counters.contextTokens).toBe(1200);
    expect(c.status).toBe('idle');
    expect(c.compactPending).toBe(false);
  });

  it('agent compact: compacting until compact_complete; compact_failed ends it with an error notice', () => {
    let c = feed(agent(), { type: 'local_compact' }).conv;
    expect(c.status).toBe('compacting');
    c = feed(c, { type: 'error', error: 'compact_failed', detail: 'nope' }).conv;
    expect(c.status).toBe('idle');
    expect(c.compactPending).toBe(false);
    const t = feed(agent(), { type: 'local_compact' }, tc).conv;
    expect(t.status).toBe('compacting'); // turn_complete first: compact_complete still pending
  });

  it('a stall is only shown while busy; content clears it', () => {
    expect(feed(agent(), { type: 'session_stalled', elapsed_seconds: 130 }).conv.stall).toBeNull();
    const c = feed(agent(), proc, { type: 'session_stalled', elapsed_seconds: 130, last_tool_name: 'Bash', last_tool_use_id: 't' }, { type: 'text_delta', text: 'x' }).conv;
    expect(c.stall).toBeNull();
  });

  it('local_send while busy queues (I-12); interrupt clears only local tray items', () => {
    let c = feed(agent(), proc, { type: 'local_send', text: 'mine' }, { type: 'user_message', text: 'theirs', queued: true }).conv;
    expect(c.queue).toEqual([
      { text: 'mine', owner: 'local' },
      { text: 'theirs', owner: 'remote' },
    ]);
    expect(c.entries).toEqual([]);
    c = feed(c, { type: 'local_interrupt' }).conv;
    expect(c.queue).toEqual([{ text: 'theirs', owner: 'remote' }]);
    expect(feed(orch(), { type: 'local_interrupt' }).conv.queue).toEqual([]);
  });

  it('I-12: a pre-O-6 re-echo of the dispatched prompt during its own turn is swallowed, not a second entry', () => {
    let c = feed(agent(), proc, { type: 'user_message', text: 'next', queued: true }, tc, proc).conv;
    expect(texts(c)).toEqual(['U(echo):next']);
    expect(c.dispatchedFromTray).toEqual(['next']);
    c = feed(c, { type: 'user_message', text: 'next' }, { type: 'text_complete', text: 'answer' }).conv;
    expect(texts(c)).toEqual(['U(echo):next', 'A[text:answer]']);
    expect(c.inTurn).toBe(true);
    expect(feed(c, tc).conv.dispatchedFromTray).toEqual([]);
  });

  it('observer of an interrupted turn: the next user_message ends it (TL-2, superseded)', () => {
    const c = feed(agent(), proc, { type: 'tool_use', tool_use_id: 'a', tool_name: 'B', tool_input: {} }, { type: 'user_message', text: 'again' }).conv;
    expect(texts(c)).toEqual(['A[tool:a:no_result]', 'U(echo):again']);
  });

  it('our own stop is swallowed (local_stop); a server stop marks the view stopped but keeps it', () => {
    const own = feed(agent(), { type: 'local_stop' }, { type: 'session_stopped' }).conv;
    expect(own.status).toBe('idle');
    expect(own.expectStopAck).toBe(false);
    const server = feed(agent(), proc, { type: 'session_stopped' }).conv;
    expect(server.status).toBe('stopped');
    const term = feed(agent(), { type: 'session_terminated', reason: 'replaced' }, { type: 'session_stopped' }).conv;
    expect(term.status).toBe('terminated');
    expect(term.termination).toEqual({ reason: 'replaced', detail: null, sdk_session_id: null });
  });
});

describe('voice transcripts (§4.7, VT-*)', () => {
  const ve = (event: Record<string, unknown>) => ({ type: 'voice_event', event });

  it('typed done events finish or add the voice text; response.done closes it; empty transcripts are ignored', () => {
    let c = feed(orch({ voiceActive: true }), ve({ type: 'response.output_text.delta', delta: 'Hi' }), ve({ type: 'response.output_text.done', text: '' })).conv;
    expect(texts(c)).toEqual(['A[text:Hi]']);
    c = feed(c, ve({ type: 'response.text.done', text: 'Second.' })).conv;
    expect(texts(c)).toEqual(['A[text:Hi|text:Second.]']);
    c = feed(c, ve({ type: 'response.text.delta', delta: 'x' }), ve({ type: 'response.done' })).conv;
    const last = c.entries[0];
    expect(last?.kind === 'assistant' && last.blocks[2]).toMatchObject({ text: 'x', streaming: false });
    expect(feed(c, ve({ type: 'conversation.item.input_audio_transcription.completed', transcript: '  ' })).conv).toBe(c);
    expect(feed(c, ve({ type: 'response.output_audio_transcript.done', transcript: '' })).conv).toBe(c);
  });

  it('ignored provider events: tool calls, item.created, status, Gemini modelTurn/toolCall', () => {
    const c = orch({ voiceActive: true });
    for (const ev of [
      { type: 'response.function_call_arguments.done', call_id: 'x', name: 'n', arguments: '{}' },
      { type: 'conversation.item.created', item: {} },
      { type: 'voice_status', status: 'ready' },
      { serverContent: { modelTurn: { parts: [{ text: 'dup' }] } } },
      { toolCall: { functionCalls: [] } },
      { setupComplete: {} },
    ])
      expect(feed(c, ve(ev)).conv).toBe(c);
  });

  it('Gemini interrupted ends the voice text; an open fragment is finalised by output, turn end or another entry', () => {
    let c = feed(orch({ voiceActive: true }), ve({ serverContent: { inputTranscription: { text: 'Hel' } } })).conv;
    expect(c.entries).toMatchObject([{ kind: 'user', streaming: true }]);
    c = feed(c, ve({ serverContent: { inputTranscription: { text: 'lo' }, outputTranscription: { text: '' } } })).conv;
    expect(c.entries).toMatchObject([{ kind: 'user', text: 'Hello', streaming: false }]);
    c = feed(c, ve({ serverContent: { outputTranscription: { text: 'Hi' } } }), ve({ serverContent: { interrupted: true } })).conv;
    expect(c.entries[1]).toMatchObject({ kind: 'assistant', blocks: [{ text: 'Hi', streaming: false }] });
    const open = feed(orch({ voiceActive: true }), ve({ serverContent: { inputTranscription: { text: 'Hey' } } })).conv;
    expect(feed(open, { type: 'local_send', text: 'typed' }).conv.entries[0]).toMatchObject({ streaming: false });
    expect(feed(open, { type: 'voice_local_end' }).conv.entries[0]).toMatchObject({ streaming: false });
  });

  it('I-9: the anchor is only taken when no fragment is open; a transcript inserted at the anchor does not split the run', () => {
    let c = feed(orch({ voiceActive: true }), ve({ type: 'input_audio_buffer.speech_started' }), ve({ type: 'response.audio_transcript.delta', delta: 'A' }), { type: 'tool_use', tool_use_id: 'c', tool_name: 'x', tool_input: {} }).conv;
    c = feed(c, ve({ type: 'conversation.item.input_audio_transcription.completed', transcript: 'question' })).conv;
    expect(texts(c)).toEqual(['U(voice):question', 'A[text:A|tool:c:running]']);
    expect(c.speechAnchor).toBeNull();
    const g = feed(orch({ voiceActive: true }), ve({ serverContent: { inputTranscription: { text: 'a' } } }), ve({ type: 'input_audio_buffer.speech_started' })).conv;
    expect(g.speechAnchor).toBeNull();
  });

  it('voice tool scope: tool cards outside a text turn are voice-scoped and end at voice end, not at a text turn end', () => {
    let c = feed(orch({ voiceActive: true }), { type: 'tool_use', tool_use_id: 'v', tool_name: 'x', tool_input: {} }, { type: 'status', status: 'streaming' }, { type: 'status', status: 'idle' }).conv;
    expect(texts(c)).toEqual(['A[tool:v:running]']);
    c = feed(c, { type: 'voice_owner_active', active: false }).conv;
    expect(texts(c)).toEqual(['A[tool:v:no_result]']);
    expect(c.voiceActive).toBe(false);
    expect(feed(c, { type: 'voice_owner_active', active: true }).conv.voiceActive).toBe(true);
    expect(feed(c, { type: 'session_started', session_id: 'O1', voice: true }).conv.voiceActive).toBe(true);
  });

  it('inject: text mode is a local turn; voice mode stays pending until its echo (§7.9)', () => {
    const t = feed(orch(), { type: 'local_inject', text: '[shared text]\na' }).conv;
    expect(t.localTurnsPending).toBe(1);
    expect(texts(t)).toEqual(['U(inject):[shared text]\na']);
    const v = feed(orch({ voiceActive: true }), { type: 'local_inject', text: 'x' }, { type: 'local_inject', text: 'x' }, { type: 'user_message', text: 'x', source: 'shared_inject' }).conv;
    expect(texts(v)).toEqual(['U(inject):x', 'U(inject,pending):x']);
  });
});

describe('history pages (§5.1 – §5.3)', () => {
  const resp = (messages: Record<string, unknown>[], start: number, more: boolean) =>
    ({ messages, start_index: start, total_count: 99, has_more: more }) as unknown as MessagesPage;

  it('classifies user lines and converts every block kind', () => {
    const c = feed(
      agent(),
      page([
        userLine('[voice] hello'),
        userLine('[voice, recording: rec.wav] hi again'),
        userLine('[audio:webm] spoken'),
        userLine('[shared file] a.pdf (1 KB, application/pdf) — /uploads/a'),
        userLine('[Request interrupted by user]'),
        userLine('This session is being continued from a previous conversation. Summary...'),
        userLine('<task-notification>done</task-notification>'),
        userLine('<command-name>/help</command-name>'),
        { role: 'user', text: '', blocks: [{ type: 'image' }] },
        { role: 'assistant', text: 'plain', blocks: [] },
        { role: 'assistant', text: '', blocks: [] },
        { role: 'assistant', text: '', blocks: [{ type: 'thinking', text: 'hmm' }, { type: 'text', text: '' }, { type: 'tool_use', tool_use_id: 't', tool_name: 'Read', tool_input: 'bad' }, { type: 'tool_result', tool_use_id: 't', output: ['a', 1] }] },
      ]),
    ).conv;
    expect(texts(c)).toEqual([
      'U(voice):hello',
      'U(voice):hi again',
      'U(audio):spoken',
      'U(inject):[shared file] a.pdf (1 KB, application/pdf) — /uploads/a',
      'N(interrupted):',
      'N(compaction):This session is being continued from a previous conversation. Summary...',
      'N(background):<task-notification>done</task-notification>',
      'N(command):<command-name>/help</command-name>',
      'A[text:plain|thinking:hmm|tool:t:done]',
    ]);
    const tb = runOf(c.entries[8]).blocks[2] as ToolBlock;
    expect(tb.output).toBe('["a",1]');
    expect(tb.tool_input).toEqual({});
  });

  it('prepend keeps the live state and shifts the voice anchor (D2); history orphans wait for older pages (H-5)', () => {
    let c = feed(orch({ voiceActive: true }), page([toolResultLine('old', 'r'), asstText('tail')], 4)).conv;
    expect(c.orphanResults).toEqual([{ tool_use_id: 'old', output: 'r', is_error: false, origin: 'history' }]);
    c = feed(c, { type: 'datachannel_event', event: { type: 'input_audio_buffer.speech_started' } }, { type: 'local_send', text: 'hi' }).conv;
    expect(c.speechAnchor).toBe(1);
    const before = c.promptSinceTurnEnd;
    c = feed(c, { type: 'history_page', mode: 'prepend', response: resp([userLine('first'), userLine('second')], 2, true) }).conv;
    expect(c.promptSinceTurnEnd).toBe(before);
    expect(c.speechAnchor).toBe(3);
    expect(c.history).toEqual({ loaded: true, startIndex: 2, totalCount: 99, hasMore: true });
    c = feed(c, { type: 'history_page', mode: 'prepend', response: resp([toolUseLine('old')], 0, false) }).conv;
    expect(c.orphanResults).toEqual([]);
    expect(texts(c)[0]).toBe('A[tool:old:done]');
  });

  it('an older page whose tool_use repeats a known id does not duplicate the card (I-11)', () => {
    let c = feed(agent(), page([toolUseLine('t1')], 1)).conv;
    c = feed(c, { type: 'history_page', mode: 'prepend', response: resp([userLine('q'), toolUseLine('t1')], 0, false) }).conv;
    expect(texts(c)).toEqual(['U(history):q', 'A[tool:t1:no_result]']);
  });

  it('a malformed response is tolerated', () => {
    const c = feed(agent(), { type: 'history_page', mode: 'replace', response: { messages: null } as never } as ConversationInput).conv;
    expect(c.entries).toEqual([]);
    expect(c.history).toEqual({ loaded: true, startIndex: 0, totalCount: 0, hasMore: false });
    expect(feed(agent(), { type: 'history_page', mode: 'reconcile', response: { messages: [{ role: 'user', blocks: null }] } as never } as ConversationInput).conv.entries).toEqual([]);
  });
});
