/**
 * Delta coalescing (spec 13 §3.3, §5.1): session runtimes apply every event to the reducer at
 * once, but React subscribers are notified at most once per animation frame (main) or every
 * 100 ms (low-end). The scheduler is injectable so tests can flush frames by hand.
 */
import { isLowEnd } from '@/platform';

export interface FrameScheduler {
  /** Run `fn` at the next publish slot. Returns a cancel function. */
  schedule(fn: () => void): () => void;
}

export const LOW_END_PUBLISH_MS = 100;

/** requestAnimationFrame on main; a 100 ms timer on low-end devices (or without rAF). */
export function createFrameScheduler(lowEnd: () => boolean = isLowEnd): FrameScheduler {
  return {
    schedule(fn) {
      if (!lowEnd() && typeof requestAnimationFrame === 'function') {
        const id = requestAnimationFrame(() => fn());
        return () => cancelAnimationFrame(id);
      }
      const t = setTimeout(fn, lowEnd() ? LOW_END_PUBLISH_MS : 16);
      return () => clearTimeout(t);
    },
  };
}

/** A scheduler whose frames run only when the test calls `flush()`. */
export interface ManualScheduler extends FrameScheduler {
  flush(): void;
  readonly pending: number;
}

export function createManualScheduler(): ManualScheduler {
  let queue: (() => void)[] = [];
  return {
    schedule(fn) {
      queue.push(fn);
      return () => {
        queue = queue.filter((f) => f !== fn);
      };
    },
    flush() {
      const run = queue;
      queue = [];
      for (const fn of run) fn();
    },
    get pending() {
      return queue.length;
    },
  };
}

let current: FrameScheduler | null = null;

export function getFrameScheduler(): FrameScheduler {
  if (!current) current = createFrameScheduler();
  return current;
}

/** Tests (and low-end overrides) replace the global scheduler. `null` restores the default. */
export function setFrameScheduler(s: FrameScheduler | null): void {
  current = s;
}
