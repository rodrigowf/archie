/**
 * Navigation drawer (spec 13 §3.5, IA §5; mockups phone (b) `.drawer`): `modal` on Compact (scrim,
 * focus trap, Escape, Back, scroll lock, background `aria-hidden`; slides from the left) and
 * `standard` (in flow, no overlay behaviour). Content is free-form: a header (Archie mark +
 * connection), search, the history list (W-14), then footer entries. `NavigationDrawerItem` and
 * `NavigationDrawerHeadline` give the M3 item/section look for the parts W-04 owns.
 */
import { forwardRef, useRef, type ButtonHTMLAttributes, type ReactNode, type RefObject } from 'react';
import { useOverlayLayer, type OverlayCloseReason } from '@/ui/a11y';
import { Portal, Scrim } from '@/ui/overlays';
import { Icon, ScrollArea, type IconName } from '@/ui/primitives';
import styles from './NavigationDrawer.module.css';

export interface NavigationDrawerProps {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  variant?: 'modal' | 'standard';
  'aria-label': string;
  /** Fixed content above the scrolling body (mark, connection, search). */
  header?: ReactNode;
  /** Fixed content below the body (Memory, Visuals, Settings). */
  footer?: ReactNode;
  children: ReactNode;
  /** Width in px (default 344, the mockups' phone drawer; max: window − 56). */
  width?: number;
  history?: boolean;
  initialFocus?: RefObject<HTMLElement | null>;
  /** Render in place, without overlay behaviour (gallery previews; parent must be positioned). */
  inline?: boolean;
  className?: string;
}

export function NavigationDrawer(props: NavigationDrawerProps) {
  const { open, variant = 'modal', inline } = props;
  if (variant === 'standard') return <DrawerPanel {...props} modal={false} />;
  if (!open) return null;
  if (inline) {
    return (
      <div className={styles.inlineLayer}>
        <Scrim />
        <DrawerPanel {...props} modal={false} />
      </div>
    );
  }
  return <ModalDrawer {...props} />;
}

function ModalDrawer(props: NavigationDrawerProps) {
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
      <div className={styles.layer}>
        <Scrim
          onPress={() => {
            props.onClose('outside');
          }}
        />
        <DrawerPanel {...props} modal panelRef={ref} />
      </div>
    </Portal>
  );
}

function DrawerPanel({
  modal,
  panelRef,
  variant = 'modal',
  header,
  footer,
  children,
  width = 344,
  className,
  inline,
  ...rest
}: NavigationDrawerProps & { modal: boolean; panelRef?: RefObject<HTMLDivElement | null> }) {
  const label = rest['aria-label'];
  const standard = variant === 'standard';
  const cls = [styles.drawer, standard ? styles.standard : styles.modal, inline ? styles.inline : '', className].filter(Boolean).join(' ');
  const body = (
    <nav aria-label={label} className={styles.nav}>
      {header ? <div className={styles.header}>{header}</div> : null}
      <ScrollArea className={styles.body}>{children}</ScrollArea>
      {footer ? <div className={styles.footer}>{footer}</div> : null}
    </nav>
  );
  if (standard) {
    return (
      <div className={cls} style={{ width }}>
        {body}
      </div>
    );
  }
  return (
    <div
      ref={panelRef as RefObject<HTMLDivElement>}
      role="dialog"
      aria-modal={modal ? true : undefined}
      aria-label={label}
      tabIndex={-1}
      className={cls}
      style={{ width, maxWidth: 'calc(100% - 56px)' }}
    >
      {body}
    </div>
  );
}

/* ------------------------------------------------------------------ items */

export interface NavigationDrawerItemProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  icon?: IconName;
  /** Custom leading content (Archie mark, provider icon). */
  leading?: ReactNode;
  label: ReactNode;
  /** Second line (status, provider). */
  supporting?: ReactNode;
  /** Trailing content: badge, time, status. */
  trailing?: ReactNode;
  active?: boolean;
}

export const NavigationDrawerItem = forwardRef<HTMLButtonElement, NavigationDrawerItemProps>(function NavigationDrawerItem(
  { icon, leading, label, supporting, trailing, active, className, type = 'button', ...rest },
  ref,
) {
  const cls = [styles.item, supporting ? styles.twoLine : '', active ? styles.active : '', 'has-state-layer', className]
    .filter(Boolean)
    .join(' ');
  return (
    <button {...rest} ref={ref} type={type} className={cls} aria-current={active ? 'page' : undefined}>
      {leading || icon ? <span className={styles.lead}>{leading ?? (icon ? <Icon name={icon} size={20} /> : null)}</span> : null}
      <span className={styles.text}>
        <span className={styles.label}>{label}</span>
        {supporting ? <span className={styles.supporting}>{supporting}</span> : null}
      </span>
      {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
    </button>
  );
});

export function NavigationDrawerHeadline({ children, trailing }: { children: ReactNode; trailing?: ReactNode }) {
  return (
    <div className={styles.headline}>
      <span>{children}</span>
      {trailing ? <span className={styles.headlineMeta}>{trailing}</span> : null}
    </div>
  );
}
