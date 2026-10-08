/**
 * Settings building blocks (mockups phone (e) and (f)): grouped rows that show their current
 * value, labelled fields with one short helper line and the details behind ⓘ, the scope chip
 * ("This device" / "Archie (server)"), inline notices and the load-error state.
 */
import { useId, useState, type ReactNode } from 'react';
import { Button, IconButton, Tag } from '@/ui/controls';
import { Icon, cx, typeClass, type IconName } from '@/ui/primitives';
import styles from './settings.module.css';

/** Stable ids for label / help / details association. */
export function useFieldId(prefix: string): string {
  return `${prefix}${useId().replace(/:/g, '')}`;
}

// ───────────────────────── home ─────────────────────────

export function SettingsGroup({ title, meta, children }: { title: string; meta?: ReactNode; children: ReactNode }) {
  const id = useFieldId('sg');
  return (
    <section className={styles.group} aria-labelledby={id}>
      <h2 id={id} className={styles.groupHeader}>
        <span>{title}</span>
        {meta ? <span className={styles.groupMeta}>{meta}</span> : null}
      </h2>
      <ul className={styles.rows}>{children}</ul>
    </section>
  );
}

export interface SettingsRowProps {
  icon: IconName;
  title: string;
  summary: ReactNode;
  selected?: boolean;
  disabled?: boolean;
  /** Why it is disabled (read as the summary instead). */
  disabledReason?: string;
  onOpen: () => void;
  showChevron?: boolean;
}

export function SettingsRow({ icon, title, summary, selected, disabled, disabledReason, onOpen, showChevron = true }: SettingsRowProps) {
  return (
    <li className={styles.rowItem}>
      <button
        type="button"
        className={cx(styles.row, 'has-state-layer', selected && styles.rowSelected)}
        aria-current={selected ? 'page' : undefined}
        aria-disabled={disabled || undefined}
        data-settings-row=""
        onClick={() => {
          if (!disabled) onOpen();
        }}
      >
        <Icon name={icon} size={24} className={styles.rowIcon} />
        <span className={styles.rowText}>
          <span className={styles.rowTitle}>{title}</span>{' '}
          <span className={styles.rowSummary}>{disabled && disabledReason ? disabledReason : summary}</span>
        </span>
        {showChevron ? <Icon name="chevron_right" size={24} className={styles.rowChevron} /> : null}
      </button>
    </li>
  );
}

// ───────────────────────── detail pages ─────────────────────────

export type Scope = 'device' | 'server';

/** Device-vs-server scope, always visible on a detail page (IA §7). */
export function ScopeChip({ scope, name }: { scope: Scope; name: string }) {
  return (
    <Tag variant="scope" icon={scope === 'device' ? 'devices' : 'dns'} className={styles.scope} data-scope={scope}>
      {scope === 'device' ? 'This device' : 'Archie (server)'}
      {name ? ` · ${name}` : ''}
    </Tag>
  );
}

export function FieldStack({ children, label }: { children: ReactNode; label?: string }) {
  return label ? (
    <div className={styles.stackBlock}>
      <h3 className={styles.stackLabel}>{label}</h3>
      <div className={styles.fieldStack}>{children}</div>
    </div>
  ) : (
    <div className={cx(styles.fieldStack, styles.stackBlock)}>{children}</div>
  );
}

export interface FieldProps {
  /** Visible label (omit when the control carries its own, e.g. Select). */
  label?: ReactNode;
  labelId?: string;
  /** Current value, right-aligned (sliders). */
  value?: ReactNode;
  /** One short line. */
  help?: ReactNode;
  /** The details behind ⓘ. */
  info?: ReactNode;
  /** A control on the label row's end (Switch). */
  trailing?: ReactNode;
  children?: ReactNode;
  /** Small actions after the helper line ("Use default"). */
  footer?: ReactNode;
  className?: string;
}

/** One labelled setting: label + value, the control, one helper line, details behind ⓘ. */
export function Field({ label, labelId, value, help, info, trailing, children, footer, className }: FieldProps) {
  const [open, setOpen] = useState(false);
  const detailsId = useFieldId('fd');
  const helpLine =
    help || info ? (
      <p className={styles.help}>
        {help}
        {info ? (
          <button
            type="button"
            className={cx(styles.infoButton, 'has-state-layer', open && styles.infoOpen)}
            aria-expanded={open}
            aria-controls={detailsId}
            aria-label={open ? 'Hide details' : 'Details'}
            onClick={() => {
              setOpen((o) => !o);
            }}
          >
            <Icon name="info" size={16} />
          </button>
        ) : null}
      </p>
    ) : null;
  const details = info ? (
    <div id={detailsId} className={styles.details} hidden={!open}>
      {info}
    </div>
  ) : null;
  return (
    <div className={cx(styles.field, className)}>
      {label !== undefined || trailing ? (
        <div className={styles.fieldRow}>
          <div className={styles.fieldLabelCol}>
            {label !== undefined ? (
              <span id={labelId} className={styles.fieldLabel}>
                {label}
              </span>
            ) : null}
            {trailing ? helpLine : null}
          </div>
          {value !== undefined ? <span className={styles.fieldValue}>{value}</span> : null}
          {trailing ? <span className={styles.fieldTrailing}>{trailing}</span> : null}
        </div>
      ) : null}
      {children ? <div className={styles.fieldControl}>{children}</div> : null}
      {trailing ? null : helpLine}
      {details}
      {footer}
    </div>
  );
}

export type NoticeTone = 'info' | 'warning' | 'error';

export function Notice({
  tone = 'info',
  title,
  children,
  onDismiss,
  action,
}: {
  tone?: NoticeTone;
  title?: ReactNode;
  children?: ReactNode;
  onDismiss?: () => void;
  action?: ReactNode;
}) {
  const icon: IconName = tone === 'error' ? 'error' : tone === 'warning' ? 'warning' : 'info';
  return (
    <div className={cx(styles.notice, styles[`notice-${tone}`])} role={tone === 'info' ? 'status' : 'alert'}>
      <Icon name={icon} size={20} className={styles.noticeIcon} />
      <div className={styles.noticeBody}>
        {title ? <p className={cx(typeClass('title-small'), styles.noticeTitle)}>{title}</p> : null}
        {children ? <div className={styles.noticeText}>{children}</div> : null}
        {action ? <div className={styles.noticeAction}>{action}</div> : null}
      </div>
      {onDismiss ? <IconButton icon="close" size="small" aria-label="Dismiss" onClick={onDismiss} /> : null}
    </div>
  );
}

/** The page could not load its data (offline, server error): the verbatim message + Retry. */
export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <Notice tone="error" title="Couldn't load the server settings" action={<Button variant="tonal" icon="refresh" onClick={onRetry}>Retry</Button>}>
      {message}
    </Notice>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <p className={styles.loading} role="status">
      {label}
    </p>
  );
}
