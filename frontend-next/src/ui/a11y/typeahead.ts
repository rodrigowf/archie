/**
 * Type-ahead for menus, trees and lists (WAI-ARIA APG): printable keys typed within 500 ms build a
 * prefix; the next item (after the current one, wrapping) whose label starts with it is chosen.
 * Repeating one character cycles through items starting with it.
 */
export const TYPEAHEAD_RESET_MS = 500;

export interface Typeahead {
  /** Returns the index to focus, or -1. `labels` are the items' visible text. */
  next(key: string, labels: readonly string[], current: number, now?: number): number;
  reset(): void;
}

export function isTypeaheadKey(e: { key: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean }): boolean {
  return e.key.length === 1 && e.key !== ' ' && !e.ctrlKey && !e.metaKey && !e.altKey;
}

export function createTypeahead(resetMs = TYPEAHEAD_RESET_MS): Typeahead {
  let buffer = '';
  let last = 0;
  return {
    next(key, labels, current, now = Date.now()) {
      if (now - last > resetMs) buffer = '';
      last = now;
      buffer += key.toLowerCase();
      const n = labels.length;
      if (n === 0) return -1;
      const norm = labels.map((l) => l.trim().toLowerCase());
      const same = buffer.split('').every((c) => c === buffer[0]);
      // A repeated single character cycles; otherwise search the whole prefix from the current item.
      const prefix = same ? buffer[0] ?? '' : buffer;
      const start = same || buffer.length === 1 ? current + 1 : current;
      for (let k = 0; k < n; k++) {
        const i = (((start + k) % n) + n) % n;
        if (norm[i]?.startsWith(prefix)) return i;
      }
      return -1;
    },
    reset() {
      buffer = '';
      last = 0;
    },
  };
}
