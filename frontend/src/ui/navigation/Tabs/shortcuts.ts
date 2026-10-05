/**
 * Tab keyboard shortcuts (plan/20 P-7, spec 13 §4.3). Browsers reserve Ctrl+Tab / Ctrl+W /
 * Ctrl+1…9 in a normal tab, so the web app uses:
 *
 *   Ctrl+Alt+→ / Ctrl+Alt+←   next / previous tab (wrapping)
 *   Ctrl+Alt+W                close the active tab
 *   Ctrl+Alt+1 … 8            go to tab N;  Ctrl+Alt+9  last tab (browser convention)
 *
 * `event.code` is used for letters and digits because Alt changes `event.key` on many layouts.
 * W-07 owns the global binding table (`src/app/keyboard/bindings.ts`); this module provides the
 * matcher and an opt-in hook for a tab strip.
 */
import { useEffect } from 'react';
import { useLatest } from '@/ui/a11y';

export type TabShortcut = { type: 'next' } | { type: 'prev' } | { type: 'close' } | { type: 'goto'; index: number } | { type: 'last' };

interface KeyLike {
  key: string;
  code?: string;
  ctrlKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  metaKey: boolean;
}

export function matchTabShortcut(e: KeyLike): TabShortcut | null {
  if (!e.ctrlKey || !e.altKey || e.shiftKey || e.metaKey) return null;
  if (e.key === 'ArrowRight') return { type: 'next' };
  if (e.key === 'ArrowLeft') return { type: 'prev' };
  if (e.code === 'KeyW' || e.key.toLowerCase() === 'w') return { type: 'close' };
  const digit = e.code && /^Digit[1-9]$/.test(e.code) ? Number(e.code.slice(5)) : /^[1-9]$/.test(e.key) ? Number(e.key) : 0;
  if (digit === 9) return { type: 'last' };
  if (digit > 0) return { type: 'goto', index: digit - 1 };
  return null;
}

/** Resolve a shortcut against the open tabs: the tab id to activate, or to close. */
export function resolveTabShortcut(
  s: TabShortcut,
  ids: readonly string[],
  active: string | null,
): { activate: string } | { close: string } | null {
  if (ids.length === 0) return null;
  const i = active ? ids.indexOf(active) : -1;
  switch (s.type) {
    case 'next':
      return { activate: ids[(i + 1 + ids.length) % ids.length] as string };
    case 'prev':
      return { activate: ids[(i - 1 + ids.length) % ids.length] as string };
    case 'close':
      return active ? { close: active } : null;
    case 'last':
      return { activate: ids[ids.length - 1] as string };
    case 'goto': {
      const id = ids[s.index];
      return id ? { activate: id } : null;
    }
  }
}

export interface TabShortcutsOptions {
  ids: readonly string[];
  active: string | null;
  onActivate: (id: string) => void;
  onClose?: (id: string) => void;
  enabled?: boolean;
}

/** Document-level P-7 shortcuts for one tab strip. */
export function useTabShortcuts(o: TabShortcutsOptions): void {
  const latest = useLatest(o);
  const enabled = o.enabled !== false;
  useEffect(() => {
    if (!enabled) return undefined;
    const onKey = (e: KeyboardEvent): void => {
      if (e.defaultPrevented) return;
      const s = matchTabShortcut(e);
      if (!s) return;
      const { ids, active, onActivate, onClose } = latest.current;
      const r = resolveTabShortcut(s, ids, active);
      if (!r) return;
      e.preventDefault();
      if ('activate' in r) onActivate(r.activate);
      else onClose?.(r.close);
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, [enabled, latest]);
}
