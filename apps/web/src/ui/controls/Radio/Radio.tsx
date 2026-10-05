/**
 * M3 radio button and group (spec 13 §3.5 W-03). Real `<input type="radio">`s sharing one `name`,
 * so the browser gives the group one tab stop and arrow-key selection (↑/↓/←/→ move and select).
 *
 *   <RadioGroup label="Theme" value={t} onChange={setT}>
 *     <Radio value="system" label="System" />
 *     <Radio value="dark" label="Dark" />
 *   </RadioGroup>
 *
 * The group is `role="radiogroup"`, named by its visible `label` (or `aria-label`).
 */
import {
  createContext,
  forwardRef,
  useContext,
  useId,
  type ChangeEvent,
  type HTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
} from 'react';
import { cx } from '@/ui/primitives';
import { useControllable } from '../shared/hooks';
import styles from './Radio.module.css';

interface RadioGroupContextValue {
  name: string;
  value: string;
  disabled: boolean;
  error: boolean;
  select: (value: string) => void;
}

const RadioGroupContext = createContext<RadioGroupContextValue | null>(null);

export interface RadioGroupProps extends Omit<HTMLAttributes<HTMLDivElement>, 'onChange' | 'defaultValue'> {
  /** Visible group label (or pass aria-label). */
  label?: ReactNode;
  name?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
  error?: boolean;
  /** Lay the radios out in a row instead of a column. */
  orientation?: 'vertical' | 'horizontal';
  children: ReactNode;
}

export function RadioGroup({
  label,
  name,
  value,
  defaultValue = '',
  onChange,
  disabled = false,
  error = false,
  orientation = 'vertical',
  className,
  children,
  ...rest
}: RadioGroupProps) {
  const autoId = useId();
  const labelId = `rg${autoId}-label`;
  const [current, setCurrent] = useControllable<string>(value, defaultValue, onChange);
  return (
    <div
      {...rest}
      role="radiogroup"
      aria-labelledby={label !== undefined ? labelId : rest['aria-labelledby']}
      aria-disabled={disabled || undefined}
      aria-invalid={error || undefined}
      className={cx(styles.group, orientation === 'horizontal' && styles.horizontal, className)}
    >
      {label !== undefined ? (
        <div className={styles.groupLabel} id={labelId}>
          {label}
        </div>
      ) : null}
      <RadioGroupContext.Provider value={{ name: name ?? `rg${autoId}`, value: current, disabled, error, select: setCurrent }}>
        <div className={styles.items}>{children}</div>
      </RadioGroupContext.Provider>
    </div>
  );
}

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'size'> {
  value: string;
  label?: ReactNode;
  /** Gallery only: force `hover`, `focus` or `pressed`. */
  'data-state'?: string;
}

export const Radio = forwardRef<HTMLInputElement, RadioProps>(function Radio(
  { value, label, className, style, disabled, checked, name, onChange, 'data-state': forcedState, ...rest },
  ref,
) {
  const group = useContext(RadioGroupContext);
  const isDisabled = disabled ?? group?.disabled ?? false;
  const isChecked = group ? group.value === value : checked;
  const handleChange = (e: ChangeEvent<HTMLInputElement>): void => {
    onChange?.(e);
    if (e.target.checked) group?.select(value);
  };
  const control = (
    <span className={styles.control}>
      <input
        {...rest}
        ref={ref}
        type="radio"
        className={styles.input}
        name={group?.name ?? name}
        value={value}
        checked={isChecked}
        disabled={isDisabled}
        onChange={handleChange}
      />
      <span className={styles.layer} aria-hidden="true" />
      <span className={styles.ring} aria-hidden="true" />
    </span>
  );
  const rootClass = cx(styles.root, group?.error && styles.error, isDisabled && styles.disabled, className);
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
