/**
 * Server lists with loading/error (spec 13 §3.3): session history, live pool, memory tree,
 * visualizations. Pure state: services fetch and write here (`@/services` refresh functions).
 */
import { createStore } from 'zustand/vanilla';
import type { MemoryNode, PoolSession, SessionInfo, VisualizationInfo } from '@/services';

export interface ListSlice<T> {
  readonly items: readonly T[];
  readonly loading: boolean;
  /** Verbatim server detail (or transport message) of the last failed fetch. */
  readonly error: string | null;
  /** `Date.now()` of the last successful fetch; 0 = never. */
  readonly loadedAt: number;
}

export interface CatalogState {
  readonly sessions: ListSlice<SessionInfo>;
  readonly pool: ListSlice<PoolSession>;
  readonly memory: ListSlice<MemoryNode>;
  readonly visuals: ListSlice<VisualizationInfo>;
}

export type CatalogKey = keyof CatalogState;

const empty = <T>(): ListSlice<T> => ({ items: [], loading: false, error: null, loadedAt: 0 });

export const catalogStore = createStore<CatalogState>(() => ({
  sessions: empty(),
  pool: empty(),
  memory: empty(),
  visuals: empty(),
}));

export function setCatalogLoading(key: CatalogKey, loading: boolean): void {
  catalogStore.setState((s) => ({ [key]: { ...s[key], loading } }) as Partial<CatalogState>);
}

export function setCatalogItems<K extends CatalogKey>(key: K, items: CatalogState[K]['items'], now = Date.now()): void {
  catalogStore.setState({ [key]: { items, loading: false, error: null, loadedAt: now } } as Partial<CatalogState>);
}

/** A failed fetch keeps the previous items (unlike inv02 F-19, which blanked the list). */
export function setCatalogError(key: CatalogKey, error: string): void {
  catalogStore.setState((s) => ({ [key]: { ...s[key], loading: false, error } }) as Partial<CatalogState>);
}

/** Optimistic edit of the session list (rename, delete; MC-1). */
export function updateSessionItems(fn: (items: readonly SessionInfo[]) => readonly SessionInfo[]): void {
  catalogStore.setState((s) => ({ sessions: { ...s.sessions, items: fn(s.sessions.items) } }));
}

export function updateVisualItems(fn: (items: readonly VisualizationInfo[]) => readonly VisualizationInfo[]): void {
  catalogStore.setState((s) => ({ visuals: { ...s.visuals, items: fn(s.visuals.items) } }));
}

export function resetCatalog(): void {
  catalogStore.setState({ sessions: empty(), pool: empty(), memory: empty(), visuals: empty() });
}
