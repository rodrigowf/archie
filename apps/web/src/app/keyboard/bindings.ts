/**
 * The app's keyboard table (spec 13 §4.3, plan/20 P-7). Browsers reserve Ctrl+Tab, Ctrl+W and
 * Ctrl+1…9 in a normal tab (and Linux Chrome takes Alt+1…8), so a page uses Ctrl+Alt+…; an
 * installed PWA window also receives the browser-style keys, which are honoured "when delivered".
 *
 * | Action               | Browser tab                    | Installed PWA window (also)        |
 * |----------------------|--------------------------------|------------------------------------|
 * | Next / previous tab  | Ctrl+Alt+→ / Ctrl+Alt+←        | Ctrl+Tab / Ctrl+Shift+Tab          |
 * | Close tab            | Ctrl+Alt+W                     | Ctrl+W                             |
 * | Go to tab N (9=last) | Ctrl+Alt+1…9                   | Ctrl+1…9                           |
 * | Focus composer       | `/` (when not typing)          | same                               |
 * | Search conversations | Ctrl+K                         | same                               |
 * | New Archie / agent   | Ctrl+Alt+N / Ctrl+Alt+Shift+N  | same                               |
 * | Escape               | closes the topmost overlay (`@/ui/a11y` overlayStack)               |
 *
 * Tab matching reuses W-04's `matchTabShortcut` (event.code for letters/digits: Alt changes
 * event.key on many layouts).
 */
import { matchTabShortcut, type TabShortcut } from '@/ui/navigation';

export type AppCommand =
  | { readonly type: 'tab'; readonly shortcut: TabShortcut }
  | { readonly type: 'focusComposer' }
  | { readonly type: 'focusSearch' }
  | { readonly type: 'newArchie' }
  | { readonly type: 'newAgent' };

export interface KeyInput {
  key: string;
  code?: string;
  ctrlKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  metaKey: boolean;
}

export interface BindingRow {
  readonly action: string;
  readonly browser: string;
  readonly pwa: string;
}

/** Human-readable table (menus, help, tests). */
export const BINDINGS: readonly BindingRow[] = [
  { action: 'Next tab', browser: 'Ctrl+Alt+→', pwa: 'Ctrl+Tab' },
  { action: 'Previous tab', browser: 'Ctrl+Alt+←', pwa: 'Ctrl+Shift+Tab' },
  { action: 'Close tab', browser: 'Ctrl+Alt+W', pwa: 'Ctrl+W' },
  { action: 'Go to tab 1…8, last tab', browser: 'Ctrl+Alt+1…9', pwa: 'Ctrl+1…9' },
  { action: 'Focus the message field', browser: '/', pwa: '/' },
  { action: 'Search conversations', browser: 'Ctrl+K', pwa: 'Ctrl+K' },
  { action: 'New Archie conversation', browser: 'Ctrl+Alt+N', pwa: 'Ctrl+Alt+N' },
  { action: 'New agent session', browser: 'Ctrl+Alt+Shift+N', pwa: 'Ctrl+Alt+Shift+N' },
  { action: 'Close the topmost menu, sheet or dialog', browser: 'Escape', pwa: 'Escape' },
];

function digitOf(e: KeyInput): number {
  if (e.code && /^Digit[1-9]$/.test(e.code)) return Number(e.code.slice(5));
  return /^[1-9]$/.test(e.key) ? Number(e.key) : 0;
}

/** Browser-style keys, only honoured in an installed (standalone) window. */
function matchPwa(e: KeyInput): TabShortcut | null {
  if (!e.ctrlKey || e.altKey || e.metaKey) return null;
  if (e.key === 'Tab') return e.shiftKey ? { type: 'prev' } : { type: 'next' };
  if (e.shiftKey) return null;
  if (e.code === 'KeyW' || e.key.toLowerCase() === 'w') return { type: 'close' };
  const d = digitOf(e);
  if (d === 9) return { type: 'last' };
  if (d > 0) return { type: 'goto', index: d - 1 };
  return null;
}

export interface MatchContext {
  /** The page runs as an installed PWA window (`display-mode: standalone`). */
  standalone: boolean;
  /** Focus is in a text field / editable element (`/` is then typing, not a command). */
  typing: boolean;
}

export function matchAppCommand(e: KeyInput, ctx: MatchContext): AppCommand | null {
  if (e.ctrlKey && e.altKey && !e.metaKey && (e.code === 'KeyN' || e.key.toLowerCase() === 'n'))
    return e.shiftKey ? { type: 'newAgent' } : { type: 'newArchie' };
  if (e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey && (e.code === 'KeyK' || e.key.toLowerCase() === 'k')) return { type: 'focusSearch' };
  const tab = matchTabShortcut(e);
  if (tab) return { type: 'tab', shortcut: tab };
  if (ctx.standalone) {
    const pwa = matchPwa(e);
    if (pwa) return { type: 'tab', shortcut: pwa };
  }
  if (e.key === '/' && !e.ctrlKey && !e.altKey && !e.metaKey && !ctx.typing) return { type: 'focusComposer' };
  return null;
}

export function isTypingTarget(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (tag === 'INPUT') {
    const type = (el as HTMLInputElement).type;
    return ['button', 'checkbox', 'radio', 'range', 'submit', 'reset', 'file', 'color'].indexOf(type) < 0;
  }
  return (el as HTMLElement).isContentEditable === true;
}
