/**
 * Compat build (Safari 12 / iOS 12) runtime polyfills that core-js does not cover (spec 13 §2.6):
 *  - ResizeObserver (Safari 13.1+) via @juggle/resize-observer;
 *  - :focus-visible via the focus-visible polyfill (adds `.focus-visible`; postcss-preset-env
 *    rewrites `:focus-visible` selectors to `.focus-visible` in the compat CSS).
 * ES built-ins (Object.fromEntries, Promise.allSettled, Array.prototype.at, ...) are injected
 * by @vitejs/plugin-legacy (core-js, usage-based).
 */
import { ResizeObserver as ResizeObserverPolyfill } from '@juggle/resize-observer';
import 'focus-visible';

if (typeof window !== 'undefined' && typeof window.ResizeObserver === 'undefined') {
  window.ResizeObserver = ResizeObserverPolyfill as unknown as typeof window.ResizeObserver;
}

export {};
