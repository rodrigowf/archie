/**
 * Remote console control (spec 13 §1.4, inv02 F-38). The capture itself is the hand-written ES5
 * inline script `scripts/remote-console.js`, which runs before the app and installs
 * `window.__archieRemoteConsole`. This module is the typed app-side API:
 *  - the device pref "Remote logging" (Settings → This device) calls setRemoteLogEnabled();
 *  - remoteLog() sends one line (e.g. a `perf` timing beacon) through the same rate limit.
 * Default: off on main, on for compat. Errors are always sent by the inline script.
 */
export const REMOTE_CONSOLE_KEY = 'archie.remoteConsole';
export const REMOTE_LOG_ENDPOINT = '/api/debug/log';

export interface RemoteConsoleApi {
  key: string;
  isEnabled(): boolean;
  setEnabled(on: boolean, persist?: boolean): void;
  send(level: string, msg: string): boolean;
  stats(): { sent: number; dropped: number };
}

declare global {
  interface Window {
    __archieRemoteConsole?: RemoteConsoleApi;
  }
}

function api(): RemoteConsoleApi | undefined {
  return typeof window !== 'undefined' ? window.__archieRemoteConsole : undefined;
}

export function remoteLogDefault(): boolean {
  return __TARGET__ === 'compat';
}

export function isRemoteLogEnabled(): boolean {
  return api()?.isEnabled() ?? false;
}

/** Persists the pref (localStorage `archie.remoteConsole`) and applies it immediately. */
export function setRemoteLogEnabled(on: boolean): void {
  const rc = api();
  if (rc) {
    rc.setEnabled(on, true);
    return;
  }
  try {
    window.localStorage.setItem(REMOTE_CONSOLE_KEY, on ? '1' : '0');
  } catch {
    // ignore
  }
}

/** Sends one line regardless of the console-mirroring pref (still rate-limited). */
export function remoteLog(level: string, msg: string): boolean {
  const rc = api();
  if (rc) return rc.send(level, msg);
  try {
    const body = JSON.stringify({ level, msg, ts: new Date().toISOString() });
    return typeof navigator.sendBeacon === 'function' && navigator.sendBeacon(REMOTE_LOG_ENDPOINT, body);
  } catch {
    return false;
  }
}

export function remoteLogStats(): { sent: number; dropped: number } {
  return api()?.stats() ?? { sent: 0, dropped: 0 };
}
