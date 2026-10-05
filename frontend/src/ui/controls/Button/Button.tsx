/**
 * M3 common button (spec 13 §3.5 W-03; mockups §5 "Buttons": 40 dp, full radius).
 *
 *   <Button variant="filled" icon="send">Send</Button>
 *   <Button variant="text" tone="error">Delete</Button>
 *   <Button loading>Saving</Button>
 *
 * Variants: filled · tonal · outlined · text · elevated. `tone` recolors for destructive (error)
 * and stall (warning) actions. Disabled is `aria-disabled` (still focusable, never activates);
 * `loading` swaps the leading icon for a spinner and sets `aria-busy`. The hit area is 48 dp tall
 * even when the visual is 32/40 dp. Spacing between icon and label is a margin, never `gap`.
 */
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Spinner, cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { guardClick } from '../shared/hooks';
import styles from './Button.module.css';

export type ButtonVariant = 'filled' | 'tonal' | 'outlined' | 'text' | 'elevated';
export type ButtonTone = 'default' | 'error' | 'warning';
export type ButtonSize = 'small' | 'medium' | 'large';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled'> {
  variant?: ButtonVariant;
  tone?: ButtonTone;
  /** small 32 dp · medium 40 dp (default) · large 56 dp. */
  size?: ButtonSize;
  /** Leading icon (18 px). */
  icon?: IconName;
  /** Use the FILL=1 glyph for `icon` when the manifest has one. */
  iconFilled?: boolean;
  /** Trailing icon (e.g. a dropdown arrow). */
  trailingIcon?: IconName;
  /** Shows a spinner in the icon slot, sets aria-busy and blocks activation. */
  loading?: boolean;
  /** aria-disabled: focusable, not activatable, 38 % content. */
  disabled?: boolean;
  /** Stretch to the container width. */
  fullWidth?: boolean;
  children: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'filled',
    tone = 'default',
    size = 'medium',
    icon,
    iconFilled,
    trailingIcon,
    loading = false,
    disabled = false,
    fullWidth = false,
    type = 'button',
    className,
    onClick,
    children,
    ...rest
  },
  ref,
) {
  const lead = loading ? (
    <Spinner size={18} className={styles.spinner} />
  ) : icon ? (
    <Glyph name={icon} filled={iconFilled} size={size === 'large' ? 24 : 18} className={styles.icon} />
  ) : null;
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      className={cx(
        styles.button,
        'has-state-layer',
        styles[variant],
        tone !== 'default' && styles[tone],
        size !== 'medium' && styles[size],
        lead && styles.withIcon,
        trailingIcon && styles.withTrailing,
        fullWidth && styles.fullWidth,
        className,
      )}
      aria-disabled={disabled || undefined}
      aria-busy={loading || undefined}
      onClick={guardClick(disabled || loading, onClick)}
    >
      {lead}
      <span className={styles.label}>{children}</span>
      {trailingIcon ? <Glyph name={trailingIcon} size={18} className={styles.trailing} /> : null}
    </button>
  );
});
