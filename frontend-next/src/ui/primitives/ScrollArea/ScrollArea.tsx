/**
 * ScrollArea (spec 13 §2.6): the only scroll container primitive. Renders one element with
 * momentum scrolling and `overflow-anchor: none`, and exposes the momentum-safe imperative API of
 * ScrollController through a ref:
 *
 *   const area = useRef<ScrollAreaHandle>(null);
 *   area.current?.scrollToBottom();
 *   area.current?.preserveAnchor(() => flushSync(() => prepend(page)), { anchor: firstOldItem });
 *   area.current?.isNearBottom(150);
 *
 * Give a scroll region a label (`aria-label`) and `tabIndex={0}` when it holds no focusable
 * content, so keyboard users can scroll it.
 */
import { forwardRef, useImperativeHandle, useLayoutEffect, useRef, useState, type HTMLAttributes, type ReactNode } from 'react';
import { ScrollController, type PreserveAnchorOptions, type ScrollEnv } from './scrollController';
import styles from './ScrollArea.module.css';

export interface ScrollAreaHandle {
  /** The scroll container element (null before mount). */
  readonly element: HTMLDivElement | null;
  /** Scrolls to the end, deferred while the user is touching or momentum is running. */
  scrollToBottom(): Promise<void>;
  scrollTo(top: number): Promise<void>;
  /** Runs a synchronous DOM mutation (e.g. a prepend in `flushSync`) and keeps the anchor in place. */
  preserveAnchor(mutate: () => void, opts?: PreserveAnchorOptions): Promise<void>;
  isNearBottom(px?: number): boolean;
  distanceFromBottom(): number;
  isUserScrolling(): boolean;
}

export interface ScrollAreaProps extends HTMLAttributes<HTMLDivElement> {
  /** Scroll axis (default 'y'). */
  axis?: 'y' | 'x' | 'both';
  /** Fill the parent's height (height: 100%). */
  fill?: boolean;
  children?: ReactNode;
  /** Test seam: timers/frames for the controller. */
  env?: ScrollEnv;
}

export const ScrollArea = forwardRef<ScrollAreaHandle, ScrollAreaProps>(function ScrollArea(
  { axis = 'y', fill, env, className, children, ...rest },
  ref,
) {
  const elRef = useRef<HTMLDivElement>(null);
  const [controller] = useState(() => new ScrollController(env));

  useLayoutEffect(() => {
    const el = elRef.current;
    return el ? controller.attach(el) : undefined;
  }, [controller]);

  useImperativeHandle(
    ref,
    () => ({
      get element() {
        return elRef.current;
      },
      scrollToBottom: () => controller.scrollToBottom(),
      scrollTo: (top) => controller.scrollTo(top),
      preserveAnchor: (mutate, opts) => controller.preserveAnchor(mutate, opts),
      isNearBottom: (px) => controller.isNearBottom(px),
      distanceFromBottom: () => controller.distanceFromBottom(),
      isUserScrolling: () => controller.isUserScrolling(),
    }),
    [controller],
  );

  const cls = [styles.root, axis === 'x' ? styles.x : axis === 'both' ? styles.both : '', fill ? styles.fill : '', className]
    .filter(Boolean)
    .join(' ');
  return (
    <div {...rest} ref={elRef} className={cls}>
      {children}
    </div>
  );
});
