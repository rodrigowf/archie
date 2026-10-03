/**
 * M3 (2024) slider (spec 13 §3.5 W-03; mockups §5 "Selection": 16 dp track, bar handle, stop
 * dot, value bubble).
 *
 * The control is a native `<input type="range">` (keyboard, touch, screen readers, Safari 12),
 * transparent and sized so its 4 dp thumb (`-webkit-slider-thumb`) sits exactly on the drawn
 * handle; the visible track is drawn underneath from the same value.
 *
 * **Commits on release** (fixes inv02 §6.2 "PUT per tick"): `onValueChange` fires live while
 * dragging (every `input` event); `onCommit` fires once from the native `change` event (pointer
 * release, or each keyboard step). A controlled `value` is the committed value: the slider shows
 * its own draft while the user is interacting and snaps to `value` afterwards.
 *
 *   <Slider aria-label="VAD threshold" min={0} max={1} step={0.01} value={v}
 *           onCommit={save} formatValue={(x) => x.toFixed(2)} />
 */
import {
  forwardRef,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
  type InputHTMLAttributes,
} from 'react';
import { cx } from '@/ui/primitives';
import { useMergedRef } from '../shared/hooks';
import styles from './Slider.module.css';

export interface SliderProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'type' | 'value' | 'defaultValue' | 'onChange' | 'min' | 'max' | 'step' | 'size'
> {
  value?: number;
  defaultValue?: number;
  min?: number;
  max?: number;
  step?: number;
  /** Live value while the user drags or presses keys (do not save here). */
  onValueChange?: (value: number) => void;
  /** Final value: on release, or per keyboard step. Save here. */
  onCommit?: (value: number) => void;
  /** Text for the value bubble and aria-valuetext. */
  formatValue?: (value: number) => string;
  /** When the value bubble shows: while dragging/focused (default), always, or never. */
  valueLabel?: 'auto' | 'always' | 'never';
  /** Draw the stop dot at the end of the track (default true). */
  showStop?: boolean;
  disabled?: boolean;
  'data-state'?: string;
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

export const Slider = forwardRef<HTMLInputElement, SliderProps>(function Slider(
  {
    value,
    defaultValue,
    min = 0,
    max = 100,
    step = 1,
    onValueChange,
    onCommit,
    formatValue = String,
    valueLabel = 'auto',
    showStop = true,
    disabled = false,
    className,
    style,
    onMouseDown,
    onTouchStart,
    'data-state': forcedState,
    ...rest
  },
  ref,
) {
  const [draft, setDraft] = useState(() => value ?? defaultValue ?? min);
  const [editing, setEditing] = useState(false);
  const [dragging, setDragging] = useState(false);
  const shown = value === undefined || editing ? draft : value;
  const node = useRef<HTMLInputElement | null>(null);
  const setRef = useMergedRef(ref, node);
  const commitRef = useRef(onCommit);
  useLayoutEffect(() => {
    commitRef.current = onCommit;
  });

  // Commit from the native `change` event: React's onChange fires on every `input`.
  useEffect(() => {
    const el = node.current;
    if (!el) return;
    const onNativeChange = (): void => {
      const v = Number(el.value);
      setDraft(v);
      setEditing(false);
      commitRef.current?.(v);
    };
    el.addEventListener('change', onNativeChange);
    return () => {
      el.removeEventListener('change', onNativeChange);
    };
  }, []);

  // The bubble shows while a mouse/touch drag is active; the drag ends anywhere on the page.
  useEffect(() => {
    if (!dragging) return;
    const end = (): void => {
      setDragging(false);
    };
    window.addEventListener('mouseup', end);
    window.addEventListener('touchend', end);
    window.addEventListener('touchcancel', end);
    return () => {
      window.removeEventListener('mouseup', end);
      window.removeEventListener('touchend', end);
      window.removeEventListener('touchcancel', end);
    };
  }, [dragging]);

  const handleInput = (e: FormEvent<HTMLInputElement>): void => {
    const v = Number(e.currentTarget.value);
    setDraft(v);
    setEditing(true);
    onValueChange?.(v);
  };

  const fraction = max > min ? clamp((shown - min) / (max - min), 0, 1) : 0;
  const vars = { '--slider-f': String(fraction), ...style } as CSSProperties;
  const text = formatValue(shown);

  return (
    <div
      className={cx(
        styles.slider,
        disabled && styles.disabled,
        (dragging || forcedState === 'pressed') && styles.dragging,
        valueLabel === 'always' && styles.always,
        valueLabel === 'never' && styles.never,
        className,
      )}
      style={vars}
      data-state={forcedState}
    >
      <input
        {...rest}
        ref={setRef}
        type="range"
        className={styles.input}
        min={min}
        max={max}
        step={step}
        value={shown}
        disabled={disabled}
        aria-valuetext={formatValue === String ? undefined : text}
        onInput={handleInput}
        onChange={() => undefined}
        onMouseDown={(e) => {
          if (!disabled) setDragging(true);
          onMouseDown?.(e);
        }}
        onTouchStart={(e) => {
          if (!disabled) setDragging(true);
          onTouchStart?.(e);
        }}
      />
      <span className={styles.track} aria-hidden="true">
        <span className={styles.active} />
        <span className={styles.inactive} />
        {showStop ? <span className={styles.stop} /> : null}
        <span className={styles.handle} />
        <span className={styles.bubble}>{text}</span>
      </span>
    </div>
  );
});
