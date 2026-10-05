/**
 * Every "Archie" entry point goes through W-11's spec 12 §6.11 flows (inv02 F-25, §6.3 #11):
 * Ctrl+Alt+N, the empty workspace, the compact switcher's "New Archie chat", and a history click
 * on a past Archie conversation (resume: `start{local_id: uuid(), resume_sdk_id}`, or the
 * three-action dialog when another Archie runs). Agent history rows still reopen live.
 */
import { act, fireEvent, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { openSession, startServices, type SessionInfo } from '@/services';
import { setCatalogItems, setFrameScheduler, tabsStore } from '@/stores';
import { resetSessionActions } from '@/features/session-actions';
import { renderUi } from '@/test/render';
import { App } from '../App';
import { FakeWebSocket, setupApp, SIZES, teardownApp } from './testUtils';
import type { Harness } from '../../services/__tests__/fakes';

const ORCH = '/api/orchestrator/chat';
const CHAT = '/api/sessions/chat';
let h: Harness;

function row(p: Partial<SessionInfo> & { session_id: string; title: string }): SessionInfo {
  return {
    started_at: '2026-10-03T08:00:00Z',
    last_activity: new Date().toISOString(),
    message_count: 2,
    is_orchestrator: false,
    provider: 'claude',
    local_id: null,
    ...p,
  };
}

function setup(width: number = SIZES.expanded): void {
  h = setupApp(width);
  setFrameScheduler({
    schedule: (fn) => {
      let c = false;
      void Promise.resolve().then(() => {
        if (!c) fn();
      });
      return () => {
        c = true;
      };
    },
  });
  h.fetch.on('GET', /\/api\/sessions\/[^/]+\/messages/, { messages: [], total_count: 0, has_more: false, start_index: 0 });
  resetSessionActions();
  startServices({ skipInitialSync: true });
}

/** A live Archie (O1) on the orchestrator socket. */
function liveArchie(): void {
  openSession({ kind: 'archie', localId: 'O1', sdkId: 'O1', focus: true });
  const ws = FakeWebSocket.last(ORCH);
  ws.open();
  ws.emit({ type: 'session_started', session_id: 'O1', jsonl_id: 'O1' });
}

const orchStarts = () =>
  FakeWebSocket.all(ORCH)
    .flatMap((w) => w.messages())
    .filter((m) => m.type === 'start');

afterEach(() => {
  teardownApp();
});

describe('New Archie entry points (requestNewArchie)', () => {
  beforeEach(() => {
    setup();
  });

  it('Ctrl+Alt+N with Archie running → the conflict dialog; "Open the running one" focuses it, nothing starts', async () => {
    liveArchie();
    openSession({ kind: 'agent', localId: 'A1', focus: true, titleHint: 'Agent' });
    const { findByRole } = renderUi(<App services={false} />);
    fireEvent.keyDown(document, { key: 'n', code: 'KeyN', ctrlKey: true, altKey: true });
    const dlg = await findByRole('alertdialog', { name: 'Archie is already active' });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Open the running one' }));
    await waitFor(() => {
      expect(tabsStore.getState().activeId).toBe('O1');
    });
    expect(orchStarts()).toHaveLength(1);
  });

  it('the empty workspace button with nothing running → a plain start{local_id}, no dialog', async () => {
    const { getByRole, queryByRole } = renderUi(<App services={false} />);
    fireEvent.click(getByRole('button', { name: 'New Archie conversation' }));
    await waitFor(() => {
      expect(tabsStore.getState().tabs.map((t) => t.kind)).toEqual(['archie']);
    });
    const id = tabsStore.getState().activeId as string;
    await act(async () => {
      FakeWebSocket.last(ORCH).open();
    });
    expect(orchStarts()).toEqual([{ type: 'start', local_id: id }]);
    expect(queryByRole('alertdialog')).toBeNull();
  });
});

describe('compact switcher', () => {
  beforeEach(() => {
    setup(SIZES.compact);
  });

  it('"New Archie chat" with Archie running → the conflict dialog; "Stop it and start new" closes it first', async () => {
    liveArchie();
    const { getByRole, findByRole } = renderUi(<App services={false} />);
    fireEvent.click(getByRole('button', { name: /Switch session/ }));
    fireEvent.click(within(getByRole('dialog', { name: 'Sessions' })).getByRole('button', { name: 'New Archie chat' }));
    const dlg = await findByRole('alertdialog', { name: 'Archie is already active' });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Stop it and start new' }));
    await waitFor(() => {
      expect(tabsStore.getState().tabs.some((t) => t.kind === 'archie' && t.id !== 'O1')).toBe(true);
    });
    expect(h.fetch.calls('POST', '/api/sessions/O1/close')).toHaveLength(1);
    expect(tabsStore.getState().tabs.map((t) => t.id)).not.toContain('O1');
  });
});

describe('history click (openFromHistory)', () => {
  beforeEach(() => {
    setup();
    setCatalogItems('sessions', [
      row({ session_id: 'PAST', title: 'Morning briefing', is_orchestrator: true }),
      row({ session_id: 'AGENT-SDK', title: 'Weather script' }),
    ]);
  });

  it('a past Archie conversation with nothing running resumes live: start{local_id: uuid(), resume_sdk_id}', async () => {
    const { getByRole } = renderUi(<App services={false} />);
    fireEvent.click(getByRole('button', { name: /Morning briefing/ }));
    await waitFor(() => {
      expect(tabsStore.getState().tabs.map((t) => [t.kind, t.sdkId, t.readOnly])).toEqual([['archie', 'PAST', false]]);
    });
    const id = tabsStore.getState().activeId as string;
    await act(async () => {
      FakeWebSocket.last(ORCH).open();
    });
    expect(orchStarts()).toEqual([{ type: 'start', local_id: id, resume_sdk_id: 'PAST' }]);
    expect(id).not.toBe('PAST');
  });

  it('with another Archie running → the dialog (no read-only view); "Stop it and resume this one" closes it, then resumes', async () => {
    liveArchie();
    const { getByRole, findByRole } = renderUi(<App services={false} />);
    fireEvent.click(getByRole('button', { name: /Morning briefing/ }));
    const dlg = await findByRole('alertdialog', { name: 'Another Archie conversation is running' });
    expect(tabsStore.getState().tabs.map((t) => t.id)).toEqual(['O1']);
    fireEvent.click(within(dlg).getByRole('button', { name: 'Stop it and resume this one' }));
    await waitFor(() => {
      expect(tabsStore.getState().tabs.map((t) => [t.kind, t.sdkId, t.readOnly])).toEqual([['archie', 'PAST', false]]);
    });
    expect(h.fetch.calls('POST', '/api/sessions/O1/close')).toHaveLength(1);
    const id = tabsStore.getState().activeId as string;
    await waitFor(() => {
      expect(orchStarts().pop()).toEqual({ type: 'start', local_id: id, resume_sdk_id: 'PAST' });
    });
  });

  it('an agent session reopens live on its chat socket', async () => {
    const { getByRole } = renderUi(<App services={false} />);
    fireEvent.click(getByRole('button', { name: /Weather script/ }));
    await waitFor(() => {
      expect(tabsStore.getState().tabs.map((t) => [t.kind, t.sdkId, t.readOnly])).toEqual([['agent', 'AGENT-SDK', false]]);
    });
    const ws = FakeWebSocket.last(CHAT);
    await act(async () => {
      ws.open();
    });
    expect(ws.messages()[0]).toEqual({ type: 'start', local_id: tabsStore.getState().activeId, resume_sdk_id: 'AGENT-SDK' });
  });
});
