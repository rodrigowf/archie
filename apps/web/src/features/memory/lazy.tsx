/**
 * The memory document is its own chunk (spec 13 §5.4 initial-JS budget): it brings the markdown
 * renderer, which the first paint does not need. Fetched the first time a memory file opens.
 */
import { lazy, Suspense } from 'react';
import type { MemoryDocumentProps } from './MemoryDocument';
import styles from './memory.module.css';

const loadDocument = () => import('./MemoryDocument');
const LazyDocument = lazy(() => loadDocument().then((m) => ({ default: m.MemoryDocument })));

/** Start fetching the document chunk (e.g. when the Memory pane opens). */
export function preloadMemoryDocument(): void {
  void loadDocument();
}

export function MemoryDocument(props: MemoryDocumentProps) {
  return (
    <Suspense
      fallback={
        <p role="status" className={styles.note}>
          Loading…
        </p>
      }
    >
      <LazyDocument {...props} />
    </Suspense>
  );
}
