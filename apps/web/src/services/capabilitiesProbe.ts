/**
 * Backend capability probes (spec 13 §3.4, §3.7):
 * - audio-capable orchestrator models (P-5: the voice-message button follows the **selected**
 *   model; `modelAcceptsAudio` in `@/stores`);
 * - BX-2 "Show on TV": `GET /api/visualizations/cast` → `{available, reason}`. A 404, a
 *   `text/html` SPA-fallback 200 (a backend without BX-2, G-38) or a network error all mean
 *   "unavailable": the action is hidden, not disabled. Probed at start and on visibility.
 *
 * Deviation from spec 13 §3.7's sketch (`/cast/status` + `tv_connected`): the shipped BX-2
 * route is `GET /api/visualizations/cast` with `{available, reason}` (plan/20 §A).
 */
import { patchCapabilities } from '@/stores';
import { api } from './http/endpoints';
import { errorMessage } from './http/errors';
import type { CastResult } from './http/types';

export async function probeCast(): Promise<boolean> {
  try {
    const r = await api.visuals.castProbe();
    const available = !!r && r.available === true;
    patchCapabilities({
      castAvailable: available,
      castReason: available ? null : (r && typeof r.reason === 'string' ? r.reason : 'Not available'),
      probedAt: Date.now(),
    });
    return available;
  } catch (err) {
    patchCapabilities({ castAvailable: false, castReason: errorMessage(err), probedAt: Date.now() });
    return false;
  }
}

/** Cast a visualization. Rejects with the verbatim server error; `{ok:false}` carries its message. */
export function castVisualization(path: string): Promise<CastResult> {
  return api.visuals.cast(path);
}

export async function probeAudioModels(): Promise<void> {
  try {
    const r = await api.voice.orchestratorModels();
    patchCapabilities({
      audioCapableModels: Array.isArray(r.audio_capable_models) ? r.audio_capable_models : [],
      orchestratorModels: Array.isArray(r.models) ? r.models : [],
    });
  } catch {
    // keep the previous values; the button stays hidden until known
  }
}

export async function probeBackendCapabilities(): Promise<void> {
  await Promise.all([probeCast(), probeAudioModels()]);
}
