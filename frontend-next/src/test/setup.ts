/**
 * Vitest setup for the jsdom projects (`dom`, `compat`). jsdom lacks a few browser APIs the app
 * relies on; these shims are minimal and deterministic.
 */
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});

if (typeof window !== 'undefined') {
  // user-event uses CSS.escape (radio-group arrow keys); jsdom does not provide it.
  if (typeof CSS === 'undefined' || typeof CSS.escape !== 'function') {
    const escape = (value: string): string => value.replace(/[^a-zA-Z0-9_-]/g, (ch) => '\\' + ch);
    (globalThis as { CSS?: object }).CSS = { ...(globalThis as { CSS?: object }).CSS, escape };
  }

  if (typeof window.matchMedia !== 'function') {
    // Matches nothing. Tests that need a specific media state stub window.matchMedia themselves.
    window.matchMedia = (query: string): MediaQueryList =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        dispatchEvent: () => false,
      }) as MediaQueryList;
  }

  if (typeof window.ResizeObserver === 'undefined') {
    class ResizeObserverStub {
      observe(): void {}
      unobserve(): void {}
      disconnect(): void {}
    }
    window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
  }

  if (typeof Element.prototype.scrollTo !== 'function') {
    Element.prototype.scrollTo = function scrollTo(): void {};
  }
}
