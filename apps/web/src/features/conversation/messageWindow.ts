/**
 * The bounded message window (spec 13 §5.2): which slice `[start, end)` of a conversation's
 * entries is rendered. Pure and framework-free; `useMessageWindow` drives it from scroll events.
 *
 * The window is kept by entry **id**, not by index, so a prepended history page (§5.3) or a voice
 * transcript inserted at its anchor (spec 12 I-9) never moves what is on screen:
 *
 * - `startId`: the first rendered entry (null = the first entry). Trimming at the bottom moves it
 *   down; scrolling to the top of the slice moves it up again (re-expand from memory) before any
 *   REST page is fetched.
 * - `frozenTailId`: the **freeze buffer** (**[LOAD-BEARING]** inv02 F-11, commit 4624a5b,
 *   frontend/src/components/MessageList.tsx:36-90). While the user is scrolled up, entries after
 *   this one are buffered, not rendered, so the viewport never moves while reading. Entries that
 *   are already rendered keep updating in place (a tool result, R7, still shows up); prepends
 *   always show (MessageList.tsx:48-52).
 *
 * If an id disappears (canonical reload replaced every entry, §5.6), the window falls back to the
 * tail and unfreezes.
 */
import type { Entry } from '@/protocol';

/** Scrolled within this distance of the bottom = "at the bottom" (pinned). LOAD-BEARING inv02 F-11 (MessageList.tsx:16). */
export const NEAR_BOTTOM_PX = 150;
/** `scrollTop` at or below this loads more above. LOAD-BEARING inv02 F-11 (MessageList.tsx:17), spec 12 H-4. */
export const LOAD_MORE_PX = 80;
/** Max rendered entries (spec 13 §5.2). */
export const WINDOW_CAP_MAIN = 200;
export const WINDOW_CAP_LOW_END = 80;
/** Entries re-expanded from memory per step when the user reaches the top of the slice. */
export const EXPAND_STEP = 50;

export interface WindowState {
  readonly startId: string | null;
  readonly frozenTailId: string | null;
}

export interface WindowView {
  /** The rendered slice. */
  readonly entries: readonly Entry[];
  readonly start: number;
  readonly end: number;
  /** Entries buffered behind the freeze (shown on the jump-to-latest control). */
  readonly buffered: number;
  /** Entries above the slice that are in memory (re-expandable without REST). */
  readonly hiddenAbove: number;
}

export const OPEN_WINDOW: WindowState = { startId: null, frozenTailId: null };

function indexOfId(entries: readonly Entry[], id: string): number {
  for (let i = entries.length - 1; i >= 0; i--) if ((entries[i] as Entry).id === id) return i;
  return -1;
}

/** The slice for `entries` under window `w` (cap applies only when the start id is gone). */
export function viewOf(entries: readonly Entry[], w: WindowState, cap: number): WindowView {
  let end = entries.length;
  if (w.frozenTailId !== null) {
    const i = indexOfId(entries, w.frozenTailId);
    if (i >= 0) end = i + 1;
  }
  let start = 0;
  if (w.startId !== null) {
    const i = indexOfId(entries, w.startId);
    start = i >= 0 ? i : Math.max(0, end - cap);
  }
  if (start > end) start = Math.max(0, end - cap);
  return { entries: entries.slice(start, end), start, end, buffered: entries.length - end, hiddenAbove: start };
}

/** Repair ids that no longer exist (canonical reload): back to the tail, unfrozen. */
export function normalizeWindow(entries: readonly Entry[], w: WindowState, cap: number): WindowState {
  const frozenOk = w.frozenTailId === null || indexOfId(entries, w.frozenTailId) >= 0;
  const startOk = w.startId === null || indexOfId(entries, w.startId) >= 0;
  if (frozenOk && startOk) return w;
  const next: WindowState = { startId: startOk ? w.startId : null, frozenTailId: frozenOk ? w.frozenTailId : null };
  return startOk ? next : trimToCap(entries, next, cap);
}

/** At the bottom: keep at most `cap` entries by advancing the start (spec 13 §5.2). */
export function trimToCap(entries: readonly Entry[], w: WindowState, cap: number): WindowState {
  const v = viewOf(entries, w, cap);
  if (v.end - v.start <= cap) return w;
  const first = entries[v.end - cap];
  return first ? { ...w, startId: first.id } : w;
}

/** The user scrolled up: freeze after the last rendered entry. */
export function freeze(entries: readonly Entry[], w: WindowState, cap: number): WindowState {
  if (w.frozenTailId !== null) return w;
  const v = viewOf(entries, w, cap);
  const last = v.entries[v.entries.length - 1];
  return last ? { ...w, frozenTailId: last.id } : w;
}

/** Back at the bottom (or "jump to latest"): flush the buffer. */
export function unfreeze(w: WindowState): WindowState {
  return w.frozenTailId === null ? w : { ...w, frozenTailId: null };
}

/** Top of the slice reached with entries above it in memory: show up to `step` more. */
export function expandUp(entries: readonly Entry[], w: WindowState, cap: number, step: number = EXPAND_STEP): WindowState {
  const v = viewOf(entries, w, cap);
  if (v.start <= 0) return w;
  const s = Math.max(0, v.start - step);
  return { ...w, startId: s === 0 ? null : (entries[s] as Entry).id };
}

/**
 * Does going from `prev` to `next` insert entries **above** the first rendered entry while it
 * stays rendered? Then the update must keep that entry where it is on screen (anchor restore).
 * Returns the anchor id, or null for an ordinary update.
 */
export function prependAnchor(prev: WindowView, next: WindowView): string | null {
  const first = prev.entries[0];
  if (!first) return null;
  const i = indexOfId(next.entries, first.id);
  return i > 0 ? first.id : null;
}
