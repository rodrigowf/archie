/** Memory (inventory 01 §3.4; spec 12 §9.2). Read-only; paths percent-encoded per segment (MEM-3). */
import { encodePath, http } from '../client';
import type { MemoryNode } from '../types';

export const memory = {
  tree: () => http.get<MemoryNode[]>('/api/memory/tree'),
  /** Raw markdown. 404 for directories and missing files. `''` = the root `MEMORY.md`. */
  file: (path: string) => http.get<string>(path ? `/memory/${encodePath(path)}` : '/memory/', { as: 'text' }),
};
