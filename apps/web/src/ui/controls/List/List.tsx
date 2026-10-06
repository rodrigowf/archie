/**
 * M3 list and list item (spec 13 §3.5 W-03; mockups `.li` in the list pane and settings).
 *
 *   <List aria-label="Open now">
 *     <ListItem leading="terminal" headline="Refactor voice module"
 *               supporting={<><Tag>Claude</Tag> Ready</>} trailing={<StatusDot status="idle" />}
 *               selected onClick={open} action={<IconButton icon="more_vert" aria-label="Menu" />} />
 *   </List>
 *
 * An actionable row renders a real `<button>` (onClick) or `<a>` (href) — never a div with
 * onClick (inv02 §6.2). `action` sits **beside** that element (no button inside a button).
 * One-line 48 dp, two-line 60 dp, three-line 88 dp (`lines`, default from `supporting`).
 * `size="large"` is the settings row: 64 dp with a 40 dp tile behind the leading icon.
 * `selected` sets `aria-current` (default "true"; "page" for navigation lists).
 */
import { createElement, forwardRef, type HTMLAttributes, type MouseEvent, type ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import { guardClick } from '../shared/hooks';
import styles from './List.module.css';

export interface ListProps extends HTMLAttributes<HTMLUListElement> {
  children: ReactNode;
}

/** A semantic list (`role="list"` restores list semantics that `list-style: none` drops in Safari). */
export const List = forwardRef<HTMLUListElement, ListProps>(function List({ className, children, ...rest }, ref) {
  return (
    // eslint-disable-next-line jsx-a11y/no-redundant-roles -- Safari/VoiceOver drops list semantics without it
    <ul {...rest} ref={ref} role="list" className={cx(styles.list, className)}>
      {children}
    </ul>
  );
});

export interface ListItemProps extends Omit<HTMLAttributes<HTMLElement>, 'onClick'> {
  headline: ReactNode;
  supporting?: ReactNode;
  overline?: ReactNode;
  /** Icon name, or a node (avatar, mark). */
  leading?: IconName | ReactNode;
  /** Use the filled glyph for an icon `leading`. */
  leadingFilled?: boolean;
  /** Short trailing text (time, count), inside the row. */
  meta?: ReactNode;
  /** Non-interactive trailing content inside the row (status dot, chevron, spinner). */
  trailing?: ReactNode;
  /** An interactive control beside the row (IconButton, Switch, Checkbox). */
  action?: ReactNode;
  lines?: 1 | 2 | 3;
  size?: 'medium' | 'large';
  selected?: boolean;
  /** aria-current value when selected (default "true"). */
  current?: 'true' | 'page' | 'location';
  disabled?: boolean;
  href?: string;
  target?: string;
  onClick?: (e: MouseEvent<HTMLElement>) => void;
  /** Gallery only. */
  'data-state'?: string;
}

function isIcon(x: unknown): x is IconName {
  return typeof x === 'string';
}

export const ListItem = forwardRef<HTMLElement, ListItemProps>(function ListItem(
  {
    headline,
    supporting,
    overline,
    leading,
    leadingFilled,
    meta,
    trailing,
    action,
    lines,
    size = 'medium',
    selected = false,
    current = 'true',
    disabled = false,
    href,
    target,
    onClick,
    className,
    style,
    'data-state': forcedState,
    ...rest
  },
  ref,
) {
  const lineCount = lines ?? (supporting ? (overline ? 3 : 2) : 1);
  const interactive = href !== undefined || onClick !== undefined;
  const content = (
    <>
      {leading !== undefined && leading !== null ? (
        <span className={styles.leading} aria-hidden={isIcon(leading) || undefined}>
          {isIcon(leading) ? <Glyph name={leading} filled={leadingFilled} size={size === 'large' ? 24 : 20} /> : leading}
        </span>
      ) : null}
      <span className={styles.text}>
        {/* The {' '} separators keep words apart in the accessible name (not rendered in a flex column). */}
        {overline ? <span className={styles.overline}>{overline}</span> : null}
        {overline ? ' ' : null}
        <span className={styles.headline}>{headline}</span>
        {supporting ? ' ' : null}
        {supporting ? <span className={styles.supporting}>{supporting}</span> : null}
      </span>
      {meta !== undefined && meta !== null ? <span className={styles.meta}>{meta}</span> : null}
      {trailing !== undefined && trailing !== null ? <span className={styles.trailing}>{trailing}</span> : null}
    </>
  );
  const mainClass = cx(styles.main, interactive && 'has-state-layer', interactive && styles.interactive);
  const shared = {
    ...rest,
    ref,
    className: mainClass,
    'data-state': forcedState,
    'aria-current': selected ? current : undefined,
  };
  let main: ReactNode;
  if (href !== undefined) {
    main = createElement(
      'a',
      { ...shared, href: disabled ? undefined : href, target, 'aria-disabled': disabled || undefined, onClick },
      content,
    );
  } else if (onClick !== undefined) {
    main = createElement(
      'button',
      { ...shared, type: 'button', 'aria-disabled': disabled || undefined, onClick: guardClick(disabled, onClick) },
      content,
    );
  } else {
    main = createElement('div', shared, content);
  }
  return (
    <li
      className={cx(
        styles.item,
        styles[`lines${String(lineCount)}`],
        size === 'large' && styles.large,
        selected && styles.selected,
        disabled && styles.disabled,
        action !== undefined && action !== null && styles.withAction,
        className,
      )}
      style={style}
    >
      {main}
      {action !== undefined && action !== null ? <span className={styles.action}>{action}</span> : null}
    </li>
  );
});
