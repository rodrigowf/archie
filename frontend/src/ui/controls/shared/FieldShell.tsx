/**
 * The M3 text-field frame shared by TextField and Select (mockups §5 "Text fields": outlined ·
 * filled · error). Draws the container, the floating label, the outline (a notched
 * <fieldset>/<legend>, so the label cut-out works on any surface color and on Safari 12), the
 * leading/trailing slots and the supporting/error line. The control itself is `children`.
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from './Glyph';
import styles from './Field.module.css';

export type FieldVariant = 'outlined' | 'filled';

export interface FieldShellProps {
  variant: FieldVariant;
  label?: ReactNode;
  /** id of the control: the label's `for`. Omit to render the label as a <span> (custom controls). */
  htmlFor?: string;
  labelId: string;
  /** Label floats above the value (focused, populated, or forced). */
  floated: boolean;
  focused?: boolean;
  error?: boolean;
  disabled?: boolean;
  multiline?: boolean;
  required?: boolean;
  leadingIcon?: IconName;
  /** Icon name or a node (e.g. an IconButton) at the trailing edge. */
  trailing?: IconName | ReactNode;
  supportingId: string;
  /** Helper text, or the error message when `error` is a string. */
  supportingText?: ReactNode;
  className?: string;
  style?: CSSProperties;
  /** Forced visual state for the gallery (`hover`, `focus`). */
  forcedState?: string;
  onLabelClick?: () => void;
  children: ReactNode;
}

function isIconName(x: unknown): x is IconName {
  return typeof x === 'string';
}

export function FieldShell({
  variant,
  label,
  htmlFor,
  labelId,
  floated,
  focused,
  error,
  disabled,
  multiline,
  required,
  leadingIcon,
  trailing,
  supportingId,
  supportingText,
  className,
  style,
  forcedState,
  onLabelClick,
  children,
}: FieldShellProps) {
  const hasLabel = label !== undefined && label !== null && label !== '';
  const labelContent = (
    <>
      {label}
      {required ? <span aria-hidden="true">{' *'}</span> : null}
    </>
  );
  return (
    <div
      className={cx(
        styles.root,
        styles[variant],
        floated && styles.floated,
        (focused || forcedState === 'focus') && styles.focused,
        forcedState === 'hover' && styles.hovered,
        error && styles.error,
        disabled && styles.disabled,
        multiline && styles.multiline,
        hasLabel && styles.labelled,
        leadingIcon && styles.withLeading,
        trailing !== undefined && trailing !== null && styles.withTrailing,
        className,
      )}
      style={style}
    >
      <div className={styles.field}>
        {variant === 'filled' ? <span className={styles.stateLayer} aria-hidden="true" /> : null}
        {leadingIcon ? (
          <span className={styles.leading} aria-hidden="true">
            <Glyph name={leadingIcon} size={24} />
          </span>
        ) : null}
        <div className={styles.control}>{children}</div>
        {trailing !== undefined && trailing !== null ? (
          isIconName(trailing) ? (
            <span className={styles.trailingIcon} aria-hidden="true">
              <Glyph name={trailing} size={24} />
            </span>
          ) : (
            <span className={styles.trailingNode}>{trailing}</span>
          )
        ) : null}
        {hasLabel ? (
          htmlFor ? (
            <label className={styles.label} htmlFor={htmlFor} id={labelId}>
              {labelContent}
            </label>
          ) : (
            // A custom control (combobox) is named by aria-labelledby; clicking the label focuses it.
            // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
            <span className={styles.label} id={labelId} onClick={onLabelClick}>
              {labelContent}
            </span>
          )
        ) : null}
        {variant === 'outlined' ? (
          <fieldset className={styles.outline} aria-hidden="true">
            <legend className={styles.legend}>{hasLabel ? <span>{labelContent}</span> : null}</legend>
          </fieldset>
        ) : (
          <span className={styles.indicator} aria-hidden="true" />
        )}
      </div>
      {supportingText ? (
        <div className={styles.supporting} id={supportingId}>
          {supportingText}
        </div>
      ) : null}
    </div>
  );
}
