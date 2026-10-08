/**
 * Labels of session harnesses ("providers": `claude`, `qwen`, `gemini`, `codex`, `modelstudio`, …).
 * The ids come from the backend's harness registry, so the web app knows none of them in advance:
 * the short tag of a known harness comes from `SHORT_PROVIDER_LABELS`, any other id is labelled from
 * the registry the store already loaded (`GET /api/config/harnesses`, or `/api/config/providers`
 * on older servers), and an id nobody knows shows as is. A new harness needs no web edit.
 */
import type { HarnessInfo, ProviderInfo } from '@/services';
import { serverConfigStore } from './serverConfig';

/** Compact tags for the harnesses Archie ships (tab tags, history rows). */
export const SHORT_PROVIDER_LABELS: Readonly<Record<string, string>> = {
  claude: 'Claude',
  qwen: 'Qwen',
  gemini: 'Gemini',
  codex: 'Codex',
  modelstudio: 'Model Studio',
};

/** The label of a harness id: short tag → registry label → the id ("" / null → null). */
export function providerLabel(
  id: string | null | undefined,
  harnesses?: readonly Pick<HarnessInfo, 'id' | 'label'>[] | null,
  providers?: readonly Pick<ProviderInfo, 'id' | 'label'>[] | null,
): string | null {
  if (!id) return null;
  const short = Object.prototype.hasOwnProperty.call(SHORT_PROVIDER_LABELS, id) ? SHORT_PROVIDER_LABELS[id] : undefined;
  if (short) return short;
  const fromRegistry = (harnesses ?? []).find((h) => h.id === id)?.label || (providers ?? []).find((p) => p.id === id)?.label;
  return fromRegistry || id;
}

/** `providerLabel` with the registry currently in the store (non-React callers). */
export function currentProviderLabel(id: string | null | undefined): string | null {
  const s = serverConfigStore.getState();
  return providerLabel(id, s.harnesses, s.providers);
}
