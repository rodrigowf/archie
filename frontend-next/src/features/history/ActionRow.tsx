/**
 * A list-pane row with row actions (spec 13 §3.6: "menu on touch, hover icons on pointer";
 * inv02 F-19 SessionItem / SessionMenu, F-36 VizItem). The row itself is one real `button`
 * (fixes inv02 §6.2: div rows with onClick); the actions are sibling buttons, never nested.
 *
 * - Pointer devices (`hover: hover` + fine pointer): icon buttons appear on hover in place of the
 *   trailing stamp. They are mouse affordances (out of the tab order); the keyboard path is the
 *   ⋮ menu, which shows while the row has focus.
 * - Touch devices: the ⋮ menu button is always visible.
 */
import { useId, useRef, useState, type ReactNode } from 'react';
import type { IconName } from '@/ui/icons';
import { IconButton } from '@/ui/controls';
import { NavigationDrawerItem } from '@/ui/navigation';
import { Menu, MenuItem } from '@/ui/overlays';
import { cx } from '@/ui/primitives';
import styles from './history.module.css';

export interface RowAction {
  readonly id: string;
  readonly icon: IconName;
  readonly label: string;
  readonly onSelect: () => void;
  readonly destructive?: boolean;
  /** Only in the ⋮ menu (not as a hover icon). */
  readonly menuOnly?: boolean;
}

export interface ActionRowProps {
  readonly leading: ReactNode;
  readonly label: ReactNode;
  readonly supporting?: ReactNode;
  /** Stamp or status; hidden while the hover icons show. */
  readonly trailing?: ReactNode;
  readonly active?: boolean;
  readonly title?: string;
  readonly onClick: () => void;
  readonly actions: readonly RowAction[];
  /** Accessible name of the ⋮ button (default "More actions"); the row is its description. */
  readonly menuLabel?: string;
  readonly className?: string;
  /** Extra attributes for tests / QA. */
  readonly 'data-row'?: string;
}

export function ActionRow({ leading, label, supporting, trailing, active, title, onClick, actions, menuLabel = 'More actions', className, ...rest }: ActionRowProps) {
  const [open, setOpen] = useState(false);
  // Action names stay short ("Rename", "More actions"); the row button describes which item.
  const rowId = `row${useId().replace(/:/g, '')}`;
  const menuRef = useRef<HTMLButtonElement>(null);
  const hoverActions = actions.filter((a) => !a.menuOnly);
  return (
    <div
      className={cx(styles.row, supporting ? styles.rowTwo : null, open ? styles.rowMenuOpen : null, className)}
      style={{ ['--row-actions' as string]: String(hoverActions.length) }}
      data-row={rest['data-row']}
    >
      <NavigationDrawerItem
        id={rowId}
        className={styles.rowMain}
        leading={leading}
        label={label}
        supporting={supporting}
        trailing={trailing !== undefined && trailing !== null && trailing !== '' ? <span className={styles.trail}>{trailing}</span> : undefined}
        active={active}
        title={title}
        onClick={onClick}
      />
      {hoverActions.length ? (
        <span className={styles.hoverActions}>
          {hoverActions.map((a) => (
            <IconButton
              key={a.id}
              size="small"
              iconSize={20}
              icon={a.icon}
              aria-label={a.label}
              aria-describedby={rowId}
              title={a.label}
              tabIndex={-1}
              onClick={a.onSelect}
            />
          ))}
        </span>
      ) : null}
      <span className={styles.menuSlot}>
        <IconButton
          ref={menuRef}
          size="small"
          iconSize={20}
          icon="more_vert"
          aria-label={menuLabel}
          aria-describedby={rowId}
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={() => {
            setOpen((o) => !o);
          }}
        />
      </span>
      <Menu
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        anchor={menuRef}
        placement="bottom-end"
        minWidth={200}
        aria-label={menuLabel}
      >
        {actions.map((a) => (
          <MenuItem key={a.id} icon={a.icon} destructive={a.destructive} onSelect={a.onSelect}>
            {a.label}
          </MenuItem>
        ))}
      </Menu>
    </div>
  );
}
