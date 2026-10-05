/**
 * M3 checkbox (spec 13 §3.5 W-03). A real `<input type="checkbox">` (keyboard, forms and screen
 * readers for free) under an 18 dp drawn box, with a 40 dp state layer and a 48 dp hit area.
 *
 *   <Checkbox label="Enabled" checked={on} onCheckedChange={setOn} />
 *   <Checkbox aria-label="Select row" indeterminate />
 *
 * `indeterminate` sets the DOM property (exposed as "mixed"). `error` colors it and sets
 * aria-invalid. Pass `label` for a visible label, or `aria-label`/`aria-labelledby`.
 */
import { forwardRef, useLayoutEffect, useRef, type ChangeEvent, type InputHTMLAttributes, type ReactNode } from 'react';
import { Icon, cx } from '@/ui/primitives';
import { useMergedRef } from '../shared/hooks';
import styles from './Checkbox.module.css';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: ReactNode;
  indeterminate?: boolean;
  error?: boolean;
  onCheckedChange?: (checked: boolean) => void;
  /** Gallery only: force `hover`, `focus` or `pressed`. */
  'data-state'?: string;
}

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  {
    label,
    indeterminate = false,
    error = false,
    onCheckedChange,
    onChange,
    className,
    style,
    disabled,
    'data-state': forcedState,
    ...rest
  },
  ref,
) {
  const node = useRef<HTMLInputElement | null>(null);
  const setRef = useMergedRef(ref, node);
  useLayoutEffect(() => {
    if (node.current) node.current.indeterminate = indeterminate;
  }, [indeterminate]);
  const handleChange = (e: ChangeEvent<HTMLInputElement>): void => {
    onChange?.(e);
    onCheckedChange?.(e.target.checked);
  };
  const control = (
    <span className={styles.control}>
      <input
        {...rest}
        ref={setRef}
        type="checkbox"
        className={styles.input}
        disabled={disabled}
        aria-invalid={error || undefined}
        onChange={handleChange}
      />
      <span className={styles.layer} aria-hidden="true" />
      <span className={styles.box} aria-hidden="true">
        <Icon name="check" size={18} className={styles.check} />
        <span className={styles.dash} />
      </span>
    </span>
  );
  const rootClass = cx(styles.root, error && styles.error, disabled && styles.disabled, className);
  return label !== undefined ? (
    <label className={rootClass} style={style} data-state={forcedState}>
      {control}
      <span className={styles.label}>{label}</span>
    </label>
  ) : (
    <span className={rootClass} style={style} data-state={forcedState}>
      {control}
    </span>
  );
});
