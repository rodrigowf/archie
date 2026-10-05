/**
 * Drives the bounded message window (messageWindow.ts) from the session store and the scroll
 * container (spec 13 §5.1 item 5, §5.2).
 *
 * - The rendered slice is a small external store (`WindowController`, read with
 *   `useSyncExternalStore`) fed by a direct session-store subscription, outside React rendering.
 *   So an update that inserts entries above the first rendered one can run as
 *   `ScrollArea.preserveAnchor(() => flushSync(commit))`: the one anchor-restore path for both
 *   re-expansion from memory and REST prepends (**[LOAD-BEARING]** inv02 F-11,
 *   frontend/src/components/MessageList.tsx:132-151; compat shim
 *   frontend-compat/src/shims/MessageList.tsx:39-46).
 * - Pinned (within 150 px of the bottom): new content keeps the list at the bottom. Keyed on the
 *   content **height** (ResizeObserver on the content), not on a block count, so a growing
 *   streamed reply stays in view (fixes inv02 §6.2, MessageList.tsx:153-160).
 * - Not pinned: the freeze buffer holds new entries back (F-11, MessageList.tsx:36-90) until the
 *   user returns near the bottom or presses "Jump to latest".
 * - `scrollTop <= 80 px`: re-expand the slice from memory first, then page from REST (H-4,
 *   MessageList.tsx:92-100).
 * - Tab activation: on hidden → visible, scroll to the bottom if pinned, otherwise re-run the scroll
 *   handler (F-11, MessageList.tsx:117-129).
 */
import { useEffect, useLayoutEffect, useState, useSyncExternalStore, type RefObject } from 'react';
import { flushSync } from 'react-dom';
import type { Entry } from '@/protocol';
import type { SessionStore } from '@/stores';
import type { ScrollAreaHandle } from '@/ui/primitives';
import {
  expandUp,
  freeze,
  LOAD_MORE_PX,
  NEAR_BOTTOM_PX,
  normalizeWindow,
  OPEN_WINDOW,
  prependAnchor,
  trimToCap,
  unfreeze,
  viewOf,
  type WindowState,
  type WindowView,
} from './messageWindow';

export interface UseMessageWindowOptions {
  readonly store: SessionStore;
  readonly area: RefObject<ScrollAreaHandle | null>;
  /** The element whose height is the content height (observed for auto-scroll). */
  readonly content: RefObject<HTMLElement | null>;
  readonly cap: number;
  readonly hidden: boolean;
  /** More history exists on the server and no page is loading. */
  readonly canLoadOlder: boolean;
  readonly loadOlder: () => void;
}

export interface MessageWindow {
  readonly view: WindowView;
  /** Within 150 px of the bottom. */
  readonly pinned: boolean;
  /** Attach to the scroll container's `onScroll`. */
  readonly onScroll: () => void;
  /** Flush the buffer and go to the bottom. */
  readonly jumpToLatest: () => void;
}

export interface WindowSnapshot {
  readonly view: WindowView;
  readonly pinned: boolean;
}

/** The DOM node of a rendered entry (`data-entry-id`). */
export function findEntryElement(root: HTMLElement | null | undefined, id: string): HTMLElement | null {
  if (!root) return null;
  const nodes = root.querySelectorAll<HTMLElement>('[data-entry-id]');
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i] as HTMLElement;
    if (n.getAttribute('data-entry-id') === id) return n;
  }
  return null;
}

function sameView(a: WindowView, b: WindowView): boolean {
  if (a.buffered !== b.buffered || a.hiddenAbove !== b.hiddenAbove || a.entries.length !== b.entries.length) return false;
  for (let i = 0; i < a.entries.length; i++) if (a.entries[i] !== b.entries[i]) return false;
  return true;
}

type ControllerOptions = Omit<UseMessageWindowOptions, 'store'>;

/** The window model + its scroll behaviour, outside React. One per mounted list. */
export class WindowController {
  private entries: readonly Entry[];
  private w: WindowState;
  /** The view the DOM shows (or is about to show). */
  private snap: WindowSnapshot;
  /** The latest computed view (ahead of `snap` while an anchored update waits). */
  private latest: WindowView;
  private anchoring = false;
  private readonly listeners = new Set<() => void>();
  private opts: ControllerOptions | null = null;

  constructor(entries: readonly Entry[], cap: number) {
    this.entries = entries;
    this.w = trimToCap(entries, OPEN_WINDOW, cap);
    this.latest = viewOf(entries, this.w, cap);
    this.snap = { view: this.latest, pinned: true };
  }

  setOptions(o: ControllerOptions): void {
    this.opts = o;
  }

  readonly subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  };

  readonly getSnapshot = (): WindowSnapshot => this.snap;

  get pinned(): boolean {
    return this.snap.pinned;
  }

  private emit(next: WindowSnapshot): void {
    if (next.view === this.snap.view && next.pinned === this.snap.pinned) return;
    this.snap = next;
    for (const fn of Array.from(this.listeners)) fn();
  }

  private get cap(): number {
    return this.opts?.cap ?? 200;
  }

  setEntries(entries: readonly Entry[]): void {
    if (entries === this.entries) return;
    this.entries = entries;
    this.publish();
  }

  /** Recompute the slice and render it; anchored when entries appear above the first rendered one. */
  publish(): void {
    const cap = this.cap;
    this.w = normalizeWindow(this.entries, this.w, cap);
    if (this.snap.pinned) this.w = trimToCap(this.entries, this.w, cap);
    const next = viewOf(this.entries, this.w, cap);
    if (sameView(this.latest, next)) return;
    const anchorId = prependAnchor(this.snap.view, next);
    this.latest = next;
    if (this.anchoring) return; // the pending anchored render applies the latest view
    const handle = this.opts?.area.current;
    if (anchorId === null || !handle || !handle.element) {
      this.emit({ view: next, pinned: this.snap.pinned });
      return;
    }
    this.anchoring = true;
    // Not inside React's render or commit (flushSync is not allowed there): a microtask later,
    // still before the browser paints.
    void Promise.resolve()
      .then(() =>
        handle.preserveAnchor(
          () => {
            flushSync(() => {
              this.emit({ view: this.latest, pinned: this.snap.pinned });
            });
          },
          { anchor: () => findEntryElement(handle.element, anchorId) },
        ),
      )
      .then(() => {
        this.anchoring = false;
        this.emit({ view: this.latest, pinned: this.snap.pinned });
      });
  }

  /** Keep a pinned list at the bottom. */
  pinToBottom(): void {
    const o = this.opts;
    if (o && !o.hidden && this.snap.pinned) void o.area.current?.scrollToBottom();
  }

  /** At the top: re-expand from memory, else page from REST (H-4). */
  maybeLoadOlder(): void {
    const o = this.opts;
    const el = o?.area.current?.element;
    if (!o || !el || o.hidden || el.scrollTop > LOAD_MORE_PX) return;
    if (this.latest.start > 0) {
      this.w = expandUp(this.entries, this.w, this.cap);
      this.publish();
    } else if (o.canLoadOlder) {
      o.loadOlder();
    }
  }

  /** After a render: stay at the bottom while pinned; a screen that is not full loads more. */
  afterRender(): void {
    this.pinToBottom();
    const el = this.opts?.area.current?.element;
    if (el && el.scrollHeight > 0 && el.scrollHeight <= el.clientHeight + LOAD_MORE_PX) this.maybeLoadOlder();
  }

  readonly onScroll = (): void => {
    const o = this.opts;
    const handle = o?.area.current;
    if (!o || !handle || o.hidden) return;
    const near = handle.isNearBottom(NEAR_BOTTOM_PX);
    if (near !== this.snap.pinned) {
      if (near) {
        this.w = unfreeze(this.w);
        this.emit({ view: this.snap.view, pinned: true });
        this.publish();
      } else {
        this.w = freeze(this.entries, this.w, this.cap);
        this.emit({ view: this.snap.view, pinned: false });
      }
    }
    this.maybeLoadOlder();
  };

  readonly jumpToLatest = (): void => {
    this.w = unfreeze(this.w);
    this.emit({ view: this.snap.view, pinned: true });
    this.publish();
    void this.opts?.area.current?.scrollToBottom();
  };

  /** Tab activation (F-11). */
  shown(): void {
    if (this.snap.pinned) void this.opts?.area.current?.scrollToBottom();
    else this.onScroll();
  }
}

export function useMessageWindow(o: UseMessageWindowOptions): MessageWindow {
  const [ctl] = useState(() => new WindowController(o.store.getState().conv.entries, o.cap));
  // Options first (layout effects run in order, before the passive ones below).
  useLayoutEffect(() => {
    ctl.setOptions({ area: o.area, content: o.content, cap: o.cap, hidden: o.hidden, canLoadOlder: o.canLoadOlder, loadOlder: o.loadOlder });
  });
  const snap = useSyncExternalStore(ctl.subscribe, ctl.getSnapshot, ctl.getSnapshot);

  // Entries from the store (outside React rendering; the store is frozen while hidden, W-06).
  const { store } = o;
  useEffect(() => {
    ctl.setEntries(store.getState().conv.entries);
    return store.subscribe((s) => {
      ctl.setEntries(s.conv.entries);
    });
  }, [store, ctl]);

  useLayoutEffect(() => {
    ctl.afterRender();
  }, [snap.view, ctl]);

  // Content height changes (streaming text, an expanded card): keep pinned lists at the bottom.
  const { content } = o;
  useEffect(() => {
    const el = content.current;
    if (!el || typeof ResizeObserver !== 'function') return;
    const ro = new ResizeObserver(() => {
      ctl.pinToBottom();
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
    };
  }, [content, ctl]);

  const { hidden } = o;
  useEffect(() => {
    if (!hidden) ctl.shown();
  }, [hidden, ctl]);

  return { view: snap.view, pinned: snap.pinned, onScroll: ctl.onScroll, jumpToLatest: ctl.jumpToLatest };
}
