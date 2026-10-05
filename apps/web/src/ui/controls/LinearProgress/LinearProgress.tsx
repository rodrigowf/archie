/**
 * M3 (2024) linear progress (spec 13 §3.5 W-03): 4 dp, rounded; primary indicator, a 4 dp gap,
 * secondary-container track, stop dot at the end (determinate). Indeterminate when `value` is
 * undefined. `role="progressbar"`; `aria-label` is required by type.
 *
 *   <LinearProgress aria-label="Uploading" value={0.4} />
 *   <LinearProgress aria-label="Loading history" />
 */
import type { CSSProperties, HTMLAttributes } from 'react';
import { cx } from '@/ui/primitives';
import styles from './LinearProgress.module.css';

export interface LinearProgressProps extends Omit<HTMLAttributes<HTMLDivElement>, 'children' | 'aria-label'> {
  'aria-label': string;
  /** 0…1; omit for indeterminate. */
  value?: number;
  /** Spoken value, e.g. "3 of 7". Defaults to a percentage. */
  valueText?: string;
}

export function LinearProgress({ value, valueText, className, style, ...rest }: LinearProgressProps) {
  const determinate = value !== undefined;
  const v = determinate ? Math.min(1, Math.max(0, value)) : 0;
  const vars = { '--progress': String(v), ...style } as CSSProperties;
  return (
    <div
      {...rest}
      role="progressbar"
      aria-valuemin={determinate ? 0 : undefined}
      aria-valuemax={determinate ? 100 : undefined}
      aria-valuenow={determinate ? Math.round(v * 100) : undefined}
      aria-valuetext={determinate ? valueText : undefined}
      className={cx(styles.progress, !determinate && styles.indeterminate, className)}
      style={vars}
    >
      {determinate ? (
        <>
          <span className={styles.indicator} />
          <span className={styles.track} />
          <span className={styles.stop} />
        </>
      ) : (
        <>
          <span className={cx(styles.bar, styles.bar1)} />
          <span className={cx(styles.bar, styles.bar2)} />
        </>
      )}
    </div>
  );
}
