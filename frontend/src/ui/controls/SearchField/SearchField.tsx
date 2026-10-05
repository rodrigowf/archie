/**
 * Search bar (spec 13 §3.5 W-03; mockups `.search`: 52 dp, full radius, surface-container-high,
 * leading search icon). A clear button appears once there is text; Escape clears a non-empty
 * field (and is left to bubble when the field is already empty, so a drawer can close). Enter
 * calls `onSubmit`. Text is 16 px (iOS focus zoom).
 *
 *   <SearchField label="Search conversations" value={q} onValueChange={setQ} />
 *
 * `label` is the accessible name and, unless `placeholder` is given, the placeholder.
 */
import {
  forwardRef,
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type CSSProperties,
  type InputHTMLAttributes,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { Icon, cx } from '@/ui/primitives';
import { IconButton } from '../IconButton/IconButton';
import { useMergedRef } from '../shared/hooks';
import styles from './SearchField.module.css';

export interface SearchFieldProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'value' | 'defaultValue' | 'onChange' | 'type' | 'size' | 'onSubmit' | 'className' | 'style'
> {
  /** Accessible name (also the default placeholder). */
  label: string;
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  onChange?: (event: ChangeEvent<HTMLInputElement>) => void;
  onSubmit?: (value: string) => void;
  /** Extra trailing content (e.g. a filter IconButton), after the clear button. */
  trailing?: ReactNode;
  /** Label of the clear button (default "Clear search"). */
  clearLabel?: string;
  className?: string;
  style?: CSSProperties;
  'data-state'?: string;
}

export const SearchField = forwardRef<HTMLInputElement, SearchFieldProps>(function SearchField(
  {
    label,
    value,
    defaultValue,
    onValueChange,
    onChange,
    onSubmit,
    trailing,
    clearLabel = 'Clear search',
    placeholder,
    className,
    style,
    disabled,
    id,
    onKeyDown,
    'data-state': forcedState,
    ...rest
  },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? `sf${autoId}`;
  const [inner, setInner] = useState(defaultValue ?? '');
  const current = value ?? inner;
  const node = useRef<HTMLInputElement | null>(null);
  const setRef = useMergedRef(ref, node);

  const commit = (next: string): void => {
    if (value === undefined) setInner(next);
    onValueChange?.(next);
  };
  const handleChange = (e: ChangeEvent<HTMLInputElement>): void => {
    commit(e.target.value);
    onChange?.(e);
  };
  const clear = (): void => {
    commit('');
    node.current?.focus();
  };
  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>): void => {
    onKeyDown?.(e);
    if (e.defaultPrevented) return;
    if (e.key === 'Escape' && current !== '') {
      e.preventDefault();
      e.stopPropagation();
      commit('');
    } else if (e.key === 'Enter') {
      onSubmit?.(current);
    }
  };

  return (
    <div className={cx(styles.root, disabled && styles.disabled, className)} style={style} data-state={forcedState}>
      <Icon name="search" className={styles.lead} />
      <input
        {...rest}
        ref={setRef}
        id={inputId}
        type="search"
        className={styles.input}
        aria-label={label}
        placeholder={placeholder ?? label}
        value={current}
        disabled={disabled}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        autoComplete="off"
        spellCheck={false}
      />
      {current !== '' && !disabled ? (
        <IconButton icon="close" aria-label={clearLabel} size="small" className={styles.clear} onClick={clear} />
      ) : null}
      {trailing}
    </div>
  );
});
