/**
 * Lazy access to the highlighter chunk (spec 13 §5.3: highlight languages are code-split).
 * `getLoadedHighlighter()` is synchronous once the chunk has loaded, so code blocks rendered
 * after that highlight in the same render with no flash of plain text.
 */
export type HighlighterModule = typeof import('./highlighter');

let loaded: HighlighterModule | null = null;
let pending: Promise<HighlighterModule> | null = null;

export function getLoadedHighlighter(): HighlighterModule | null {
  return loaded;
}

export function loadHighlighter(): Promise<HighlighterModule> {
  if (loaded) return Promise.resolve(loaded);
  if (!pending) {
    pending = import('./highlighter').then(
      (mod) => {
        loaded = mod;
        return mod;
      },
      (err: unknown) => {
        pending = null; // a failed chunk load (offline) may be retried by the next code block
        throw err;
      },
    );
  }
  return pending;
}
