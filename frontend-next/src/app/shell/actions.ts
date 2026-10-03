/**
 * Shell-level user actions (W-07). Every action here is a direct user action, so it may take
 * focus (`focus: true`); background opens from pool sync / watcher events never pass through
 * here (P-6, implemented in `@/services`).
 *
 * Closing (P-1, Rodrigo): an explicit close of a chat tab closes the session for everybody
 * (`closeTab` → `POST /api/sessions/{local_id}/close`). A running session asks first
 * (**[LOAD-BEARING]** inv02 §1.6, frontend/src/components/TabBar.tsx:74-91 ConfirmCloseModal),
 * and Archie always asks, because closing it stops the orchestrator on every device. Nothing
 * else in the shell (unmount, unload, window-class change, hiding) ever closes a session.
 */
import { errorMessage, closeTab, getArchieRuntime, openArchie, openSession, renameSession } from '@/services';
import { activateTab, catalogStore, findTab, openTab, showSnackbar, tabsStore, type Tab } from '@/stores';
import { navigate, type Route } from '../navigation/route';
import { liveSummary } from '../workspace/tabSummary';
import { closeShellOverlays, setShell, shellStore } from './shellState';

export type CloseConfirmKind = 'archie' | 'running';

/** Why closing `tab` needs a confirmation, or null to close at once. */
export function closeConfirmKind(tab: Tab): CloseConfirmKind | null {
  if (tab.kind === 'memory' || tab.kind === 'visual' || tab.readOnly) return null;
  const s = liveSummary(tab);
  if (s?.readOnly) return null;
  if (tab.kind === 'archie') return 'archie';
  return s?.busy ? 'running' : null;
}

/** × on a tab, middle-click, Ctrl+Alt+W, the switcher's ×, the session menu's Close. */
export function requestCloseTab(id: string): void {
  const tab = findTab(id);
  if (!tab) return;
  if (closeConfirmKind(tab)) {
    setShell({ confirmCloseId: id });
    return;
  }
  void closeTab(id);
}

export function confirmCloseTab(): void {
  const id = shellStore.getState().confirmCloseId;
  setShell({ confirmCloseId: null });
  if (id) void closeTab(id);
}

export function cancelCloseTab(): void {
  setShell({ confirmCloseId: null });
}

/**
 * The sdk id a rename goes to: the tab's own, else the session-list row matched by `local_id`
 * (the same match the derived title uses, inv02 §7 #9).
 */
export function renameTarget(tab: Tab): string | null {
  if (tab.sdkId) return tab.sdkId;
  const row = catalogStore.getState().sessions.items.find((s) => s.local_id === tab.id);
  return row ? row.session_id : null;
}

/** Rename needs the sdk id (inv02 §6.2: it used to fail silently before the first turn). */
export function requestRename(id: string): void {
  const tab = findTab(id);
  if (!tab || (tab.kind !== 'archie' && tab.kind !== 'agent')) return;
  if (tab.readOnly) return;
  if (!renameTarget(tab)) {
    showSnackbar('Rename is available after the first reply');
    return;
  }
  setShell({ renameId: id });
}

export async function commitRename(id: string, title: string): Promise<boolean> {
  const tab = findTab(id);
  const sdk = tab ? renameTarget(tab) : null;
  const t = title.trim();
  if (!sdk || !t) return false;
  try {
    await renameSession(sdk, t);
    return true;
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return false;
  }
}

/**
 * New Archie conversation. One Archie per app (inv02 F-25): when one is open it is focused.
 * The "Archie already active → Stop & start new" dialog belongs to W-11's NewMenu.
 */
export function newArchie(): void {
  closeShellOverlays();
  navigate({ name: 'workspace' });
  void openArchie({ focus: true }).catch((err: unknown) => {
    showSnackbar(errorMessage(err), { tone: 'error' });
  });
}

export function newAgent(): void {
  closeShellOverlays();
  navigate({ name: 'workspace' });
  openSession({ kind: 'agent', focus: true });
}

/** Open a past conversation from the history list (interim; W-14's HistoryPane owns it). */
export function openFromHistory(sdkId: string, isArchie: boolean): void {
  closeShellOverlays();
  navigate({ name: 'workspace' });
  if (isArchie) {
    void openArchie({ focus: true, resumeSdkId: sdkId }).then((r) => {
      if (r.conflict) {
        // inv02 §6.3 #11: a past Archie conversation opens read-only next to the live one.
        openSession({ kind: 'archie', sdkId, focus: true, readOnly: true });
      }
    });
    return;
  }
  openSession({ kind: 'agent', sdkId, focus: true });
}

/** Focus an open tab from a list (drawer, switcher, list pane). */
export function focusTab(id: string): void {
  closeShellOverlays();
  navigate({ name: 'workspace' });
  activateTab(id);
}

export type DocumentKind = 'memory' | 'visual';

/**
 * Open a memory document or a visual: a tab on medium/expanded (IA §9.2), a detail screen on
 * compact (spec 13 §4.2 table). W-14 calls this from its panes.
 */
export function openDocument(kind: DocumentKind, path: string, opts: { compact: boolean; url?: string; title?: string }): void {
  if (opts.compact) {
    setShell({ drawerOpen: false, switcherOpen: false });
    navigate(kind === 'memory' ? { name: 'memory', path } : { name: 'visuals', path });
    return;
  }
  closeShellOverlays();
  const id = `${kind === 'memory' ? 'memory' : 'viz'}:${path}`;
  openTab(
    {
      id,
      kind,
      path,
      ...(opts.url ? { url: opts.url } : {}),
      ...(opts.title ? { titleHint: opts.title } : {}),
    },
    { focus: true },
  );
}

/** Rail / drawer destinations. */
export function routeForDestination(d: 'chats' | 'memory' | 'visuals' | 'settings'): Route {
  switch (d) {
    case 'memory':
      return { name: 'memory', path: null };
    case 'visuals':
      return { name: 'visuals', path: null };
    case 'settings':
      return { name: 'settings', page: null };
    default:
      return { name: 'history' };
  }
}

export function hasLiveArchie(): boolean {
  return !!getArchieRuntime();
}

export function activeTab(): Tab | undefined {
  const s = tabsStore.getState();
  return s.tabs.find((t) => t.id === s.activeId);
}
