/**
 * The latest value in a ref, for event listeners that must not re-subscribe on every render.
 * Updated in a layout effect (never during render), so it is current before any DOM event fires.
 */
import { useLayoutEffect, useRef, type MutableRefObject } from 'react';

export function useLatest<T>(value: T): MutableRefObject<T> {
  const ref = useRef(value);
  useLayoutEffect(() => {
    ref.current = value;
  });
  return ref;
}
