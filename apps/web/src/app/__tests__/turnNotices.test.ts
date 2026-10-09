/**
 * "Agent session finished" notices (spec 12 §3.7 `agent_turn_finished`, §8.2): the decision
 * (switch, permission, Stop, looking at it), the copy, and the wiring from the watcher socket to
 * a system notification and back to the session on a click.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentTurnFinishedFrame } from '@/protocol';
import { getSessionRuntime, startServices } from '@/services';
import { prefsStore, setCatalogItems, setPref, tabsStore } from '@/stores';
import { FakeWebSocket, setupServices, teardownServices } from '../../services/__tests__/fakes';
import { navigate, resetRoute } from '../navigation/route';
import { FALLBACK_TITLE, decideTurnNotice, isViewing, noticeBody, noticeTitle, type ViewState } from '../notifications/turnNotices';
import { installTurnNotifications } from '../notifications/turnNotifier';

const F = (o: Partial<AgentTurnFinishedFrame> = {}): AgentTurnFinishedFrame => ({
  type: 'agent_turn_finished',
  session_id: 'A1',
  sdk_session_id: 'S1',
  provider: 'claude',
  title: 'Energy dashboard',
  status: 'ok',
  preview: 'All 12 tests pass.',
  error: null,
  ...o,
});

const ON = { enabled: true, permission: 'granted' as const, viewing: false };

describe('decideTurnNotice', () => {
  it('posts a finished turn: session title, preview body, one tag per session, click data', () => {
    const d = decideTurnNotice(F(), ON);
    expect(d).toEqual({
      post: true,
      localId: 'A1',
      sdkId: 'S1',
      notice: { title: 'Energy dashboard', body: 'All 12 tests pass.', tag: 'archie-turn:A1', data: { kind: 'agent-turn', localId: 'A1', sdkId: 'S1' } },
    });
  });

  it('suppresses: switch off, no permission, a Stop, or the user is looking at it', () => {
    expect(decideTurnNotice(F(), { ...ON, enabled: false })).toEqual({ post: false, reason: 'disabled' });
    expect(decideTurnNotice(F(), { ...ON, permission: 'denied' })).toEqual({ post: false, reason: 'permission' });
    expect(decideTurnNotice(F({ status: 'interrupted' }), ON)).toEqual({ post: false, reason: 'interrupted' });
    expect(decideTurnNotice(F(), { ...ON, viewing: true })).toEqual({ post: false, reason: 'viewing' });
  });

  it('copy: failures say so; titles fall back to what the app knows, then a placeholder', () => {
    expect(noticeBody(F({ status: 'error', error: 'Credit balance is too low' }))).toBe('Failed: Credit balance is too low');
    expect(noticeBody(F({ status: 'error', error: null, preview: null }))).toBe('Failed: the turn ended with an error');
    expect(noticeBody(F({ preview: '  ' }))).toBe('Finished');
    expect(noticeBody(F({ status: 'something-new' }))).toBe('All 12 tests pass.'); // unknown status reads as ok
    expect(noticeTitle(F({ title: null }), 'From the list')).toBe('From the list');
    expect(noticeTitle(F({ title: ' ' }), null)).toBe(FALLBACK_TITLE);
  });

  it('isViewing needs visible + focused + workspace on top + that tab active', () => {
    const v: ViewState = { visible: true, focused: true, workspaceOnTop: true, activeId: 'A1' };
    expect(isViewing(v, 'A1')).toBe(true);
    expect(isViewing({ ...v, visible: false }, 'A1')).toBe(false);
    expect(isViewing({ ...v, focused: false }, 'A1')).toBe(false);
    expect(isViewing({ ...v, workspaceOnTop: false }, 'A1')).toBe(false);
    expect(isViewing(v, 'B2')).toBe(false);
  });
});

describe('turn notifier wiring', () => {
  const made: { title: string; options: Record<string, unknown>; onclick: (() => void) | null }[] = [];
  let uninstall: () => void = () => undefined;

  beforeEach(() => {
    made.length = 0;
    class FakeNotification {
      static permission = 'granted';
      static requestPermission = () => Promise.resolve('granted');
      onclick: (() => void) | null = null;
      constructor(
        public title: string,
        public options: Record<string, unknown>,
      ) {
        made.push(this);
      }
      close(): void {}
    }
    vi.stubGlobal('Notification', FakeNotification);
    Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    vi.spyOn(console, 'info').mockImplementation(() => undefined);
    setupServices();
    resetRoute();
    setPref('notifyAgentTurns', true);
    startServices({ skipInitialSync: true });
    uninstall = installTurnNotifications();
  });
  afterEach(() => {
    uninstall();
    setPref('notifyAgentTurns', false);
    teardownServices();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function watcher(): FakeWebSocket {
    const ws = FakeWebSocket.last('/api/orchestrator/chat');
    if (ws.readyState !== 1) ws.open();
    return ws;
  }

  const settle = () => new Promise((r) => setTimeout(r, 0));

  it('a finished turn becomes a notification; its click opens that session focused', async () => {
    setCatalogItems('sessions', [{ session_id: 'S1', title: 'From the list', provider: 'claude' } as never]);
    watcher().emit({ ...F({ title: null }) });
    await settle();
    expect(made).toHaveLength(1);
    expect(made[0]?.title).toBe('From the list');
    expect(made[0]?.options).toMatchObject({ body: 'All 12 tests pass.', tag: 'archie-turn:A1' });
    expect(console.info).toHaveBeenCalledWith(expect.stringMatching(/^\[notify\] posted A1 status=ok via=page/));
    navigate({ name: 'settings', page: null });
    made[0]?.onclick?.();
    expect(tabsStore.getState().activeId).toBe('A1');
    expect(getSessionRuntime('A1')).toBeDefined();
  });

  it('nothing while the user is looking at that session, or with the switch off', async () => {
    watcher().emit({ ...F({ session_id: 'B2', status: 'interrupted' }) });
    tabsStore.setState({ activeId: 'A1' });
    watcher().emit({ ...F() });
    prefsStore.setState({ notifyAgentTurns: false });
    watcher().emit({ ...F({ session_id: 'C3' }) });
    await settle();
    expect(made).toHaveLength(0);
    expect(console.info).toHaveBeenCalledWith('[notify] suppressed A1 status=ok reason=viewing');
    expect(console.info).toHaveBeenCalledWith('[notify] suppressed B2 status=interrupted reason=interrupted');
  });
});
