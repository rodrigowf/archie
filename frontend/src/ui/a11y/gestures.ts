/**
 * Gestures on mouse + touch events (spec 13 §2.6: Pointer Events need Safari 13). Native listeners
 * are attached through a ref, because React registers touch listeners as passive and a drag must be
 * able to `preventDefault()` the page scroll.
 *
 *   useLongPress(ref, onLongPress)      touch hold ≥ 500 ms without moving (rename a tab, row menus)
 *   useSwipe(ref, onSwipe)              horizontal flick on touch (switch sessions from the title)
 *   useDrag(ref, handlers)              press–move–release with velocity (sheet swipe-down, reorder)
 *
 * The pure trackers (`createDragTracker`, `classifySwipe`) carry the math and are unit-tested.
 */
import { useEffect, type RefObject } from 'react';
import { useLatest } from './useLatest';

export interface Point {
  x: number;
  y: number;
}

/* ---------------------------------------------------------------- pure helpers */

export interface DragSample {
  dx: number;
  dy: number;
  /** px per ms over the last ~100 ms (positive = right / down). */
  vx: number;
  vy: number;
}

export function createDragTracker(start: Point, t0: number) {
  const samples: { x: number; y: number; t: number }[] = [{ ...start, t: t0 }];
  return {
    move(p: Point, t: number): DragSample {
      samples.push({ ...p, t });
      while (samples.length > 2 && t - (samples[0]?.t ?? t) > 100) samples.shift();
      return this.sample();
    },
    sample(): DragSample {
      const first = samples[0] ?? { x: start.x, y: start.y, t: t0 };
      const lastS = samples[samples.length - 1] ?? first;
      const dt = Math.max(1, lastS.t - first.t);
      return {
        dx: lastS.x - start.x,
        dy: lastS.y - start.y,
        vx: (lastS.x - first.x) / dt,
        vy: (lastS.y - first.y) / dt,
      };
    },
  };
}

export const SWIPE_MIN_PX = 48;
export const SWIPE_MIN_VELOCITY = 0.3;

/** A horizontal swipe needs distance or speed, and must be clearly more horizontal than vertical. */
export function classifySwipe(s: DragSample, minPx = SWIPE_MIN_PX): 'left' | 'right' | null {
  const ax = Math.abs(s.dx);
  if (ax < Math.abs(s.dy) * 1.5) return null;
  if (ax < minPx && Math.abs(s.vx) < SWIPE_MIN_VELOCITY) return null;
  if (ax < 16) return null;
  return s.dx < 0 ? 'left' : 'right';
}

/** Event time in ms (real events always carry a timeStamp; synthetic ones may not). */
function stamp(e: Event): number {
  return typeof e.timeStamp === 'number' && Number.isFinite(e.timeStamp) ? e.timeStamp : Date.now();
}

function touchPoint(e: TouchEvent): Point | null {
  const t = e.touches[0] ?? e.changedTouches[0];
  return t ? { x: t.clientX, y: t.clientY } : null;
}

/* ---------------------------------------------------------------- long press */

export const LONG_PRESS_MS = 500;
/** How long the click that follows a gesture's release is swallowed. */
const CLICK_GRACE_MS = 400;
const MOVE_TOLERANCE_PX = 10;

export interface LongPressOptions {
  ms?: number;
  enabled?: boolean;
}

/**
 * Touch long-press. After it fires, the click that the browser synthesises on release is
 * swallowed, so a long-press never also activates the element. (Mouse users get the context menu
 * or double-click instead.)
 */
export function useLongPress(
  ref: RefObject<HTMLElement | null>,
  onLongPress: (at: Point) => void,
  { ms = LONG_PRESS_MS, enabled = true }: LongPressOptions = {},
): void {
  const cb = useLatest(onLongPress);
  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let start: Point | null = null;
    let fired = false;
    const cancel = (): void => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    };
    const onStart = (e: TouchEvent): void => {
      if (e.touches.length !== 1) return cancel();
      start = touchPoint(e);
      fired = false;
      cancel();
      timer = setTimeout(() => {
        timer = undefined;
        if (!start) return;
        fired = true;
        cb.current(start);
      }, ms);
    };
    const onMove = (e: TouchEvent): void => {
      const p = touchPoint(e);
      if (start && p && Math.hypot(p.x - start.x, p.y - start.y) > MOVE_TOLERANCE_PX) cancel();
    };
    const onEnd = (e: TouchEvent): void => {
      cancel();
      if (fired) {
        if (e.cancelable) e.preventDefault(); // no synthetic click / mouse events
        setTimeout(() => {
          fired = false;
        }, CLICK_GRACE_MS);
      }
    };
    const onClick = (e: MouseEvent): void => {
      if (fired) {
        fired = false;
        e.preventDefault();
        e.stopPropagation();
      }
    };
    const onContext = (e: Event): void => {
      if (timer !== undefined || fired) e.preventDefault(); // Android shows its own menu on hold
    };
    el.addEventListener('touchstart', onStart, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: true });
    el.addEventListener('touchend', onEnd);
    el.addEventListener('touchcancel', cancel);
    el.addEventListener('click', onClick, true);
    el.addEventListener('contextmenu', onContext);
    return () => {
      cancel();
      el.removeEventListener('touchstart', onStart);
      el.removeEventListener('touchmove', onMove);
      el.removeEventListener('touchend', onEnd);
      el.removeEventListener('touchcancel', cancel);
      el.removeEventListener('click', onClick, true);
      el.removeEventListener('contextmenu', onContext);
    };
  }, [ref, ms, enabled, cb]);
}

/* ---------------------------------------------------------------- swipe */

export function useSwipe(
  ref: RefObject<HTMLElement | null>,
  onSwipe: (direction: 'left' | 'right') => void,
  { enabled = true, minPx = SWIPE_MIN_PX }: { enabled?: boolean; minPx?: number } = {},
): void {
  const cb = useLatest(onSwipe);
  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return undefined;
    let tracker: ReturnType<typeof createDragTracker> | null = null;
    let swiped = false;
    const onStart = (e: TouchEvent): void => {
      const p = touchPoint(e);
      tracker = p && e.touches.length === 1 ? createDragTracker(p, stamp(e)) : null;
    };
    const onMove = (e: TouchEvent): void => {
      const p = touchPoint(e);
      if (tracker && p) tracker.move(p, stamp(e));
    };
    const onEnd = (e: TouchEvent): void => {
      const p = touchPoint(e);
      if (!tracker) return;
      const s = p ? tracker.move(p, stamp(e)) : tracker.sample();
      tracker = null;
      const dir = classifySwipe(s, minPx);
      if (dir) {
        swiped = true;
        if (e.cancelable) e.preventDefault();
        setTimeout(() => {
          swiped = false;
        }, CLICK_GRACE_MS);
        cb.current(dir);
      }
    };
    const onClick = (e: MouseEvent): void => {
      if (swiped) {
        swiped = false;
        e.preventDefault();
        e.stopPropagation();
      }
    };
    el.addEventListener('touchstart', onStart, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: true });
    el.addEventListener('touchend', onEnd);
    el.addEventListener('click', onClick, true);
    return () => {
      el.removeEventListener('touchstart', onStart);
      el.removeEventListener('touchmove', onMove);
      el.removeEventListener('touchend', onEnd);
      el.removeEventListener('click', onClick, true);
    };
  }, [ref, enabled, minPx, cb]);
}

/* ---------------------------------------------------------------- drag */

export interface DragHandlers {
  /** Return false to refuse the drag (e.g. the content is scrolled). */
  onStart?: (at: Point, e: MouseEvent | TouchEvent) => boolean | undefined;
  /** Once past the threshold: return false to hand the gesture back (e.g. an upward scroll). */
  shouldStart?: (s: DragSample) => boolean;
  onMove?: (s: DragSample, e: MouseEvent | TouchEvent) => void;
  onEnd?: (s: DragSample, e: MouseEvent | TouchEvent) => void;
  onCancel?: () => void;
}

export interface DragOptions {
  enabled?: boolean;
  /** Only start once the pointer moved this far on the given axis (default 4 px, any axis). */
  axis?: 'x' | 'y' | 'both';
  threshold?: number;
  /** Mouse drags too (default true). */
  mouse?: boolean;
  /** Touch drags too (default true; false keeps touch for native scrolling). */
  touch?: boolean;
}

export function useDrag(ref: RefObject<HTMLElement | null>, handlers: DragHandlers, opts: DragOptions = {}): void {
  const h = useLatest(handlers);
  const { enabled = true, axis = 'both', threshold = 4, mouse = true, touch = true } = opts;
  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return undefined;
    let tracker: ReturnType<typeof createDragTracker> | null = null;
    let started = false;
    let suppressClick = false;

    const begin = (p: Point, e: MouseEvent | TouchEvent): boolean => {
      if (h.current.onStart?.(p, e) === false) return false;
      tracker = createDragTracker(p, stamp(e));
      started = false;
      return true;
    };
    const passes = (s: DragSample): boolean => {
      const ax = Math.abs(s.dx);
      const ay = Math.abs(s.dy);
      if (axis === 'x') return ax >= threshold && ax > ay;
      if (axis === 'y') return ay >= threshold && ay > ax;
      return Math.max(ax, ay) >= threshold;
    };
    const move = (p: Point, e: MouseEvent | TouchEvent): void => {
      if (!tracker) return;
      const s = tracker.move(p, stamp(e));
      if (!started) {
        if (!passes(s)) {
          // Wrong axis first: give the gesture back to the browser (scrolling).
          if (axis !== 'both' && Math.max(Math.abs(s.dx), Math.abs(s.dy)) >= threshold) tracker = null;
          return;
        }
        if (h.current.shouldStart && !h.current.shouldStart(s)) {
          tracker = null;
          return;
        }
        started = true;
      }
      if (e.cancelable) e.preventDefault();
      h.current.onMove?.(s, e);
    };
    const end = (p: Point | null, e: MouseEvent | TouchEvent): void => {
      if (!tracker) return;
      const s = p ? tracker.move(p, stamp(e)) : tracker.sample();
      tracker = null;
      if (started) {
        suppressClick = true;
        setTimeout(() => {
          suppressClick = false;
        }, CLICK_GRACE_MS);
        h.current.onEnd?.(s, e);
      }
      started = false;
    };

    const onTouchStart = (e: TouchEvent): void => {
      const p = touchPoint(e);
      if (p && e.touches.length === 1) begin(p, e);
    };
    const onTouchMove = (e: TouchEvent): void => {
      const p = touchPoint(e);
      if (p) move(p, e);
    };
    const onTouchEnd = (e: TouchEvent): void => {
      end(touchPoint(e), e);
    };
    const onTouchCancel = (): void => {
      if (started) h.current.onCancel?.();
      tracker = null;
      started = false;
    };

    const onMouseMove = (e: MouseEvent): void => {
      move({ x: e.clientX, y: e.clientY }, e);
    };
    const onMouseUp = (e: MouseEvent): void => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      end({ x: e.clientX, y: e.clientY }, e);
    };
    const onMouseDown = (e: MouseEvent): void => {
      if (e.button !== 0) return;
      if (!begin({ x: e.clientX, y: e.clientY }, e)) return;
      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
    };
    const onClick = (e: MouseEvent): void => {
      if (suppressClick) {
        suppressClick = false;
        e.preventDefault();
        e.stopPropagation();
      }
    };

    if (touch) {
      el.addEventListener('touchstart', onTouchStart, { passive: true });
      el.addEventListener('touchmove', onTouchMove, { passive: false });
      el.addEventListener('touchend', onTouchEnd);
      el.addEventListener('touchcancel', onTouchCancel);
    }
    el.addEventListener('click', onClick, true);
    if (mouse) el.addEventListener('mousedown', onMouseDown);
    return () => {
      el.removeEventListener('touchstart', onTouchStart);
      el.removeEventListener('touchmove', onTouchMove);
      el.removeEventListener('touchend', onTouchEnd);
      el.removeEventListener('touchcancel', onTouchCancel);
      el.removeEventListener('click', onClick, true);
      el.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [ref, enabled, axis, threshold, mouse, touch, h]);
}
