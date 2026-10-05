/**
 * matchMedia helpers. `MediaQueryList.addEventListener` needs Safari 14; Safari 12 only has the
 * deprecated addListener/removeListener (spec 13 §2.6). Use these helpers instead of matchMedia.
 */
type LegacyMql = MediaQueryList & {
  addListener?: (fn: (e: MediaQueryListEvent) => void) => void;
  removeListener?: (fn: (e: MediaQueryListEvent) => void) => void;
};

export function mediaMatches(query: string, win: Window = window): boolean {
  if (typeof win.matchMedia !== 'function') return false;
  return win.matchMedia(query).matches;
}

/** Calls `cb` on every change. Returns an unsubscribe function. */
export function watchMedia(query: string, cb: (matches: boolean) => void, win: Window = window): () => void {
  if (typeof win.matchMedia !== 'function') return () => undefined;
  const mql = win.matchMedia(query) as LegacyMql;
  const handler = (e: MediaQueryListEvent): void => {
    cb(e.matches);
  };
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', handler);
    return () => {
      mql.removeEventListener('change', handler);
    };
  }
  if (typeof mql.addListener === 'function') {
    mql.addListener(handler);
    return () => {
      mql.removeListener?.(handler);
    };
  }
  return () => undefined;
}
