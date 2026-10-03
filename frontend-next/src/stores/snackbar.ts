/**
 * Snackbar queue (spec 13 §3.3), rendered by the W-04 `SnackbarHost`. Messages are shown
 * verbatim: callers pass the backend `detail`, never a generic "400 Bad Request" (W-6.2).
 */
import { createStore } from 'zustand/vanilla';

export interface SnackbarAction {
  readonly label: string;
  readonly run: () => void;
}

export interface SnackbarItem {
  readonly id: number;
  readonly message: string;
  readonly action?: SnackbarAction;
  /** ms; 0 = until dismissed. */
  readonly durationMs: number;
  readonly tone: 'info' | 'error';
}

export interface SnackbarState {
  readonly queue: readonly SnackbarItem[];
}

export const snackbarStore = createStore<SnackbarState>(() => ({ queue: [] }));

let nextId = 1;
export const SNACKBAR_DEFAULT_MS = 4000;
const MAX_QUEUE = 5;

export interface ShowSnackbarOptions {
  action?: SnackbarAction;
  durationMs?: number;
  tone?: 'info' | 'error';
}

export function showSnackbar(message: string, opts: ShowSnackbarOptions = {}): number {
  const id = nextId++;
  const item: SnackbarItem = {
    id,
    message,
    durationMs: opts.durationMs ?? (opts.action ? 8000 : SNACKBAR_DEFAULT_MS),
    tone: opts.tone ?? 'info',
    ...(opts.action ? { action: opts.action } : {}),
  };
  snackbarStore.setState((s) => ({ queue: s.queue.concat([item]).slice(-MAX_QUEUE) }));
  return id;
}

export function dismissSnackbar(id: number): void {
  snackbarStore.setState((s) => ({ queue: s.queue.filter((x) => x.id !== id) }));
}

export function clearSnackbars(): void {
  snackbarStore.setState({ queue: [] });
}
