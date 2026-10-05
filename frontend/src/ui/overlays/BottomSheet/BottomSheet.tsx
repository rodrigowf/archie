/**
 * Modal bottom sheet (spec 13 §3.5; mockups `.sheet`, the phone session switcher). Height snaps to
 * its content (capped below the top of the window), a drag handle on top, swipe-down to dismiss
 * with touch events (and mouse drag), plus the overlay contract: `role="dialog"`, `aria-modal`,
 * focus trap, Escape, Back, scroll lock, background `aria-hidden`.
 *
 * The drag starts anywhere on the sheet except inside scrolled content (its `scrollTop > 0`),
 * so scrolling a long list never fights the dismiss gesture.
 */
import { useId, useRef, useState, type CSSProperties, type ReactNode, type MutableRefObject, type RefObject } from 'react';
import { useDrag, useOverlayLayer, type OverlayCloseReason } from '@/ui/a11y';
import { ScrollArea, type ScrollAreaHandle } from '@/ui/primitives';
import layer from '../internal/layer.module.css';
import { Portal } from '../Portal/Portal';
import { Scrim } from '../Scrim/Scrim';
import styles from './BottomSheet.module.css';

/** Dismiss when dragged past this fraction of the sheet height, or flicked faster than this. */
export const SHEET_DISMISS_FRACTION = 0.3;
export const SHEET_DISMISS_VELOCITY = 0.5;
const EXIT_MS = 200;

export function shouldDismissSheet(dy: number, vy: number, height: number): boolean {
  if (dy <= 0) return false;
  return dy > height * SHEET_DISMISS_FRACTION || vy > SHEET_DISMISS_VELOCITY;
}

export interface BottomSheetProps {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  /** Visible headline (also the accessible name). */
  title?: ReactNode;
  /** Accessible name when there is no visible title. */
  'aria-label'?: string;
  /** Trailing header content (e.g. a "History" text button). */
  headerAction?: ReactNode;
  /** Sticky content under the scrolling body (e.g. the "New …" actions). */
  footer?: ReactNode;
  children: ReactNode;
  /** Drag handle and swipe-down (default true). */
  draggable?: boolean;
  history?: boolean;
  initialFocus?: RefObject<HTMLElement | null>;
  className?: string;
  /** Render in place, without overlay behaviour (gallery previews; parent must be positioned). */
  inline?: boolean;
}

export function BottomSheet(props: BottomSheetProps) {
  if (!props.open) return null;
  return props.inline ? <SheetLayer {...props} modal={false} /> : <ModalSheet {...props} />;
}

function ModalSheet(props: BottomSheetProps) {
  const ref = useRef<HTMLDivElement | null>(null);
  useOverlayLayer({
    open: true,
    onClose: props.onClose,
    containerRef: ref,
    modal: true,
    history: props.history ?? true,
    ...(props.initialFocus ? { initialFocus: props.initialFocus } : null),
  });
  return (
    <Portal>
      <SheetLayer {...props} modal sheetRef={ref} />
    </Portal>
  );
}

function SheetLayer({
  onClose,
  title,
  headerAction,
  footer,
  children,
  draggable = true,
  className,
  modal,
  sheetRef,
  ...rest
}: BottomSheetProps & { modal: boolean; sheetRef?: MutableRefObject<HTMLDivElement | null> }) {
  const titleId = useId();
  const localRef = useRef<HTMLDivElement | null>(null);
  const bodyRef = useRef<ScrollAreaHandle | null>(null);
  const [offset, setOffset] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [leaving, setLeaving] = useState(false);

  const setRef = (el: HTMLDivElement | null): void => {
    localRef.current = el;
    if (sheetRef) sheetRef.current = el;
  };

  const dismiss = (reason: OverlayCloseReason): void => {
    const lowEnd = document.documentElement.classList.contains('low-end');
    setLeaving(true);
    setTimeout(
      () => {
        onClose(reason);
      },
      lowEnd ? 0 : EXIT_MS,
    );
  };

  useDrag(
    localRef,
    {
      onStart: (_p, e) => {
        const body = bodyRef.current?.element;
        const target = e.target as Node | null;
        if (body && target && body.contains(target) && body.scrollTop > 0) return false;
        return undefined;
      },
      // Only a downward drag dismisses; an upward one scrolls the content.
      shouldStart: (s) => s.dy > 0,
      onMove: (s) => {
        setDragging(true);
        setOffset(Math.max(0, s.dy));
      },
      onEnd: (s) => {
        setDragging(false);
        const h = localRef.current?.offsetHeight ?? 0;
        if (shouldDismissSheet(s.dy, s.vy, h)) dismiss('swipe');
        else setOffset(0);
      },
      onCancel: () => {
        setDragging(false);
        setOffset(0);
      },
    },
    { enabled: draggable && modal, axis: 'y', threshold: 6 },
  );

  const style: CSSProperties | undefined = leaving
    ? { transform: 'translateY(100%)' }
    : offset > 0
      ? { transform: `translateY(${offset}px)` }
      : undefined;

  return (
    <div className={`${layer.layer} ${modal ? '' : layer.inline} ${styles.layer}`}>
      <Scrim
        className={leaving ? styles.scrimLeaving : undefined}
        onPress={() => {
          onClose('outside');
        }}
      />
      <div
        ref={setRef}
        role="dialog"
        aria-modal={modal ? true : undefined}
        aria-labelledby={title ? titleId : undefined}
        aria-label={title ? undefined : rest['aria-label']}
        tabIndex={-1}
        className={[styles.sheet, dragging ? styles.dragging : '', leaving ? styles.leaving : '', className].filter(Boolean).join(' ')}
        style={style}
      >
        {draggable ? (
          <button
            type="button"
            className={styles.handle}
            aria-label="Close sheet"
            onClick={() => {
              dismiss('action');
            }}
          >
            <span className={styles.handleBar} aria-hidden="true" />
          </button>
        ) : (
          <div className={styles.noHandle} />
        )}
        {title || headerAction ? (
          <div className={styles.header}>
            {title ? (
              <h2 id={titleId} className={styles.title}>
                {title}
              </h2>
            ) : (
              <span />
            )}
            {headerAction}
          </div>
        ) : null}
        <ScrollArea ref={bodyRef} className={styles.body}>
          {children}
        </ScrollArea>
        {footer ? <div className={styles.footer}>{footer}</div> : null}
      </div>
    </div>
  );
}
