/**
 * SessionRuntime subagent paths (spec 12 §4.8): SA-5 transcript fetch through the real REST
 * client (held frames, 404 = empty, errors kept in the tab), the 3-transcript LRU, SA-8 list fetch
 * and SA-9 stop.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Subagent } from '@/protocol';
import { openSession, SessionRuntime } from '@/services';
import { FakeWebSocket, flushPromises, jsonResponse, setupServices, teardownServices, type Harness } from './fakes';

const CHAT = '/api/sessions/chat';
let h: Harness;

beforeEach(() => {
  vi.useFakeTimers();
  h = setupServices();
});
afterEach(() => {
  teardownServices();
  vi.useRealTimers();
});

function live(): { rt: SessionRuntime; ws: FakeWebSocket } {
  h.fetch.on('GET', /^\/api\/sessions\/sdk-1\/messages/, { messages: [], total_count: 0, has_more: false, start_index: 0 });
  const rt = openSession({ kind: 'agent', focus: true, localId: 'L1', sdkId: 'sdk-1' }) as SessionRuntime;
  const ws = FakeWebSocket.last(CHAT);
  ws.open();
  ws.emit({ type: 'session_started', session_id: 'L1', context_window: 200000 });
  return { rt, ws };
}

const spawn = (ws: FakeWebSocket, id: string) => {
  ws.emit({ type: 'tool_use', tool_use_id: id, tool_name: 'Agent', tool_input: { subagent_type: 'Explore', description: `do ${id}` } });
  ws.emit({ type: 'subagent_started', tool_use_id: id, parent_tool_use_id: null, agent_type: 'Explore', description: `do ${id}`, background: false });
};
const sub = (rt: SessionRuntime, id: string): Subagent => rt.conv.subagents.find((s) => s.tool_use_id === id) as Subagent;

describe('SA-5 transcripts', () => {
  it('fetches the first page and applies the held frames after it', async () => {
    const { rt, ws } = live();
    await flushPromises();
    ws.emit({ type: 'status', status: 'processing' });
    spawn(ws, 't1');
    h.fetch.on('GET', '/api/sessions/sdk-1/subagents/t1/messages', {
      messages: [{ role: 'user', text: 'find it', blocks: [{ type: 'text', text: 'find it' }] }],
      total_count: 1,
      has_more: false,
      start_index: 0,
    });
    rt.openSubagent('t1');
    ws.emit({ type: 'subagent_event', tool_use_id: 't1', frame: { type: 'text_complete', text: 'found' } });
    expect(sub(rt, 't1').held).toHaveLength(1);
    await flushPromises();
    expect(h.fetch.calls('GET', '/api/sessions/sdk-1/subagents/t1/messages?limit=50')).toHaveLength(1);
    const s = sub(rt, 't1');
    expect(s.held).toBeNull();
    expect(s.conv?.entries.map((e) => e.kind)).toEqual(['user', 'assistant']);
  });

  it('a 404 is an empty transcript; a 500 keeps the error for the tab', async () => {
    const { rt, ws } = live();
    await flushPromises();
    spawn(ws, 'a');
    spawn(ws, 'b');
    h.fetch.on('GET', '/api/sessions/sdk-1/subagents/b/messages', () => jsonResponse({ detail: 'store down' }, 500));
    rt.openSubagent('a');
    rt.openSubagent('b');
    await flushPromises();
    expect(sub(rt, 'a')).toMatchObject({ held: null, loadError: null });
    expect(sub(rt, 'b')).toMatchObject({ held: null, loadError: 'store down' });
  });

  it('keeps at most 3 transcripts, releasing the least recently viewed', async () => {
    const { rt, ws } = live();
    await flushPromises();
    for (const id of ['a', 'b', 'c', 'd']) spawn(ws, id);
    rt.openSubagent('a');
    rt.openSubagent('b');
    rt.openSubagent('a');
    rt.openSubagent('c');
    rt.openSubagent('d');
    const open = rt.conv.subagents.filter((s) => s.conv !== null).map((s) => s.tool_use_id);
    expect(open).toEqual(['a', 'c', 'd']);
  });
});

describe('SA-8 / SA-9', () => {
  it('fetches the list once after a history page with placeholders', async () => {
    h.fetch.on('GET', /^\/api\/sessions\/sdk-1\/messages/, {
      messages: [{ role: 'assistant', text: '', blocks: [{ type: 'tool_use', tool_use_id: 't0', tool_name: 'Agent', tool_input: { subagent_type: 'Explore' } }] }],
      total_count: 1,
      has_more: false,
      start_index: 0,
    });
    h.fetch.on('GET', '/api/sessions/sdk-1/subagents', { subagents: [{ tool_use_id: 't0', status: 'running', background: true, description: 'logs' }] });
    const rt = openSession({ kind: 'agent', focus: true, localId: 'L1', sdkId: 'sdk-1' }) as SessionRuntime;
    const ws = FakeWebSocket.last(CHAT);
    ws.open();
    ws.emit({ type: 'session_started', session_id: 'L1' });
    await flushPromises();
    await flushPromises();
    expect(h.fetch.calls('GET', '/api/sessions/sdk-1/subagents')).toHaveLength(1);
    expect(sub(rt, 't0')).toMatchObject({ status: 'running', background: true, description: 'logs' });
  });

  it('stop_subagent goes out on the chat socket', async () => {
    const { rt, ws } = live();
    await flushPromises();
    rt.stopSubagent('t1');
    expect(ws.messages()).toContainEqual({ type: 'stop_subagent', tool_use_id: 't1' });
  });
});
