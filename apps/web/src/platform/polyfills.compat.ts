/**
 * Compat build (Safari 12 / iOS 12) runtime polyfills that core-js does not cover (spec 13 §2.6):
 *  - ResizeObserver (Safari 13.1+) via @juggle/resize-observer;
 *  - :focus-visible via the focus-visible polyfill (adds `.focus-visible`; postcss-preset-env
 *    rewrites `:focus-visible` selectors to `.focus-visible` in the compat CSS).
 * ES built-ins (Object.fromEntries, Promise.allSettled, Array.prototype.at, ...) are injected
 * by @vitejs/plugin-legacy (core-js, usage-based).
 *
 * The polyfilled ResizeObserver counts its callbacks and their time in `window.__archieRoStats`;
 * the remote console's `[perf]` lines report them per touch gesture.
 */
import { ResizeObserver as ResizeObserverPolyfill } from '@juggle/resize-observer';
import 'focus-visible';

interface RoStats {
  calls: number;
  ms: number;
}

declare global {
  interface Window {
    __archieRoStats?: RoStats;
  }
}

if (typeof window !== 'undefined' && typeof window.ResizeObserver === 'undefined') {
  const stats: RoStats = { calls: 0, ms: 0 };
  window.__archieRoStats = stats;
  class CountingResizeObserver extends ResizeObserverPolyfill {
    constructor(callback: ResizeObserverCallback) {
      super((entries, observer) => {
        const t0 = performance.now();
        try {
          callback(entries as unknown as ResizeObserverEntry[], observer as unknown as ResizeObserver);
        } finally {
          stats.calls += 1;
          stats.ms += performance.now() - t0;
        }
      });
    }
  }
  window.ResizeObserver = CountingResizeObserver as unknown as typeof window.ResizeObserver;
}

export {};
