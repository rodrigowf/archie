/**
 * Outside-press dismiss (spec 13 §2.6: `mousedown` + `touchstart`, never Pointer Events). Calls
 * `onOutside` when a press starts outside every element in `refs`, and only while this layer is the
 * topmost overlay (`isTop`), so pressing outside a menu inside a dialog closes the menu, not both.
 * The emulated `mousedown` that follows a touch is ignored.
 */
import { useEffect, type RefObject } from 'react';
import { useLatest } from './useLatest';

export interface DismissOptions {
  enabled: boolean;
  isTop?: () => boolean;
}

const EMULATED_MOUSE_MS = 800;

export function useOutsidePress(
  refs: readonly RefObject<Element | null>[],
  onOutside: (e: MouseEvent | TouchEvent) => void,
  { enabled, isTop }: DismissOptions,
): void {
  const latest = useLatest({ refs, onOutside, isTop });

  useEffect(() => {
    if (!enabled) return undefined;
    let lastTouch = 0;
    const handle = (e: MouseEvent | TouchEvent): void => {
      const { refs: rs, onOutside: cb, isTop: top } = latest.current;
      if (top && !top()) return;
      const target = e.target as Node | null;
      if (!target || !target.isConnected) return;
      for (const r of rs) if (r.current?.contains(target)) return;
      cb(e);
    };
    const onTouch = (e: TouchEvent): void => {
      lastTouch = Date.now();
      handle(e);
    };
    const onMouse = (e: MouseEvent): void => {
      if (Date.now() - lastTouch < EMULATED_MOUSE_MS) return;
      if (e.button !== 0) return;
      handle(e);
    };
    document.addEventListener('mousedown', onMouse, true);
    document.addEventListener('touchstart', onTouch, true);
    return () => {
      document.removeEventListener('mousedown', onMouse, true);
      document.removeEventListener('touchstart', onTouch, true);
    };
  }, [enabled, latest]);
}
