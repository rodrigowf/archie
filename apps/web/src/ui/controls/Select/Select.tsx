/**
 * Select (spec 13 §3.5 W-03): an exposed dropdown with the TextField frame.
 *
 * - **Native** `<select>` on touch devices (`pointer: coarse`) and always on the compat build
 *   (Safari 12 / iPad): the OS picker is the best touch UI and needs no popup code.
 * - **Menu** on pointer devices: an APG "select-only combobox" (`role="combobox"` +
 *   `aria-activedescendant`, focus stays on the field) with a `role="listbox"` popup styled like
 *   the M3 menu (mockups `.menu`/`.mi`). Keys: ↓/↑/Enter/Space/Alt+↓ open; ↓/↑/Home/End/PageUp/
 *   PageDown move; Enter/Space/Alt+↑ choose; Escape closes; Tab closes; printable keys type-ahead.
 *   Outside press (mousedown/touchstart, no Pointer Events on Safari 12) closes it.
 *
 * Both modes take the same props. `native` forces a mode (tests, special cases).
 * The popup is positioned under the field inside the component (no portal): put the Select where
 * the popup is not clipped, or swap in W-04's Popover later (same markup and ids).
 */
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { mediaMatches } from '@/platform';
import { Icon, cx, type IconName } from '@/ui/primitives';
import { FieldShell, type FieldVariant } from '../shared/FieldShell';
import { useControllable } from '../shared/hooks';
import fieldStyles from '../shared/Field.module.css';
import styles from './Select.module.css';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  /** Optional secondary line in the menu (not shown by the native picker). */
  description?: string;
}

export interface SelectProps {
  label: ReactNode;
  options: readonly SelectOption[];
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  variant?: FieldVariant;
  /** Shown when no option is selected. */
  placeholder?: string;
  supportingText?: ReactNode;
  error?: boolean | ReactNode;
  disabled?: boolean;
  required?: boolean;
  leadingIcon?: IconName;
  /** Form field name (menu mode renders a hidden input). */
  name?: string;
  id?: string;
  /** Force native (`true`) or menu (`false`); default: native on touch and on compat. */
  native?: boolean;
  className?: string;
  style?: CSSProperties;
  /** Gallery only: `hover`, `focus` or `open`. */
  'data-state'?: string;
}

/** Native picker on the compat build and on touch-first devices (spec 13 §3.5). */
export function prefersNativeSelect(): boolean {
  if (__TARGET__ === 'compat') return true;
  return mediaMatches('(pointer: coarse)');
}

const TYPEAHEAD_MS = 500;

export function Select(props: SelectProps) {
  const [native] = useState(() => props.native ?? prefersNativeSelect());
  return native ? <NativeSelect {...props} /> : <MenuSelect {...props} />;
}

function useFieldIds(id: string | undefined) {
  const autoId = useId();
  const base = id ?? `sel${autoId}`;
  return { base, labelId: `${base}-label`, supportingId: `${base}-support`, listId: `${base}-list` };
}

function errorMessage(error: SelectProps['error'], supporting: ReactNode): ReactNode {
  if (typeof error === 'string' || (typeof error === 'object' && error !== null)) return error;
  return supporting;
}

/* ------------------------------------------------------------------------------------------ */

function NativeSelect({
  label,
  options,
  value,
  defaultValue,
  onChange,
  variant = 'outlined',
  placeholder,
  supportingText,
  error,
  disabled,
  required,
  leadingIcon,
  name,
  id,
  className,
  style,
  'data-state': forcedState,
}: SelectProps) {
  const ids = useFieldIds(id);
  const [current, setCurrent] = useControllable<string>(value, defaultValue ?? '', onChange);
  const [focused, setFocused] = useState(false);
  const message = errorMessage(error, supportingText);
  return (
    <FieldShell
      variant={variant}
      label={label}
      htmlFor={ids.base}
      labelId={ids.labelId}
      floated={current !== '' || Boolean(placeholder) || focused || forcedState === 'focus'}
      focused={focused}
      error={Boolean(error)}
      disabled={disabled}
      required={required}
      leadingIcon={leadingIcon}
      trailing="arrow_drop_down"
      supportingId={ids.supportingId}
      supportingText={message}
      className={cx(styles.select, className)}
      style={style}
      forcedState={forcedState}
    >
      <select
        id={ids.base}
        name={name}
        className={cx(fieldStyles.input, styles.native)}
        value={current}
        disabled={disabled}
        required={required}
        aria-invalid={Boolean(error) || undefined}
        aria-describedby={message ? ids.supportingId : undefined}
        onChange={(e: ChangeEvent<HTMLSelectElement>) => {
          setCurrent(e.target.value);
        }}
        onFocus={() => {
          setFocused(true);
        }}
        onBlur={() => {
          setFocused(false);
        }}
      >
        {current === '' ? (
          <option value="" disabled>
            {placeholder ?? ''}
          </option>
        ) : null}
        {options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </option>
        ))}
      </select>
    </FieldShell>
  );
}

/* ------------------------------------------------------------------------------------------ */

function MenuSelect({
  label,
  options,
  value,
  defaultValue,
  onChange,
  variant = 'outlined',
  placeholder,
  supportingText,
  error,
  disabled,
  required,
  leadingIcon,
  name,
  id,
  className,
  style,
  'data-state': forcedState,
}: SelectProps) {
  const ids = useFieldIds(id);
  const [current, setCurrent] = useControllable<string>(value, defaultValue ?? '', onChange);
  const [focused, setFocused] = useState(false);
  const [openState, setOpen] = useState(false);
  const open = openState || forcedState === 'open';
  const selectedIndex = useMemo(() => options.findIndex((o) => o.value === current), [options, current]);
  const [active, setActive] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const comboRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const typeahead = useRef({ text: '', at: 0 });
  const message = errorMessage(error, supportingText);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : undefined;
  const optionId = (i: number): string => `${ids.listId}-${String(i)}`;

  const enabledIndex = (from: number, step: 1 | -1): number => {
    for (let i = from; i >= 0 && i < options.length; i += step) if (!options[i]?.disabled) return i;
    return -1;
  };
  const first = (): number => enabledIndex(0, 1);
  const last = (): number => enabledIndex(options.length - 1, -1);

  const openAt = (index: number): void => {
    if (disabled) return;
    setOpen(true);
    setActive(index >= 0 ? index : first());
  };
  const close = (): void => {
    setOpen(false);
  };
  const choose = (index: number): void => {
    const o = options[index];
    if (!o || o.disabled) return;
    setCurrent(o.value);
    close();
  };
  const move = (index: number): void => {
    if (index >= 0) setActive(index);
  };

  // Keep the active option visible (scrollTop only; scrollIntoView(options) is not on Safari 12).
  useEffect(() => {
    if (!open || active < 0) return;
    const list = listRef.current;
    const item = list?.children[active] as HTMLElement | undefined;
    if (!list || !item) return;
    if (item.offsetTop < list.scrollTop) list.scrollTop = item.offsetTop;
    else if (item.offsetTop + item.offsetHeight > list.scrollTop + list.clientHeight)
      list.scrollTop = item.offsetTop + item.offsetHeight - list.clientHeight;
  }, [open, active]);

  // Outside press closes (mouse + touch; spec 13 §2.6: no Pointer Events).
  useEffect(() => {
    if (!openState) return;
    const onDown = (e: Event): void => {
      if (rootRef.current && e.target instanceof Node && !rootRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('touchstart', onDown);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('touchstart', onDown);
    };
  }, [openState]);

  const typeAhead = (ch: string): void => {
    const now = Date.now();
    const t = typeahead.current;
    t.text = now - t.at > TYPEAHEAD_MS ? ch : t.text + ch;
    t.at = now;
    const q = t.text.toLowerCase();
    const start = open ? active : selectedIndex;
    const order = options.map((_, i) => (i + Math.max(start, 0) + (t.text.length === 1 ? 1 : 0)) % options.length);
    const hit = order.find((i) => !options[i]?.disabled && options[i]?.label.toLowerCase().startsWith(q));
    if (hit === undefined) return;
    if (open) setActive(hit);
    else openAt(hit);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>): void => {
    if (disabled) return;
    const { key } = e;
    if (!open) {
      if (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Enter' || key === ' ') {
        e.preventDefault();
        openAt(selectedIndex >= 0 ? selectedIndex : key === 'ArrowUp' ? last() : first());
      } else if (key === 'Home') {
        e.preventDefault();
        openAt(first());
      } else if (key === 'End') {
        e.preventDefault();
        openAt(last());
      } else if (key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
        typeAhead(key);
      }
      return;
    }
    switch (key) {
      case 'ArrowDown':
        e.preventDefault();
        if (e.altKey) return;
        move(enabledIndex(active + 1, 1));
        return;
      case 'ArrowUp':
        e.preventDefault();
        if (e.altKey) {
          choose(active);
          return;
        }
        move(enabledIndex(Math.max(active - 1, 0), -1));
        return;
      case 'Home':
        e.preventDefault();
        move(first());
        return;
      case 'End':
        e.preventDefault();
        move(last());
        return;
      case 'PageDown':
        e.preventDefault();
        move(enabledIndex(Math.min(active + 10, options.length - 1), -1));
        return;
      case 'PageUp':
        e.preventDefault();
        move(enabledIndex(Math.max(active - 10, 0), 1));
        return;
      case 'Enter':
      case ' ':
        e.preventDefault();
        choose(active);
        return;
      case 'Escape':
        e.preventDefault();
        e.stopPropagation(); // close the popup only, not the dialog/sheet around it
        close();
        return;
      case 'Tab':
        close();
        return;
      default:
        if (key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) typeAhead(key);
    }
  };

  return (
    <div ref={rootRef} className={cx(styles.select, open && styles.open, className)} style={style}>
      <FieldShell
        variant={variant}
        label={label}
        labelId={ids.labelId}
        floated={Boolean(selected) || Boolean(placeholder) || focused || open || forcedState === 'focus'}
        focused={focused || open}
        error={Boolean(error)}
        disabled={disabled}
        required={required}
        leadingIcon={leadingIcon}
        trailing="arrow_drop_down"
        supportingId={ids.supportingId}
        supportingText={message}
        forcedState={forcedState}
        onLabelClick={() => comboRef.current?.focus()}
      >
        <div
          ref={comboRef}
          id={ids.base}
          role="combobox"
          tabIndex={disabled ? -1 : 0}
          className={cx(fieldStyles.input, fieldStyles.value)}
          aria-labelledby={ids.labelId}
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-controls={ids.listId}
          aria-activedescendant={open && active >= 0 ? optionId(active) : undefined}
          aria-disabled={disabled || undefined}
          aria-invalid={Boolean(error) || undefined}
          aria-required={required || undefined}
          aria-describedby={message ? ids.supportingId : undefined}
          onKeyDown={onKeyDown}
          onClick={() => {
            if (disabled) return;
            if (open) close();
            else openAt(selectedIndex);
          }}
          onFocus={() => {
            setFocused(true);
          }}
          onBlur={() => {
            setFocused(false);
            setOpen(false);
          }}
        >
          {selected ? <span>{selected.label}</span> : <span className={fieldStyles.placeholder}>{placeholder ?? ' '}</span>}
        </div>
      </FieldShell>
      {name ? <input type="hidden" name={name} value={current} /> : null}
      <ul ref={listRef} id={ids.listId} role="listbox" aria-labelledby={ids.labelId} className={styles.list} hidden={!open} tabIndex={-1}>
        {options.map((o, i) => (
          // Keyboard is handled on the combobox (aria-activedescendant); options only take the mouse.
          // eslint-disable-next-line jsx-a11y/click-events-have-key-events
          <li
            key={o.value}
            id={optionId(i)}
            role="option"
            aria-selected={i === selectedIndex}
            aria-disabled={o.disabled || undefined}
            className={cx(styles.option, i === active && styles.active)}
            // Keep focus on the combobox while choosing with the mouse.
            onMouseDown={(e) => {
              e.preventDefault();
            }}
            onMouseMove={() => {
              if (!o.disabled && i !== active) setActive(i);
            }}
            onClick={() => {
              choose(i);
            }}
          >
            <span className={styles.check} aria-hidden="true">
              {i === selectedIndex ? <Icon name="check" size={20} /> : null}
            </span>
            <span className={styles.optionText}>
              <span>{o.label}</span>
              {o.description ? <span className={styles.description}>{o.description}</span> : null}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
