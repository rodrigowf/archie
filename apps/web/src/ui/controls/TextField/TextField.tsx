/**
 * M3 text field (spec 13 §3.5 W-03; mockups §5 "Text fields"): outlined (default) or filled;
 * floating label; supporting or error text; leading icon; trailing icon or node; multiline with
 * auto-grow. Text is always 16 px (iOS focus zoom, spec 13 §2.6) at every breakpoint.
 *
 *   <TextField label="Server address" supportingText="Port 8765 is added for you" />
 *   <TextField label="SSH host" error="Port must be a number" value={v} onChange={…} />
 *   <TextField label="Notes" multiline maxRows={6} />
 *
 * `error` may be a boolean or the message (shown in place of the supporting text, with
 * aria-invalid and aria-describedby). Other props go to the <input>/<textarea>.
 */
import {
  forwardRef,
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
  type CSSProperties,
  type FocusEvent,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { FieldShell, type FieldVariant } from '../shared/FieldShell';
import { useMergedRef } from '../shared/hooks';
import styles from '../shared/Field.module.css';

type NativeProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'value' | 'defaultValue' | 'onChange'> &
  Pick<TextareaHTMLAttributes<HTMLTextAreaElement>, 'rows'>;

export interface TextFieldProps extends Omit<NativeProps, 'className' | 'style'> {
  label?: ReactNode;
  variant?: FieldVariant;
  value?: string;
  defaultValue?: string;
  onChange?: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => void;
  /** Called with the new string value (convenience next to onChange). */
  onValueChange?: (value: string) => void;
  supportingText?: ReactNode;
  /** true, or the error message (replaces the supporting text). */
  error?: boolean | ReactNode;
  leadingIcon?: IconName;
  /** An icon name, or a node such as an IconButton (password visibility, clear). */
  trailing?: IconName | ReactNode;
  /** Render a <textarea> that grows with its content. */
  multiline?: boolean;
  /** Rows before the textarea scrolls (default 8). */
  maxRows?: number;
  /** Root element class/style (the frame). */
  className?: string;
  style?: CSSProperties;
  /** Gallery only: force the `hover` or `focus` look. */
  'data-state'?: string;
}

const LINE = 24;

export const TextField = forwardRef<HTMLInputElement | HTMLTextAreaElement, TextFieldProps>(function TextField(
  {
    label,
    variant = 'outlined',
    value,
    defaultValue,
    onChange,
    onValueChange,
    supportingText,
    error,
    leadingIcon,
    trailing,
    multiline = false,
    maxRows = 8,
    rows = 1,
    className,
    style,
    id,
    disabled,
    required,
    placeholder,
    onFocus,
    onBlur,
    'data-state': forcedState,
    'aria-describedby': describedBy,
    ...rest
  },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? `tf${autoId}`;
  const labelId = `${inputId}-label`;
  const supportingId = `${inputId}-support`;
  const [focused, setFocused] = useState(false);
  const [innerFilled, setInnerFilled] = useState(() => (defaultValue ?? '') !== '');
  const filled = value !== undefined ? value !== '' : innerFilled;
  const node = useRef<HTMLInputElement | HTMLTextAreaElement | null>(null);
  const setRef = useMergedRef(ref, node);

  const errorText = typeof error === 'string' || (typeof error === 'object' && error !== null) ? error : null;
  const hasError = Boolean(error);
  const message = errorText ?? supportingText;

  const grow = useCallback(() => {
    const el = node.current;
    if (!multiline || !(el instanceof HTMLTextAreaElement)) return;
    el.style.height = 'auto';
    const cs = window.getComputedStyle(el);
    const pad = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
    const max = maxRows * LINE + pad;
    const next = Math.min(el.scrollHeight, max);
    el.style.height = `${String(next)}px`;
    el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden';
  }, [multiline, maxRows]);

  useLayoutEffect(() => {
    grow();
  }, [grow, value]);

  const handleChange = (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>): void => {
    if (value === undefined) setInnerFilled(e.target.value !== '');
    if (value === undefined) grow();
    onChange?.(e);
    onValueChange?.(e.target.value);
  };
  const handleFocus = (e: FocusEvent<HTMLInputElement & HTMLTextAreaElement>): void => {
    setFocused(true);
    onFocus?.(e);
  };
  const handleBlur = (e: FocusEvent<HTMLInputElement & HTMLTextAreaElement>): void => {
    setFocused(false);
    onBlur?.(e);
  };

  const hasLabel = label !== undefined && label !== null && label !== '';
  const common = {
    id: inputId,
    className: styles.input,
    disabled,
    required,
    placeholder,
    value,
    defaultValue,
    onChange: handleChange,
    onFocus: handleFocus,
    onBlur: handleBlur,
    'aria-invalid': hasError || undefined,
    'aria-describedby': cx(message ? supportingId : undefined, describedBy) || undefined,
  };

  return (
    <FieldShell
      variant={variant}
      label={label}
      htmlFor={inputId}
      labelId={labelId}
      floated={!hasLabel || focused || filled || forcedState === 'focus' || Boolean(placeholder && focused)}
      focused={focused}
      error={hasError}
      disabled={disabled}
      multiline={multiline}
      required={required}
      leadingIcon={leadingIcon}
      trailing={trailing}
      supportingId={supportingId}
      supportingText={message}
      className={className}
      style={style}
      forcedState={forcedState}
    >
      {multiline ? (
        <textarea
          {...(rest as TextareaHTMLAttributes<HTMLTextAreaElement>)}
          {...common}
          ref={setRef as (el: HTMLTextAreaElement | null) => void}
          rows={rows}
        />
      ) : (
        <input {...rest} {...common} ref={setRef as (el: HTMLInputElement | null) => void} />
      )}
    </FieldShell>
  );
});
