/**
 * Scroll lock for modal overlays (spec 13 §2.6): the fixed-body technique, because iOS 12 ignores
 * `overflow: hidden` on <body>. Reference-counted, so nested modals lock once and unlock on the
 * last close. The app shell never scrolls the page itself, so the offset is normally 0, but a
 * page offset left by the iOS keyboard is preserved and restored.
 */
import { useLayoutEffect } from 'react';

let locks = 0;
let saved: { top: string; left: string; right: string; position: string; overflow: string; scrollY: number } | null = null;

export function lockScroll(): () => void {
  if (typeof document === 'undefined') return () => undefined;
  locks += 1;
  if (locks === 1) {
    const b = document.body.style;
    const scrollY = window.pageYOffset || 0;
    saved = { top: b.top, left: b.left, right: b.right, position: b.position, overflow: b.overflow, scrollY };
    b.position = 'fixed';
    b.top = `${-scrollY}px`;
    b.left = '0';
    b.right = '0';
    b.overflow = 'hidden';
    document.documentElement.setAttribute('data-scroll-locked', '');
  }
  let released = false;
  return () => {
    if (released) return;
    released = true;
    locks -= 1;
    if (locks === 0 && saved) {
      const b = document.body.style;
      b.position = saved.position;
      b.top = saved.top;
      b.left = saved.left;
      b.right = saved.right;
      b.overflow = saved.overflow;
      document.documentElement.removeAttribute('data-scroll-locked');
      if (saved.scrollY) window.scrollTo(0, saved.scrollY);
      saved = null;
    }
  };
}

export function isScrollLocked(): boolean {
  return locks > 0;
}

export function useScrollLock(active: boolean): void {
  useLayoutEffect(() => (active ? lockScroll() : undefined), [active]);
}
