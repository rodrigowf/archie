/**
 * The session tab strip in the workspace top bar (spec 13 §4.2, IA §3; mockups `.wtop .tabs`),
 * built on W-04's `Tabs variant="strip"`: kind icon (Archie mark / agent / memory / visual),
 * provider chip, live status, title **derived from the session list** (**[LOAD-BEARING]**
 * inv02 §7 #9, frontend/src/components/TabBar.tsx:45-50, `tabTitle` in `@/stores`), close ×
 * (active + hover), middle-click close, double-click / F2 / long-press → Rename, a context menu,
 * drag or Ctrl+Shift+←/→ to reorder (Archie pinned first), the "N ⌄" all-tabs menu, and an
 * unseen badge for background opens (P-6).
 */
import { useMemo, useState } from 'react';
import { useStore } from 'zustand';
import { activateTab, catalogStore, moveTab, useTabs, type Tab } from '@/stores';
import { pointAnchor, Menu, MenuItem, MenuSeparator } from '@/ui/overlays';
import { Tabs, type TabItem } from '@/ui/navigation';
import { requestCloseTab, requestRename } from '../shell/actions';
import { ProviderTag, StatusGlyph, TabLeading } from './TabParts';
import { displayTabTitle } from './titles';
import { useTabSummaries, type TabSummary } from './tabSummary';

export const PANEL_ID_PREFIX = 'panel-';

export function panelDomId(tabId: string): string {
  return PANEL_ID_PREFIX + tabId.replace(/[^a-zA-Z0-9_-]/g, '_');
}

export function canRename(tab: Tab): boolean {
  return (tab.kind === 'archie' || tab.kind === 'agent') && !tab.readOnly;
}

export function toTabItem(tab: Tab, title: string, summary: TabSummary | null, active = false): TabItem {
  const unseen = tab.unseen && !active;
  const status = [summary?.label, unseen ? 'New activity' : null].filter(Boolean).join(', ');
  return {
    id: tab.id,
    label: title,
    // P-6: unseen = a badge on the icon; the strip's own unseen dot is not used (one dot per tab).
    leading: <TabLeading tab={tab} unseen={unseen} />,
    meta: tab.kind === 'agent' ? <ProviderTag provider={tab.provider} /> : undefined,
    status: summary ? <StatusGlyph summary={summary} /> : undefined,
    statusLabel: status || undefined,
    closable: true,
    pinned: tab.kind === 'archie',
    unseen: false,
    panelId: panelDomId(tab.id),
  };
}

/** Tabs with their derived titles (re-renders when the session or visuals list changes). */
export function useTitledTabs(): { tabs: readonly Tab[]; titles: Readonly<Record<string, string>> } {
  const tabs = useTabs((s) => s.tabs);
  const sessions = useStore(catalogStore, (s) => s.sessions);
  const visuals = useStore(catalogStore, (s) => s.visuals);
  const titles = useMemo(() => {
    const out: Record<string, string> = {};
    const cat = { ...catalogStore.getState(), sessions, visuals };
    for (const t of tabs) out[t.id] = displayTabTitle(t, cat);
    return out;
  }, [tabs, sessions, visuals]);
  return { tabs, titles };
}

export function SessionTabStrip() {
  const { tabs, titles } = useTitledTabs();
  const activeId = useTabs((s) => s.activeId);
  const summaries = useTabSummaries(tabs);
  const [menu, setMenu] = useState<{ id: string; x: number; y: number } | null>(null);
  const menuAnchor = useMemo(() => pointAnchor(menu?.x ?? 0, menu?.y ?? 0), [menu]);

  const items = tabs.map((t) => toTabItem(t, titles[t.id] ?? '', summaries[t.id] ?? null, t.id === activeId));
  const menuTab = menu ? tabs.find((t) => t.id === menu.id) : undefined;

  return (
    <>
      <Tabs
        variant="strip"
        aria-label="Open sessions"
        items={items}
        value={activeId}
        onChange={activateTab}
        onClose={requestCloseTab}
        onRename={(id) => {
          const t = tabs.find((x) => x.id === id);
          if (t && canRename(t)) requestRename(id);
        }}
        onTabContextMenu={(id, at) => {
          setMenu({ id, x: at.x, y: at.y });
        }}
        onReorder={(id, index) => {
          moveTab(id, index);
        }}
      />
      <Menu
        open={!!menu && !!menuTab}
        onClose={() => {
          setMenu(null);
        }}
        anchor={menuAnchor}
        placement="bottom-start"
        aria-label="Tab actions"
      >
        {menuTab && canRename(menuTab) ? (
          <MenuItem
            icon="edit"
            shortcut="F2"
            onSelect={() => {
              requestRename(menuTab.id);
            }}
          >
            Rename
          </MenuItem>
        ) : null}
        {menuTab && canRename(menuTab) ? <MenuSeparator /> : null}
        <MenuItem
          icon="close"
          shortcut="Ctrl+Alt+W"
          onSelect={() => {
            if (menuTab) requestCloseTab(menuTab.id);
          }}
        >
          Close
        </MenuItem>
      </Menu>
    </>
  );
}
