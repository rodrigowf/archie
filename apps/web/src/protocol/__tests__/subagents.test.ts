/** Subagent rules (spec 12 §4.8 SA-*) beyond what the shared `subagent_*` fixtures pin down. */
import { describe, expect, it } from 'vitest';
import type { Conversation, MessagesPage, Subagent } from '../index';
import { isSubagentTool, runningSubagents, subagentPath } from '../index';
import { agent, feed, orch, page, texts, toolResultLine, toolUseLine, userLine } from './util';

const proc = { type: 'status', status: 'processing' };
const tc = { type: 'turn_complete', session_id: 'sdk-1', is_error: false, num_turns: 1 };
const spawn = (id: string, input: Record<string, unknown> = { subagent_type: 'Explore', description: 'Find it' }) => ({
  type: 'tool_use',
  tool_use_id: id,
  tool_name: 'Agent',
  tool_input: input,
});
const started = (id: string, extra: Record<string, unknown> = {}) => ({
  type: 'subagent_started',
  tool_use_id: id,
  parent_tool_use_id: null,
  agent_type: 'Explore',
  description: 'Find it',
  background: false,
  started_at: '2026-10-12T12:00:00Z',
  ...extra,
});
const finished = (id: string, status = 'done', summary = 'ok') => ({
  type: 'subagent_finished',
  tool_use_id: id,
  status,
  summary,
  usage: { tokens: 10, tool_uses: 1, duration_ms: 1000 },
  ended_at: '2026-10-12T12:00:09Z',
});
const inner = (id: string, frame: Record<string, unknown>) => ({ type: 'subagent_event', tool_use_id: id, frame });
const pageOf = (messages: Record<string, unknown>[]): MessagesPage => (page(messages) as { response: MessagesPage }).response;
const EMPTY: MessagesPage = { messages: [], total_count: 0, has_more: false, start_index: 0 };

function sub(c: Conversation, id: string): Subagent {
  const s = c.subagents.find((x) => x.tool_use_id === id);
  if (!s) throw new Error(`no subagent ${id}`);
  return s;
}

describe('SA-1 / SA-2 placeholders and lifecycle', () => {
  it('a spawn card creates a placeholder; lifecycle frames never touch the parent', () => {
    const base = feed(agent(), { type: 'local_send', text: 'go' }, proc, spawn('t1')).conv;
    expect(sub(base, 't1')).toMatchObject({ agent_type: 'Explore', description: 'Find it', status: 'running', lifecycle: false });
    const after = feed(base, started('t1'), { type: 'subagent_progress', tool_use_id: 't1', activity: 'Grep', usage: { tokens: 5 } }).conv;
    expect(after.entries).toBe(base.entries);
    expect(after.status).toBe(base.status);
    expect(sub(after, 't1')).toMatchObject({ activity: 'Grep', usage: { tokens: 5, tool_uses: null, duration_ms: null }, lifecycle: true });
  });

  it('inner frames outside a turn never start a parent turn (TL-3 does not apply)', () => {
    const c = feed(agent(), spawn('t1'), tc, inner('t1', { type: 'text_delta', text: 'x' })).conv;
    expect(c.inTurn).toBe(false);
    expect(c.status).toBe('idle');
  });

  it('empty lifecycle strings never blank what the spawn card set (D6)', () => {
    const c = feed(agent(), proc, spawn('t1'), started('t1', { agent_type: '', description: '' })).conv;
    expect(sub(c, 't1')).toMatchObject({ agent_type: 'Explore', description: 'Find it' });
  });

  it('a placeholder without lifecycle follows its card: error → failed, turn end → stopped', () => {
    let c = feed(agent(), proc, spawn('a'), spawn('b'), { type: 'tool_result', tool_use_id: 'a', output: 'boom', is_error: true }).conv;
    expect(sub(c, 'a')).toMatchObject({ status: 'failed', summary: 'boom' });
    c = feed(c, tc).conv;
    expect(sub(c, 'b')).toMatchObject({ status: 'stopped', summary: null });
  });

  it('only agent sessions track subagents', () => {
    const c = feed(orch(), { type: 'status', status: 'streaming' }, spawn('t1')).conv;
    expect(c.subagents).toEqual([]);
  });

  it('every alias normalises to Task', () => {
    for (const n of ['Agent', 'Task', 'agent', 'task', 'invoke_agent']) expect(isSubagentTool(n)).toBe(true);
    expect(isSubagentTool('Bash')).toBe(false);
  });
});

describe('SA-3 snapshot and stops', () => {
  it('session_started.subagents upserts in order and never deletes', () => {
    let c = feed(agent(), proc, spawn('t0')).conv;
    c = feed(c, {
      type: 'session_started',
      session_id: 'L1',
      subagents: [
        { tool_use_id: 't1', parent_tool_use_id: null, agent_type: 'Plan', description: 'p', background: true, status: 'running', activity: 'Read', usage: null, summary: null, started_at: null, ended_at: null },
        { tool_use_id: 't2', parent_tool_use_id: 't1', agent_type: 'Explore', description: 'q', background: false, status: 'done', activity: null, usage: null, summary: 'ok', started_at: null, ended_at: null },
        'junk',
      ],
    }).conv;
    expect(c.subagents.map((s) => s.tool_use_id)).toEqual(['t0', 't1', 't2']);
    expect(sub(c, 't1')).toMatchObject({ background: true, activity: 'Read', lifecycle: true });
    expect(sub(c, 't2')).toMatchObject({ parent_tool_use_id: 't1', status: 'done', summary: 'ok' });
  });

  it('session_stopped / session_terminated stop every running subagent', () => {
    const base = feed(agent(), proc, spawn('a'), started('a'), spawn('b'), started('b'), finished('b')).conv;
    expect(sub(feed(base, { type: 'session_stopped' }).conv, 'a').status).toBe('stopped');
    const t = feed(base, { type: 'session_terminated', reason: 'crash' }).conv;
    expect(sub(t, 'a').status).toBe('stopped');
    expect(sub(t, 'b').status).toBe('done');
  });
});

describe('SA-5 lazy transcripts', () => {
  const running = () => feed(agent(), proc, spawn('t1'), started('t1')).conv;

  it('open emits the fetch, holds frames, and a failed fetch still releases them', () => {
    const r = feed(running(), { type: 'open_subagent', toolUseId: 't1' });
    expect(r.effects).toContainEqual({ type: 'fetch_subagent_page', toolUseId: 't1' });
    let c = feed(r.conv, inner('t1', { type: 'tool_use', tool_use_id: 'g', tool_name: 'Grep', tool_input: {} })).conv;
    expect(sub(c, 't1').held).toHaveLength(1);
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: null, error: 'HTTP 500' }).conv;
    const s = sub(c, 't1');
    expect(s.held).toBeNull();
    expect(s.loadError).toBe('HTTP 500');
    expect(texts(s.conv as Conversation)).toEqual(['A[tool:g:running]']);
  });

  it('opening twice is a no-op; release drops the transcript; a late page for a released one is ignored', () => {
    const r = feed(running(), { type: 'open_subagent', toolUseId: 't1' }, { type: 'open_subagent', toolUseId: 't1' });
    expect(r.effects.filter((e) => e.type === 'fetch_subagent_page')).toHaveLength(1);
    const released = feed(r.conv, { type: 'release_subagent', toolUseId: 't1' }).conv;
    expect(sub(released, 't1')).toMatchObject({ conv: null, held: null });
    const late = feed(released, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: EMPTY }).conv;
    expect(late).toBe(released);
  });

  it('a subagent that finishes while its page loads ends its turn after the held frames', () => {
    let c = feed(running(), { type: 'open_subagent', toolUseId: 't1' }).conv;
    c = feed(c, inner('t1', { type: 'tool_use', tool_use_id: 'g', tool_name: 'Grep', tool_input: {} }), finished('t1')).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: pageOf([userLine('find')]) }).conv;
    const conv = sub(c, 't1').conv as Conversation;
    expect(conv.inTurn).toBe(false);
    expect(texts(conv)).toEqual(['U(history):find', 'A[tool:g:no_result]']);
  });

  it('an open transcript of a done agent never keeps a running tail (TC-2)', () => {
    let c = feed(agent(), proc, spawn('t1'), started('t1'), finished('t1')).conv;
    c = feed(c, { type: 'open_subagent', toolUseId: 't1' }).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: pageOf([toolUseLine('g', 'Grep')]) }).conv;
    expect(texts(sub(c, 't1').conv as Conversation)).toEqual(['A[tool:g:no_result]']);
  });

  it('prepend adds an older page to an open transcript', () => {
    let c = feed(running(), { type: 'open_subagent', toolUseId: 't1' }).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: { messages: [userLine('new')], total_count: 2, has_more: true, start_index: 1 } as never }).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'prepend', response: { messages: [userLine('old')], total_count: 2, has_more: false, start_index: 0 } as never }).conv;
    expect(texts(sub(c, 't1').conv as Conversation)).toEqual(['U(history):old', 'U(history):new']);
  });

  it('a canonical reload re-fetches every open transcript and holds their frames meanwhile', () => {
    let c = feed(running(), { type: 'open_subagent', toolUseId: 't1' }).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: EMPTY }).conv;
    c = feed(c, { type: 'begin_reload' }, inner('t1', { type: 'text_delta', text: 'x' })).conv;
    const r = feed(c, page([userLine('go')]));
    expect(r.effects).toContainEqual({ type: 'fetch_subagent_page', toolUseId: 't1' });
    expect(sub(r.conv, 't1').held).toHaveLength(1);
  });
});

describe('SA-6 nesting', () => {
  it('spawn cards inside an open transcript are hoisted into the root map with their parent', () => {
    let c = feed(agent(), proc, spawn('t1'), started('t1'), { type: 'open_subagent', toolUseId: 't1' }).conv;
    c = feed(c, { type: 'subagent_page', toolUseId: 't1', mode: 'replace', response: EMPTY }).conv;
    c = feed(c, inner('t1', spawn('n1', { subagent_type: 'worker', description: 'Dig' }))).conv;
    expect(sub(c, 'n1')).toMatchObject({ parent_tool_use_id: 't1', agent_type: 'worker', description: 'Dig', status: 'running' });
    c = feed(c, inner('t1', { type: 'tool_result', tool_use_id: 'n1', output: 'dug', is_error: false })).conv;
    expect(sub(c, 'n1')).toMatchObject({ status: 'done', summary: 'dug' });
    expect(subagentPath(c, 'n1').map((s) => s.tool_use_id)).toEqual(['t1', 'n1']);
    expect(runningSubagents(c)).toBe(1);
  });
});

describe('SA-7 task notifications', () => {
  it('a notice, never a user bubble, and the tray is not popped by its turn', () => {
    let c = feed(agent(), proc, { type: 'local_send', text: 'queued one' }, tc).conv;
    expect(c.queue).toHaveLength(1);
    c = feed(c, { type: 'user_message', text: '<task-notification>x</task-notification>', source: 'task_notification' }, proc).conv;
    expect(texts(c)).toEqual(['N(background):<task-notification>x</task-notification>']);
    expect(c.queue).toEqual([{ text: 'queued one', owner: 'local' }]);
  });
});

describe('SA-8 list fetch', () => {
  it('after the first history page with placeholders, once; rows upsert with authority over the card', () => {
    const r = feed(agent(), page([toolUseLine('t0', 'Agent', { subagent_type: 'Explore', description: 'old' }), toolResultLine('t0', 'Async agent launched')]));
    expect(r.effects).toContainEqual({ type: 'fetch_subagents' });
    expect(sub(r.conv, 't0').status).toBe('done');
    const again = feed(r.conv, page([userLine('x')]));
    expect(again.effects.some((e) => e.type === 'fetch_subagents')).toBe(false);
    const listed = feed(r.conv, { type: 'subagents_list', rows: [{ tool_use_id: 't0', status: 'running', background: true, activity: 'Bash' }] }).conv;
    expect(sub(listed, 't0')).toMatchObject({ status: 'running', background: true, activity: 'Bash', lifecycle: true });
  });

  it('no fetch without placeholders or without an sdk id', () => {
    expect(feed(agent(), page([userLine('x')])).effects.some((e) => e.type === 'fetch_subagents')).toBe(false);
    expect(feed(agent({ sdkId: null }), page([toolUseLine('t0', 'Agent')])).effects.some((e) => e.type === 'fetch_subagents')).toBe(false);
  });
});

describe('decoder', () => {
  it('subagent frames are coerced; a bad inner frame is dropped', () => {
    const c = feed(agent(), proc, spawn('t1'), { type: 'subagent_event', tool_use_id: 't1', frame: { nope: 1 } }, { type: 'subagent_finished', tool_use_id: 't1', status: 'weird' }).conv;
    expect(sub(c, 't1')).toMatchObject({ status: 'done', activity: null });
  });
});

describe('SA-2 amendment (2026-10-12)', () => {
  it('a re-applied spawn card without a description never blanks a known one', () => {
    let c = feed(agent(), proc, spawn('t1')).conv;
    c = feed(c, { ...spawn('t1', { subagent_type: '' }), tool_input: {} }).conv;
    expect(sub(c, 't1')).toMatchObject({ agent_type: 'Explore', description: 'Find it' });
  });

  it('a running snapshot row stops the spawn card deciding the status (late-joining device)', () => {
    let c = feed(agent(), {
      type: 'session_started',
      session_id: 'L1',
      subagents: [{ tool_use_id: 't1', agent_type: 'general-purpose', description: 'logs', background: true, status: 'running' }],
    }).conv;
    c = feed(c, proc, spawn('t1'), { type: 'tool_result', tool_use_id: 't1', output: 'Async agent launched', is_error: false }).conv;
    expect(sub(c, 't1').status).toBe('running');
  });

  it('a subagent_finished without subagent_started also takes over from the card', () => {
    let c = feed(agent(), proc, spawn('t1'), finished('t1', 'stopped', 'Stopped by user')).conv;
    c = feed(c, { type: 'tool_result', tool_use_id: 't1', output: 'boom', is_error: true }).conv;
    expect(sub(c, 't1')).toMatchObject({ status: 'stopped', summary: 'Stopped by user' });
  });
});

describe('SA-3 terminal statuses stick (2026-10-12 review)', () => {
  it('a stale running row never flips a finished agent back; a later subagent_finished still applies', () => {
    let c = feed(agent(), proc, spawn('t1'), started('t1'), finished('t1', 'done', 'first')).conv;
    c = feed(c, { type: 'subagents_list', rows: [{ tool_use_id: 't1', status: 'running', activity: 'Grep' }] }).conv;
    expect(sub(c, 't1')).toMatchObject({ status: 'done', summary: 'first', activity: 'Grep' });
    c = feed(c, { type: 'session_started', session_id: 'L1', subagents: [{ tool_use_id: 't1', status: 'running' }] }).conv;
    expect(sub(c, 't1').status).toBe('done');
    c = feed(c, finished('t1', 'done', 'full report')).conv;
    expect(sub(c, 't1').summary).toBe('full report');
    c = feed(c, { type: 'subagents_list', rows: [{ tool_use_id: 't1', status: 'failed' }] }).conv;
    expect(sub(c, 't1').status).toBe('failed'); // terminal → terminal from a row is allowed by the spec text
  });
});
