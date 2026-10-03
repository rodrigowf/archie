/**
 * Fetchers that fill `@/stores` catalog and server-config stores, plus the list mutations
 * (rename, duplicate) and config saves. Mutations are not broadcast (G-27, MC-1): the acting
 * client refreshes its own stores. Every failure surfaces the backend `detail` verbatim.
 */
import {
  catalogStore,
  patchServerConfig,
  serverConfigStore,
  setCatalogError,
  setCatalogItems,
  setCatalogLoading,
  showSnackbar,
  updateSessionItems,
  updateVisualItems,
} from '@/stores';
import { api } from './http/endpoints';
import { errorMessage, isApiError } from './http/errors';
import type { ServerConfig, ServerConfigUpdate } from './http/types';

export const LIST_REFRESH_DEBOUNCE_MS = 2000;

function singleFlight<T>(fn: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return () => {
    if (!inFlight)
      inFlight = fn().finally(() => {
        inFlight = null;
      });
    return inFlight;
  };
}

function debounced(fn: () => Promise<unknown>, ms: number): { schedule(): void; cancel(): void } {
  let t: ReturnType<typeof setTimeout> | null = null;
  return {
    schedule() {
      if (t) return; // at most one refresh per window (MC-2: 1 per 2 s)
      t = setTimeout(() => {
        t = null;
        void fn();
      }, ms);
    },
    cancel() {
      if (t) clearTimeout(t);
      t = null;
    },
  };
}

/** `GET /api/sessions` (titles are derived from this list, MC-2). */
export const refreshSessionList = singleFlight(async () => {
  setCatalogLoading('sessions', true);
  try {
    setCatalogItems('sessions', await api.sessions.list());
  } catch (err) {
    setCatalogError('sessions', errorMessage(err));
  }
});

export const refreshVisuals = singleFlight(async () => {
  setCatalogLoading('visuals', true);
  try {
    setCatalogItems('visuals', await api.visuals.list());
  } catch (err) {
    setCatalogError('visuals', errorMessage(err));
  }
});

export const refreshMemoryTree = singleFlight(async () => {
  setCatalogLoading('memory', true);
  try {
    setCatalogItems('memory', await api.memory.tree());
  } catch (err) {
    setCatalogError('memory', errorMessage(err));
  }
});

const listRefresh = debounced(refreshSessionList, LIST_REFRESH_DEBOUNCE_MS);
const visualsRefresh = debounced(refreshVisuals, LIST_REFRESH_DEBOUNCE_MS);

/** After any view's turn end (MC-2; §9.1, compat included). */
export function scheduleListRefresh(): void {
  listRefresh.schedule();
}

export function scheduleVisualsRefresh(): void {
  visualsRefresh.schedule();
}

export function cancelScheduledRefreshes(): void {
  listRefresh.cancel();
  visualsRefresh.cancel();
}

/** §6.6: optimistic title, `PATCH` (404 tolerated), then refresh. Rejects with the verbatim error and rolls back. */
export async function renameSession(sdkId: string, title: string): Promise<void> {
  const t = title.trim();
  const prev = catalogStore.getState().sessions.items;
  updateSessionItems((items) => items.map((s) => (s.session_id === sdkId ? { ...s, title: t } : s)));
  try {
    await api.sessions.rename(sdkId, t);
  } catch (err) {
    if (!isApiError(err, 404)) {
      updateSessionItems(() => prev);
      throw err;
    }
  }
  void refreshSessionList();
}

/** Duplicate: `{session_id}`; refresh; not opened (web parity, §6.8). */
export async function duplicateSession(sdkId: string): Promise<string> {
  const r = await api.sessions.duplicate(sdkId);
  void refreshSessionList();
  return r.session_id;
}

/** §9.1 rename: optimistic, 404 tolerated, refresh. */
export async function renameVisualization(path: string, title: string): Promise<void> {
  const t = title.trim();
  const prev = catalogStore.getState().visuals.items;
  updateVisualItems((items) => items.map((v) => (v.path === path ? { ...v, title: t } : v)));
  try {
    await api.visuals.rename(path, t);
  } catch (err) {
    if (!isApiError(err, 404)) {
      updateVisualItems(() => prev);
      throw err;
    }
  }
  void refreshVisuals();
}

// ───────────────────────── server config (§8.1) ─────────────────────────

/** CFG-3: settings screens refetch on open. */
export async function loadServerConfig(): Promise<void> {
  patchServerConfig({ loading: true, error: null });
  try {
    patchServerConfig({ config: await api.config.get(), loading: false });
  } catch (err) {
    patchServerConfig({ loading: false, error: errorMessage(err) });
  }
}

/** The read-only catalogs of the settings pages. Each failure is independent. */
export async function loadConfigCatalogs(): Promise<void> {
  const settle = async <T>(p: Promise<T>, apply: (v: T) => void): Promise<void> => {
    try {
      apply(await p);
    } catch {
      // the page shows what it has; the field stays null
    }
  };
  await Promise.all([
    settle(api.config.providers(), (r) => patchServerConfig({ providers: r.providers })),
    settle(api.config.qwenModels(), (r) => patchServerConfig({ qwenModels: r.models })),
    settle(api.voice.orchestratorModels(), (r) => patchServerConfig({ orchestratorModels: r })),
    settle(api.voice.voiceModels(), (r) => patchServerConfig({ voiceModels: r })),
    settle(api.catalogs.mcpServers(), (r) => patchServerConfig({ mcpServers: r })),
    settle(api.catalogs.skills(), (r) => patchServerConfig({ skills: r.skills })),
    settle(api.catalogs.agents(), (r) => patchServerConfig({ agents: r.agents })),
  ]);
}

export async function loadGoogleVoiceModels(endpoint: string): Promise<void> {
  try {
    const r = await api.config.googleVoiceModels(endpoint);
    patchServerConfig({ googleVoiceModels: { ...serverConfigStore.getState().googleVoiceModels, [endpoint]: r.models } });
  } catch {
    // keep the previous list
  }
}

/**
 * CFG-1/CFG-2: partial PUT; the returned object replaces the local copy (CFG-5). On failure
 * the snackbar shows the backend `detail` verbatim with Retry, and the promise rejects.
 */
export async function saveServerConfig(patch: ServerConfigUpdate, opts: { key?: string; snackbar?: boolean } = {}): Promise<ServerConfig> {
  const key = opts.key ?? Object.keys(patch).join(',');
  patchServerConfig({ saving: key, saveError: null });
  try {
    const config = await api.config.put(patch);
    patchServerConfig({ config, saving: null });
    if (opts.snackbar !== false) showSnackbar('Saved', { durationMs: 2000 });
    return config;
  } catch (err) {
    const msg = errorMessage(err);
    patchServerConfig({ saving: null, saveError: msg });
    if (opts.snackbar !== false)
      showSnackbar(msg, { tone: 'error', action: { label: 'Retry', run: () => void saveServerConfig(patch, opts).catch(() => undefined) } });
    throw err;
  }
}
