/**
 * Focus helpers shared by the trap, menus and trees. `tabbable` 6.5.0 decides what is focusable.
 * jsdom has no layout, so its display check is switched off there (tabbable's documented advice).
 */
import { focusable, tabbable, type CheckOptions } from 'tabbable';

const IS_JSDOM = typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent);
const OPTIONS: CheckOptions = { displayCheck: IS_JSDOM ? 'none' : 'full' };

export function tabbableIn(el: Element): HTMLElement[] {
  return tabbable(el, OPTIONS) as HTMLElement[];
}

export function focusableIn(el: Element): HTMLElement[] {
  return focusable(el, OPTIONS) as HTMLElement[];
}

/** Focus without scrolling where supported (Safari 12 ignores the options object harmlessly). */
export function focusElement(el: HTMLElement | null | undefined): boolean {
  if (!el || !el.isConnected) return false;
  try {
    el.focus({ preventScroll: true });
  } catch {
    el.focus();
  }
  return document.activeElement === el;
}

export function activeElement(): HTMLElement | null {
  const a = document.activeElement;
  return a instanceof HTMLElement && a !== document.body ? a : null;
}

/*
 * Last pressed control. Safari (and iOS) does not focus a button on click, so an overlay opened
 * by a tap has no focused trigger to return to; useReturnFocus falls back to this element.
 */
const FOCUSABLE_SELECTOR = 'button, a[href], input, select, textarea, [tabindex]';
let lastPressed: HTMLElement | null = null;
let pressTracking = false;

function onPress(e: Event): void {
  const t = e.target;
  lastPressed = t instanceof Element ? (t.closest(FOCUSABLE_SELECTOR) as HTMLElement | null) : null;
}

export function trackPresses(): void {
  if (pressTracking || typeof document === 'undefined') return;
  pressTracking = true;
  document.addEventListener('mousedown', onPress, true);
  document.addEventListener('touchstart', onPress, true);
}

/** The control the user last pressed, if it is still in the document. */
export function lastPressedElement(): HTMLElement | null {
  return lastPressed && lastPressed.isConnected ? lastPressed : null;
}
