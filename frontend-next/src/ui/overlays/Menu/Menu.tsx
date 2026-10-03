/**
 * Menu (spec 13 §3.5; mockups `.menu` / `.mi`): `role="menu"` in a Popover, roving focus with
 * arrows, Home/End and type-ahead, Escape/Tab/outside press close it, focus returns to the
 * trigger. Items: `MenuItem` (icon, shortcut hint, destructive, disabled, radio/checkbox state),
 * `MenuSeparator`, `MenuLabel`. A `header` slot holds content above the list (the all-tabs search).
 *
 *   const anchor = useRef<HTMLButtonElement>(null);
 *   <button ref={anchor} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>⋮</button>
 *   <Menu open={open} onClose={() => setOpen(false)} anchor={anchor} aria-label="Session menu">
 *     <MenuItem icon="edit" shortcut="F2" onSelect={rename}>Rename</MenuItem>
 *     <MenuSeparator />
 *     <MenuItem icon="delete" destructive onSelect={del}>Delete</MenuItem>
 *   </Menu>
 */
import {
  createContext,
  forwardRef,
  useContext,
  useEffect,
  useRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { focusElement, rovingItems, useRovingFocus, type OverlayCloseReason } from '@/ui/a11y';
import { Icon, type IconName } from '@/ui/primitives';
import { Popover, type Placement, type PopoverAnchor } from '../Popover/Popover';
import styles from './Menu.module.css';

const ITEM_SELECTOR = '[role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"]';

interface MenuCtx {
  close: (reason: OverlayCloseReason) => void;
}
const MenuContext = createContext<MenuCtx | null>(null);

/* ------------------------------------------------------------------ MenuList */

export interface MenuListProps extends Omit<HTMLAttributes<HTMLDivElement>, 'role'> {
  'aria-label'?: string;
  'aria-labelledby'?: string;
  /** Where focus lands when the list mounts focused: first item, last item, the checked item. */
  initialFocus?: 'first' | 'last' | 'selected' | 'none';
  /** Called by Tab (the menu closes and focus returns to the trigger). */
  onTabOut?: () => void;
  dense?: boolean;
  children: ReactNode;
}

/** The `role="menu"` list. Rendered by `Menu`; usable alone for previews or custom popovers. */
export const MenuList = forwardRef<HTMLDivElement, MenuListProps>(function MenuList(
  { initialFocus = 'none', onTabOut, dense, className, onKeyDown, children, ...rest },
  ref,
) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const rove = useRovingFocus(listRef, { itemSelector: ITEM_SELECTOR, orientation: 'vertical', typeahead: true });

  useEffect(() => {
    const el = listRef.current;
    if (!el || initialFocus === 'none') return;
    const items = rovingItems(el, ITEM_SELECTOR);
    const target =
      initialFocus === 'last'
        ? items[items.length - 1]
        : initialFocus === 'selected'
          ? (items.find((i) => i.getAttribute('aria-checked') === 'true') ?? items[0])
          : items[0];
    focusElement(target ?? el);
    // Initial focus only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleKey = (e: KeyboardEvent<HTMLDivElement>): void => {
    onKeyDown?.(e);
    if (e.defaultPrevented) return;
    if (e.key === 'Tab') {
      if (onTabOut) {
        e.preventDefault();
        onTabOut();
      }
      return;
    }
    rove(e);
  };

  const setRef = (el: HTMLDivElement | null): void => {
    listRef.current = el;
    if (typeof ref === 'function') ref(el);
    else if (ref) ref.current = el;
  };

  return (
    <div
      {...rest}
      ref={setRef}
      role="menu"
      tabIndex={-1}
      className={[styles.list, dense ? styles.dense : '', className].filter(Boolean).join(' ')}
      onKeyDown={handleKey}
    >
      {children}
    </div>
  );
});

/* ------------------------------------------------------------------ Menu */

export interface MenuProps {
  open: boolean;
  onClose: (reason: OverlayCloseReason) => void;
  anchor: PopoverAnchor;
  placement?: Placement;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  initialFocus?: MenuListProps['initialFocus'];
  /** Content above the list, inside the popover (e.g. a search field). */
  header?: ReactNode;
  /** Content below the list. */
  footer?: ReactNode;
  minWidth?: number;
  matchAnchorWidth?: boolean;
  /** Render the surface in place (gallery previews). */
  inline?: boolean;
  className?: string;
  id?: string;
  children: ReactNode;
}

export function Menu({
  open,
  onClose,
  anchor,
  placement = 'bottom-start',
  initialFocus = 'first',
  header,
  footer,
  minWidth = 220,
  matchAnchorWidth,
  inline,
  className,
  id,
  children,
  ...aria
}: MenuProps) {
  const ctx: MenuCtx = { close: onClose };
  const headerRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  // With a header (search), focus starts there; ArrowDown moves into the list.
  useEffect(() => {
    if (!open || inline || !header) return;
    const h = headerRef.current;
    if (h) focusElement(h.querySelector<HTMLElement>('[data-autofocus], input, button, [tabindex="0"]'));
    // Initial focus on open only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const onHeaderKey = (e: KeyboardEvent<HTMLDivElement>): void => {
    if (e.key !== 'ArrowDown' || e.defaultPrevented) return;
    const list = listRef.current;
    const first = list ? rovingItems(list, ITEM_SELECTOR)[0] : undefined;
    if (first) {
      e.preventDefault();
      focusElement(first);
    }
  };

  return (
    <Popover
      open={open}
      onClose={onClose}
      anchor={anchor}
      placement={placement}
      matchAnchorWidth={matchAnchorWidth}
      role="presentation"
      inline={inline}
      surfaceClassName={styles.surface}
      className={className}
      style={{ minWidth }}
    >
      <MenuContext.Provider value={ctx}>
        {header ? (
          // Keyboard bridge from the header field into the list; the field itself is focusable.
          // eslint-disable-next-line jsx-a11y/no-static-element-interactions
          <div ref={headerRef} className={styles.header} onKeyDown={onHeaderKey}>
            {header}
          </div>
        ) : null}
        <MenuList
          {...aria}
          ref={listRef}
          id={id}
          initialFocus={inline || header ? 'none' : initialFocus}
          onTabOut={() => {
            onClose('action');
          }}
        >
          {children}
        </MenuList>
        {footer ? <div className={styles.footer}>{footer}</div> : null}
      </MenuContext.Provider>
    </Popover>
  );
}

/* ------------------------------------------------------------------ items */

export interface MenuItemProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'role' | 'onSelect' | 'type'> {
  icon?: IconName;
  /** Custom leading content instead of an icon (e.g. the Archie mark). */
  leading?: ReactNode;
  /** Keyboard hint on the right ("F2", "Ctrl+Alt+W"). */
  shortcut?: string;
  /** Trailing content (badge, status). */
  trailing?: ReactNode;
  destructive?: boolean;
  /** `aria-disabled`: stays focusable (APG) but does nothing. */
  disabled?: boolean;
  /** Radio / checkbox state; renders `menuitemradio` / `menuitemcheckbox`. */
  checked?: boolean;
  kind?: 'radio' | 'checkbox';
  /** Keep the menu open after selecting (default false). */
  keepOpen?: boolean;
  onSelect?: () => void;
  /** Supporting line under the label. */
  description?: ReactNode;
  children: ReactNode;
}

export function MenuItem({
  icon,
  leading,
  shortcut,
  trailing,
  destructive,
  disabled,
  checked,
  kind,
  keepOpen,
  onSelect,
  description,
  className,
  onClick,
  children,
  ...rest
}: MenuItemProps) {
  const ctx = useContext(MenuContext);
  const role = kind === 'radio' ? 'menuitemradio' : kind === 'checkbox' ? 'menuitemcheckbox' : 'menuitem';
  const cls = [
    styles.item,
    destructive ? styles.danger : '',
    checked && kind === 'radio' ? styles.current : '',
    description ? styles.twoLine : '',
    'has-state-layer',
    className,
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <button
      {...rest}
      type="button"
      role={role}
      tabIndex={-1}
      className={cls}
      aria-disabled={disabled ? true : undefined}
      aria-checked={kind ? !!checked : undefined}
      onClick={(e) => {
        onClick?.(e);
        if (disabled || e.defaultPrevented) return;
        onSelect?.();
        if (!keepOpen) ctx?.close('action');
      }}
    >
      {leading ? <span className={styles.lead}>{leading}</span> : icon ? <Icon name={icon} size={20} className={styles.icon} /> : null}
      {description ? (
        <span className={styles.labels}>
          <span className={styles.label}>{children}</span>
          <span className={styles.desc}>{description}</span>
        </span>
      ) : (
        <span className={styles.label}>{children}</span>
      )}
      {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
      {shortcut ? (
        <kbd className={styles.kbd} aria-hidden="true">
          {shortcut}
        </kbd>
      ) : null}
      {kind === 'checkbox' && checked ? <Icon name="check" size={20} className={styles.check} /> : null}
    </button>
  );
}

export function MenuSeparator() {
  return <div role="separator" className={styles.sep} />;
}

/** A non-interactive group label inside a menu. */
export function MenuLabel({ children }: { children: ReactNode }) {
  return (
    <div role="presentation" className={styles.groupLabel}>
      {children}
    </div>
  );
}
