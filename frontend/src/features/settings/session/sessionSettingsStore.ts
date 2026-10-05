/** Which session's settings sheet is open (one at a time; opened from the ⋮ menu). */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';

export interface SessionSettingsTarget {
  readonly localId: string | null;
}

export const sessionSettingsStore = createStore<SessionSettingsTarget>(() => ({ localId: null }));

export function openSessionSettings(localId: string): void {
  sessionSettingsStore.setState({ localId });
}

export function closeSessionSettings(): void {
  sessionSettingsStore.setState({ localId: null });
}

export function useSessionSettingsTarget(): string | null {
  return useStore(sessionSettingsStore, (s) => s.localId);
}
