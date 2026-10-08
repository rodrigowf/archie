/**
 * Session switcher (compact; IA §5, §9.1 approved; mockups phone (c)): the phone version of
 * tabs. Opened from the app-bar title. Open sessions with live status, each closable by × or a
 * horizontal swipe, plus "New Archie chat" / "New agent session"; "History" opens the History
 * screen.
 */
import { useRef } from 'react';
import { useProviderLabel, useTabs, type Tab } from '@/stores';
import { useSwipe } from '@/ui/a11y';
import { Button, IconButton, Tag } from '@/ui/controls';
import { BottomSheet } from '@/ui/overlays';
import { NavigationDrawerHeadline } from '@/ui/navigation';
import { navigate } from '../navigation/route';
import { focusTab, newAgent, newArchie, requestCloseTab } from '../shell/actions';
import { setShell, useShell } from '../shell/shellState';
import { useTitledTabs } from './SessionTabStrip';
import { StatusGlyph, TabLeading } from './TabParts';
import { useTabSummaries, type TabSummary } from './tabSummary';
import styles from './workspace.module.css';

function docSubtitle(tab: Tab): string {
  const dir = (tab.path ?? '').split('/').slice(0, -1).join('/');
  const kind = tab.kind === 'memory' ? 'Memory' : 'Visual';
  return dir ? `${kind} · ${dir}` : kind;
}

function SwitcherRow({ tab, title, summary, active }: { tab: Tab; title: string; summary: TabSummary | null; active: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useSwipe(ref, () => {
    requestCloseTab(tab.id);
  });
  const label = useProviderLabel();
  const provider = tab.kind === 'agent' ? label(tab.provider) : null;
  return (
    <div ref={ref} className={active ? `${styles.swRow} ${styles.swActive}` : styles.swRow}>
      <button
        type="button"
        className={`${styles.swMain} has-state-layer`}
        aria-current={active ? 'page' : undefined}
        onClick={() => {
          focusTab(tab.id);
        }}
      >
        <span className={styles.tile}>
          <TabLeading tab={tab} size={tab.kind === 'archie' ? 40 : 24} />
        </span>
        <span className={styles.swText}>
          <span className={styles.swTitle}>{title}</span>
          <span className={styles.swSub}>
            {tab.kind === 'archie' ? <span>Archie</span> : null}
            {provider ? <Tag>{provider}</Tag> : null}
            {summary ? <StatusGlyph summary={summary} /> : null}
            {summary ? <span>{summary.turns > 0 && summary.indicator === 'idle' ? `${summary.label} · ${summary.turns} turns` : summary.label}</span> : null}
            {tab.kind === 'memory' || tab.kind === 'visual' ? <span>{docSubtitle(tab)}</span> : null}
            {tab.unseen && !active ? <span className={styles.newBadge}>New</span> : null}
          </span>
        </span>
      </button>
      <IconButton
        icon="close"
        aria-label={`Close ${title}`}
        onClick={() => {
          requestCloseTab(tab.id);
        }}
      />
    </div>
  );
}

export function SessionSwitcherSheet() {
  const open = useShell((s) => s.switcherOpen);
  const activeId = useTabs((s) => s.activeId);
  const { tabs, titles } = useTitledTabs();
  const summaries = useTabSummaries(tabs);
  const close = (): void => {
    setShell({ switcherOpen: false });
  };
  return (
    <BottomSheet
      open={open}
      onClose={close}
      title="Sessions"
      headerAction={
        <Button
          variant="text"
          onClick={() => {
            setShell({ switcherOpen: false });
            navigate({ name: 'history' });
          }}
        >
          History
        </Button>
      }
      footer={
        <div className={styles.sheetActs}>
          <Button variant="tonal" icon="add_comment" onClick={newArchie}>
            New Archie chat
          </Button>
          <Button variant="outlined" icon="terminal" onClick={newAgent}>
            New agent session
          </Button>
        </div>
      }
    >
      {tabs.length ? <NavigationDrawerHeadline>Open now</NavigationDrawerHeadline> : null}
      {tabs.map((t) => (
        <SwitcherRow key={t.id} tab={t} title={titles[t.id] ?? ''} summary={summaries[t.id] ?? null} active={t.id === activeId} />
      ))}
      {tabs.length === 0 ? <p className={styles.swEmpty}>No open sessions. Start one below.</p> : null}
    </BottomSheet>
  );
}
