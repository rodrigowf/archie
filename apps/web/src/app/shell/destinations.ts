/**
 * Destination selection per size class (spec 13 §4.2 table, IA §3–§5):
 *
 * | Destination      | Expanded                                  | Medium                       | Compact                |
 * |------------------|-------------------------------------------|------------------------------|------------------------|
 * | Chats            | list pane: history (expands it if collapsed)| list-pane overlay: history | History screen         |
 * | Memory / Visuals | list pane: tree / gallery                 | list-pane overlay            | full screen            |
 * | Settings         | Settings screen (rail stays)              | Settings screen              | full screen            |
 */
import { prefsStore, setPref } from '@/stores';
import { navigate } from '../navigation/route';
import type { WindowClass } from '../useWindowClass';
import { routeForDestination } from './actions';
import { closeShellOverlays, setShell, type ListDestination } from './shellState';

export type RailId = ListDestination | 'settings';

export function selectDestination(id: RailId, wc: WindowClass): void {
  if (id === 'settings') {
    closeShellOverlays();
    navigate(routeForDestination('settings'));
    return;
  }
  setPref('lastRailDestination', id);
  if (wc === 'compact') {
    setShell({ drawerOpen: false, switcherOpen: false });
    navigate(routeForDestination(id));
    return;
  }
  navigate(id === 'chats' ? { name: 'workspace' } : routeForDestination(id));
  if (wc === 'medium') setShell({ listOverlayOpen: true });
  else if (prefsStore.getState().listPaneCollapsed) setPref('listPaneCollapsed', false);
}
