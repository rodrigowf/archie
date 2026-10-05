/**
 * Circular progress (spec 13 §3.5 W-03). Determinate (the composer's context ring, mockups
 * `.ring`: 20 dp, 3 dp stroke, outline-variant track, primary value, round cap) or indeterminate
 * (a turning arc) when `value` is undefined. `tone` recolors the value arc (context at 50 % /
 * 80 %: warning / error). `role="progressbar"`; `aria-label` required by type.
 *
 *   <CircularProgress aria-label="Context used" value={0.62} size={20} thickness={3} />
 */
import type { HTMLAttributes } from 'react';
import { cx } from '@/ui/primitives';
import styles from './CircularProgress.module.css';

export interface CircularProgressProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children' | 'aria-label'> {
  'aria-label': string;
  /** 0…1; omit for indeterminate. */
  value?: number;
  /** Diameter in px (default 40). */
  size?: number;
  /** Stroke width in px (default 4). */
  thickness?: number;
  tone?: 'primary' | 'warning' | 'error';
  valueText?: string;
}

export function CircularProgress({
  value,
  size = 40,
  thickness = 4,
  tone = 'primary',
  valueText,
  className,
  style,
  ...rest
}: CircularProgressProps) {
  const determinate = value !== undefined;
  const v = determinate ? Math.min(1, Math.max(0, value)) : 0.3;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  const half = size / 2;
  return (
    <span
      {...rest}
      role="progressbar"
      aria-valuemin={determinate ? 0 : undefined}
      aria-valuemax={determinate ? 100 : undefined}
      aria-valuenow={determinate ? Math.round(v * 100) : undefined}
      aria-valuetext={determinate ? valueText : undefined}
      className={cx(styles.circular, !determinate && styles.indeterminate, tone !== 'primary' && styles[tone], className)}
      style={{ width: size, height: size, ...style }}
    >
      <svg viewBox={`0 0 ${String(size)} ${String(size)}`} width={size} height={size} focusable="false" aria-hidden="true">
        {determinate ? <circle className={styles.track} cx={half} cy={half} r={r} strokeWidth={thickness} /> : null}
        <circle
          className={styles.value}
          cx={half}
          cy={half}
          r={r}
          strokeWidth={thickness}
          strokeDasharray={`${String(c * v)} ${String(c)}`}
        />
      </svg>
    </span>
  );
}
