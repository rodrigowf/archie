/**
 * Always-live per-session status for the tab strip, the switcher and the history "Open now"
 * list (W-07 request). The per-session store's render snapshot is frozen while its panel is
 * hidden (spec 13 §4.4); this tiny summary is **not** frozen: runtimes publish it on every
 * step, and it changes (and notifies) only when one of its fields changes, so a streaming
 * delta costs one shallow compare and no render.
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import { busy, type ConnState, type Conversation, type Entry, type SessionStatus } from '@/protocol';

export interface TabLiveStatus {
  readonly status: SessionStatus;
  readonly conn: ConnState;
  /** A turn is running (busy status or in turn). */
  readonly busy: boolean;
  /** A permission prompt waits for an answer (the tab needs the user). */
  readonly permissionPending: boolean;
  /** `session_stalled` is showing. */
  readonly stalled: boolean;
  /** Socket down (reconnecting). */
  readonly disconnected: boolean;
  /**
   * Something the user must see: a failed start (`connectionBanner` code), a termination
   * reason, or `null`.
   */
  readonly error: string | null;
  /** Voice is live on some device (orchestrator). */
  readonly voiceActive: boolean;
}

export interface LiveStatusState {
  readonly byId: Readonly<Record<string, TabLiveStatus>>;
}

export const liveStatusStore = createStore<LiveStatusState>(() => ({ byId: {} }));

/** Pending permissions live in the tail run of a turn in flight (endTurn resolves the rest). */
const PERMISSION_SCAN_ENTRIES = 3;

function hasPendingPermission(entries: readonly Entry[]): boolean {
  for (let i = entries.length - 1, n = 0; i >= 0 && n < PERMISSION_SCAN_ENTRIES; i--, n++) {
    const e = entries[i];
    if (e && e.kind === 'assistant') for (const b of e.blocks) if (b.type === 'permission' && b.state === 'pending') return true;
  }
  return false;
}

export function deriveLiveStatus(c: Conversation): TabLiveStatus {
  let error: string | null = null;
  if (c.termination) error = c.termination.reason;
  else if (c.conn === 'failed' && c.connectionBanner) error = c.connectionBanner.code;
  return {
    status: c.status,
    conn: c.conn,
    busy: c.inTurn || busy(c.status),
    permissionPending: hasPendingPermission(c.entries),
    stalled: c.stall !== null,
    disconnected: c.conn === 'offline',
    error,
    voiceActive: c.voiceActive,
  };
}

function without(byId: Readonly<Record<string, TabLiveStatus>>, id: string): Record<string, TabLiveStatus> {
  const out: Record<string, TabLiveStatus> = {};
  for (const k of Object.keys(byId)) if (k !== id) out[k] = byId[k] as TabLiveStatus;
  return out;
}

function same(a: TabLiveStatus, b: TabLiveStatus): boolean {
  return (
    a.status === b.status &&
    a.conn === b.conn &&
    a.busy === b.busy &&
    a.permissionPending === b.permissionPending &&
    a.stalled === b.stalled &&
    a.disconnected === b.disconnected &&
    a.error === b.error &&
    a.voiceActive === b.voiceActive
  );
}

/** Called by the runtime after every step. Notifies only on a real change. */
export function publishLiveStatus(localId: string, conv: Conversation): void {
  const next = deriveLiveStatus(conv);
  const prev = liveStatusStore.getState().byId[localId];
  if (prev && same(prev, next)) return;
  liveStatusStore.setState((s) => ({ byId: { ...s.byId, [localId]: next } }));
}

export function removeLiveStatus(localId: string): void {
  if (!(localId in liveStatusStore.getState().byId)) return;
  liveStatusStore.setState((s) => ({ byId: without(s.byId, localId) }));
}

export function rekeyLiveStatus(oldId: string, newId: string): void {
  const cur = liveStatusStore.getState().byId[oldId];
  if (!cur || oldId === newId) return;
  liveStatusStore.setState((s) => ({ byId: { ...without(s.byId, oldId), [newId]: cur } }));
}

export function getLiveStatus(localId: string): TabLiveStatus | undefined {
  return liveStatusStore.getState().byId[localId];
}

/**
 * The live status of one tab (chat tabs; `undefined` for doc tabs and unknown ids). Re-renders
 * only when that tab's summary changes, also while its panel is hidden.
 */
export function useTabLiveStatus(tabId: string): TabLiveStatus | undefined {
  return useStore(liveStatusStore, (s) => s.byId[tabId]);
}
