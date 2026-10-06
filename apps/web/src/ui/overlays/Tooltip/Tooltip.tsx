/**
 * Plain tooltip (spec 13 §3.5). Shows on hover (after 500 ms), on keyboard focus, and on touch
 * long-press (for 1.5 s); Escape hides it. It describes the trigger (`aria-describedby` on the
 * first focusable element inside) and is never its only label: icon buttons keep their own
 * `aria-label`. Positioned like Popover (floating-ui, flip/shift) but outside the overlay stack:
 * it takes no focus.
 *
 * The trigger is wrapped in an inline-flex span that receives the hover/focus events, so any
 * child works without ref forwarding.
 *
 *   <Tooltip label="14 turns · $0.82 · context 42%"><span tabIndex={0}>Ready</span></Tooltip>
 */
import { autoUpdate, flip, offset, shift, useFloating, type Placement } from '@floating-ui/react-dom';
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type FocusEvent, type ReactNode } from 'react';
import { focusableIn, useLongPress } from '@/ui/a11y';
import { Portal } from '../Portal/Portal';
import styles from './Tooltip.module.css';

export const TOOLTIP_DELAY_MS = 500;
export const TOOLTIP_TOUCH_MS = 1500;

export interface TooltipProps {
  label: string;
  placement?: Placement;
  children: ReactNode;
  disabled?: boolean;
  /** Force visible (gallery previews). */
  open?: boolean;
  /** Render the bubble in flow under the trigger instead of floating (gallery previews). */
  inline?: boolean;
  className?: string;
}

function isKeyboardFocus(el: Element): boolean {
  try {
    return el.matches(':focus-visible');
  } catch {
    return el.classList.contains('focus-visible'); // Safari 12 + polyfill
  }
}

export function Tooltip({ label, placement = 'top', children, disabled, open: forced, inline, className }: TooltipProps) {
  const id = useId();
  const [shown, setShown] = useState(false);
  const wrapRef = useRef<HTMLSpanElement | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const visible = !disabled && (forced ?? shown);

  const clear = useCallback((): void => {
    if (timer.current !== undefined) clearTimeout(timer.current);
    timer.current = undefined;
  }, []);
  const showAfter = useCallback(
    (ms: number, hideAfter?: number): void => {
      clear();
      timer.current = setTimeout(() => {
        setShown(true);
        if (hideAfter) timer.current = setTimeout(() => setShown(false), hideAfter);
      }, ms);
    },
    [clear],
  );
  const hide = useCallback((): void => {
    clear();
    setShown(false);
  }, [clear]);
  useEffect(() => clear, [clear]);

  useLongPress(wrapRef, () => {
    showAfter(0, TOOLTIP_TOUCH_MS);
  });

  useEffect(() => {
    if (!visible) return undefined;
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape' || e.key === 'Esc') hide();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, [visible, hide]);

  // Describe the trigger while the tooltip is shown.
  useLayoutEffect(() => {
    const wrap = wrapRef.current;
    const target = wrap ? (focusableIn(wrap)[0] ?? (wrap.firstElementChild as HTMLElement | null)) : null;
    if (!target || !visible) return undefined;
    const prev = target.getAttribute('aria-describedby');
    target.setAttribute('aria-describedby', prev ? `${prev} ${id}` : id);
    return () => {
      if (prev) target.setAttribute('aria-describedby', prev);
      else target.removeAttribute('aria-describedby');
    };
  }, [visible, id]);

  const { refs, floatingStyles } = useFloating({
    open: visible,
    placement,
    strategy: 'fixed',
    middleware: [offset(6), flip({ padding: 8 }), shift({ padding: 8 })],
    whileElementsMounted: autoUpdate,
  });
  const setFloating = useCallback(
    (el: HTMLDivElement | null) => {
      refs.setFloating(el);
    },
    [refs],
  );
  const setWrap = useCallback(
    (el: HTMLSpanElement | null) => {
      wrapRef.current = el;
      refs.setReference(el);
    },
    [refs],
  );

  return (
    <>
      {/* Hover/focus listeners only; the child stays the interactive element. */}
      <span
        ref={setWrap}
        className={className ? `${styles.anchor} ${className}` : styles.anchor}
        onMouseEnter={() => {
          showAfter(TOOLTIP_DELAY_MS);
        }}
        onMouseLeave={hide}
        onFocus={(e: FocusEvent<HTMLSpanElement>) => {
          if (isKeyboardFocus(e.target)) showAfter(0);
        }}
        onBlur={hide}
      >
        {children}
      </span>
      {visible && inline ? (
        <span id={id} role="tooltip" className={`${styles.tooltip} ${styles.inline}`}>
          {label}
        </span>
      ) : null}
      {visible && !inline ? (
        <Portal>
          <div ref={setFloating} id={id} role="tooltip" className={styles.tooltip} style={floatingStyles}>
            {label}
          </div>
        </Portal>
      ) : null}
    </>
  );
}
