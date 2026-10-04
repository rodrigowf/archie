/** Compact window class (< 600 px, spec 13 §4.1): bottom sheet instead of side sheet. */
import { useSyncExternalStore } from 'react';
import { mediaMatches, watchMedia } from '@/platform';

export const COMPACT_QUERY = '(max-width: 599.98px)';

const subscribe = (cb: () => void): (() => void) => watchMedia(COMPACT_QUERY, cb);
const read = (): boolean => mediaMatches(COMPACT_QUERY);

export function useCompactWindow(): boolean {
  return useSyncExternalStore(subscribe, read, () => false);
}
