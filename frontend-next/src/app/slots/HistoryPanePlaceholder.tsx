/**
 * INTERIM PLACEHOLDER (W-14 `HistoryPane{variant}`). Enough of the approved list (mockups desktop
 * list pane, phone (b) drawer) for the shell to be usable and comparable with the mockups:
 * search (title filter), **Open now** (the open chat tabs with live status), then the session
 * history in date groups Today / Yesterday / Previous 7 days / Earlier. W-14 replaces it with the
 * full pane (provider chips everywhere, rename / duplicate / delete, Archie conflict dialog…).
 */
import { useEffect, useState } from 'react';
import { calendarDayDiff, formatClockTime, formatShortDate } from '@/platform';
import type { SessionInfo } from '@/services';
import { useCatalog, useTabs, type Tab } from '@/stores';
import { SearchField, Tag } from '@/ui/controls';
import { NavigationDrawerHeadline, NavigationDrawerItem } from '@/ui/navigation';
import { Icon } from '@/ui/primitives';
import { ArchieMark } from '../shell/ArchieMark';
import { focusTab, openFromHistory } from '../shell/actions';
import { useTitledTabs } from '../workspace/SessionTabStrip';
import { StatusGlyph, TabLeading } from '../workspace/TabParts';
import { conversationTitle } from '../workspace/titles';
import { PROVIDER_LABEL, useTabSummaries } from '../workspace/tabSummary';
import { SlotNote } from './SlotNote';
import styles from './placeholders.module.css';

type Group = 'Today' | 'Yesterday' | 'Previous 7 days' | 'Earlier';
const GROUPS: readonly Group[] = ['Today', 'Yesterday', 'Previous 7 days', 'Earlier'];
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function groupOf(ts: number, now: number): Group {
  const d = calendarDayDiff(ts, now);
  if (d <= 0) return 'Today';
  if (d === 1) return 'Yesterday';
  if (d < 7) return 'Previous 7 days';
  return 'Earlier';
}

function stamp(ts: number, now: number): string {
  const d = calendarDayDiff(ts, now);
  if (d <= 0) return formatClockTime(ts);
  if (d < 7) return WEEKDAYS[new Date(ts).getDay()] ?? '';
  return formatShortDate(ts, now);
}

/** `Date.now()`, refreshed every minute (date groups and clock times). */
function useMinuteClock(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => {
      setNow(Date.now());
    }, 60_000);
    return () => {
      clearInterval(t);
    };
  }, []);
  return now;
}

function isChat(t: Tab): boolean {
  return t.kind === 'archie' || t.kind === 'agent';
}

export function HistoryPane({ variant = 'pane' }: { variant?: 'pane' | 'drawer' | 'switcher' }) {
  const [query, setQuery] = useState('');
  const sessions = useCatalog((s) => s.sessions);
  const activeId = useTabs((s) => s.activeId);
  const { tabs, titles } = useTitledTabs();
  const open = tabs.filter(isChat);
  const summaries = useTabSummaries(open);
  const q = query.trim().toLowerCase();
  const match = (title: string): boolean => !q || title.toLowerCase().indexOf(q) >= 0;

  const openSdk = new Set(open.map((t) => t.sdkId).filter((x): x is string => !!x));
  const openIds = new Set(open.map((t) => t.id));
  const now = useMinuteClock();
  const grouped = ((): Record<Group, SessionInfo[]> => {
    const out: Record<Group, SessionInfo[]> = { Today: [], Yesterday: [], 'Previous 7 days': [], Earlier: [] };
    const rows = sessions.items
      .filter((s) => !openSdk.has(s.session_id) && !(s.local_id && openIds.has(s.local_id)) && (!q || conversationTitle(s.title, s.is_orchestrator).toLowerCase().indexOf(q) >= 0))
      .slice()
      .sort((a, b) => Date.parse(b.last_activity) - Date.parse(a.last_activity));
    for (const s of rows) out[groupOf(Date.parse(s.last_activity) || 0, now)].push(s);
    return out;
  })();

  const shownOpen = open.filter((t) => match(titles[t.id] ?? ''));

  return (
    <div className={variant === 'pane' ? styles.history : `${styles.history} ${styles.historyDrawer}`}>
      <SearchField label="Search conversations" value={query} onValueChange={setQuery} className={styles.search} data-app-search="" />
      {shownOpen.length ? (
        <>
          <NavigationDrawerHeadline trailing={String(shownOpen.length)}>Open now</NavigationDrawerHeadline>
          {shownOpen.map((t) => {
            const sum = summaries[t.id] ?? null;
            const provider = t.kind === 'agent' && t.provider ? PROVIDER_LABEL[t.provider] : null;
            return (
              <NavigationDrawerItem
                key={t.id}
                leading={<TabLeading tab={t} size={t.kind === 'archie' ? 24 : 20} unseen={t.unseen && t.id !== activeId} />}
                label={titles[t.id] ?? ''}
                supporting={
                  variant === 'pane' ? (
                    <>
                      {t.kind === 'archie' ? <span>Archie</span> : provider ? <Tag>{provider}</Tag> : null}
                      {sum ? <span className={t.kind === 'archie' || provider ? styles.supportText : undefined}>{sum.label}</span> : null}
                    </>
                  ) : undefined
                }
                trailing={
                  <>
                    {variant !== 'pane' && provider ? <Tag>{provider}</Tag> : null}
                    <StatusGlyph summary={sum} />
                  </>
                }
                active={t.id === activeId}
                onClick={() => {
                  focusTab(t.id);
                }}
              />
            );
          })}
        </>
      ) : null}
      {GROUPS.map((g) =>
        grouped[g].length ? (
          <div key={g} role="group" aria-label={g}>
            <NavigationDrawerHeadline>{g}</NavigationDrawerHeadline>
            {grouped[g].map((s) => (
              <NavigationDrawerItem
                key={s.session_id}
                leading={s.is_orchestrator ? <ArchieMark size={20} /> : <Icon name="terminal" size={20} />}
                label={conversationTitle(s.title, s.is_orchestrator)}
                trailing={stamp(Date.parse(s.last_activity) || 0, now)}
                onClick={() => {
                  openFromHistory(s.session_id, s.is_orchestrator);
                }}
              />
            ))}
          </div>
        ) : null,
      )}
      {sessions.error ? <p className={styles.error}>{sessions.error}</p> : null}
      {!sessions.loading && sessions.loadedAt > 0 && sessions.items.length === 0 && open.length === 0 ? (
        <p className={styles.emptyLine}>No conversations yet</p>
      ) : null}
      <SlotNote owner="W-14">History list</SlotNote>
    </div>
  );
}
