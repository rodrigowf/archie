/**
 * M3 segmented button (spec 13 §3.5 W-03; mockups §5 "Selection": 40 dp, full radius, outline,
 * selected segment in secondary-container with a check).
 *
 * - Single select: `role="radiogroup"` of `role="radio"` buttons with one tab stop (roving
 *   tabindex); ←/→/↑/↓ move and select, Home/End jump.
 * - Multi select (`multiple`): `role="group"` of toggle buttons (`aria-pressed`), each a tab stop.
 *
 *   <SegmentedButton aria-label="Theme" value={t} onChange={setT}
 *     options={[{ value: 'system', label: 'System', icon: 'brightness_auto' }, …]} />
 */
import { useRef, type CSSProperties, type KeyboardEvent } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { useControllable } from '../shared/hooks';
import styles from './SegmentedButton.module.css';

export interface SegmentOption<V extends string = string> {
  value: V;
  label: string;
  icon?: IconName;
  disabled?: boolean;
  /** Hide the label visually (icon-only segment); the label stays the accessible name. */
  iconOnly?: boolean;
}

interface CommonProps<V extends string> {
  options: readonly SegmentOption<V>[];
  'aria-label'?: string;
  'aria-labelledby'?: string;
  disabled?: boolean;
  /** Stretch to the container width with equal segments (mockups). */
  fullWidth?: boolean;
  /** Show the check on the selected segment(s) (default true). */
  showCheck?: boolean;
  className?: string;
  style?: CSSProperties;
}

export interface SingleSegmentedButtonProps<V extends string = string> extends CommonProps<V> {
  multiple?: false;
  value?: V;
  defaultValue?: V;
  onChange?: (value: V) => void;
}

export interface MultiSegmentedButtonProps<V extends string = string> extends CommonProps<V> {
  multiple: true;
  value?: readonly V[];
  defaultValue?: readonly V[];
  onChange?: (value: V[]) => void;
}

export type SegmentedButtonProps<V extends string = string> = SingleSegmentedButtonProps<V> | MultiSegmentedButtonProps<V>;

export function SegmentedButton<V extends string = string>(props: SegmentedButtonProps<V>) {
  return props.multiple ? <MultiSegmented {...props} /> : <SingleSegmented {...props} />;
}

function Segment<V extends string>({
  option,
  selected,
  showCheck,
  disabled,
  ...aria
}: {
  option: SegmentOption<V>;
  selected: boolean;
  showCheck: boolean;
  disabled: boolean;
  role?: 'radio';
  tabIndex?: number;
  'aria-checked'?: boolean;
  'aria-pressed'?: boolean;
  onClick: () => void;
  buttonRef?: (el: HTMLButtonElement | null) => void;
}) {
  const { onClick, buttonRef, ...attrs } = aria;
  const icon: IconName | undefined = selected && showCheck ? 'check' : option.icon;
  return (
    <button
      {...attrs}
      ref={buttonRef}
      type="button"
      className={cx(styles.segment, 'has-state-layer', selected && styles.selected, option.iconOnly && styles.iconOnly)}
      aria-disabled={disabled || undefined}
      aria-label={option.iconOnly ? option.label : undefined}
      onClick={() => {
        if (!disabled) onClick();
      }}
    >
      {icon ? <Glyph name={icon} size={18} className={option.iconOnly ? undefined : styles.icon} /> : null}
      {option.iconOnly ? null : <span className={styles.label}>{option.label}</span>}
    </button>
  );
}

function SingleSegmented<V extends string>({
  options,
  value,
  defaultValue,
  onChange,
  disabled = false,
  fullWidth = false,
  showCheck = true,
  className,
  style,
  ...aria
}: SingleSegmentedButtonProps<V>) {
  const [current, setCurrent] = useControllable<V | undefined>(value, defaultValue, (v) => {
    if (v !== undefined) onChange?.(v);
  });
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const enabled = options.map((o, i) => (!disabled && !o.disabled ? i : -1)).filter((i) => i >= 0);
  const selectedIndex = options.findIndex((o) => o.value === current);
  const tabStop = selectedIndex >= 0 ? selectedIndex : (enabled[0] ?? 0);

  const go = (index: number): void => {
    const o = options[index];
    if (!o) return;
    refs.current[index]?.focus();
    setCurrent(o.value);
  };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>): void => {
    if (enabled.length === 0) return;
    const pos = enabled.indexOf(document.activeElement ? refs.current.indexOf(document.activeElement as HTMLButtonElement) : -1);
    let next: number | undefined;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = enabled[(pos + 1) % enabled.length];
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = enabled[(pos - 1 + enabled.length) % enabled.length];
    else if (e.key === 'Home') next = enabled[0];
    else if (e.key === 'End') next = enabled[enabled.length - 1];
    if (next === undefined) return;
    e.preventDefault();
    go(next);
  };

  return (
    // eslint-disable-next-line jsx-a11y/interactive-supports-focus -- focus lives on the radios (roving tabindex)
    <div
      {...aria}
      role="radiogroup"
      aria-disabled={disabled || undefined}
      className={cx(styles.group, fullWidth && styles.fullWidth, className)}
      style={style}
      onKeyDown={onKeyDown}
    >
      {options.map((o, i) => (
        <Segment
          key={o.value}
          option={o}
          role="radio"
          aria-checked={i === selectedIndex}
          tabIndex={i === tabStop ? 0 : -1}
          selected={i === selectedIndex}
          showCheck={showCheck}
          disabled={disabled || Boolean(o.disabled)}
          buttonRef={(el) => {
            refs.current[i] = el;
          }}
          onClick={() => {
            setCurrent(o.value);
          }}
        />
      ))}
    </div>
  );
}

function MultiSegmented<V extends string>({
  options,
  value,
  defaultValue,
  onChange,
  disabled = false,
  fullWidth = false,
  showCheck = true,
  className,
  style,
  ...aria
}: MultiSegmentedButtonProps<V>) {
  const [current, setCurrent] = useControllable<readonly V[]>(value, defaultValue ?? [], (v) => {
    onChange?.([...v]);
  });
  return (
    <div
      {...aria}
      role="group"
      aria-disabled={disabled || undefined}
      className={cx(styles.group, fullWidth && styles.fullWidth, className)}
      style={style}
    >
      {options.map((o) => {
        const on = current.includes(o.value);
        return (
          <Segment
            key={o.value}
            option={o}
            aria-pressed={on}
            selected={on}
            showCheck={showCheck}
            disabled={disabled || Boolean(o.disabled)}
            onClick={() => {
              setCurrent(on ? current.filter((v) => v !== o.value) : [...current, o.value]);
            }}
          />
        );
      })}
    </div>
  );
}
