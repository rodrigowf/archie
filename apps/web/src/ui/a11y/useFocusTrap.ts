/**
 * Focus trap (spec 13 §3.5; fixes inv02 §6.2 "no focus trap"). While active and on top of the
 * overlay stack, Tab / Shift+Tab cycle inside the container and focus that escapes (a click on the
 * page behind, a programmatic focus) is pulled back. Initial focus: `initialFocus` → the first
 * element with `data-autofocus` → the first tabbable → the container itself (give it tabIndex=-1).
 *
 * Only the topmost layer traps: a menu opened from a dialog lives in its own portal, and the
 * dialog's trap stands down while the menu is on top (`isTop`).
 */
import { useEffect, type RefObject } from 'react';
import { focusElement, tabbableIn } from './focus';
import { useLatest } from './useLatest';

export interface FocusTrapOptions {
  active: boolean;
  /** Element to focus first. */
  initialFocus?: RefObject<HTMLElement | null>;
  /** Is this layer the topmost overlay? (default: always). */
  isTop?: () => boolean;
}

export function initialFocusTarget(container: HTMLElement, initial?: HTMLElement | null): HTMLElement {
  if (initial && container.contains(initial)) return initial;
  const marked = container.querySelector<HTMLElement>('[data-autofocus]');
  if (marked) return marked;
  return tabbableIn(container)[0] ?? container;
}

export function useFocusTrap(ref: RefObject<HTMLElement | null>, { active, initialFocus, isTop }: FocusTrapOptions): void {
  const isTopRef = useLatest(isTop);

  useEffect(() => {
    if (!active) return undefined;
    const container = ref.current;
    if (!container) return undefined;
    const top = (): boolean => (isTopRef.current ? isTopRef.current() : true);

    if (!container.contains(document.activeElement)) {
      focusElement(initialFocusTarget(container, initialFocus?.current));
    }

    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key !== 'Tab' || !top()) return;
      const items = tabbableIn(container);
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) {
        e.preventDefault();
        focusElement(container);
        return;
      }
      const current = document.activeElement;
      if (e.shiftKey && (current === first || !container.contains(current))) {
        e.preventDefault();
        focusElement(last);
      } else if (!e.shiftKey && (current === last || !container.contains(current))) {
        e.preventDefault();
        focusElement(first);
      }
    };
    const onFocusIn = (e: FocusEvent): void => {
      if (!top()) return;
      const target = e.target as Node | null;
      if (target && !container.contains(target)) {
        focusElement(tabbableIn(container)[0] ?? container);
      }
    };
    document.addEventListener('keydown', onKeyDown, true);
    document.addEventListener('focusin', onFocusIn, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      document.removeEventListener('focusin', onFocusIn, true);
    };
    // initialFocus is read once per activation on purpose.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, ref]);
}
