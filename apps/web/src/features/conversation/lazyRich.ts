/**
 * Loader of the "rich" chunk (rich.tsx). The download starts when this module is evaluated (the
 * app imports the conversation view at startup), so it runs in parallel with the shell's first
 * paint and the first history request. Until it has loaded, the message list shows its
 * "Loading conversation…" state and the permission cards are absent; nothing renders half-styled.
 */
import { useEffect, useState } from 'react';

export type RichModule = typeof import('./rich') & typeof import('./richCards');

let loaded: RichModule | null = null;
let pending: Promise<RichModule> | null = null;

/**
 * Two small entry chunks (the run renderer, the permission cards) over shared chunks. The markdown
 * pipeline is also imported on its own here, so the bundler puts it in its own chunk instead of
 * one markdown + tools chunk (keeps every lazy chunk under the 60 kB budget, spec 13 §5.4).
 */
export function preloadRich(): Promise<RichModule> {
  if (!pending) {
    pending = Promise.all([import('./rich'), import('./richCards'), import('@/features/markdown')]).then(([a, b]) => {
      loaded = { ...a, ...b };
      return loaded;
    });
  }
  return pending;
}

void preloadRich();

/** The rich module, or null while it loads (re-renders once it arrives). */
export function useRichModule(): RichModule | null {
  const [mod, setMod] = useState<RichModule | null>(loaded);
  useEffect(() => {
    if (mod) return;
    let live = true;
    void preloadRich().then((m) => {
      if (live) setMod(m);
    });
    return () => {
      live = false;
    };
  }, [mod]);
  return mod;
}
