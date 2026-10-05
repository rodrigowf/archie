/**
 * Momentum-safe scrolling for one scroll container (spec 13 §2.6 "Momentum scrolling").
 * Framework-free; <ScrollArea> owns one instance. Every programmatic scroll in the app goes through
 * here, which is what the never-wired compat shim (inv02 §5.3, §6.3 #13) was meant to do.
 *
 * Rules:
 * 1. iOS-safe assignment. With `-webkit-overflow-scrolling: touch`, iOS 12 ignores (or locks at 0)
 *    a `scrollTop` written during native momentum. So: set `webkitOverflowScrolling = 'auto'`
 *    (stops the native scroller), assign `scrollTop`, restore `'touch'` on the next frame.
 *    LOAD-BEARING inv02 §5.3 (frontend-compat/src/shims/MessageList.tsx:28-37, commit 5411f6f).
 * 2. Deferral. A programmatic scroll requested while a finger is down, or within QUIET_MS of the
 *    last user scroll event (momentum still running), waits until the touch ends and scroll events
 *    have been quiet for QUIET_MS. Repeated scroll requests collapse to the latest one; anchor
 *    operations run in order.
 * 3. Anchor preservation for prepends: record the first old item's offset (or the content height),
 *    hide the container for one frame, run the mutation, restore the offset. `overflow-anchor` is
 *    unsupported on Safari 12. LOAD-BEARING inv02 F-11 (frontend/src/components/MessageList.tsx:132-151)
 *    and inv02 §5.3 (frontend-compat/src/shims/MessageList.tsx:39-46).
 */

export const QUIET_MS = 100;
export const NEAR_BOTTOM_PX = 150;

export interface ScrollEnv {
  now(): number;
  raf(cb: () => void): number;
  cancelRaf(id: number): void;
  setTimeout(cb: () => void, ms: number): number;
  clearTimeout(id: number): void;
}

export const browserScrollEnv: ScrollEnv = {
  now: () => Date.now(),
  raf: (cb) => window.requestAnimationFrame(cb),
  cancelRaf: (id) => {
    window.cancelAnimationFrame(id);
  },
  setTimeout: (cb, ms) => window.setTimeout(cb, ms),
  clearTimeout: (id) => {
    window.clearTimeout(id);
  },
};

export type AnchorRef = Element | null | undefined | (() => Element | null | undefined);

export interface PreserveAnchorOptions {
  /**
   * The first item that existed before the mutation (e.g. the first old message). Its offset from
   * the container top is kept. Without it, the growth of `scrollHeight` is added to `scrollTop`,
   * which is exact when content is only inserted above.
   */
  anchor?: AnchorRef;
}

type IosStyle = CSSStyleDeclaration & { webkitOverflowScrolling?: string };

interface Task {
  kind: 'scroll' | 'anchor';
  run: () => void;
  resolve: () => void;
}

export class ScrollController {
  private el: HTMLElement | null = null;
  private touching = false;
  private lastUserActivity = Number.NEGATIVE_INFINITY;
  private expectedTop: number | null = null;
  private queue: Task[] = [];
  private quietTimer: number | null = null;
  private restoreRaf: number | null = null;
  private unhideRaf: number | null = null;
  private readonly env: ScrollEnv;

  constructor(env: ScrollEnv = browserScrollEnv) {
    this.env = env;
  }

  get element(): HTMLElement | null {
    return this.el;
  }

  /** Starts tracking `el`. Returns a detach function. */
  attach(el: HTMLElement): () => void {
    this.detach();
    this.el = el;
    el.addEventListener('scroll', this.onScroll, { passive: true });
    el.addEventListener('touchstart', this.onTouchStart, { passive: true });
    el.addEventListener('touchend', this.onTouchEnd, { passive: true });
    el.addEventListener('touchcancel', this.onTouchEnd, { passive: true });
    return () => {
      if (this.el === el) this.detach();
    };
  }

  detach(): void {
    const el = this.el;
    if (el) {
      el.removeEventListener('scroll', this.onScroll);
      el.removeEventListener('touchstart', this.onTouchStart);
      el.removeEventListener('touchend', this.onTouchEnd);
      el.removeEventListener('touchcancel', this.onTouchEnd);
    }
    if (this.quietTimer !== null) this.env.clearTimeout(this.quietTimer);
    if (this.restoreRaf !== null) this.env.cancelRaf(this.restoreRaf);
    if (this.unhideRaf !== null) this.env.cancelRaf(this.unhideRaf);
    if (el && this.unhideRaf !== null) el.style.visibility = '';
    this.quietTimer = this.restoreRaf = this.unhideRaf = null;
    const pending = this.queue;
    this.queue = [];
    for (const t of pending) t.resolve(); // nothing to scroll any more; never leave callers hanging
    this.el = null;
    this.touching = false;
    this.expectedTop = null;
  }

  /** True while a finger is down or momentum is (probably) still running. */
  isUserScrolling(): boolean {
    return this.touching || this.env.now() - this.lastUserActivity < QUIET_MS;
  }

  isNearBottom(px: number = NEAR_BOTTOM_PX): boolean {
    const el = this.el;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= px;
  }

  distanceFromBottom(): number {
    const el = this.el;
    return el ? Math.max(0, el.scrollHeight - el.scrollTop - el.clientHeight) : 0;
  }

  scrollToBottom(): Promise<void> {
    return this.enqueue('scroll', () => {
      const el = this.el;
      if (el) this.assign(el.scrollHeight);
    });
  }

  scrollTo(top: number): Promise<void> {
    return this.enqueue('scroll', () => {
      this.assign(top);
    });
  }

  /**
   * Runs `mutate` (which must update the DOM synchronously: in React, wrap the state update in
   * `flushSync`) and keeps the anchor where it was on screen.
   */
  preserveAnchor(mutate: () => void, opts: PreserveAnchorOptions = {}): Promise<void> {
    return this.enqueue('anchor', () => {
      const el = this.el;
      if (!el) {
        mutate();
        return;
      }
      const anchor = resolveAnchor(opts.anchor);
      const beforeHeight = el.scrollHeight;
      const beforeTop = el.scrollTop;
      const beforeOffset = anchor ? offsetWithin(anchor, el) : 0;
      el.style.visibility = 'hidden';
      try {
        mutate();
      } finally {
        const target =
          anchor && anchor.isConnected
            ? el.scrollTop + (offsetWithin(anchor, el) - beforeOffset)
            : beforeTop + (el.scrollHeight - beforeHeight);
        this.assign(target);
        if (this.unhideRaf !== null) this.env.cancelRaf(this.unhideRaf);
        this.unhideRaf = this.env.raf(() => {
          this.unhideRaf = null;
          el.style.visibility = '';
        });
      }
    });
  }

  // ---------------------------------------------------------------------------------------------

  private readonly onScroll = (): void => {
    const el = this.el;
    if (!el) return;
    if (this.expectedTop !== null && Math.abs(el.scrollTop - this.expectedTop) <= 1) {
      this.expectedTop = null; // the event our own assignment caused
      return;
    }
    this.expectedTop = null;
    this.lastUserActivity = this.env.now();
    this.scheduleFlush();
  };

  private readonly onTouchStart = (): void => {
    this.touching = true;
    if (this.quietTimer !== null) {
      this.env.clearTimeout(this.quietTimer);
      this.quietTimer = null;
    }
  };

  private readonly onTouchEnd = (): void => {
    this.touching = false;
    this.lastUserActivity = this.env.now();
    this.scheduleFlush();
  };

  private enqueue(kind: Task['kind'], run: () => void): Promise<void> {
    return new Promise<void>((resolve) => {
      if (!this.isUserScrolling() && this.queue.length === 0) {
        run();
        resolve();
        return;
      }
      if (kind === 'scroll') {
        // A newer scroll request supersedes older ones; their promises settle when it runs.
        const superseded = this.queue.filter((t) => t.kind === 'scroll');
        this.queue = this.queue.filter((t) => t.kind !== 'scroll');
        this.queue.push({
          kind,
          run,
          resolve: () => {
            for (const t of superseded) t.resolve();
            resolve();
          },
        });
      } else {
        this.queue.push({ kind, run, resolve });
      }
      this.scheduleFlush();
    });
  }

  private scheduleFlush(): void {
    if (this.queue.length === 0 || this.touching) return;
    if (this.quietTimer !== null) this.env.clearTimeout(this.quietTimer);
    this.quietTimer = null;
    const wait = QUIET_MS - (this.env.now() - this.lastUserActivity);
    if (wait <= 0) {
      this.flush();
      return;
    }
    this.quietTimer = this.env.setTimeout(() => {
      this.quietTimer = null;
      this.scheduleFlush();
    }, wait);
  }

  private flush(): void {
    const tasks = this.queue;
    this.queue = [];
    for (const t of tasks) {
      try {
        t.run();
      } finally {
        t.resolve();
      }
    }
  }

  /** iOS-safe scrollTop assignment (rule 1). */
  private assign(top: number): void {
    const el = this.el;
    if (!el) return;
    const max = Math.max(0, el.scrollHeight - el.clientHeight);
    const target = Math.min(Math.max(0, Math.round(top)), max);
    const style = el.style as IosStyle;
    style.webkitOverflowScrolling = 'auto';
    el.scrollTop = target;
    this.expectedTop = el.scrollTop;
    if (this.restoreRaf !== null) this.env.cancelRaf(this.restoreRaf);
    this.restoreRaf = this.env.raf(() => {
      this.restoreRaf = null;
      style.webkitOverflowScrolling = 'touch';
      // The scroll event of our own assignment fires before this frame's rAF callbacks.
      this.expectedTop = null;
    });
  }
}

function resolveAnchor(anchor: AnchorRef): Element | null {
  const el = typeof anchor === 'function' ? anchor() : anchor;
  return el ?? null;
}

/** Offset of `child`'s top from the scroll container's visible top edge. */
function offsetWithin(child: Element, container: Element): number {
  return child.getBoundingClientRect().top - container.getBoundingClientRect().top;
}
