/**
 * M3 icon button (spec 13 §3.5 W-03; mockups §5 "Icon buttons and FABs": 48 dp targets).
 *
 *   <IconButton icon="more_vert" aria-label="Session menu" />
 *   <IconButton icon="mic" selectedIcon="mic_off" selected={muted} onClick={toggle} aria-label="Mute" />
 *
 * `aria-label` is required by the type (icon-only control). Passing `selected` makes it a toggle
 * (`aria-pressed`); the selected look is the mockups' `.ib.sel` (primary container, filled glyph).
 * `tone="error"` is the end-call style. Disabled is `aria-disabled`.
 */
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Spinner, cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { guardClick } from '../shared/hooks';
import styles from './IconButton.module.css';

export type IconButtonVariant = 'standard' | 'filled' | 'tonal' | 'outlined';

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'children' | 'aria-label'> {
  icon: IconName;
  /** Required: the accessible name of an icon-only control. */
  'aria-label': string;
  variant?: IconButtonVariant;
  tone?: 'default' | 'error';
  /** small: 40 dp visual inside a 48 dp target; medium: 48 dp (default). */
  size?: 'small' | 'medium';
  /** Icon size in px (default 24). */
  iconSize?: number;
  /** Use the filled glyph (always on when selected). */
  filled?: boolean;
  /** Toggle state: renders aria-pressed. Leave undefined for a plain action. */
  selected?: boolean;
  /** Glyph shown while selected (defaults to `icon`). */
  selectedIcon?: IconName;
  disabled?: boolean;
  loading?: boolean;
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  {
    icon,
    variant = 'standard',
    tone = 'default',
    size = 'medium',
    iconSize = 24,
    filled = false,
    selected,
    selectedIcon,
    disabled = false,
    loading = false,
    type = 'button',
    className,
    onClick,
    ...rest
  },
  ref,
) {
  const isSelected = selected === true;
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      className={cx(
        styles.iconButton,
        'has-state-layer',
        styles[variant],
        tone === 'error' && styles.error,
        size === 'small' && styles.small,
        isSelected && styles.selected,
        className,
      )}
      aria-pressed={selected === undefined ? undefined : selected}
      aria-disabled={disabled || undefined}
      aria-busy={loading || undefined}
      onClick={guardClick(disabled || loading, onClick)}
    >
      {loading ? (
        <Spinner size={Math.round(iconSize * 0.75)} className={styles.spinner} />
      ) : (
        <Glyph name={isSelected && selectedIcon ? selectedIcon : icon} filled={filled || isSelected} size={iconSize} />
      )}
    </button>
  );
});
