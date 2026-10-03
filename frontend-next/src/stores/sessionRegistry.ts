/**
 * Map `local_id → { store, runtime }` (spec 13 §3.3, §4.4). Runtimes live here, outside React,
 * so connections survive re-renders, layout changes and panel visibility changes; an entry is
 * removed only when its tab is closed (explicitly) or the app tears down.
 *
 * The registry is typed against `RuntimeHandle` so stores never import services; services read
 * their concrete runtime back through `getSessionRuntime` (`@/services`).
 */
import { createStore } from 'zustand/vanilla';
import type { SessionStoreHandle } from './sessionStore';

/** What the registry needs from a runtime. */
export interface RuntimeHandle {
  readonly localId: string;
  /** Close the transport and stop timers. MUST NOT send `stop`/`close` (P-1). */
  dispose(): void;
}

export interface SessionEntry {
  readonly handle: SessionStoreHandle;
  readonly runtime: RuntimeHandle;
}

const entries = new Map<string, SessionEntry>();

/** Bumps on every add/remove/rekey so React lists of open sessions can subscribe. */
export const sessionRegistryVersion = createStore<{ version: number }>(() => ({ version: 0 }));
const bump = (): void => sessionRegistryVersion.setState((s) => ({ version: s.version + 1 }));

export function registerSession(localId: string, entry: SessionEntry): void {
  const prev = entries.get(localId);
  if (prev && prev !== entry) prev.runtime.dispose();
  entries.set(localId, entry);
  bump();
}

export function getSessionEntry(localId: string): SessionEntry | undefined {
  return entries.get(localId);
}

export function listSessionEntries(): SessionEntry[] {
  return Array.from(entries.values());
}

export function listSessionIds(): string[] {
  return Array.from(entries.keys());
}

/** ID-1: the server adopted a different `local_id`. */
export function rekeySession(oldId: string, newId: string): void {
  const e = entries.get(oldId);
  if (!e || oldId === newId) return;
  entries.delete(oldId);
  entries.set(newId, e);
  e.handle.rekey(newId);
  bump();
}

/** Remove (and dispose) one entry. `forget` also drops its persisted draft (explicit close). */
export function removeSession(localId: string, forget = false): void {
  const e = entries.get(localId);
  if (!e) return;
  entries.delete(localId);
  e.runtime.dispose();
  e.handle.dispose(forget);
  bump();
}

/** Teardown (tests, page unload): disposes every runtime without closing any session (P-1). */
export function clearSessionRegistry(): void {
  for (const id of listSessionIds()) removeSession(id, false);
}
