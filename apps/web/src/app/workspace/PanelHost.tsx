/**
 * Every open tab's panel, mounted (spec 13 §4.4, **[LOAD-BEARING]** inv02 §7 #1, F-01:
 * frontend/src/components/ChatPanelContainer.tsx renders all tab panels and hides the inactive
 * ones). Inactive panels get the `hidden` attribute (display: none), so scroll position, draft,
 * expanded cards and visual iframes (F-36) survive tab switches.
 *
 * DOM order is the order in which tabs were first seen, NOT the tab-strip order: reordering tabs
 * must never move a panel in the DOM (moving an iframe reloads it). Connections live in the
 * session registry outside React; hiding a panel only freezes its store snapshot (W-06).
 */
import { useMemo } from 'react';
import { useTabs, type Tab } from '@/stores';
import { ConversationPanel, MemoryDocument, VisualViewer } from '../slots';
import { panelDomId, useTitledTabs } from './SessionTabStrip';
import { WorkspaceEmpty } from './WorkspaceEmpty';
import styles from './workspace.module.css';

function PanelContent({ tab, hidden }: { tab: Tab; hidden: boolean }) {
  if (tab.kind === 'memory') return <MemoryDocument path={tab.path ?? ''} hidden={hidden} />;
  if (tab.kind === 'visual') return <VisualViewer path={tab.path ?? ''} url={tab.url} hidden={hidden} />;
  return <ConversationPanel localId={tab.id} hidden={hidden} />;
}

/** First-seen sequence per tab id (module level: one PanelHost per app). */
const firstSeen = new Map<string, number>();
let nextSeq = 0;

/** Tabs in stable first-seen order. */
export function stableOrder(tabs: readonly Tab[]): Tab[] {
  for (const t of tabs)
    if (!firstSeen.has(t.id)) {
      firstSeen.set(t.id, nextSeq);
      nextSeq += 1;
    }
  if (firstSeen.size > tabs.length * 4 + 32) {
    const live = new Set(tabs.map((t) => t.id));
    for (const id of Array.from(firstSeen.keys())) if (!live.has(id)) firstSeen.delete(id);
  }
  return tabs.slice().sort((a, b) => (firstSeen.get(a.id) ?? 0) - (firstSeen.get(b.id) ?? 0));
}

export function PanelHost() {
  const { tabs, titles } = useTitledTabs();
  const activeId = useTabs((s) => s.activeId);
  const ordered = useMemo(() => stableOrder(tabs), [tabs]);
  return (
    <div className={styles.panels}>
      {ordered.map((t) => {
        const hidden = t.id !== activeId;
        return (
          <div
            key={t.id}
            id={panelDomId(t.id)}
            role="tabpanel"
            aria-label={titles[t.id]}
            data-panel-id={t.id}
            className={styles.panel}
            hidden={hidden}
          >
            <PanelContent tab={t} hidden={hidden} />
          </div>
        );
      })}
      {tabs.length === 0 || !activeId ? <WorkspaceEmpty /> : null}
    </div>
  );
}
