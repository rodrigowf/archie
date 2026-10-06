/**
 * Pending session-action UI (W-11): which confirmation is open and whether a busy overlay covers
 * the app. Kept outside React so actions started from anywhere (the ⋮ menu, the ＋ New menu, the
 * composer's Resume bar, keyboard) drive one host (`SessionActionsHost`).
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';

/** The running orchestrator a conflict is about (spec 12 §6.11). */
export interface RunningArchie {
  readonly localId: string;
  readonly sdkId: string | null;
}

/**
 * The three-action conflict dialog (spec 12 §6.11, inv02 F-25): Open the running one / Stop it
 * and start new (or resume the requested one) / Cancel.
 *
 * - `new`: "New Archie conversation" while one is active.
 * - `resume`: a past Archie conversation was asked for while another one is active.
 * - `readOnlyId`: the read-only view (H-3) the request came from; it goes away once resumed.
 */
export interface ArchieConflict {
  readonly mode: 'new' | 'resume';
  readonly resumeSdkId: string | null;
  readonly running: RunningArchie;
  readonly readOnlyId?: string;
}

export type ConfirmKind = 'delete' | 'fork';

export interface SessionActionsState {
  /** Delete / fork confirmation for this `local_id`. */
  readonly confirm: { readonly kind: ConfirmKind; readonly localId: string } | null;
  readonly archieConflict: ArchieConflict | null;
  /** App-wide busy overlay label (inv02 F-12: "Deleting…", "Forking…", "Starting Archie…"). */
  readonly busy: string | null;
}

const INITIAL: SessionActionsState = { confirm: null, archieConflict: null, busy: null };

export const sessionActionsStore = createStore<SessionActionsState>(() => INITIAL);

export function useSessionActionsState<T>(selector: (s: SessionActionsState) => T): T {
  return useStore(sessionActionsStore, selector);
}

export function patchSessionActions(partial: Partial<SessionActionsState>): void {
  sessionActionsStore.setState(partial);
}

/** Tests. */
export function resetSessionActions(): void {
  sessionActionsStore.setState(INITIAL, true);
}

/** Run `fn` under the busy overlay (it always comes down, also on failure). */
export async function withBusy<T>(label: string, fn: () => Promise<T>): Promise<T> {
  patchSessionActions({ busy: label });
  try {
    return await fn();
  } finally {
    patchSessionActions({ busy: null });
  }
}
