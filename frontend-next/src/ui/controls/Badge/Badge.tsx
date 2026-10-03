/**
 * M3 badge (spec 13 §3.5 W-03; mockups `.badge`): a 6 dp dot or a 16 dp count pill in error
 * colors. Standalone, or anchored to the top-right of `children` (a navigation icon).
 *
 *   <Badge count={3} label="3 sessions need you"><Icon name="forum" /></Badge>
 *   <Badge dot label="New activity" />
 *
 * `label` is the screen-reader text (visually hidden); the badge glyph itself is aria-hidden so the
 * count is not read twice. Counts above `max` show "max+".
 */
import type { HTMLAttributes, ReactNode } from 'react';
import { VisuallyHidden, cx } from '@/ui/primitives';
import styles from './Badge.module.css';

export interface BadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  /** Count to show; 0 or undefined hides a count badge (unless `dot`). */
  count?: number;
  /** Small dot instead of a count. */
  dot?: boolean;
  max?: number;
  /** Screen-reader text, e.g. "3 sessions need you". */
  label?: string;
  /** Anchor element; the badge sits on its top-right corner. */
  children?: ReactNode;
}

export function Badge({ count, dot = false, max = 99, label, className, children, ...rest }: BadgeProps) {
  const visible = dot || (count !== undefined && count > 0);
  const text = count !== undefined && count > max ? `${String(max)}+` : String(count ?? '');
  const glyph = visible ? (
    <span className={cx(dot ? styles.dot : styles.count, children !== undefined && styles.anchored)} aria-hidden="true">
      {dot ? null : text}
    </span>
  ) : null;
  const sr = visible && label ? <VisuallyHidden>{label}</VisuallyHidden> : null;
  if (children === undefined) {
    return glyph || sr ? (
      <span {...rest} className={cx(styles.standalone, className)}>
        {glyph}
        {sr}
      </span>
    ) : null;
  }
  return (
    <span {...rest} className={cx(styles.anchor, className)}>
      {children}
      {glyph}
      {sr}
    </span>
  );
}
