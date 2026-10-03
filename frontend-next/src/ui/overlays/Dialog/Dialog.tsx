/**
 * Dialog (spec 13 §3.5; mockups `.dlg`). Fixes inv02 §6.2: `role="dialog"` (or `alertdialog`) +
 * `aria-modal="true"` + `aria-labelledby`/`aria-describedby`, focus trap with initial focus,
 * Escape closes it when topmost, focus returns to the trigger, background `aria-hidden`, scroll
 * lock, Back closes it (one history entry).
 *
 * Variants: basic (title, supporting text, actions), confirm (`ConfirmDialog`, with a destructive
 * flavour), and full-screen on Compact (`fullScreen="compact"`: a top bar with close, title and the
 * actions; M3 full-screen dialog).
 */
import { useId, useRef, type ReactNode, type MutableRefObject, type RefObject } from 'react';
import { useOverlayLayer, type OverlayCloseReason } from '@/ui/a11y';
import { Button, IconButton } from '@/ui/controls';
import { Icon, ScrollArea, type IconName } from '@/ui/primitives';
import layer from '../internal/layer.module.css';
import { useCompact } from '../internal/useCompact';
import { Portal } from '../Portal/Portal';
import { Scrim } from '../Scrim/Scrim';
import styles from './Dialog.module.css';

export interface DialogProps {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  title: ReactNode;
  /** Hero icon above a centered title (M3). */
  icon?: IconName;
  /** Supporting text / body. */
  children?: ReactNode;
  /** Buttons, right-aligned (put the confirming action last). */
  actions?: ReactNode;
  role?: 'dialog' | 'alertdialog';
  /** Scrim press and Escape close it (default true). */
  dismissible?: boolean;
  /** `true` always, `'compact'` on phones only. */
  fullScreen?: boolean | 'compact';
  initialFocus?: RefObject<HTMLElement | null>;
  /** Back closes it (default true). */
  history?: boolean;
  /** Max width in px (default 560; M3 basic dialogs are 280–560). */
  maxWidth?: number;
  className?: string;
  /** Render the surface in place, without overlay behaviour (gallery previews). */
  inline?: boolean;
}

export function Dialog(props: DialogProps) {
  const { open, inline } = props;
  if (!open) return null;
  return inline ? <DialogSurface {...props} modal={false} /> : <ModalDialog {...props} />;
}

function ModalDialog(props: DialogProps) {
  const { onClose, dismissible = true, history = true, initialFocus } = props;
  const ref = useRef<HTMLDivElement | null>(null);
  useOverlayLayer({
    open: true,
    onClose,
    containerRef: ref,
    modal: true,
    history,
    escape: dismissible,
    ...(initialFocus ? { initialFocus } : null),
  });
  return (
    <Portal>
      <div className={`${layer.layer} ${styles.layer}`}>
        <Scrim
          onPress={
            dismissible
              ? () => {
                  onClose('outside');
                }
              : undefined
          }
        />
        <DialogSurface {...props} modal surfaceRef={ref} />
      </div>
    </Portal>
  );
}

function DialogSurface({
  title,
  icon,
  children,
  actions,
  role = 'dialog',
  fullScreen,
  maxWidth = 560,
  className,
  onClose,
  modal,
  surfaceRef,
}: DialogProps & { modal: boolean; surfaceRef?: MutableRefObject<HTMLDivElement | null> }) {
  const titleId = useId();
  const bodyId = useId();
  const compact = useCompact(fullScreen === 'compact');
  const full = fullScreen === true || compact;

  const common = {
    ref: surfaceRef,
    role,
    'aria-modal': modal ? true : undefined,
    'aria-labelledby': titleId,
    'aria-describedby': children ? bodyId : undefined,
    tabIndex: -1,
  } as const;

  if (full) {
    return (
      <div {...common} className={[styles.dialog, styles.full, className].filter(Boolean).join(' ')}>
        <div className={styles.fullBar}>
          <IconButton
            icon="close"
            aria-label="Close"
            onClick={() => {
              onClose('action');
            }}
          />
          <h2 id={titleId} className={styles.fullTitle}>
            {title}
          </h2>
          {actions ? <div className={styles.fullActions}>{actions}</div> : null}
        </div>
        <ScrollArea className={styles.fullBody} id={bodyId}>
          {children}
        </ScrollArea>
      </div>
    );
  }

  return (
    <div
      {...common}
      className={[styles.dialog, icon ? styles.hero : '', className].filter(Boolean).join(' ')}
      style={{ maxWidth }}
    >
      {icon ? <Icon name={icon} className={styles.heroIcon} /> : null}
      <h2 id={titleId} className={styles.title}>
        {title}
      </h2>
      {children ? (
        <ScrollArea className={styles.body} id={bodyId}>
          {children}
        </ScrollArea>
      ) : null}
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </div>
  );
}

/* ------------------------------------------------------------------ ConfirmDialog */

export interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  children?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  /** Red confirm action; initial focus goes to Cancel (the safe choice). */
  destructive?: boolean;
  onConfirm: () => void;
  /** Cancel, Escape, Back or scrim press. */
  onCancel: (reason: OverlayCloseReason | 'cancel') => void;
  icon?: IconName;
  /** Disable the confirm action (e.g. while a request runs). */
  busy?: boolean;
  inline?: boolean;
}

export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  cancelLabel = 'Cancel',
  destructive,
  onConfirm,
  onCancel,
  icon,
  busy,
  inline,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  return (
    <Dialog
      open={open}
      inline={inline}
      onClose={(r) => {
        onCancel(r);
      }}
      title={title}
      icon={icon}
      role="alertdialog"
      maxWidth={400}
      initialFocus={destructive ? cancelRef : confirmRef}
      actions={
        <>
          <Button
            ref={cancelRef}
            variant="text"
            onClick={() => {
              onCancel('cancel');
            }}
          >
            {cancelLabel}
          </Button>
          <Button ref={confirmRef} variant="text" tone={destructive ? 'error' : 'default'} loading={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children}
    </Dialog>
  );
}
