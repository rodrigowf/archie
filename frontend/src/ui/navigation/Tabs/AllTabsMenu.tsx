/**
 * "N ⌄" all-tabs button + menu (IA §3: "overflow chevron ⌄ All tabs menu with search"; mockups
 * `.alltabs`). Lists every open tab as `menuitemradio` (checked = active) with its leading icon
 * and status; a search field on top filters by title (ArrowDown moves into the list).
 */
import { useRef, useState } from 'react';
import { SearchField } from '@/ui/controls';
import { Menu, MenuItem } from '@/ui/overlays';
import { Icon } from '@/ui/primitives';
import type { TabItem } from './Tabs';
import styles from './Tabs.module.css';

export interface AllTabsMenuProps {
  items: TabItem[];
  value: string | null;
  onChange: (id: string) => void;
  /** Show the search field (default: always). */
  searchable?: boolean;
}

export function filterTabs(items: TabItem[], query: string): TabItem[] {
  const q = query.trim().toLowerCase();
  return q ? items.filter((t) => t.label.toLowerCase().indexOf(q) >= 0) : items;
}

export function AllTabsMenu({ items, value, onChange, searchable = true }: AllTabsMenuProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const anchor = useRef<HTMLButtonElement | null>(null);
  const shown = filterTabs(items, query);
  const close = (): void => {
    setOpen(false);
    setQuery('');
  };
  return (
    <>
      <button
        ref={anchor}
        type="button"
        className={`${styles.allTabs} has-state-layer`}
        aria-label={`All tabs (${items.length})`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          if (open) close();
          else setOpen(true);
        }}
      >
        <span className={styles.allTabsCount}>{items.length}</span>
        <Icon name="keyboard_arrow_down" size={20} />
      </button>
      <Menu
        open={open}
        onClose={close}
        anchor={anchor}
        placement="bottom-end"
        aria-label="All tabs"
        minWidth={300}
        header={
          searchable ? <SearchField label="Search tabs" value={query} onValueChange={setQuery} data-autofocus="" /> : undefined
        }
      >
        {shown.map((t) => (
          <MenuItem
            key={t.id}
            kind="radio"
            checked={t.id === value}
            leading={t.leading ?? (t.icon ? <Icon name={t.icon} size={20} /> : undefined)}
            trailing={
              t.meta || t.status ? (
                <>
                  {t.meta}
                  {t.status ? <span className={styles.menuStatus}>{t.status}</span> : null}
                </>
              ) : undefined
            }
            description={t.statusLabel}
            onSelect={() => {
              onChange(t.id);
            }}
          >
            {t.label}
          </MenuItem>
        ))}
        {shown.length === 0 ? (
          <MenuItem disabled icon="search">
            No tabs match “{query}”
          </MenuItem>
        ) : null}
      </Menu>
    </>
  );
}
