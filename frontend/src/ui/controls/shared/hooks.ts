/**
 * Small helpers shared by the W-03 controls: controlled/uncontrolled state, ref merging, and the
 * aria-disabled click guard (spec 13 §3.5: disabled = `aria-disabled` + 38 % content opacity, so a
 * disabled action stays focusable and discoverable but never activates).
 */
import { useCallback, useLayoutEffect, useRef, useState, type MouseEvent, type MutableRefObject, type Ref } from 'react';

/** Value that is controlled when `value !== undefined`, otherwise internal (seeded by `defaultValue`). */
export function useControllable<T>(value: T | undefined, defaultValue: T, onChange?: (next: T) => void): [T, (next: T) => void] {
  const [inner, setInner] = useState<T>(defaultValue);
  const controlled = value !== undefined;
  const current = controlled ? value : inner;
  const onChangeRef = useRef(onChange);
  useLayoutEffect(() => {
    onChangeRef.current = onChange;
  });
  const set = useCallback(
    (next: T) => {
      if (!controlled) setInner(next);
      onChangeRef.current?.(next);
    },
    [controlled],
  );
  return [current, set];
}

export function assignRef<T>(ref: Ref<T> | undefined, value: T | null): void {
  if (typeof ref === 'function') ref(value);
  else if (ref) (ref as MutableRefObject<T | null>).current = value;
}

/** One callback ref that feeds several refs (forwarded + internal). */
export function useMergedRef<T>(...refs: (Ref<T> | undefined)[]): (value: T | null) => void {
  const refsRef = useRef(refs);
  useLayoutEffect(() => {
    refsRef.current = refs;
  });
  return useCallback((value: T | null) => {
    for (const r of refsRef.current) assignRef(r, value);
  }, []);
}

/**
 * Wraps an onClick so it never fires while the control is disabled (aria-disabled) or busy. Enter
 * and Space on a <button> also arrive as click events, so this covers the keyboard too.
 */
export function guardClick<E extends HTMLElement>(
  blocked: boolean | undefined,
  onClick: ((e: MouseEvent<E>) => void) | undefined,
): (e: MouseEvent<E>) => void {
  return (e) => {
    if (blocked) {
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    onClick?.(e);
  };
}
