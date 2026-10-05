import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  REMOTE_CONSOLE_KEY,
  isRemoteLogEnabled,
  remoteLog,
  remoteLogDefault,
  remoteLogStats,
  setRemoteLogEnabled,
  type RemoteConsoleApi,
} from './remoteLog';

afterEach(() => {
  delete window.__archieRemoteConsole;
  window.localStorage.clear();
  vi.restoreAllMocks();
});

function fakeApi(): RemoteConsoleApi & { on: boolean } {
  const api = {
    key: REMOTE_CONSOLE_KEY,
    on: false,
    isEnabled: () => api.on,
    setEnabled: vi.fn((v: boolean) => {
      api.on = v;
    }),
    send: vi.fn(() => true),
    stats: () => ({ sent: 3, dropped: 1 }),
  };
  return api;
}

describe('remoteLog', () => {
  it('defaults: off on main (this test project), on for compat', () => {
    expect(remoteLogDefault()).toBe(false);
  });

  it('delegates to the inline remote console when installed', () => {
    const api = fakeApi();
    window.__archieRemoteConsole = api;
    setRemoteLogEnabled(true);
    expect(api.setEnabled).toHaveBeenCalledWith(true, true);
    expect(isRemoteLogEnabled()).toBe(true);
    expect(remoteLog('perf', 'first render 120ms')).toBe(true);
    expect(api.send).toHaveBeenCalledWith('perf', 'first render 120ms');
    expect(remoteLogStats()).toEqual({ sent: 3, dropped: 1 });
  });

  it('without the inline script: persists the pref and beacons directly', () => {
    setRemoteLogEnabled(true);
    expect(window.localStorage.getItem(REMOTE_CONSOLE_KEY)).toBe('1');
    expect(isRemoteLogEnabled()).toBe(false);
    const beacon = vi.fn(() => true);
    Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true });
    expect(remoteLog('info', 'x')).toBe(true);
    expect(beacon).toHaveBeenCalledWith('/api/debug/log', expect.stringContaining('"level":"info"'));
    expect(remoteLogStats()).toEqual({ sent: 0, dropped: 0 });
  });
});
