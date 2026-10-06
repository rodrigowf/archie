/**
 * The workspace's top bar (spec 13 §4.2; IA §3, §5; mockups `.wtop`, phone `.appbar`).
 *
 * - Expanded / medium: the session tab strip integrated in the bar, then the status text
 *   ("Thinking…", "Using Bash…"; turns · cost · context in a tooltip; expanded only) and the
 *   ⋮ session menu. Expanded with the list pane collapsed adds the "show list" toggle first.
 * - Compact: ☰ (drawer) · title ⌄ (session switcher; subtitle = live status; horizontal swipe
 *   switches sessions) · voice state (W-12) · ⋮.
 */
import { cycleTab, setPref, usePrefs, useTabs } from '@/stores';
import { IconButton } from '@/ui/controls';
import { Tooltip } from '@/ui/overlays';
import { TopAppBar } from '@/ui/navigation';
import { ConnectionLine } from '../shell/AppDrawer';
import { ArchieMark } from '../shell/ArchieMark';
import { setShell, useShell } from '../shell/shellState';
import { SessionMenu, VoiceAction } from '../slots';
import type { WindowClass } from '../useWindowClass';
import { SessionTabStrip, useTitledTabs } from './SessionTabStrip';
import { StatusGlyph, TabLeading } from './TabParts';
import { countersText, useTabSummary } from './tabSummary';
import styles from './workspace.module.css';

function useActive() {
  const activeId = useTabs((s) => s.activeId);
  const { tabs, titles } = useTitledTabs();
  const tab = tabs.find((t) => t.id === activeId);
  return { tab, title: tab ? (titles[tab.id] ?? '') : '' };
}

function StatusText() {
  const { tab } = useActive();
  const summary = useTabSummary(tab);
  if (!summary) return null;
  const counters = countersText(summary);
  const text = summary.turns > 0 ? `${summary.label} · ${summary.turns} ${summary.turns === 1 ? 'turn' : 'turns'}` : summary.label;
  const body = (
    // A focusable status chip so the tooltip (turns · cost · context) is reachable by keyboard.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
    <span className={styles.wstat} tabIndex={counters ? 0 : undefined} role="status" aria-live="off">
      <StatusGlyph summary={summary} />
      <span>{text}</span>
    </span>
  );
  return counters ? <Tooltip label={counters}>{body}</Tooltip> : body;
}

function WideBar({ wc }: { wc: WindowClass }) {
  const { tab } = useActive();
  const collapsed = usePrefs((p) => p.listPaneCollapsed);
  return (
    <header className={styles.wtop}>
      {wc === 'expanded' && collapsed ? (
        <IconButton
          icon="left_panel_open"
          aria-label="Show list"
          onClick={() => {
            setPref('listPaneCollapsed', false);
          }}
        />
      ) : null}
      <div className={styles.stripSlot}>
        <SessionTabStrip />
      </div>
      {wc === 'expanded' ? <StatusText /> : null}
      {tab ? <SessionMenu key={tab.id} localId={tab.id} /> : null}
    </header>
  );
}

function CompactBar() {
  const { tab, title } = useActive();
  const summary = useTabSummary(tab);
  const switcherOpen = useShell((s) => s.switcherOpen);
  const subtitle = tab ? (
    summary ? (
      <>
        <StatusGlyph summary={summary} />
        {summary.label}
      </>
    ) : tab.kind === 'memory' ? (
      'Memory'
    ) : (
      'Visual'
    )
  ) : (
    <ConnectionLine />
  );
  return (
    <TopAppBar
      className={styles.appbar}
      leading={
        <IconButton
          icon="menu"
          aria-label="Open navigation"
          onClick={() => {
            setShell({ drawerOpen: true });
          }}
        />
      }
      titleIcon={tab ? <TabLeading tab={tab} size={24} /> : <ArchieMark size={24} />}
      title={tab ? title : 'Archie'}
      subtitle={subtitle}
      onTitleClick={() => {
        setShell({ switcherOpen: true });
      }}
      titleButtonLabel="Switch session"
      titleExpanded={switcherOpen}
      onTitleSwipe={(dir) => {
        cycleTab(dir === 'left' ? 1 : -1);
      }}
      actions={
        tab ? (
          <>
            <VoiceAction localId={tab.id} />
            <SessionMenu key={tab.id} localId={tab.id} />
          </>
        ) : undefined
      }
    />
  );
}

export function WorkspaceTopBar({ wc }: { wc: WindowClass }) {
  return wc === 'compact' ? <CompactBar /> : <WideBar wc={wc} />;
}
