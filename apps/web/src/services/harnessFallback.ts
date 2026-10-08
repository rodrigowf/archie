/**
 * Older servers have no `GET /api/config/harnesses`: build the same rows from
 * `GET /api/config/providers` and `GET /api/config/harness/qwen/models` so the settings screens
 * render one way. Also used when a newer server lists Qwen without a catalog.
 */
import type { HarnessCatalog, HarnessCatalogModel, HarnessInfo, ProviderInfo } from './http/types';

const str = (v: unknown): string => (typeof v === 'string' ? v : '');

/** `/api/config/harness/qwen/models` rows: plain ids or `{id, display_name, context_window, supports_*}`. */
export function qwenCatalogFromModels(raw: readonly unknown[] | null | undefined): HarnessCatalog {
  const models: HarnessCatalogModel[] = [];
  for (const r of raw ?? []) {
    if (typeof r === 'string') {
      if (r && !models.some((m) => m.id === r)) models.push({ id: r, label: r, source: 'settings' });
      continue;
    }
    if (!r || typeof r !== 'object') continue;
    const o = r as Record<string, unknown>;
    const id = str(o.id);
    if (!id || models.some((m) => m.id === id)) continue;
    const row: HarnessCatalogModel = { id, label: str(o.display_name) || str(o.label) || id, source: 'settings' };
    if (typeof o.context_window === 'number' && o.context_window > 0) row.context_window = o.context_window;
    if (typeof o.supports_thinking === 'boolean') row.supports_thinking = o.supports_thinking;
    if (typeof o.supports_vision === 'boolean') row.supports_vision = o.supports_vision;
    if (typeof o.description === 'string' && o.description) row.description = o.description;
    models.push(row);
  }
  return {
    provider: 'qwen',
    models,
    options: [],
    default_model: null,
    allow_custom_model: true,
    warnings: models.length ? [] : ['No models listed. Run qwen once on the server to create ~/.qwen/settings.json.'],
  };
}

export function harnessesFromProviders(providers: readonly ProviderInfo[], qwenModels: readonly unknown[] | null): HarnessInfo[] {
  return providers.map((p) => ({
    id: p.id,
    label: p.label || p.id,
    ...(p.description ? { description: p.description } : {}),
    catalog: p.id === 'qwen' && qwenModels ? qwenCatalogFromModels(qwenModels) : null,
  }));
}

export function providersFromHarnesses(harnesses: readonly HarnessInfo[]): ProviderInfo[] {
  return harnesses.map((h) => ({ id: h.id, label: h.label || h.id, ...(h.description ? { description: h.description } : {}) }));
}
