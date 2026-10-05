/**
 * Navigation rail (spec 13 §3.5, IA §3; mockups `.rail`, `.rd`, `.fab-new`): 80 dp wide, an
 * optional leading menu button (Medium: opens the list-pane overlay), a FAB slot ("＋ New"),
 * destinations with a 56 × 32 active-indicator pill and the filled icon when selected, and
 * end destinations pinned to the bottom (Settings).
 *
 * `<nav>` landmark; destinations are buttons with `aria-current="page"`; one tab stop with
 * Up/Down/Home/End moving between destinations (roving focus).
 */
import { useRef, useState, type ReactNode } from 'react';
import { useRovingFocus } from '@/ui/a11y';
import { hasFilledVariant, type IconName } from '@/ui/icons';
import { Icon } from '@/ui/primitives';
import styles from './NavigationRail.module.css';

export interface RailDestination {
  id: string;
  label: string;
  icon: IconName;
  /** Small badge on the icon (a count, or `true` for a dot). */
  badge?: number | boolean;
  /** Accessible badge text, e.g. "3 unread". */
  badgeLabel?: string;
}

export interface NavigationRailProps {
  destinations: RailDestination[];
  /** Destinations pinned at the bottom (Settings). */
  endDestinations?: RailDestination[];
  value: string | null;
  onChange: (id: string) => void;
  /** The "＋ New" FAB: a W-03 `<Fab lowered>` menu trigger. */
  fab?: ReactNode;
  /** Leading button above the FAB (☰ on Medium). */
  menuButton?: ReactNode;
  'aria-label'?: string;
  className?: string;
}

const ITEM = '[data-rail-item]';

export function NavigationRail({
  destinations,
  endDestinations = [],
  value,
  onChange,
  fab,
  menuButton,
  'aria-label': label = 'Main',
  className,
}: NavigationRailProps) {
  const ref = useRef<HTMLElement | null>(null);
  const all = [...destinations, ...endDestinations];
  const [focusId, setFocusId] = useState<string | null>(null);
  const tabStop = all.some((d) => d.id === focusId) ? focusId : all.some((d) => d.id === value) ? value : (all[0]?.id ?? null);
  const onKeyDown = useRovingFocus(ref, { itemSelector: ITEM, orientation: 'vertical', loop: true });

  const renderItem = (d: RailDestination) => {
    const active = d.id === value;
    return (
      <button
        key={d.id}
        type="button"
        data-rail-item=""
        className={active ? `${styles.dest} ${styles.active}` : styles.dest}
        aria-current={active ? 'page' : undefined}
        tabIndex={d.id === tabStop ? 0 : -1}
        onFocus={() => {
          setFocusId(d.id);
        }}
        onClick={() => {
          onChange(d.id);
        }}
      >
        <span className={styles.pill}>
          {active && hasFilledVariant(d.icon) ? <Icon name={d.icon} filled /> : <Icon name={d.icon} />}
          {d.badge ? (
            <span className={typeof d.badge === 'number' ? styles.badgeCount : styles.badgeDot} aria-hidden="true">
              {typeof d.badge === 'number' ? (d.badge > 99 ? '99+' : d.badge) : null}
            </span>
          ) : null}
        </span>
        <span className={styles.label}>{d.label}</span>
        {d.badge && d.badgeLabel ? <span className="visually-hidden">, {d.badgeLabel}</span> : null}
      </button>
    );
  };

  return (
    // Arrow-key navigation between the destination buttons (roving focus).
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <nav ref={ref} aria-label={label} className={className ? `${styles.rail} ${className}` : styles.rail} onKeyDown={onKeyDown}>
      {menuButton ? <div className={styles.menu}>{menuButton}</div> : null}
      {fab ? <div className={styles.fab}>{fab}</div> : null}
      <div className={styles.group}>{destinations.map(renderItem)}</div>
      <span className={styles.grow} />
      {endDestinations.length ? <div className={styles.group}>{endDestinations.map(renderItem)}</div> : null}
    </nav>
  );
}
