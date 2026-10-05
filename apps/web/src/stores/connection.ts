/**
 * Backend reachability for the app-level connection indicator (spec 13 §3.3). Fed by the HTTP
 * client (network failures vs. any HTTP answer) and by socket opens/closes.
 */
import { createStore } from 'zustand/vanilla';

export type Reachability = 'unknown' | 'online' | 'offline';

export interface ConnectionState {
  readonly backend: Reachability;
  /** Human message of the last transport failure. */
  readonly lastError: string | null;
  readonly since: number;
}

export const connectionStore = createStore<ConnectionState>(() => ({ backend: 'unknown', lastError: null, since: 0 }));

export function markBackendOnline(now = Date.now()): void {
  if (connectionStore.getState().backend !== 'online') connectionStore.setState({ backend: 'online', lastError: null, since: now });
}

export function markBackendOffline(error: string, now = Date.now()): void {
  const s = connectionStore.getState();
  if (s.backend !== 'offline' || s.lastError !== error)
    connectionStore.setState({ backend: 'offline', lastError: error, since: s.backend === 'offline' ? s.since : now });
}

export function resetConnection(): void {
  connectionStore.setState({ backend: 'unknown', lastError: null, since: 0 });
}
