/**
 * W-07 requests: (1) an always-live tab status while the panel's render snapshot is frozen,
 * (2) reload restores the active tab and does not badge restored tabs, (3) startServices loads
 * the visuals list; memory loads on demand (spec 12 §9.2).
 */
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  activateTab,
  ACTIVE_TAB_STORAGE_KEY,
  catalogStore,
  deriveLiveStatus,
  getLiveStatus,
  liveStatusStore,
  openTab,
  tabsStore,
  TABS_STORAGE_KEY,
  useTabLiveStatus,
} from '@/stores';
import { ensureMemoryTree, openSession, startServices, stopServices, type SessionRuntime } from '@/services';
import { initialConversation } from '@/protocol';
import { FakeWebSocket, flushPromises, setupServices, teardownServices, type Harness } from './fakes';

const CHAT = '/api/sessions/chat';
let h: Harness;
beforeEach(() => {
  vi.useFakeTimers();
  h = setupServices();
  window.sessionStorage.clear();
});
afterEach(() => {
  teardownServices();
  vi.useRealTimers();
});

function subscribed(id: string, focus: boolean): { rt: SessionRuntime; ws: FakeWebSocket } {
  const rt = openSession({ kind: 'agent', localId: id, focus }) as SessionRuntime;
  const ws = FakeWebSocket.last(CHAT);
  ws.open();
  ws.emit({ type: 'session_started', session_id: id });
  return { rt, ws };
}

describe('1. always-live tab status', () => {
  it('updates while the panel is hidden (render snapshot frozen) and notifies only on real changes', () => {
    startServices({ skipInitialSync: true });
    subscribed('FG', true);
    const { rt, ws } = subscribed('BG', false);
    expect(rt.handle.store.getState().hidden).toBe(true);
    const frozen = rt.handle.store.getState().conv;
    const notify = vi.fn();
    const off = liveStatusStore.subscribe(notify);
    ws.emit({ type: 'user_message', text: 'from another device' });
    ws.emit({ type: 'status', status: 'processing' });
    for (let i = 0; i < 30; i++) ws.emit({ type: 'text_delta', text: 'x' });
    expect(getLiveStatus('BG')).toMatchObject({ status: 'streaming', busy: true, permissionPending: false, error: null });
    expect(notify.mock.calls.length).toBeLessThanOrEqual(3); // processing, streaming — not 30 deltas
    expect(rt.handle.store.getState().conv).toBe(frozen); // the render snapshot stayed frozen
    expect(tabsStore.getState().tabs.find((t) => t.id === 'BG')?.unseen).toBe(true); // new activity in the background

    ws.emit({ type: 'permission_request', request_id: 'r1', tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } });
    expect(getLiveStatus('BG')?.permissionPending).toBe(true);
    ws.emit({ type: 'permission_resolved', request_id: 'r1', decision: 'allow', responder: 'user' });
    expect(getLiveStatus('BG')?.permissionPending).toBe(false);
    ws.emit({ type: 'session_stalled', elapsed_seconds: 130, last_tool_name: 'Bash' });
    expect(getLiveStatus('BG')?.stalled).toBe(true);
    ws.emit({ type: 'turn_complete', session_id: 'sdk-bg' });
    expect(getLiveStatus('BG')).toMatchObject({ status: 'idle', busy: false, stalled: false });
    ws.drop();
    expect(getLiveStatus('BG')?.disconnected).toBe(true);
    off();
  });

  it('errors: a failed start and a termination; removed with the session, re-keyed with it', () => {
    const { ws } = subscribed('A', true);
    ws.emit({ type: 'session_terminated', reason: 'subprocess_crashed', detail: 'x', sdk_session_id: 's' });
    expect(getLiveStatus('A')?.error).toBe('subprocess_crashed');
    const b = openSession({ kind: 'agent', localId: 'B', focus: false });
    const wb = FakeWebSocket.last(CHAT);
    wb.open();
    wb.emit({ type: 'error', error: 'start_failed', detail: 'Directory does not exist' });
    expect(getLiveStatus('B')).toMatchObject({ error: 'start_failed', conn: 'failed' });
    wb.emit({ type: 'session_started', session_id: 'B2' }); // ID-1
    expect(getLiveStatus('B')).toBeUndefined();
    expect(getLiveStatus('B2')?.conn).toBe('subscribed');
    b.dispose();
    stopServices();
    expect(liveStatusStore.getState().byId).toEqual({});
    expect(deriveLiveStatus(initialConversation({ localId: 'x', kind: 'orchestrator', voiceActive: true })).voiceActive).toBe(true);
  });

  it('useTabLiveStatus re-renders a hidden tab only when its summary changes; history loads do not badge', async () => {
    h.fetch.on('GET', /^\/api\/sessions\/sdk-h\/messages/, {
      messages: [
        { role: 'user', text: 'q', blocks: [{ type: 'text', text: 'q' }] },
        { role: 'assistant', text: 'a', blocks: [{ type: 'text', text: 'a' }] },
      ],
      total_count: 2,
      has_more: false,
      start_index: 0,
    });
    startServices({ skipInitialSync: true });
    subscribed('FG', true);
    const rt = openSession({ kind: 'agent', localId: 'H', sdkId: 'sdk-h', focus: false }) as SessionRuntime;
    const ws = FakeWebSocket.last(CHAT);
    const seen: string[] = [];
    function Strip() {
      const s = useTabLiveStatus('H');
      seen.push(s ? `${s.status}/${s.conn}` : 'none');
      return null;
    }
    render(<Strip />);
    await act(async () => {
      ws.open();
      ws.emit({ type: 'session_started', session_id: 'H' });
      await flushPromises();
    });
    expect(rt.conv.entries).toHaveLength(2);
    expect(tabsStore.getState().tabs.find((t) => t.id === 'H')?.unseen).toBe(true); // opened in background (P-6)
    activateTab('H');
    activateTab('FG');
    expect(tabsStore.getState().tabs.find((t) => t.id === 'H')?.unseen).toBe(false);
    const n = seen.length;
    act(() => {
      for (let i = 0; i < 20; i++) ws.emit({ type: 'compact_complete', summary: '' }); // entries, no status change
    });
    expect(seen.length).toBe(n);
    act(() => ws.emit({ type: 'status', status: 'processing' }));
    expect(seen.at(-1)).toBe('processing/subscribed');
  });
});

describe('2. reload restores the active tab and does not badge restored tabs', () => {
  function previousPage(): void {
    openTab({ id: 'L1', kind: 'agent', localId: 'L1' }, { focus: true });
    openTab({ id: 'memory:a.md', kind: 'memory', path: 'a.md' }, { focus: true });
    openTab({ id: 'L2', kind: 'agent', localId: 'L2' }, { focus: true });
    expect(window.sessionStorage.getItem(ACTIVE_TAB_STORAGE_KEY)).toBe('L2');
    tabsStore.setState({ tabs: [], activeId: null }); // the page reloads
  }

  it('restored chat tabs come back unbadged at their place; the saved active one is re-activated', async () => {
    previousPage();
    h.fetch.on('GET', '/api/sessions/pool/live', [
      { local_id: 'L2', sdk_session_id: null, status: 'idle', cost: 0, turns: 0, title: null, is_orchestrator: false },
      { local_id: 'L1', sdk_session_id: null, status: 'idle', cost: 0, turns: 0, title: null, is_orchestrator: false },
      { local_id: 'NEW', sdk_session_id: null, status: 'idle', cost: 0, turns: 0, title: null, is_orchestrator: false },
    ]);
    startServices();
    expect(tabsStore.getState().activeId).toBeNull(); // waiting for L2 (not the doc tab)
    await flushPromises();
    const s = tabsStore.getState();
    expect(s.tabs.map((t) => [t.id, t.unseen])).toEqual([
      ['L1', false],
      ['memory:a.md', false],
      ['L2', false],
      ['NEW', true], // a session that appeared meanwhile is new activity
    ]);
    expect(s.activeId).toBe('L2');
  });

  it('a restored doc tab can be the active one', () => {
    openTab({ id: 'L1', kind: 'agent', localId: 'L1' }, { focus: true });
    openTab({ id: 'viz:a.html', kind: 'visual', path: 'a.html' }, { focus: true });
    tabsStore.setState({ tabs: [], activeId: null });
    startServices({ skipInitialSync: true });
    expect(tabsStore.getState().activeId).toBe('viz:a.html');
  });

  it('if the saved active session is gone, the first restored tab becomes active; a first visit activates nothing', async () => {
    previousPage();
    h.fetch.on('GET', '/api/sessions/pool/live', [{ local_id: 'L1', sdk_session_id: null, status: 'idle', cost: 0, turns: 0, title: null, is_orchestrator: false }]);
    startServices();
    await flushPromises();
    expect(tabsStore.getState().activeId).toBe('L1');
    stopServices();
    window.localStorage.removeItem(TABS_STORAGE_KEY);
    window.sessionStorage.clear();
    tabsStore.setState({ tabs: [], activeId: null });
    startServices();
    await flushPromises();
    expect(tabsStore.getState().tabs.map((t) => [t.id, t.unseen])).toEqual([['L1', true]]);
    expect(tabsStore.getState().activeId).toBeNull();
  });
});

describe('3. startup loads', () => {
  it('startServices loads the visuals list; memory loads on demand, once', async () => {
    h.fetch.on('GET', '/api/visualizations', [{ path: 'a.html', url: '/a.html', title: 'A', created: '', modified: '', size: 1 }]);
    h.fetch.on('GET', '/api/memory/tree', []);
    startServices();
    await flushPromises();
    expect(catalogStore.getState().visuals.items).toHaveLength(1);
    expect(h.fetch.calls('GET', '/api/memory/tree')).toHaveLength(0);
    await ensureMemoryTree();
    await ensureMemoryTree();
    expect(h.fetch.calls('GET', '/api/memory/tree')).toHaveLength(1);
  });
});
