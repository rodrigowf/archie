/**
 * Document-level keyboard handling for the shell (spec 13 §4.3). One listener; commands resolve
 * against the tab store at key time. Shortcuts are ignored while a modal overlay is open (the
 * overlay owns the keyboard), except Escape, which the overlay stack handles itself.
 */
import { useEffect } from 'react';
import { mediaMatches } from '@/platform';
import { activateTab, setPref, tabsStore } from '@/stores';
import { overlayStack } from '@/ui/a11y';
import { resolveTabShortcut } from '@/ui/navigation';
import { navigate } from '../navigation/route';
import { newAgent, newArchie, requestCloseTab } from '../shell/actions';
import { setShell } from '../shell/shellState';
import { currentWindowClass } from '../useWindowClass';
import { isTypingTarget, matchAppCommand } from './bindings';

/** The W-11 composer marks its field with this attribute; `/` focuses the active panel's one. */
export const COMPOSER_FIELD_ATTR = 'data-composer-field';

export function focusComposer(): boolean {
  const active = tabsStore.getState().activeId;
  const panels = Array.from(document.querySelectorAll<HTMLElement>('[data-panel-id]'));
  const panel = panels.find((p) => p.getAttribute('data-panel-id') === active);
  const field = panel?.querySelector<HTMLElement>(`[${COMPOSER_FIELD_ATTR}]`);
  if (!field) return false;
  field.focus();
  return true;
}

/** The visible conversation search field (list pane / drawer), if any. */
export const SEARCH_FIELD_ATTR = 'data-app-search';

/**
 * Ctrl+K: focus the conversation search. Expanded: the list pane's field (expanding the pane);
 * medium/compact: open the list overlay / drawer first. Keyboard-initiated, so focusing a text
 * field is wanted here (unlike opening the drawer by touch).
 */
export function focusSearch(): void {
  const find = (): HTMLInputElement | null => {
    const all = Array.from(document.querySelectorAll<HTMLInputElement>(`input[${SEARCH_FIELD_ATTR}]`));
    return all.find((el) => el.offsetParent !== null || el.getClientRects().length > 0) ?? all[0] ?? null;
  };
  const now = find();
  if (now) {
    now.focus();
    return;
  }
  const wc = currentWindowClass();
  if (wc === 'compact') setShell({ drawerOpen: true });
  else if (wc === 'medium') setShell({ listOverlayOpen: true });
  else {
    setPref('listPaneCollapsed', false);
    setPref('lastRailDestination', 'chats');
    navigate({ name: 'workspace' });
  }
  setTimeout(() => find()?.focus(), 50);
}

export function handleAppKey(e: KeyboardEvent): void {
  if (e.defaultPrevented || e.isComposing) return;
  const cmd = matchAppCommand(e, {
    standalone: mediaMatches('(display-mode: standalone)'),
    typing: isTypingTarget(document.activeElement),
  });
  if (!cmd) return;
  if (overlayStack.size > 0) return;
  switch (cmd.type) {
    case 'newArchie':
      e.preventDefault();
      newArchie();
      return;
    case 'newAgent':
      e.preventDefault();
      newAgent();
      return;
    case 'focusSearch':
      e.preventDefault();
      focusSearch();
      return;
    case 'focusComposer':
      if (focusComposer()) e.preventDefault();
      return;
    case 'tab': {
      const s = tabsStore.getState();
      const r = resolveTabShortcut(
        cmd.shortcut,
        s.tabs.map((t) => t.id),
        s.activeId,
      );
      if (!r) return;
      e.preventDefault();
      if ('activate' in r) activateTab(r.activate);
      else requestCloseTab(r.close);
    }
  }
}

export function useAppKeyboard(): void {
  useEffect(() => {
    document.addEventListener('keydown', handleAppKey);
    return () => {
      document.removeEventListener('keydown', handleAppKey);
    };
  }, []);
}
