/**
 * Shell UI state (W-07): which shell overlays are open and which shell dialog is pending. Kept
 * outside React so keyboard bindings, slots and tests drive the same state. Nothing here is
 * persisted; the list-pane collapse and the last rail destination are device prefs (`@/stores`).
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';

export type ListDestination = 'chats' | 'memory' | 'visuals';

export interface ShellState {
  /** Compact: the navigation drawer. */
  readonly drawerOpen: boolean;
  /** Compact: the session switcher sheet. */
  readonly switcherOpen: boolean;
  /** Medium: the list pane as a modal side sheet next to the rail. */
  readonly listOverlayOpen: boolean;
  /** Tab waiting for the close-while-running / stop-Archie confirmation. */
  readonly confirmCloseId: string | null;
  /** Tab whose rename dialog is open. */
  readonly renameId: string | null;
}

const INITIAL: ShellState = {
  drawerOpen: false,
  switcherOpen: false,
  listOverlayOpen: false,
  confirmCloseId: null,
  renameId: null,
};

export const shellStore = createStore<ShellState>(() => INITIAL);

export function useShell<T>(selector: (s: ShellState) => T): T {
  return useStore(shellStore, selector);
}

export function setShell(partial: Partial<ShellState>): void {
  shellStore.setState(partial);
}

/** Close every shell overlay (navigation happened, or the window class changed). */
export function closeShellOverlays(): void {
  const s = shellStore.getState();
  if (s.drawerOpen || s.switcherOpen || s.listOverlayOpen) shellStore.setState({ drawerOpen: false, switcherOpen: false, listOverlayOpen: false });
}

/** Tests. */
export function resetShell(): void {
  shellStore.setState(INITIAL, true);
}
