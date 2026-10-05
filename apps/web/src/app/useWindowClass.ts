/**
 * Window size classes (spec 13 §4.1, IA §0): **compact** < 600, **medium** 600–839,
 * **expanded** ≥ 840 CSS px. JS drives structure (rail vs drawer, list pane vs overlay); CSS
 * drives dimensions through the same breakpoints (`@media (--compact)`, src/styles/media.css).
 *
 * `watchMedia` uses `addListener` on Safari 12 (§2.6). The iPad mini 2 is medium in portrait
 * (768) and expanded in landscape (1024): rotating it changes the class but never remounts the
 * panels (§4.4, AppShell keeps `<main>` at a fixed tree position).
 */
import { useEffect, useState } from 'react';
import { mediaMatches, watchMedia } from '@/platform';

export type WindowClass = 'compact' | 'medium' | 'expanded';

export const MEDIUM_QUERY = '(min-width: 600px)';
export const EXPANDED_QUERY = '(min-width: 840px)';

export function currentWindowClass(win: Window = window): WindowClass {
  if (mediaMatches(EXPANDED_QUERY, win)) return 'expanded';
  if (mediaMatches(MEDIUM_QUERY, win)) return 'medium';
  return 'compact';
}

export function useWindowClass(): WindowClass {
  const [wc, setWc] = useState<WindowClass>(() => currentWindowClass());
  useEffect(() => {
    const update = (): void => {
      setWc(currentWindowClass());
    };
    const offA = watchMedia(MEDIUM_QUERY, update);
    const offB = watchMedia(EXPANDED_QUERY, update);
    update();
    return () => {
      offA();
      offB();
    };
  }, []);
  return wc;
}
