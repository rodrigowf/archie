/**
 * Overlay stack (spec 13 §3.5 overlay contract, §4.5 Back closes overlays).
 *
 * Every open overlay (dialog, sheet, drawer, menu, popover) registers here. The stack gives:
 * - **Escape closes the topmost overlay only.** One document `keydown` listener; an overlay that
 *   opts out (`escape: false`, e.g. a blocking busy overlay) swallows Escape instead of letting it
 *   reach the overlay below. A handler that already called `preventDefault()` wins.
 * - **Browser Back / Android back gesture closes the topmost history overlay.** Overlays with
 *   `history: true` push one history entry (same URL, so the hash navigation of W-07 is not
 *   touched). `popstate` closes every history overlay above the new depth, topmost first.
 * - **No double pops.** Closing an overlay programmatically removes its history entry with a
 *   silent `history.go(-n)`; the resulting `popstate` is swallowed, and pushes requested while a
 *   silent pop is in flight wait for it (otherwise the async traversal would pop the new entry).
 *   Removals in one React commit are reconciled once, in a microtask.
 * - `isTop(id)`, used by focus traps and outside-press handlers so only the topmost layer reacts.
 */

export type OverlayCloseReason = 'escape' | 'back' | 'outside' | 'action' | 'swipe';

export interface OverlayOptions {
  /** Called when the stack wants this overlay closed. The owner sets its `open` state to false. */
  onClose: (reason: OverlayCloseReason) => void;
  /** Escape closes this overlay (default true). False: Escape is swallowed while it is on top. */
  escape?: boolean;
  /** Push a history entry so Back closes this overlay (default false). */
  history?: boolean;
}

export interface OverlayHandle {
  readonly id: number;
  isTop(): boolean;
  /** Replace the callbacks (keeps the stack position). */
  update(opts: Partial<Pick<OverlayOptions, 'onClose' | 'escape'>>): void;
  /** Unregister (on close or unmount). Idempotent. */
  remove(): void;
}

interface Entry {
  id: number;
  onClose: (reason: OverlayCloseReason) => void;
  escape: boolean;
  history: boolean;
  /** The browser entry for this overlay was already popped (by Back). */
  popped: boolean;
}

const STATE_KEY = '__archieOverlay';

const stack: Entry[] = [];
let nextId = 1;
let installed = false;
/** Depth of the overlay history entry the browser is on (0 = none of ours). */
let browserDepth = 0;
/** popstate events we caused ourselves and must ignore. */
let silentPops = 0;
/** Pushes deferred while a silent pop is in flight. */
let pendingPushes = 0;
let reconcileQueued = false;

function historyEntries(): Entry[] {
  return stack.filter((e) => e.history && !e.popped);
}

function stateDepth(state: unknown): number {
  if (state && typeof state === 'object' && STATE_KEY in state) {
    const v = (state as Record<string, unknown>)[STATE_KEY];
    return typeof v === 'number' ? v : 0;
  }
  return 0;
}

function pushEntry(): void {
  browserDepth += 1;
  const prev = window.history.state as Record<string, unknown> | null;
  const base = prev && typeof prev === 'object' ? prev : {};
  window.history.pushState({ ...base, [STATE_KEY]: browserDepth }, '');
}

function flushPendingPushes(): void {
  while (pendingPushes > 0 && silentPops === 0) {
    pendingPushes -= 1;
    pushEntry();
  }
}

function onKeyDown(e: KeyboardEvent): void {
  if (e.key !== 'Escape' && e.key !== 'Esc') return;
  if (e.defaultPrevented || e.isComposing) return;
  const top = stack[stack.length - 1];
  if (!top) return;
  e.preventDefault();
  if (top.escape) top.onClose('escape');
}

function onPopState(e: PopStateEvent): void {
  const depth = stateDepth(e.state);
  if (silentPops > 0) {
    silentPops -= 1;
    browserDepth = depth;
    flushPendingPushes();
    return;
  }
  browserDepth = depth;
  // Close history overlays above the new depth, topmost first.
  let open = historyEntries();
  while (open.length > depth) {
    const top = open[open.length - 1];
    if (!top) break;
    top.popped = true;
    top.onClose('back');
    open = historyEntries();
  }
}

function install(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;
  document.addEventListener('keydown', onKeyDown);
  window.addEventListener('popstate', onPopState);
}

/** Bring the browser history depth back to the number of open history overlays. */
function reconcile(): void {
  reconcileQueued = false;
  const want = historyEntries().length + pendingPushes;
  const extra = browserDepth - want;
  if (extra > 0) {
    // Our entries above `want` are stale: leave them silently.
    silentPops += 1;
    window.history.go(-extra);
  }
}

function queueReconcile(): void {
  if (reconcileQueued) return;
  reconcileQueued = true;
  void Promise.resolve().then(reconcile);
}

export function pushOverlay(opts: OverlayOptions): OverlayHandle {
  install();
  const entry: Entry = {
    id: nextId++,
    onClose: opts.onClose,
    escape: opts.escape !== false,
    history: opts.history === true,
    popped: false,
  };
  stack.push(entry);
  if (entry.history) {
    if (silentPops > 0) pendingPushes += 1;
    else pushEntry();
  }
  return {
    id: entry.id,
    isTop: () => stack[stack.length - 1] === entry,
    update(next) {
      if (next.onClose) entry.onClose = next.onClose;
      if (next.escape !== undefined) entry.escape = next.escape;
    },
    remove() {
      const i = stack.indexOf(entry);
      if (i < 0) return;
      stack.splice(i, 1);
      if (entry.history && !entry.popped) queueReconcile();
    },
  };
}

export const overlayStack = {
  push: pushOverlay,
  get size(): number {
    return stack.length;
  },
  isTop(id: number): boolean {
    return stack[stack.length - 1]?.id === id;
  },
  /** Ids bottom → top (diagnostics and tests). */
  ids(): number[] {
    return stack.map((e) => e.id);
  },
};

/** Test seam: forget all overlays and history bookkeeping. */
export function resetOverlayStackForTests(): void {
  stack.length = 0;
  browserDepth = stateDepth(typeof window !== 'undefined' ? window.history.state : null);
  silentPops = 0;
  pendingPushes = 0;
  reconcileQueued = false;
}
