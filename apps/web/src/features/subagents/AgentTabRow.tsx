/**
 * The nested agent tab row (PLAN §4.4 item 3, decision 1): M3 secondary scrollable tabs above the
 * message list — "Main" plus one tab per subagent, created automatically in spawn order as lazy
 * placeholders (SA-5: a transcript loads only when its tab is shown). New tabs never take the
 * selection (FOCUS-1). Nested agents carry "↳". The "N ⌄" menu lists every agent, closed ones
 * included; "Close finished" hides finished tabs (they come back when opened from a card or the
 * list). With only Main left the row hides (`showsAgentTabs`); when its own "Close finished" empties
 * it, focus moves to the composer's Agents button, which opens the list. Manual activation: arrows
 * move focus, Enter / Space opens (each open fetches a transcript).
 */
import { memo, useMemo, useRef } from 'react';
import type { Subagent } from '@/protocol';
import { useSession, useShallow } from '@/stores';
import { announce } from '@/ui/a11y';
import { IconButton } from '@/ui/controls';
import { AllTabsMenu, Tabs, type TabItem } from '@/ui/navigation';
import { Icon } from '@/ui/primitives';
import { SUBAGENT_STATUS_TEXT, subagentDepth, subagentLabel } from './labels';
import { closeFinishedSubagentTabs, isFinished, showSubagentTab } from './nav';
import { MAIN_TAB, showsAgentTabs, subagentPanelId } from './panelId';
import { SubagentStatusGlyph } from './SubagentStatusGlyph';
import styles from './SubagentView.module.css';


function tabItem(localId: string, list: readonly Subagent[], s: Subagent): TabItem {
  const nested = subagentDepth(list, s) > 0;
  return {
    id: s.tool_use_id,
    label: `${nested ? '↳ ' : ''}${subagentLabel(s)}`,
    leading: (
      <span className={styles.tabLead}>
        <SubagentStatusGlyph status={s.status} size={16} />
      </span>
    ),
    statusLabel: SUBAGENT_STATUS_TEXT[s.status],
    panelId: subagentPanelId(localId, s.tool_use_id),
  };
}

function AgentTabRowImpl({ localId }: { localId: string }) {
  const { subagents, selected, hiddenTabs } = useSession(
    localId,
    useShallow((s) => ({ subagents: s.conv.subagents, selected: s.subagentTab, hiddenTabs: s.hiddenSubagentTabs })),
  );
  const { items, all, finishedShown } = useMemo(() => {
    const main: TabItem = {
      id: MAIN_TAB,
      label: 'Main',
      leading: (
        <span className={styles.tabLead}>
          <Icon name="terminal" size={16} />
        </span>
      ),
      panelId: subagentPanelId(localId, null),
    };
    const every = subagents.map((s) => tabItem(localId, subagents, s));
    const shown = subagents.filter((s) => hiddenTabs.indexOf(s.tool_use_id) < 0 || s.tool_use_id === selected);
    return {
      items: [main, ...shown.map((s) => tabItem(localId, subagents, s))],
      all: [main, ...every],
      finishedShown: shown.some((s) => isFinished(s)),
    };
  }, [localId, subagents, selected, hiddenTabs]);
  const rowRef = useRef<HTMLDivElement | null>(null);
  if (!showsAgentTabs(subagents, hiddenTabs, selected)) return null;
  const value = selected ?? MAIN_TAB;
  const onChange = (id: string): void => showSubagentTab(localId, id === MAIN_TAB ? null : id);
  const closeFinished = (): void => {
    const panel = rowRef.current?.parentElement ?? null;
    closeFinishedSubagentTabs(localId);
    if (subagents.some((s) => !isFinished(s))) return;
    // The row is about to go: hand focus to the way back (the Agents button), or the composer.
    const target = panel?.querySelector<HTMLElement>('[data-agents-button]') ?? panel?.querySelector<HTMLElement>('[data-composer-field]');
    target?.focus();
    announce('Agent tabs closed. The Agents button reopens them.', 'polite');
  };
  return (
    <div ref={rowRef} className={styles.tabRow} data-subagent-tabs="">
      <Tabs
        variant="secondary"
        scrollable
        activation="manual"
        items={items}
        value={value}
        onChange={onChange}
        aria-label="Agents"
        className={styles.tabs}
      />
      <div className={styles.tabTools}>
        <AllTabsMenu items={all} value={value} onChange={onChange} searchable={all.length > 8} />
        {finishedShown ? (
          <IconButton icon="clear_all" size="small" aria-label="Close finished agent tabs" onClick={closeFinished} />
        ) : null}
      </div>
    </div>
  );
}

export const AgentTabRow = memo(AgentTabRowImpl);
