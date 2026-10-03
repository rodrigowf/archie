/**
 * M3 chips (spec 13 §3.5 W-03; mockups §5 "Chips": assist · filter · input · provider).
 *
 *   <Chip icon="tv">Plan the TV setup</Chip>                          assist (default)
 *   <Chip variant="filter" selected={on} onSelectedChange={setOn}>Archie</Chip>
 *   <Chip variant="input" onRemove={drop}>refactor.md</Chip>        label + remove button
 *   <Chip variant="suggestion" icon="bolt">This week's energy use</Chip>   40 dp (empty state)
 *
 * Assist/filter/suggestion chips are buttons (filter: `aria-pressed`, a check replaces the icon
 * when selected). An input chip is a group: its label is text, its remove control is a real
 * button named "Remove <label>" (Backspace/Delete on it also remove). 32 dp visual, 48 dp target.
 * `Tag` (provider / scope labels) is in Tag.tsx.
 */
import { forwardRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { guardClick } from '../shared/hooks';
import styles from './Chip.module.css';

export type ChipVariant = 'assist' | 'filter' | 'input' | 'suggestion';

export interface ChipProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'children'> {
  variant?: ChipVariant;
  icon?: IconName;
  /** Trailing icon on an assist chip (e.g. keyboard_arrow_down for a disclosure chip). */
  trailingIcon?: IconName;
  /** Filter chip state (aria-pressed). */
  selected?: boolean;
  onSelectedChange?: (selected: boolean) => void;
  /** Input chip: called by the remove button (or Backspace/Delete on it). */
  onRemove?: () => void;
  /** Accessible name of the remove button (default "Remove <children>" when children is text). */
  removeLabel?: string;
  disabled?: boolean;
  children: ReactNode;
  /** Gallery only: force `hover`, `focus` or `pressed`. */
  'data-state'?: string;
}

export const Chip = forwardRef<HTMLButtonElement, ChipProps>(function Chip(
  {
    variant = 'assist',
    icon,
    trailingIcon,
    selected = false,
    onSelectedChange,
    onRemove,
    removeLabel,
    disabled = false,
    className,
    onClick,
    type = 'button',
    children,
    'data-state': forcedState,
    ...rest
  },
  ref,
) {
  if (variant === 'input') {
    const name = removeLabel ?? (typeof children === 'string' ? `Remove ${children}` : 'Remove');
    const onKey = (e: KeyboardEvent<HTMLButtonElement>): void => {
      if ((e.key === 'Backspace' || e.key === 'Delete') && !disabled) {
        e.preventDefault();
        onRemove?.();
      }
    };
    return (
      <span
        className={cx(styles.chip, styles.input, icon && styles.withIcon, disabled && styles.disabled, className)}
        data-state={forcedState}
      >
        {icon ? <Glyph name={icon} size={18} className={styles.icon} /> : null}
        <span className={styles.label}>{children}</span>
        <button
          ref={ref}
          type="button"
          className={cx(styles.remove, 'has-state-layer')}
          aria-label={name}
          aria-disabled={disabled || undefined}
          onClick={guardClick(disabled, () => onRemove?.())}
          onKeyDown={onKey}
        >
          <Glyph name="close" size={18} />
        </button>
      </span>
    );
  }

  const isFilter = variant === 'filter';
  const showCheck = isFilter && selected;
  const lead: IconName | undefined = showCheck ? 'check' : icon;
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      data-state={forcedState}
      className={cx(
        styles.chip,
        'has-state-layer',
        styles[variant],
        lead && styles.withIcon,
        trailingIcon && styles.withTrailing,
        showCheck && styles.selected,
        disabled && styles.disabled,
        className,
      )}
      aria-pressed={isFilter ? selected : undefined}
      aria-disabled={disabled || undefined}
      onClick={guardClick(disabled, (e) => {
        onClick?.(e);
        if (isFilter && !e.defaultPrevented) onSelectedChange?.(!selected);
      })}
    >
      {lead ? <Glyph name={lead} size={18} className={styles.icon} /> : null}
      <span className={styles.label}>{children}</span>
      {trailingIcon ? <Glyph name={trailingIcon} size={18} className={styles.trailing} /> : null}
    </button>
  );
});
