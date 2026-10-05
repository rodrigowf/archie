/**
 * M3 divider (spec 13 §3.5 W-03): 1 dp outline-variant. Rendered as `role="separator"` with
 * `aria-orientation`. `decorative` hides it from
 * assistive technology. Use sparingly: surfaces separate by tone (R1).
 */
import type { HTMLAttributes } from 'react';
import { cx } from '@/ui/primitives';
import styles from './Divider.module.css';

export interface DividerProps extends HTMLAttributes<HTMLElement> {
  orientation?: 'horizontal' | 'vertical';
  /** start: 16 dp inset at the start; middle: 16 dp on both sides. */
  inset?: 'none' | 'start' | 'middle';
  decorative?: boolean;
}

export function Divider({ orientation = 'horizontal', inset = 'none', decorative = false, className, ...rest }: DividerProps) {
  const cls = cx(styles.divider, styles[orientation], inset !== 'none' && styles[inset], className);
  // A <div>, not an <hr>: no UA margins to reset, so a parent's owl spacing (Stack) still applies.
  return decorative ? (
    <div {...rest} className={cls} aria-hidden="true" />
  ) : (
    <div {...rest} className={cls} role="separator" aria-orientation={orientation} />
  );
}
