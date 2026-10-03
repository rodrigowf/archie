/**
 * Side sheet (spec 13 §3.5): `modal` (scrim, focus trap, Escape, Back, scroll lock, background
 * `aria-hidden`; the tablet list-pane overlay and the session settings sheet) and `standard` (an
 * in-flow `<aside>` beside the content on Expanded, no overlay behaviour).
 *
 * `offset` keeps a modal left sheet clear of the navigation rail (80 px on Medium, mockups §3).
 */
import { useId, useRef, type CSSProperties, type ReactNode, type MutableRefObject, type RefObject } from 'react';
import { useOverlayLayer, type OverlayCloseReason } from '@/ui/a11y';
import { IconButton } from '@/ui/controls';
import { ScrollArea } from '@/ui/primitives';
import layer from '../internal/layer.module.css';
import { Portal } from '../Portal/Portal';
import { Scrim } from '../Scrim/Scrim';
import styles from './SideSheet.module.css';

export interface SideSheetProps {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  variant?: 'modal' | 'standard';
  side?: 'left' | 'right';
  title?: ReactNode;
  'aria-label'?: string;
  /** Leading header content (e.g. a back button) instead of nothing. */
  headerLeading?: ReactNode;
  /** Trailing header content, before the close button. */
  headerActions?: ReactNode;
  /** Show the close button (default true). */
  closeButton?: boolean;
  /** Footer actions (M3: Save / Cancel). */
  footer?: ReactNode;
  children: ReactNode;
  /** Width in px (default 360; the list pane uses 320). */
  width?: number;
  /** Distance from the window edge in px (modal; e.g. 80 next to the rail). */
  offset?: number;
  history?: boolean;
  initialFocus?: RefObject<HTMLElement | null>;
  /** Let the body scroll by itself (default true); false when children bring their own scroller. */
  scrollBody?: boolean;
  className?: string;
  /** Render in place, without overlay behaviour (gallery previews). */
  inline?: boolean;
}

export function SideSheet(props: SideSheetProps) {
  const { open, variant = 'modal', inline } = props;
  if (variant === 'standard') return open ? <SheetPanel {...props} kind="standard" /> : null;
  if (!open) return null;
  return inline ? <InlineModal {...props} /> : <ModalSideSheet {...props} />;
}

function InlineModal(props: SideSheetProps) {
  return (
    <div className={`${layer.layer} ${layer.inline}`}>
      <Scrim />
      <SheetPanel {...props} kind="modal" />
    </div>
  );
}

function ModalSideSheet(props: SideSheetProps) {
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
      <div className={layer.layer}>
        <Scrim
          onPress={() => {
            props.onClose('outside');
          }}
        />
        <SheetPanel {...props} kind="modal" panelRef={ref} />
      </div>
    </Portal>
  );
}

function SheetPanel({
  kind,
  panelRef,
  onClose,
  side = 'right',
  title,
  headerLeading,
  headerActions,
  closeButton = true,
  footer,
  children,
  width = 360,
  offset = 0,
  scrollBody = true,
  className,
  inline,
  ...rest
}: SideSheetProps & { kind: 'modal' | 'standard'; panelRef?: MutableRefObject<HTMLDivElement | null> }) {
  const titleId = useId();
  const modal = kind === 'modal';
  const style: CSSProperties = modal
    ? { width, maxWidth: `calc(100% - ${offset + 56}px)`, [side]: offset }
    : { width };
  const cls = [styles.sheet, modal ? styles.modal : styles.standard, styles[side], className].filter(Boolean).join(' ');
  const labelProps = title ? { 'aria-labelledby': titleId } : { 'aria-label': rest['aria-label'] };
  const content = (
    <>
      {title || headerLeading || headerActions || closeButton ? (
        <div className={styles.header}>
          {headerLeading}
          {title ? (
            <h2 id={titleId} className={styles.title}>
              {title}
            </h2>
          ) : (
            <span className={styles.title} />
          )}
          {headerActions}
          {closeButton ? (
            <IconButton
              icon="close"
              aria-label="Close"
              onClick={() => {
                onClose('action');
              }}
            />
          ) : null}
        </div>
      ) : null}
      {scrollBody ? <ScrollArea className={styles.body}>{children}</ScrollArea> : <div className={styles.bodyPlain}>{children}</div>}
      {footer ? <div className={styles.footer}>{footer}</div> : null}
    </>
  );
  if (!modal) {
    return (
      <aside {...labelProps} className={cls} style={style}>
        {content}
      </aside>
    );
  }
  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-modal={inline ? undefined : true}
      {...labelProps}
      tabIndex={-1}
      className={cls}
      style={style}
    >
      {content}
    </div>
  );
}
