/**
 * Memory browser (spec 13 §3.6 `features/memory`, IA §3 / §5, mockups phone (g) and the desktop
 * list pane). The tree is pull-only (spec 12 §9.2): loaded the first time the pane opens, then
 * on Refresh. Search filters every file by path ("Search 138 memory files"); folders show their
 * file counts; `MEMORY.md` is pinned first; top-level folders start expanded (MEM-4, inv02 F-37
 * MemoryTree.tsx:70). The Tree (W-04) gives the APG keyboard model (arrows, Home/End,
 * type-ahead, `*`).
 *
 * Gap vs the mockup: the tree endpoint has no modification times, so files show no age (a
 * backend field would be needed; `last-modified` exists only per file).
 */
import { useEffect, useMemo, useState } from 'react';
import { ensureMemoryTree, refreshMemoryTree } from '@/services';
import { useCatalog, useTabs } from '@/stores';
import { Button, EmptyState, IconButton, SearchField } from '@/ui/controls';
import { Tree, type TreeNode } from '@/ui/navigation';
import { preloadMemoryDocument } from './lazy';
import { countAll, filterMemory, folderIds, pinIndexFirst, toTreeNodes, topFolderIds } from './tree';
import styles from './memory.module.css';

export interface MemoryPaneProps {
  /** Open a file (W-07 `openDocument('memory', …)`: a tab on medium/expanded, a screen on compact). */
  readonly onOpen?: (path: string, name: string) => void;
  /** Highlighted file; defaults to the active memory tab's path. */
  readonly selectedPath?: string | null;
}

export function MemoryPane({ onOpen, selectedPath }: MemoryPaneProps) {
  const memory = useCatalog((s) => s.memory);
  const activeMemoryPath = useTabs((s) => {
    const t = s.tabs.find((x) => x.id === s.activeId);
    return t && t.kind === 'memory' ? (t.path ?? null) : null;
  });
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<string[] | null>(null);
  // Folder state while searching, valid only for the query it was made for (a new query resets it).
  const [searchExpanded, setSearchExpanded] = useState<{ query: string; ids: string[] } | null>(null);

  useEffect(() => {
    void ensureMemoryTree();
    preloadMemoryDocument();
  }, []);

  const nodes = useMemo(() => pinIndexFirst(memory.items), [memory.items]);
  const total = useMemo(() => countAll(nodes), [nodes]);
  const searching = query.trim().length > 0;
  const shown = useMemo(() => (searching ? filterMemory(nodes, query) : nodes), [nodes, query, searching]);
  const treeNodes = useMemo<TreeNode[]>(() => toTreeNodes(shown), [shown]);
  // While searching every folder on the way to a match starts open (a new query resets it);
  // otherwise the user's state (top-level folders open until the user changes something).
  const expandedIds = searching ? (searchExpanded && searchExpanded.query === query ? searchExpanded.ids : folderIds(shown)) : (expanded ?? topFolderIds(nodes));

  const loadedOnce = memory.loadedAt > 0;
  const selected = selectedPath !== undefined ? selectedPath : activeMemoryPath;

  return (
    <div className={styles.pane}>
      <SearchField
        label={loadedOnce ? `Search ${total} memory ${total === 1 ? 'file' : 'files'}` : 'Search memory'}
        value={query}
        onValueChange={setQuery}
        className={styles.search}
        trailing={
          <IconButton
            icon="refresh"
            size="small"
            iconSize={20}
            aria-label="Refresh memory"
            loading={memory.loading}
            onClick={() => {
              void refreshMemoryTree();
            }}
          />
        }
      />
      {memory.error ? (
        <div className={styles.error} role="alert">
          <span>Could not load the memory tree: {memory.error}</span>
          <Button
            variant="text"
            size="small"
            onClick={() => {
              void refreshMemoryTree();
            }}
          >
            Retry
          </Button>
        </div>
      ) : null}
      {!loadedOnce && memory.loading ? <p className={styles.note}>Loading memory…</p> : null}
      {loadedOnce && nodes.length === 0 ? (
        <EmptyState icon="book_2" title="No memory files" description="Markdown under context/memory/ appears here." headingLevel={3} />
      ) : null}
      {searching && treeNodes.length === 0 && nodes.length > 0 ? <p className={styles.note}>No files match “{query.trim()}”</p> : null}
      {treeNodes.length ? (
        <Tree
          className={styles.tree}
          nodes={treeNodes}
          aria-label="Memory files"
          expanded={expandedIds}
          onExpandedChange={(ids) => {
            if (searching) setSearchExpanded({ query, ids });
            else setExpanded(ids);
          }}
          selectedId={selected}
          onSelect={(n) => onOpen?.(n.id, n.label)}
        />
      ) : null}
    </div>
  );
}
