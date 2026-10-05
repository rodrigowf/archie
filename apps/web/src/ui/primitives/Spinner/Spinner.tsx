/**
 * Inline activity spinner (spec 13 §3.5 W-02). For determinate/indeterminate M3 progress use
 * CircularProgress (W-03). With `label` it is a role="status" region; otherwise decorative.
 */
import type { CSSProperties, HTMLAttributes } from 'react';
import styles from './Spinner.module.css';

export interface SpinnerProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  /** Diameter in px (default 14; the mockups use 14 and 18). */
  size?: number;
  /** Accessible status text, e.g. "Working". */
  label?: string;
}

export function Spinner({ size = 14, label, className, style, ...rest }: SpinnerProps) {
  const cls = [styles.spinner, size >= 24 ? styles.large : '', className].filter(Boolean).join(' ');
  const vars = { '--spinner-size': `${size}px`, ...style } as CSSProperties;
  return label ? (
    <span {...rest} className={cls} style={vars} role="status" aria-label={label} />
  ) : (
    <span {...rest} className={cls} style={vars} aria-hidden="true" />
  );
}
