/**
 * Popover (spec 13 §3.5): an anchored, non-modal surface positioned by `@floating-ui/react-dom`
 * 2.1.9 (flip, shift, size), repositioned on scroll and resize by `autoUpdate` (fixes the detached
 * menus of the current app). The anchor is an element ref or a virtual point (context menus).
 *
 * Contract: portal into #overlay-root, Escape closes it when topmost, outside press closes it
 * (presses on the anchor are ignored so the trigger can toggle), focus returns to the anchor.
 * `strategy: 'fixed'` with top/left: no `inset`, Safari-12-safe.
 */
import { autoUpdate, flip, offset as offsetMw, shift, size, useFloating, type Placement } from '@floating-ui/react-dom';
import { useLayoutEffect, useMemo, useRef, type CSSProperties, type HTMLAttributes, type ReactNode, type RefObject } from 'react';
import { useOverlayLayer, type OverlayCloseReason } from '@/ui/a11y';
import { Portal } from '../Portal/Portal';
import styles from './Popover.module.css';

export type { Placement } from '@floating-ui/react-dom';

/** A point or rectangle on screen (e.g. a right-click position). */
export interface VirtualAnchor {
  getBoundingClientRect(): { x: number; y: number; top: number; left: number; right: number; bottom: number; width: number; height: number };
}

export type PopoverAnchor = RefObject<HTMLElement | null> | VirtualAnchor;

export function pointAnchor(x: number, y: number): VirtualAnchor {
  return { getBoundingClientRect: () => ({ x, y, top: y, left: x, right: x, bottom: y, width: 0, height: 0 }) };
}

function isRef(a: PopoverAnchor): a is RefObject<HTMLElement | null> {
  return 'current' in a;
}

export interface PopoverProps extends Omit<HTMLAttributes<HTMLDivElement>, 'children' | 'role'> {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  anchor: PopoverAnchor;
  placement?: Placement;
  /** Gap to the anchor in px (default 4). */
  offset?: number;
  /** At least as wide as the anchor. */
  matchAnchorWidth?: boolean;
  role?: string;
  /** Trap Tab inside (default false: a popover is non-modal). */
  trapFocus?: boolean;
  initialFocus?: RefObject<HTMLElement | null>;
  returnFocus?: boolean;
  outsidePress?: boolean;
  /** Back closes it (default false; menus and popovers are transient). */
  history?: boolean;
  /** Render the surface in place, without positioning or overlay behaviour (gallery previews). */
  inline?: boolean;
  /** Extra class for the positioned wrapper. */
  surfaceClassName?: string;
  children: ReactNode;
}

export function Popover({
  open,
  onClose,
  anchor,
  placement = 'bottom-start',
  offset = 4,
  matchAnchorWidth,
  role = 'dialog',
  trapFocus = false,
  initialFocus,
  returnFocus = true,
  outsidePress = true,
  history = false,
  inline,
  className,
  surfaceClassName,
  style,
  children,
  ...rest
}: PopoverProps) {
  const cls = [styles.popover, surfaceClassName, className].filter(Boolean).join(' ');
  if (inline) {
    return open ? (
      <div {...rest} role={role} className={`${cls} ${styles.inline}`} style={style}>
        {children}
      </div>
    ) : null;
  }
  return (
    <FloatingPopover
      {...rest}
      open={open}
      onClose={onClose}
      anchor={anchor}
      placement={placement}
      offset={offset}
      matchAnchorWidth={matchAnchorWidth}
      role={role}
      trapFocus={trapFocus}
      initialFocus={initialFocus}
      returnFocus={returnFocus}
      outsidePress={outsidePress}
      history={history}
      className={cls}
      style={style}
    >
      {children}
    </FloatingPopover>
  );
}

type FloatingProps = Omit<PopoverProps, 'inline' | 'surfaceClassName'>;

function FloatingPopover(props: FloatingProps) {
  return props.open ? <OpenPopover {...props} /> : null;
}

function OpenPopover({
  onClose,
  anchor,
  placement,
  offset = 4,
  matchAnchorWidth,
  role,
  trapFocus,
  initialFocus,
  returnFocus,
  outsidePress,
  history,
  className,
  style,
  children,
  ...rest
}: FloatingProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const anchorRef = isRef(anchor) ? anchor : null;

  const middleware = useMemo(
    () => [
      offsetMw(offset),
      flip({ padding: 8 }),
      shift({ padding: 8 }),
      size({
        padding: 8,
        apply({ availableHeight, availableWidth, rects, elements }) {
          const s = elements.floating.style;
          s.maxHeight = `${Math.max(96, Math.floor(availableHeight))}px`;
          s.maxWidth = `${Math.max(160, Math.floor(availableWidth))}px`;
          if (matchAnchorWidth) s.minWidth = `${Math.round(rects.reference.width)}px`;
        },
      }),
    ],
    [offset, matchAnchorWidth],
  );

  const { refs, floatingStyles, isPositioned } = useFloating({
    open: true,
    placement,
    strategy: 'fixed',
    // top/left, not transform: the enter animation owns `transform`.
    transform: false,
    middleware,
    whileElementsMounted: autoUpdate,
  });

  useLayoutEffect(() => {
    refs.setReference(isRef(anchor) ? anchor.current : anchor);
  }, [anchor, refs]);

  const ignore = useMemo(() => (anchorRef ? [anchorRef] : []), [anchorRef]);
  useOverlayLayer({
    open: true,
    onClose,
    containerRef,
    trapFocus,
    history,
    outsidePress,
    outsideIgnore: ignore,
    returnFocus,
    ...(anchorRef ? { returnFocusTo: anchorRef } : null),
    ...(initialFocus ? { initialFocus } : null),
  });

  const setRef = (el: HTMLDivElement | null): void => {
    containerRef.current = el;
    refs.setFloating(el);
  };
  const positioned: CSSProperties = { ...floatingStyles, ...(isPositioned ? null : { opacity: 0 }), ...style };

  return (
    <Portal>
      <div {...rest} ref={setRef} role={role} className={className} style={positioned} data-positioned={isPositioned ? '' : undefined}>
        {children}
      </div>
    </Portal>
  );
}
