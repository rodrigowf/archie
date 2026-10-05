/**
 * Client + backend capabilities (spec 13 §3.3, §3.7). Client capabilities come from
 * `@/platform` detection; backend capabilities from `capabilitiesProbe` (`@/services`).
 */
import { createStore } from 'zustand/vanilla';
import { detectCapabilities, type ClientCapabilities } from '@/platform';
import type { ModelInfo, OrchestratorModelInfo } from '@/protocol';

export interface CapabilitiesState {
  readonly client: ClientCapabilities | null;
  /** `GET /api/orchestrator/models.audio_capable_models` (Appendix B Q5). */
  readonly audioCapableModels: readonly string[];
  readonly orchestratorModels: readonly ModelInfo[];
  /** BX-2 probe: the "Show on TV" action is hidden (not disabled) unless true. */
  readonly castAvailable: boolean;
  readonly castReason: string | null;
  readonly probedAt: number;
}

function safeDetect(): ClientCapabilities | null {
  try {
    return typeof window === 'undefined' ? null : detectCapabilities();
  } catch {
    return null;
  }
}

export const capabilitiesStore = createStore<CapabilitiesState>(() => ({
  client: safeDetect(),
  audioCapableModels: [],
  orchestratorModels: [],
  castAvailable: false,
  castReason: null,
  probedAt: 0,
}));

export function patchCapabilities(partial: Partial<CapabilitiesState>): void {
  capabilitiesStore.setState(partial);
}

/**
 * P-5: the voice-message button shows only when the **current** Archie model accepts audio
 * (fixes inv02 §6.2: it looked at any audio-capable model). Falls back to the catalog when the
 * session has not reported `supports_audio`.
 */
export function modelAcceptsAudio(model: OrchestratorModelInfo | null, state: CapabilitiesState = capabilitiesStore.getState()): boolean {
  if (!model) return false;
  if (typeof model.supports_audio === 'boolean') return model.supports_audio;
  if (typeof model.model_info?.supports_audio === 'boolean') return model.model_info.supports_audio;
  const id = model.model ?? model.model_info?.model_id;
  return !!id && state.audioCapableModels.indexOf(id) >= 0;
}
