/**
 * M3 floating action button (spec 13 §3.5 W-03; mockups §5 "Icon buttons and FABs").
 *
 *   <Fab icon="add" aria-label="New" />                       regular 56 dp
 *   <Fab size="small" icon="add" aria-label="New" />          40 dp (48 dp target)
 *   <Fab size="large" icon="graphic_eq" aria-label="Talk" />  96 dp
 *   <Fab extended icon="add">New</Fab>                        56 dp with a label
 *
 * Used as the "New" menu trigger: pass `aria-haspopup="menu"` and `aria-expanded` (W-04's Menu).
 * Icon-only FABs require `aria-label` by type.
 */
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { guardClick } from '../shared/hooks';
import styles from './Fab.module.css';

interface FabBase extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'children' | 'aria-label'> {
  icon: IconName;
  /** primaryContainer (default, M3) or primary (the empty-state voice button). */
  color?: 'primaryContainer' | 'primary' | 'surface';
  /** Drop the shadow (FABs placed inside a rail or a sheet). */
  lowered?: boolean;
  disabled?: boolean;
}

export type FabProps = FabBase &
  (
    | { extended: true; children: ReactNode; size?: never; 'aria-label'?: string }
    | { extended?: false; size?: 'small' | 'regular' | 'large'; children?: never; 'aria-label': string }
  );

export const Fab = forwardRef<HTMLButtonElement, FabProps>(function Fab(props, ref) {
  const {
    icon,
    color = 'primaryContainer',
    lowered = false,
    disabled = false,
    extended,
    size,
    children,
    type = 'button',
    className,
    onClick,
    ...rest
  } = props;
  const shape = extended ? 'extended' : (size ?? 'regular');
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      className={cx(styles.fab, 'has-state-layer', styles[shape], styles[color], lowered && styles.lowered, className)}
      aria-disabled={disabled || undefined}
      onClick={guardClick(disabled, onClick)}
    >
      <Glyph name={icon} size={shape === 'large' ? 36 : 24} />
      {extended ? <span className={styles.label}>{children}</span> : null}
    </button>
  );
});
