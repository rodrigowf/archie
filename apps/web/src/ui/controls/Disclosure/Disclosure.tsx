/**
 * Disclosure (spec 13 §3.5 W-03): a header `<button aria-expanded aria-controls>` and a body
 * that is a **sibling** of the button (fixes inv02 §6.2 "button contains div"). The chevron
 * rotates; the body is `hidden` while collapsed (keeps its state mounted).
 *
 *   <Disclosure summary="Thought for 4 s" icon="psychology">…</Disclosure>
 *   <Disclosure variant="chip" icon="data_object" summary="Frontmatter · 4 refs">…</Disclosure>
 *
 * `headingLevel` wraps the button in an h2–h6 (for document outlines). Controlled through
 * `open` + `onOpenChange`, or uncontrolled with `defaultOpen`.
 */
import { createElement, useId, type CSSProperties, type ReactNode } from 'react';
import { Icon, cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { useControllable } from '../shared/hooks';
import styles from './Disclosure.module.css';

export interface DisclosureProps {
  /** Header content (phrasing only: it is inside a button). */
  summary: ReactNode;
  icon?: IconName;
  /** Extra header content at the end, before the chevron (status, count). */
  meta?: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** row: full-width header (default); chip: the compact chip trigger from the mockups. */
  variant?: 'row' | 'chip';
  headingLevel?: 2 | 3 | 4 | 5 | 6;
  disabled?: boolean;
  className?: string;
  headerClassName?: string;
  bodyClassName?: string;
  style?: CSSProperties;
  /** Gallery only. */
  'data-state'?: string;
  children: ReactNode;
}

export function Disclosure({
  summary,
  icon,
  meta,
  open,
  defaultOpen = false,
  onOpenChange,
  variant = 'row',
  headingLevel,
  disabled = false,
  className,
  headerClassName,
  bodyClassName,
  style,
  'data-state': forcedState,
  children,
}: DisclosureProps) {
  const id = useId();
  const bodyId = `dc${id}`;
  const [isOpen, setOpen] = useControllable<boolean>(open, defaultOpen, onOpenChange);
  const button = (
    <button
      type="button"
      className={cx(styles.header, 'has-state-layer', styles[variant], headerClassName)}
      aria-expanded={isOpen}
      aria-controls={bodyId}
      aria-disabled={disabled || undefined}
      data-state={forcedState}
      onClick={() => {
        if (!disabled) setOpen(!isOpen);
      }}
    >
      {icon ? <Glyph name={icon} size={variant === 'chip' ? 18 : 20} className={styles.icon} /> : null}
      <span className={styles.summary}>{summary}</span>
      {meta !== undefined && meta !== null ? <span className={styles.meta}>{meta}</span> : null}
      <Icon name="keyboard_arrow_down" size={variant === 'chip' ? 18 : 20} className={styles.chevron} />
    </button>
  );
  return (
    <div className={cx(styles.disclosure, isOpen && styles.open, className)} style={style}>
      {headingLevel ? createElement(`h${String(headingLevel)}`, { className: styles.heading }, button) : button}
      <div id={bodyId} className={cx(styles.body, bodyClassName)} hidden={!isOpen}>
        {children}
      </div>
    </div>
  );
}
