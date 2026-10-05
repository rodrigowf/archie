/**
 * Session status indicator (spec 13 §3.5 W-03; mockups §5 chips board, last row):
 *   idle          8 dp success dot        "Open, idle"
 *   working       14 dp spinner            "Working"
 *   warning       front_hand, warning      "Needs you"
 *   disconnected  warning icon, warning    "Disconnected"
 *   off           8 dp outline dot         "Not running"
 *
 * Never color alone: it always has a text alternative — `role="img"` + `aria-label`, or the
 * visible text with `showLabel`. `label` overrides the default wording.
 */
import type { HTMLAttributes } from 'react';
import { Icon, Spinner, cx } from '@/ui/primitives';
import styles from './StatusDot.module.css';

export type SessionStatus = 'idle' | 'working' | 'warning' | 'disconnected' | 'off';

export const STATUS_LABELS: Record<SessionStatus, string> = {
  idle: 'Open, idle',
  working: 'Working',
  warning: 'Needs you',
  disconnected: 'Disconnected',
  off: 'Not running',
};

export interface StatusDotProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  status: SessionStatus;
  label?: string;
  /** Show the label as visible text next to the indicator. */
  showLabel?: boolean;
}

function Glyph({ status }: { status: SessionStatus }) {
  switch (status) {
    case 'working':
      return <Spinner size={14} />;
    case 'warning':
      return <Icon name="front_hand" size={18} className={styles.warn} />;
    case 'disconnected':
      return <Icon name="warning" size={18} className={styles.warn} />;
    default:
      return <span className={cx(styles.dot, status === 'off' && styles.off)} aria-hidden="true" />;
  }
}

export function StatusDot({ status, label, showLabel = false, className, ...rest }: StatusDotProps) {
  const text = label ?? STATUS_LABELS[status];
  if (showLabel) {
    return (
      <span {...rest} className={cx(styles.status, styles.labelled, className)} data-status={status}>
        <Glyph status={status} />
        <span className={styles.text}>{text}</span>
      </span>
    );
  }
  return (
    <span {...rest} className={cx(styles.status, className)} role="img" aria-label={text} data-status={status}>
      <Glyph status={status} />
    </span>
  );
}
