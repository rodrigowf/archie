/** Compact window class (< 600 px, spec 13 §4.1) for overlays that change shape on phones. */
import { useSyncExternalStore } from 'react';
import { mediaMatches, watchMedia } from '@/platform';

export const COMPACT_QUERY = '(max-width: 599.98px)';

const subscribe = (cb: () => void): (() => void) => watchMedia(COMPACT_QUERY, cb);
const read = (): boolean => mediaMatches(COMPACT_QUERY);

export function useCompact(enabled = true): boolean {
  const compact = useSyncExternalStore(subscribe, read, () => false);
  return enabled && compact;
}
