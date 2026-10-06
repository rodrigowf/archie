import { afterEach, describe, expect, it, vi } from 'vitest';
import { SURFACE_FALLBACK, applyTheme, getThemeMode, isThemeMode, onThemeChange, resolveTheme } from './theme';

type Listener = (e: MediaQueryListEvent) => void;

function stubMatchMedia(initialLight: boolean) {
  let light = initialLight;
  const listeners = new Set<Listener>();
  const mql = {
    get matches() {
      return light;
    },
    media: '(prefers-color-scheme: light)',
    addListener: (fn: Listener) => listeners.add(fn),
    removeListener: (fn: Listener) => listeners.delete(fn),
  };
  vi.spyOn(window, 'matchMedia').mockImplementation(() => mql as unknown as MediaQueryList);
  return {
    set(v: boolean) {
      light = v;
      for (const fn of listeners) fn({ matches: v } as MediaQueryListEvent);
    },
    listeners,
  };
}

const meta = (): string | null => document.querySelector('meta[name="theme-color"]')?.getAttribute('content') ?? null;

describe('theme', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    applyTheme('dark');
  });

  it('applies data-theme and the matching theme-color', () => {
    stubMatchMedia(false);
    expect(applyTheme('light')).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(meta()).toBe(SURFACE_FALLBACK.light);
    expect(applyTheme('dark')).toBe('dark');
    expect(meta()).toBe(SURFACE_FALLBACK.dark);
    expect(getThemeMode()).toBe('dark');
  });

  it('follows the system preference in system mode and stops when left', () => {
    const mm = stubMatchMedia(false);
    const seen: string[] = [];
    const off = onThemeChange((resolved) => seen.push(resolved));
    expect(applyTheme('system')).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('system');
    mm.set(true);
    expect(meta()).toBe(SURFACE_FALLBACK.light);
    expect(seen).toEqual(['dark', 'light']);
    applyTheme('dark');
    expect(mm.listeners.size).toBe(0);
    off();
  });

  it('prefers the computed surface token over the fallback', () => {
    stubMatchMedia(false);
    document.documentElement.style.setProperty('--md-sys-color-surface', '#123456');
    applyTheme('dark');
    expect(meta()).toBe('#123456');
    document.documentElement.style.removeProperty('--md-sys-color-surface');
  });

  it('validates and resolves modes', () => {
    stubMatchMedia(true);
    expect(isThemeMode('system')).toBe(true);
    expect(isThemeMode('blue')).toBe(false);
    expect(resolveTheme('system')).toBe('light');
    expect(resolveTheme('dark')).toBe('dark');
  });
});
