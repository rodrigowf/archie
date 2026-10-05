/**
 * M3 switch (spec 13 §3.5 W-03; mockups §5 "Selection"): a `<button role="switch">` with
 * `aria-checked`. Space and Enter toggle (native button activation). 52 × 32 dp track inside a
 * 48 dp hit area; the handle grows from 16 to 24 dp when on, 28 dp while pressed.
 *
 *   <Switch aria-label="Wake word" checked={on} onCheckedChange={setOn} />
 *   <Switch label="Reduce motion" />                 visible label, clickable
 *
 * Disabled is `aria-disabled` (focusable, never toggles) at 38 % opacity, as in the mockups.
 */
import { forwardRef, useId, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Icon, cx } from '@/ui/primitives';
import { useControllable } from '../shared/hooks';
import styles from './Switch.module.css';

export interface SwitchProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onChange' | 'role' | 'disabled' | 'children'> {
  checked?: boolean;
  defaultChecked?: boolean;
  onCheckedChange?: (checked: boolean) => void;
  disabled?: boolean;
  /** Visible label; clicking it toggles. Otherwise pass aria-label / aria-labelledby. */
  label?: ReactNode;
  /** Put the visible label after the switch (default: before, like a settings row). */
  labelPosition?: 'start' | 'end';
  /** Show a check icon in the handle when on. */
  showIcon?: boolean;
}

export const Switch = forwardRef<HTMLButtonElement, SwitchProps>(function Switch(
  {
    checked,
    defaultChecked = false,
    onCheckedChange,
    disabled = false,
    label,
    labelPosition = 'start',
    showIcon = false,
    className,
    style,
    id,
    onClick,
    ...rest
  },
  ref,
) {
  const autoId = useId();
  const switchId = id ?? `sw${autoId}`;
  const [on, setOn] = useControllable<boolean>(checked, defaultChecked, onCheckedChange);
  const button = (
    <button
      {...rest}
      ref={ref}
      id={switchId}
      type="button"
      role="switch"
      aria-checked={on}
      aria-disabled={disabled || undefined}
      className={cx(styles.switch, on && styles.on, disabled && styles.disabled, label === undefined && className)}
      style={label === undefined ? style : undefined}
      onClick={(e) => {
        if (disabled) {
          e.preventDefault();
          return;
        }
        onClick?.(e);
        if (!e.defaultPrevented) setOn(!on);
      }}
    >
      <span className={styles.handle} aria-hidden="true">
        {showIcon && on ? <Icon name="check" size={16} /> : null}
      </span>
    </button>
  );
  if (label === undefined) return button;
  const text = (
    <label htmlFor={switchId} className={styles.label}>
      {label}
    </label>
  );
  return (
    <span className={cx(styles.row, disabled && styles.rowDisabled, className)} style={style}>
      {labelPosition === 'start' ? text : null}
      {button}
      {labelPosition === 'end' ? text : null}
    </span>
  );
});
