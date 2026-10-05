/**
 * Session settings (spec 12 §6.14, inv02 F-32): null = inherit (shown as Default), "Use default"
 * writes null, Save PUTs only the changed keys, Save and restart = PUT → POST close → `start`
 * again with the same local_id + resume_sdk_id (restart always closes first, so an idle session
 * is not mistaken for a stopped one), refused while a reply runs. Side sheet / bottom sheet, a11y.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { openSession, startServices, type SessionRuntime } from '@/services';
import { setFrameScheduler, snackbarStore } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { FakeWebSocket, jsonResponse, setupServices, teardownServices, type Harness, type RecordedRequest } from '../../../services/__tests__/fakes';
import { openSessionSettings, SessionSettingsHost, SessionSettingsSheet, sessionSettingsStore } from '..';
import { resetSettingsUi } from '../controller';
import { changedKeys } from '../session/SessionSettingsSheet';
import { serveConfig } from './fixtures';

const CHAT = '/api/sessions/chat';
let h: Harness;
let sessionCfg: Record<string, unknown>;

beforeEach(() => {
  h = setupServices();
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
  resetSettingsUi();
  sessionSettingsStore.setState({ localId: null });
  startServices({ skipInitialSync: true });
  serveConfig(h.fetch);
  sessionCfg = { working_directory: null, enabled_mcps: null, chrome_extension: null, provider: null, harness_model: null };
  h.fetch
    .on('GET', '/api/sessions/sdk-1/config', () => jsonResponse(sessionCfg))
    .on('PUT', '/api/sessions/sdk-1/config', (req: RecordedRequest) => {
      sessionCfg = { ...sessionCfg, ...(req.body as Record<string, unknown>) };
      return jsonResponse(sessionCfg);
    })
    .on('POST', '/api/sessions/L1/close', () => new Response(null, { status: 204 }));
});
afterEach(() => {
  teardownServices();
});

async function liveAgent(): Promise<{ rt: SessionRuntime; ws: FakeWebSocket }> {
  const rt = openSession({ kind: 'agent', localId: 'L1', sdkId: 'sdk-1', focus: true }) as SessionRuntime;
  const ws = FakeWebSocket.last(CHAT);
  await act(async () => {
    ws.open();
    ws.emit({ type: 'session_started', session_id: 'L1', sdk_session_id: 'sdk-1', context_window: 200000 });
  });
  return { rt, ws };
}

const order = (re: RegExp) => h.fetch.requests.findIndex((r) => re.test(`${r.method} ${r.path}`));

describe('changedKeys', () => {
  it('keeps only keys that differ from the saved config', () => {
    const saved = { working_directory: null, enabled_mcps: ['a'], chrome_extension: null, provider: null, harness_model: null };
    expect(changedKeys(saved, { enabled_mcps: ['a'], chrome_extension: false, working_directory: null })).toEqual({ chrome_extension: false });
  });
});

describe('SessionSettingsSheet', () => {
  it('shows inherited values as Default; Save and restart PUTs only changes, then close → start (same local_id, resume_sdk_id)', async () => {
    const user = userEvent.setup();
    const { ws } = await liveAgent();
    const onClose = vi.fn();
    render(<SessionSettingsSheet localId="L1" open onClose={onClose} kind="side" />);
    const sheet = await screen.findByRole('dialog', { name: 'Session settings' });
    await within(sheet).findByText('Working directory');
    // inherited: the global active directory carries the "Default" badge; every MCP on ([] = all)
    expect(within(sheet).getByRole('radio', { name: /Laptop \(Desktop\).*Default/ })).toBeTruthy();
    expect(within(sheet).getAllByRole('switch').slice(0, 3).map((s) => s.getAttribute('aria-checked'))).toEqual(['true', 'true', 'true']);
    expect(within(sheet).getByRole('button', { name: 'Restart' })).toBeTruthy();

    await user.click(within(sheet).getByRole('radio', { name: /Jetson \(local\)/ }));
    await user.click(within(sheet).getByRole('switch', { name: 'github' }));
    expect(within(sheet).getByRole('radio', { name: /Jetson \(local\).*This session/ })).toBeTruthy();
    expect(within(sheet).getByText('Changes apply after a restart.')).toBeTruthy();
    await expectNoAxeViolations(sheet);

    const startsBefore = ws.messages().filter((m) => m.type === 'start').length;
    await user.click(within(sheet).getByRole('button', { name: 'Save and restart' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const put = h.fetch.calls('PUT', '/api/sessions/sdk-1/config');
    expect(put).toHaveLength(1);
    expect(put[0]?.body).toEqual({ working_directory: '/home/rodrigo/assistant', enabled_mcps: ['chrome-devtools', 'filesystem'] });
    expect(order(/^PUT \/api\/sessions\/sdk-1\/config/)).toBeLessThan(order(/^POST \/api\/sessions\/L1\/close/));
    await waitFor(() => expect(ws.messages().filter((m) => m.type === 'start').length).toBe(startsBefore + 1));
    expect(ws.messages().filter((m) => m.type === 'start').pop()).toMatchObject({ local_id: 'L1', resume_sdk_id: 'sdk-1' });
    expect(ws.types()).not.toContain('stop');
    expect(snackbarStore.getState().queue.slice(-1)[0]?.message).toBe('Saved. Restarting the session…');
  });

  it('"Use default" writes null; Save (without restart) keeps the session running', async () => {
    const user = userEvent.setup();
    sessionCfg.enabled_mcps = ['github'];
    sessionCfg.chrome_extension = false;
    await liveAgent();
    render(<SessionSettingsSheet localId="L1" open onClose={() => undefined} kind="side" />);
    const sheet = await screen.findByRole('dialog', { name: 'Session settings' });
    await within(sheet).findByText(/chosen for this session/);
    const useDefault = within(sheet).getAllByRole('button', { name: 'Use default' });
    expect(useDefault).toHaveLength(2);
    await user.click(useDefault[0] as HTMLElement);
    await user.click(within(sheet).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(h.fetch.calls('PUT', '/api/sessions/sdk-1/config')).toHaveLength(1));
    expect(h.fetch.calls('PUT', '/api/sessions/sdk-1/config')[0]?.body).toEqual({ enabled_mcps: null });
    expect(h.fetch.calls('POST', '/api/sessions/L1/close')).toHaveLength(0);
    await waitFor(() => expect(within(sheet).getAllByRole('button', { name: 'Use default' })).toHaveLength(1));
  });

  it('restart is refused while a reply runs', async () => {
    const { rt, ws } = await liveAgent();
    await act(async () => {
      rt.send('long task');
      ws.emit({ type: 'status', status: 'thinking' });
    });
    render(<SessionSettingsSheet localId="L1" open onClose={() => undefined} kind="bottom" />);
    const sheet = await screen.findByRole('dialog', { name: 'Session settings' });
    const restart = await within(sheet).findByRole('button', { name: 'Restart' });
    expect(restart.getAttribute('aria-disabled')).toBe('true');
    expect(within(sheet).getByText(/A reply is running/)).toBeTruthy();
  });

  it('a failed PUT shows the server message (inline + snackbar with Retry) and does not restart', async () => {
    const user = userEvent.setup();
    h.fetch.on('PUT', '/api/sessions/sdk-1/config', () => jsonResponse({ detail: 'config dir is read-only' }, 500));
    await liveAgent();
    render(<SessionSettingsSheet localId="L1" open onClose={() => undefined} kind="side" />);
    const sheet = await screen.findByRole('dialog', { name: 'Session settings' });
    await user.click(await within(sheet).findByRole('switch', { name: 'github' }));
    await user.click(within(sheet).getByRole('button', { name: 'Save and restart' }));
    expect(await within(sheet).findByText('config dir is read-only')).toBeTruthy();
    const snack = snackbarStore.getState().queue.slice(-1)[0];
    expect(snack?.message).toBe('config dir is read-only');
    expect(snack?.action?.label).toBe('Retry');
    expect(h.fetch.calls('POST', '/api/sessions/L1/close')).toHaveLength(0);
  });

  it('the host opens the sheet for openSessionSettings(localId) and "Manage directories" routes to Settings', async () => {
    const user = userEvent.setup();
    await liveAgent();
    const onOpenSettings = vi.fn();
    render(<SessionSettingsHost onOpenSettings={onOpenSettings} />);
    act(() => openSessionSettings('L1'));
    const sheet = await screen.findByRole('dialog', { name: 'Session settings' });
    await user.click(await within(sheet).findByRole('button', { name: 'Manage directories' }));
    expect(onOpenSettings).toHaveBeenCalledWith('working-directories');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Session settings' })).toBeNull());
  });

  it('before the first reply there is nothing to configure yet', async () => {
    openSession({ kind: 'agent', localId: 'L2', focus: true });
    render(<SessionSettingsSheet localId="L2" open onClose={() => undefined} kind="side" />);
    expect(await screen.findByText('Available after the first reply')).toBeTruthy();
  });
});
