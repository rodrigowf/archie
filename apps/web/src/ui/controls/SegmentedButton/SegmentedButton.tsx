/**
 * M3 segmented button (spec 13 §3.5 W-03; mockups §5 "Selection": 40 dp, full radius, outline,
 * selected segment in secondary-container with a check).
 *
 * - Single select: `role="radiogroup"` of `role="radio"` buttons with one tab stop (roving
 *   tabindex); ←/→/↑/↓ move and select, Home/End jump.
 * - Multi select (`multiple`): `role="group"` of toggle buttons (`aria-pressed`), each a tab stop.
 *
 * - `wrap`: segments become separate pills that wrap onto more rows (labels that don't fit one
 *   row at phone width); `wrap="auto"` switches between the two as the available width changes.
 *
 *   <SegmentedButton aria-label="Theme" value={t} onChange={setT}
 *     options={[{ value: 'system', label: 'System', icon: 'brightness_auto' }, …]} />
 */
import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type RefObject } from 'react';
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
  /** A small dot after the label (e.g. marks the default level); explain it with `title`. */
  dot?: boolean;
  /** Tooltip. */
  title?: string;
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
  /** Separate pills wrapping onto more rows; `auto` = only when one row would cut a label. */
  wrap?: boolean | 'auto';
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
      title={option.title}
      onClick={() => {
        if (!disabled) onClick();
      }}
    >
      {icon ? <Glyph name={icon} size={18} className={option.iconOnly ? undefined : styles.icon} /> : null}
      {option.iconOnly ? null : <span className={styles.label}>{option.label}</span>}
      {option.dot ? <span className={styles.dot} aria-hidden="true" /> : null}
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
  wrap,
  className,
  style,
  ...aria
}: SingleSegmentedButtonProps<V>) {
  const [current, setCurrent] = useControllable<V | undefined>(value, defaultValue, (v) => {
    if (v !== undefined) onChange?.(v);
  });
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const groupRef = useRef<HTMLDivElement | null>(null);
  const wrapped = useWrap(wrap, groupRef, fullWidth);
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
      ref={groupRef}
      role="radiogroup"
      aria-disabled={disabled || undefined}
      className={cx(styles.group, wrapped ? styles.wrap : fullWidth && styles.fullWidth, className)}
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
  wrap,
  className,
  style,
  ...aria
}: MultiSegmentedButtonProps<V>) {
  const [current, setCurrent] = useControllable<readonly V[]>(value, defaultValue ?? [], (v) => {
    onChange?.([...v]);
  });
  const groupRef = useRef<HTMLDivElement | null>(null);
  const wrapped = useWrap(wrap, groupRef, fullWidth);
  return (
    <div
      {...aria}
      ref={groupRef}
      role="group"
      aria-disabled={disabled || undefined}
      className={cx(styles.group, wrapped ? styles.wrap : fullWidth && styles.fullWidth, className)}
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

/**
 * Whether the segments need more width than one row has: each segment's natural width (its label's
 * full text width — `scrollWidth` even while cut — plus padding, the icon / check and the dot;
 * full width: the widest one times the count, as segments are equal) against the parent's width. Independent of the current mode, so it never flips back and forth.
 */
function needsWrap(group: HTMLElement, host: HTMLElement, fullWidth: boolean): boolean {
  const avail = host.clientWidth;
  if (avail <= 0) return false;
  let sum = 0;
  let widest = 0;
  const n = group.children.length;
  for (let i = 0; i < n; i++) {
    const seg = group.children[i] as HTMLElement;
    const label = seg.querySelector<HTMLElement>(`.${styles.label ?? 'label'}`);
    const icon = seg.querySelector('svg') ? 24 : 0;
    let w = (label ? label.scrollWidth : 0) + (fullWidth ? 16 : 24);
    if (seg.querySelector(`.${styles.dot ?? 'dot'}`)) w += 12;
    sum += Math.max(w + icon, 48);
    // any segment may get the check when selected: count it, so a new selection never flips the layout
    widest = Math.max(widest, w + 24, 48);
  }
  // full width: equal segments, so the widest one decides
  return (fullWidth ? widest * n : sum) > avail;
}

/**
 * `wrap="auto"`: one row while every segment fits, separate wrapping pills otherwise. Measured in
 * ResizeObserver callbacks (the parent's width, each label's size: before paint, no flash); without
 * it (Safari 12) after each render in an animation frame and on window resizes.
 */
function useWrap(mode: boolean | 'auto' | undefined, group: RefObject<HTMLDivElement | null>, fullWidth: boolean): boolean {
  const [auto, setAuto] = useState(false);
  useEffect(() => {
    if (mode !== 'auto') return undefined;
    const el = group.current;
    const host = el?.parentElement;
    if (!el || !host) return undefined;
    const measure = (): void => {
      setAuto(needsWrap(el, host, fullWidth));
    };
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(measure);
      ro.observe(host);
      const labels = el.querySelectorAll(`.${styles.label ?? 'label'}`);
      for (let i = 0; i < labels.length; i++) {
        const l = labels[i];
        if (l) ro.observe(l);
      }
      return () => {
        ro.disconnect();
      };
    }
    const frame = requestAnimationFrame(measure);
    window.addEventListener('resize', measure);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('resize', measure);
    };
  });
  return mode === true || (mode === 'auto' && auto);
}
