/**
 * Memory tree model (inv02 F-37, spec 12 §9.2). `GET /api/memory/tree` returns only folders that
 * hold markdown at some depth, folders first, each alphabetical. This module adds what the
 * approved design shows (mockups phone (g)): file counts per folder, search across every file's
 * path, `MEMORY.md` pinned first, and top-level folders expanded by default (MEM-4).
 */
import type { MemoryNode } from '@/services';
import type { TreeNode } from '@/ui/navigation';

/** Number of files under a node (a file counts 1). Dirs whose `children` is null count 0 (inv02 §6.2 latent bug). */
export function countFiles(node: MemoryNode): number {
  if (!node.is_dir) return 1;
  let n = 0;
  for (const c of node.children ?? []) n += countFiles(c);
  return n;
}

export function countAll(nodes: readonly MemoryNode[]): number {
  let n = 0;
  for (const c of nodes) n += countFiles(c);
  return n;
}

const ROOT_INDEX = /^memory\.md$/i;

/** Root `MEMORY.md` first (the index everyone starts from), then the backend order. */
export function pinIndexFirst(nodes: readonly MemoryNode[]): MemoryNode[] {
  const idx = nodes.findIndex((n) => !n.is_dir && ROOT_INDEX.test(n.name));
  if (idx <= 0) return nodes.slice();
  const out = nodes.slice();
  const [index] = out.splice(idx, 1);
  return index ? [index].concat(out) : out;
}

/**
 * Files whose path matches every word of `query` (case-insensitive, any order), with the folders
 * that lead to them. A folder whose own path matches keeps all of its files.
 */
export function filterMemory(nodes: readonly MemoryNode[], query: string): MemoryNode[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return nodes.slice();
  const matches = (path: string): boolean => {
    const p = path.toLowerCase();
    return words.every((w) => p.indexOf(w) >= 0);
  };
  const walk = (list: readonly MemoryNode[]): MemoryNode[] => {
    const out: MemoryNode[] = [];
    for (const n of list) {
      if (!n.is_dir) {
        if (matches(n.path)) out.push(n);
        continue;
      }
      if (matches(n.path)) {
        out.push(n);
        continue;
      }
      const kids = walk(n.children ?? []);
      if (kids.length) out.push({ ...n, children: kids });
    }
    return out;
  };
  return walk(nodes);
}

/** Ids (paths) of every folder in the list, depth-first. */
export function folderIds(nodes: readonly MemoryNode[], out: string[] = []): string[] {
  for (const n of nodes)
    if (n.is_dir) {
      out.push(n.path);
      folderIds(n.children ?? [], out);
    }
  return out;
}

export function topFolderIds(nodes: readonly MemoryNode[]): string[] {
  return nodes.filter((n) => n.is_dir).map((n) => n.path);
}

/** Tree view nodes: id = path; folders carry their file count as trailing meta. */
export function toTreeNodes(nodes: readonly MemoryNode[]): TreeNode[] {
  return nodes.map((n) =>
    n.is_dir
      ? { id: n.path, label: n.name, children: toTreeNodes(n.children ?? []), meta: String(countFiles(n)) }
      : { id: n.path, label: n.name, icon: 'description' as const },
  );
}

/** "assistant / architecture" for `assistant/architecture/voice.md`; "" at the root. */
export function folderCrumb(path: string): string {
  const parts = path.split('/').filter(Boolean);
  parts.pop();
  return parts.join(' / ');
}

export function fileName(path: string): string {
  const parts = path.split('/').filter(Boolean);
  return parts[parts.length - 1] ?? path;
}

/** Find a node by path. */
export function findNode(nodes: readonly MemoryNode[], path: string): MemoryNode | null {
  for (const n of nodes) {
    if (n.path === path) return n;
    if (n.is_dir && path.indexOf(`${n.path}/`) === 0) return findNode(n.children ?? [], path);
  }
  return null;
}
