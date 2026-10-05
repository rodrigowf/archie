/**
 * Tabs (spec 13 §3.5, IA §3; mockups `.tabs`, `.tb`, `.alltabs`). `role="tablist"` with roving
 * focus (←/→, Home/End), automatic or manual activation, and three looks:
 *
 * - `primary` / `secondary`: M3 tabs (fixed or `scrollable`), an active indicator under the label.
 * - `strip`: the session tab strip in the top app bar (the base W-07 builds on). 44 px pill tabs
 *   with a leading kind icon, label, meta (provider chip) and status slots; close × visible on
 *   the active tab, on hover and on keyboard focus; middle-click closes; double-click, F2,
 *   context menu or touch long-press asks to rename; Delete closes the focused tab;
 *   Ctrl+Shift+←/→ or a mouse drag reorders (pinned tabs stay first); a fade shows overflow and
 *   the "N ⌄" button opens the searchable all-tabs menu (`allTabsMenu`).
 *
 * The close × is a mouse/touch convenience with `aria-hidden` and no tab stop: a button inside
 * `role="tab"` is invalid (nested-interactive), so keyboard and screen-reader users close with
 * Delete (announced through `aria-keyshortcuts`) or Ctrl+Alt+W.
 */
import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode, type RefObject } from 'react';
import { useDrag, useLongPress, useRovingFocus, type Point } from '@/ui/a11y';
import { Icon, type IconName } from '@/ui/primitives';
import { AllTabsMenu } from './AllTabsMenu';
import styles from './Tabs.module.css';

export interface TabItem {
  id: string;
  /** Plain-text title (accessible name, type-ahead, all-tabs search). */
  label: string;
  icon?: IconName;
  /** Custom leading content (Archie mark) instead of `icon`. */
  leading?: ReactNode;
  /** Provider chip etc., after the label (strip). */
  meta?: ReactNode;
  /** Status indicator (spinner, dot, warning), with its own text alternative. */
  status?: ReactNode;
  /** Short status text for the accessible description ("Working", "Disconnected"). */
  statusLabel?: string;
  closable?: boolean;
  /** Stays first; not draggable (Archie). */
  pinned?: boolean;
  /** Opened in the background and not looked at yet. */
  unseen?: boolean;
  /** id of the tabpanel this tab controls. */
  panelId?: string;
  disabled?: boolean;
}

export interface TabsProps {
  items: TabItem[];
  value: string | null;
  onChange: (id: string) => void;
  'aria-label': string;
  variant?: 'primary' | 'secondary' | 'strip';
  /** Primary/secondary: size to content and scroll horizontally. */
  scrollable?: boolean;
  /** Focus moves with arrows; `automatic` also activates (default automatic). */
  activation?: 'automatic' | 'manual';
  onClose?: (id: string) => void;
  onRename?: (id: string) => void;
  /** Context menu on a tab (right-click / long-press). Without it, both mean rename. */
  onTabContextMenu?: (id: string, at: Point) => void;
  /** Move tab `id` to `index` (within the non-pinned range). */
  onReorder?: (id: string, index: number) => void;
  /** Strip: show the "N ⌄" all-tabs button and menu (default true for strip). */
  allTabsMenu?: boolean;
  className?: string;
}

const TAB = '[role="tab"]:not([aria-disabled="true"])';

export function Tabs(props: TabsProps) {
  return props.variant === 'strip' ? <TabStrip {...props} /> : <M3Tabs {...props} />;
}

/* ------------------------------------------------------------------ shared */

function useTabFocus(items: TabItem[], value: string | null) {
  const [focusId, setFocusId] = useState<string | null>(null);
  const has = (id: string | null): id is string => !!id && items.some((t) => t.id === id && !t.disabled);
  const tabStop = has(focusId) ? focusId : has(value) ? value : (items.find((t) => !t.disabled)?.id ?? null);
  return { tabStop, setFocusId };
}

function tabIdFor(baseId: string, id: string): string {
  return `${baseId}-tab-${id.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
}

/* ------------------------------------------------------------------ primary / secondary */

function M3Tabs({ items, value, onChange, variant = 'primary', scrollable, activation = 'automatic', className, ...rest }: TabsProps) {
  const ref = useRef<HTMLDivElement | null>(null);
  const { tabStop, setFocusId } = useTabFocus(items, value);
  const rove = useRovingFocus(ref, {
    itemSelector: TAB,
    orientation: 'horizontal',
    onMove: (_i, el) => {
      const id = el.getAttribute('data-tab-id');
      if (id && activation === 'automatic') onChange(id);
    },
  });
  useSelectedIntoView(ref, value);
  const cls = [styles.m3, variant === 'secondary' ? styles.secondary : styles.primary, scrollable ? styles.scrollable : '', className]
    .filter(Boolean)
    .join(' ');
  return (
    // Roving focus: the tabs are the tab stops, not the list.
    // eslint-disable-next-line jsx-a11y/interactive-supports-focus
    <div ref={ref} role="tablist" aria-label={rest['aria-label']} aria-orientation="horizontal" className={cls} onKeyDown={rove}>
      {items.map((t) => {
        const selected = t.id === value;
        return (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={t.panelId ? `${t.panelId}-tab` : undefined}
            data-tab-id={t.id}
            aria-selected={selected}
            aria-controls={t.panelId}
            aria-disabled={t.disabled ? true : undefined}
            tabIndex={t.id === tabStop ? 0 : -1}
            className={`${styles.m3Tab} ${selected ? styles.selected : ''} has-state-layer`}
            onFocus={() => {
              setFocusId(t.id);
            }}
            onClick={() => {
              if (!t.disabled) onChange(t.id);
            }}
          >
            <span className={styles.m3Content}>
              {t.leading ?? (t.icon ? <Icon name={t.icon} className={styles.m3Icon} /> : null)}
              <span className={styles.m3Label}>{t.label}</span>
              {selected ? <span className={styles.indicator} aria-hidden="true" /> : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function useSelectedIntoView(ref: RefObject<HTMLElement | null>, value: string | null): void {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || !value) return;
    const tab = Array.from(el.querySelectorAll<HTMLElement>('[data-tab-id]')).find((t) => t.getAttribute('data-tab-id') === value);
    const box = (tab?.closest('[data-tab-wrap]') as HTMLElement | null) ?? tab;
    if (!box) return;
    // Direct scrollLeft assignment (spec 13 §2.6: no scrollIntoView options on Safari 12).
    // Position inside the scroller from rects (offsetLeft is relative to the offsetParent).
    const left = box.getBoundingClientRect().left - el.getBoundingClientRect().left + el.scrollLeft;
    const right = left + box.offsetWidth;
    if (left < el.scrollLeft) el.scrollLeft = Math.max(0, left - 8);
    else if (right > el.scrollLeft + el.clientWidth) el.scrollLeft = right - el.clientWidth + 36;
  }, [ref, value]);
}

/* ------------------------------------------------------------------ strip */

function TabStrip({
  items,
  value,
  onChange,
  activation = 'automatic',
  onClose,
  onRename,
  onTabContextMenu,
  onReorder,
  allTabsMenu = true,
  className,
  ...rest
}: TabsProps) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const baseId = useId();
  const { tabStop, setFocusId } = useTabFocus(items, value);
  const [overflow, setOverflow] = useState({ start: false, end: false });
  const [drag, setDrag] = useState<{ id: string; dx: number } | null>(null);

  const rove = useRovingFocus(listRef, {
    itemSelector: TAB,
    orientation: 'horizontal',
    onMove: (_i, el) => {
      const id = el.getAttribute('data-tab-id');
      if (id && activation === 'automatic') onChange(id);
    },
  });
  useSelectedIntoView(listRef, value);

  // Overflow fades, recomputed on resize, scroll and item changes.
  useEffect(() => {
    const el = listRef.current;
    if (!el) return undefined;
    const update = (): void => {
      const start = el.scrollLeft > 1;
      const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1;
      setOverflow((o) => (o.start === start && o.end === end ? o : { start, end }));
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    el.addEventListener('scroll', update);
    return () => {
      ro.disconnect();
      el.removeEventListener('scroll', update);
    };
  }, [items.length]);

  const pinnedCount = items.filter((t) => t.pinned).length;

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>, t: TabItem, index: number): void => {
    if (e.key === 'Delete' && t.closable && onClose) {
      e.preventDefault();
      onClose(t.id);
      return;
    }
    if (e.key === 'F2' && onRename) {
      e.preventDefault();
      onRename(t.id);
      return;
    }
    if (e.ctrlKey && e.shiftKey && !e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && onReorder && !t.pinned) {
      e.preventDefault();
      const to = Math.min(items.length - 1, Math.max(pinnedCount, index + (e.key === 'ArrowLeft' ? -1 : 1)));
      if (to !== index) onReorder(t.id, to);
      return;
    }
    if (e.key === 'Enter' || e.key === ' ') {
      // Native button activation already clicks; nothing else to do.
      return;
    }
    if (!e.ctrlKey && !e.altKey && !e.metaKey) rove(e);
  };

  const endDrag = (id: string, dx: number): void => {
    setDrag(null);
    const list = listRef.current;
    if (!list || !onReorder || Math.abs(dx) < 8) return;
    const wraps = Array.from(list.querySelectorAll<HTMLElement>('[data-tab-wrap]'));
    const from = wraps.findIndex((w) => w.getAttribute('data-tab-wrap') === id);
    const self = wraps[from];
    if (!self) return;
    const center = self.offsetLeft + self.offsetWidth / 2 + dx;
    let to = 0;
    wraps.forEach((w, i) => {
      if (i !== from && w.offsetLeft + w.offsetWidth / 2 < center) to += 1;
    });
    to = Math.max(pinnedCount, Math.min(items.length - 1, to));
    if (to !== from) onReorder(id, to);
  };

  const fadeCls = [overflow.start ? styles.fadeStart : '', overflow.end ? styles.fadeEnd : ''].filter(Boolean).join(' ');

  return (
    <div className={[styles.strip, className].filter(Boolean).join(' ')}>
      <div
        ref={listRef}
        role="tablist"
        aria-label={rest['aria-label']}
        aria-orientation="horizontal"
        className={`${styles.stripList} ${fadeCls}`}
      >
        {items.map((t, i) => (
          <StripTab
            key={t.id}
            tab={t}
            index={i}
            baseId={baseId}
            selected={t.id === value}
            tabStop={t.id === tabStop}
            dragDx={drag?.id === t.id ? drag.dx : 0}
            draggable={!!onReorder && !t.pinned}
            onFocusTab={() => {
              setFocusId(t.id);
            }}
            onActivate={() => {
              if (!t.disabled) onChange(t.id);
            }}
            onClose={t.closable && onClose ? () => onClose(t.id) : undefined}
            onRename={onRename ? () => onRename(t.id) : undefined}
            onContext={(at) => {
              if (onTabContextMenu) onTabContextMenu(t.id, at);
              else onRename?.(t.id);
            }}
            onKey={(e) => {
              onTabKey(e, t, i);
            }}
            onDragMove={(dx) => {
              setDrag({ id: t.id, dx });
            }}
            onDragEnd={(dx) => {
              endDrag(t.id, dx);
            }}
          />
        ))}
      </div>
      {allTabsMenu && items.length > 0 ? <AllTabsMenu items={items} value={value} onChange={onChange} /> : null}
    </div>
  );
}

interface StripTabProps {
  tab: TabItem;
  index: number;
  baseId: string;
  selected: boolean;
  tabStop: boolean;
  dragDx: number;
  draggable: boolean;
  onFocusTab: () => void;
  onActivate: () => void;
  onClose: (() => void) | undefined;
  onRename: (() => void) | undefined;
  onContext: (at: Point) => void;
  onKey: (e: KeyboardEvent<HTMLButtonElement>) => void;
  onDragMove: (dx: number) => void;
  onDragEnd: (dx: number) => void;
}

function StripTab({
  tab: t,
  baseId,
  selected,
  tabStop,
  dragDx,
  draggable,
  onFocusTab,
  onActivate,
  onClose,
  onRename,
  onContext,
  onKey,
  onDragMove,
  onDragEnd,
}: StripTabProps) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const id = tabIdFor(baseId, t.id);
  const descId = `${id}-desc`;

  useLongPress(wrapRef, (at) => {
    onContext(at);
  });
  useDrag(
    wrapRef,
    {
      onStart: (_p, e) => !(e.target instanceof Element && e.target.closest('[data-tab-close]')),
      onMove: (s) => {
        onDragMove(s.dx);
      },
      onEnd: (s) => {
        onDragEnd(s.dx);
      },
      onCancel: () => {
        onDragEnd(0);
      },
    },
    { enabled: draggable, axis: 'x', threshold: 8, touch: false },
  );

  const onMouseDown = (e: MouseEvent): void => {
    if (e.button === 1) e.preventDefault(); // no autoscroll on middle press
  };
  const onMouseUp = (e: MouseEvent): void => {
    // Middle-click close (spec 13 §2.6: `auxclick` detected via mouseup button 1).
    if (e.button === 1 && onClose) {
      e.preventDefault();
      onClose();
    }
  };

  const description = [t.statusLabel, t.unseen ? 'New activity' : null].filter(Boolean).join(', ');
  const cls = [
    styles.tab,
    selected ? styles.active : '',
    onClose ? styles.closable : '',
    dragDx ? styles.dragging : '',
    t.disabled ? styles.disabledTab : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    // The wrapper only forwards mouse gestures (middle-click, context menu, double-click) that
    // the tab button already exposes through the keyboard (Delete, F2).
    <div
      ref={wrapRef}
      role="presentation"
      data-tab-wrap={t.id}
      className={cls}
      style={dragDx ? { transform: `translateX(${dragDx}px)` } : undefined}
      onMouseDown={onMouseDown}
      onMouseUp={onMouseUp}
      onDoubleClick={() => onRename?.()}
      onContextMenu={(e) => {
        e.preventDefault();
        onContext({ x: e.clientX, y: e.clientY });
      }}
    >
      <button
        type="button"
        role="tab"
        id={id}
        data-tab-id={t.id}
        aria-selected={selected}
        aria-controls={t.panelId}
        aria-disabled={t.disabled ? true : undefined}
        aria-describedby={description ? descId : undefined}
        aria-keyshortcuts={onClose ? 'Delete' : undefined}
        tabIndex={tabStop ? 0 : -1}
        className={styles.tabButton}
        onFocus={onFocusTab}
        onClick={onActivate}
        onKeyDown={onKey}
      >
        {t.leading ? (
          <span className={styles.lead}>{t.leading}</span>
        ) : t.icon ? (
          <Icon name={t.icon} size={20} className={`${styles.lead} ${styles.leadIcon}`} />
        ) : null}
        <span className={styles.title}>{t.label}</span>
      </button>
      {t.meta ? <span className={styles.meta}>{t.meta}</span> : null}
      {t.status ? <span className={styles.status}>{t.status}</span> : null}
      {t.unseen && !selected ? <span className={styles.unseen} aria-hidden="true" /> : null}
      {description ? (
        <span id={descId} className="visually-hidden">
          {description}
        </span>
      ) : null}
      {onClose ? (
        // Mouse/touch only (aria-hidden): keyboard users press Delete on the tab (see header).
        <span
          className={styles.close}
          aria-hidden="true"
          data-tab-close=""
          onClick={(e) => {
            e.stopPropagation();
            onClose();
          }}
        >
          <Icon name="close" size={18} />
        </span>
      ) : null}
    </div>
  );
}

/* ------------------------------------------------------------------ panel */

export interface TabPanelProps {
  id: string;
  /** id of the controlling tab (`${id}-tab` for primary/secondary tabs with `panelId`). */
  labelledBy?: string;
  hidden?: boolean;
  className?: string;
  children: ReactNode;
}

export function TabPanel({ id, labelledBy, hidden, className, children }: TabPanelProps) {
  return (
    <div role="tabpanel" id={id} aria-labelledby={labelledBy ?? `${id}-tab`} hidden={hidden} className={className}>
      {children}
    </div>
  );
}
