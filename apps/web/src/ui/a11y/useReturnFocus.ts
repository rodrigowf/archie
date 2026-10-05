/**
 * Return focus to the trigger when an overlay closes (spec 13 §3.5). The element focused when
 * `active` became true is remembered; when it turns false (or the component unmounts), focus goes
 * back to it, unless the user has meanwhile put focus somewhere real (clicked another control):
 * focus is only restored when it is on <body> or still inside the closing overlay.
 */
import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';
import { activeElement, focusElement, lastPressedElement, trackPresses } from './focus';

trackPresses();

export interface ReturnFocusOptions {
  /** The overlay's container (focus inside it counts as "lost" on close). */
  container?: RefObject<HTMLElement | null>;
  /** Restore to this element instead of the one focused at open. */
  returnTo?: RefObject<HTMLElement | null>;
  /** Set false to skip restoring (default true). */
  enabled?: boolean;
}

export function useReturnFocus(active: boolean, { container, returnTo, enabled = true }: ReturnFocusOptions = {}): void {
  const saved = useRef<HTMLElement | null>(null);
  const inside = useRef(false);
  // True while activated; a deferred restore is skipped if the overlay re-activated meanwhile
  // (React StrictMode re-runs effects; an overlay may also reopen within the same tick).
  const live = useRef(false);

  // Capture before children's effects move focus into the overlay (layout effects run first).
  useLayoutEffect(() => {
    if (!active) return;
    const now = activeElement() ?? lastPressedElement();
    // StrictMode replays this after focus already moved inside: keep the real trigger.
    if (now && container?.current?.contains(now)) return;
    saved.current = now;
  }, [active, container]);

  // Track whether focus is inside the overlay, because by cleanup time the DOM may be gone.
  useEffect(() => {
    if (!active) return undefined;
    const update = (): void => {
      const el = container?.current;
      inside.current = !!el && el.contains(document.activeElement);
    };
    update();
    document.addEventListener('focusin', update, true);
    return () => {
      document.removeEventListener('focusin', update, true);
    };
  }, [active, container]);

  useLayoutEffect(() => {
    if (!active || !enabled) return undefined;
    // Captured at open: by cleanup time the refs may already point elsewhere.
    const returnEl = returnTo?.current ?? null;
    const el = container?.current ?? null;
    live.current = true;
    return () => {
      live.current = false;
      const target = returnEl ?? saved.current;
      const current = activeElement();
      const lost = current === null || inside.current || (el?.contains(current) ?? false);
      if (target && lost) {
        // After React has removed the overlay DOM and the trap's passive cleanup has run (else
        // the trap's focusin guard would pull focus back in).
        setTimeout(() => {
          if (live.current) return;
          const now = activeElement();
          if (now === null || !now.isConnected || (el?.contains(now) ?? false)) focusElement(target);
        }, 0);
      }
      inside.current = false;
    };
  }, [active, enabled, container, returnTo]);
}
