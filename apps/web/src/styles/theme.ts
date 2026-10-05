/**
 * Theme switch (spec 13 §2.6 "Theme", W-02). tokens.css is dark by default; `data-theme` on <html>
 * selects `light`, `dark` or `system` (prefers-color-scheme, Safari 12.1+; iOS 12.0 stays dark).
 * Also keeps <meta name="theme-color"> equal to the resolved surface color, so the browser chrome
 * and the PWA status bar match the page.
 *
 * Persisting the choice is the prefs store's job (W-06, Settings → Appearance); this module only
 * applies it.
 */
import { mediaMatches, watchMedia } from '@/platform';

export type ThemeMode = 'system' | 'dark' | 'light';
export type ResolvedTheme = 'dark' | 'light';

export const THEME_MODES: readonly ThemeMode[] = ['system', 'dark', 'light'];

/** tokens.color.{dark,light}.surface; used when computed styles are unavailable (tests, early boot). */
export const SURFACE_FALLBACK: Record<ResolvedTheme, string> = { dark: '#0d0e10', light: '#fbf8fb' };

const LIGHT_QUERY = '(prefers-color-scheme: light)';

let currentMode: ThemeMode = 'dark';
let stopWatching: (() => void) | null = null;
const listeners = new Set<(resolved: ResolvedTheme, mode: ThemeMode) => void>();

export function isThemeMode(value: unknown): value is ThemeMode {
  return value === 'system' || value === 'dark' || value === 'light';
}

export function resolveTheme(mode: ThemeMode, win: Window = window): ResolvedTheme {
  if (mode === 'system') return mediaMatches(LIGHT_QUERY, win) ? 'light' : 'dark';
  return mode;
}

export function getThemeMode(): ThemeMode {
  return currentMode;
}

export function getResolvedTheme(win: Window = window): ResolvedTheme {
  return resolveTheme(currentMode, win);
}

function themeColorMeta(doc: Document): HTMLMetaElement {
  let meta = doc.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = doc.createElement('meta');
    meta.name = 'theme-color';
    doc.head.appendChild(meta);
  }
  return meta;
}

function syncThemeColor(doc: Document, win: Window): void {
  const resolved = resolveTheme(currentMode, win);
  let surface = '';
  try {
    surface = win.getComputedStyle(doc.documentElement).getPropertyValue('--md-sys-color-surface').trim();
  } catch {
    surface = '';
  }
  themeColorMeta(doc).content = surface || SURFACE_FALLBACK[resolved];
  for (const fn of listeners) fn(resolved, currentMode);
}

/**
 * Applies a theme mode to <html>. 'dark' sets data-theme="dark" explicitly, so a stray
 * prefers-color-scheme rule can never flip it.
 */
export function applyTheme(mode: ThemeMode, doc: Document = document, win: Window = window): ResolvedTheme {
  currentMode = mode;
  doc.documentElement.setAttribute('data-theme', mode);
  stopWatching?.();
  stopWatching = null;
  if (mode === 'system') {
    stopWatching = watchMedia(
      LIGHT_QUERY,
      () => {
        syncThemeColor(doc, win);
      },
      win,
    );
  }
  syncThemeColor(doc, win);
  return resolveTheme(mode, win);
}

/** Called with the resolved theme after every apply and every system change. */
export function onThemeChange(fn: (resolved: ResolvedTheme, mode: ThemeMode) => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
