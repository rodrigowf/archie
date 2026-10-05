/**
 * Roving focus (WAI-ARIA APG) for composite widgets: tab lists, menus, the navigation rail, trees
 * and lists. The widget renders its items with `tabIndex={isCurrent ? 0 : -1}` from its own state;
 * this hook only moves DOM focus on arrow keys, Home/End and type-ahead, and reports the new index
 * so the widget can update that state (`onMove`).
 *
 * Items are found by `itemSelector` inside the container at key time, so items may come and go.
 * Items with `aria-disabled="true"` stay reachable (APG: disabled menu items remain focusable)
 * unless `skipDisabled` is set.
 */
import { useCallback, useRef, type KeyboardEvent as ReactKeyboardEvent, type RefObject } from 'react';
import { useLatest } from './useLatest';
import { focusElement } from './focus';
import { createTypeahead, isTypeaheadKey } from './typeahead';

export type RovingOrientation = 'horizontal' | 'vertical' | 'both';

export interface RovingFocusOptions {
  itemSelector: string;
  orientation?: RovingOrientation;
  /** Wrap from last to first (default true). */
  loop?: boolean;
  typeahead?: boolean;
  skipDisabled?: boolean;
  /** Called after focus moved to item `index` (the element is passed too). */
  onMove?: (index: number, el: HTMLElement) => void;
  /** Label used for type-ahead (default: `data-typeahead` or textContent). */
  getLabel?: (el: HTMLElement) => string;
}

export function rovingItems(container: HTMLElement, selector: string, skipDisabled = false): HTMLElement[] {
  const all = Array.from(container.querySelectorAll<HTMLElement>(selector));
  return skipDisabled ? all.filter((el) => el.getAttribute('aria-disabled') !== 'true') : all;
}

function defaultLabel(el: HTMLElement): string {
  return el.getAttribute('data-typeahead') ?? el.textContent ?? '';
}

export function useRovingFocus(
  containerRef: RefObject<HTMLElement | null>,
  opts: RovingFocusOptions,
): (e: ReactKeyboardEvent) => void {
  const optsRef = useLatest(opts);
  const typeahead = useRef(createTypeahead());

  return useCallback(
    (e: ReactKeyboardEvent) => {
      const container = containerRef.current;
      if (!container) return;
      const { itemSelector, orientation = 'vertical', loop = true, skipDisabled, onMove, getLabel = defaultLabel } = optsRef.current;
      const items = rovingItems(container, itemSelector, skipDisabled);
      if (items.length === 0) return;
      const current = items.findIndex((el) => el === document.activeElement || el.contains(document.activeElement));
      const last = items.length - 1;
      const step = (d: number): number => {
        if (current < 0) return d > 0 ? 0 : last;
        const n = current + d;
        if (n < 0) return loop ? last : 0;
        if (n > last) return loop ? 0 : last;
        return n;
      };
      const horiz = orientation !== 'vertical';
      const vert = orientation !== 'horizontal';
      let target = -1;
      switch (e.key) {
        case 'ArrowDown':
          if (vert) target = step(1);
          break;
        case 'ArrowUp':
          if (vert) target = step(-1);
          break;
        case 'ArrowRight':
          if (horiz) target = step(1);
          break;
        case 'ArrowLeft':
          if (horiz) target = step(-1);
          break;
        case 'Home':
          target = 0;
          break;
        case 'End':
          target = last;
          break;
        default:
          if (optsRef.current.typeahead && isTypeaheadKey(e)) {
            target = typeahead.current.next(e.key, items.map(getLabel), current);
          }
      }
      if (target < 0) return;
      e.preventDefault();
      const el = items[target];
      if (!el) return;
      focusElement(el);
      onMove?.(target, el);
    },
    [containerRef, optsRef],
  );
}
