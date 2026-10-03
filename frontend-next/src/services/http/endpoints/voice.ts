/** Orchestrator models and voice REST (inventory 01 §3.8). */
import type { ModelInfo } from '@/protocol';
import { http } from '../client';
import type { OrchestratorModels, VoiceModels, VoiceSessionToken } from '../types';

export interface VoiceTarget {
  provider?: string;
  model?: string;
  voice?: string;
  transcription_language?: string;
  endpoint?: string;
}

export const voice = {
  orchestratorModels: () => http.get<OrchestratorModels>('/api/orchestrator/models'),
  audioModels: () => http.get<{ models: ModelInfo[] }>('/api/orchestrator/models/audio'),
  voiceModels: () => http.get<VoiceModels>('/api/orchestrator/voice/models'),
  /**
   * Fallback token endpoint (V-3): only when `session_started.voice_connection_info` is absent,
   * and then with all five query params (its defaults differ, G-33).
   */
  session: (t: Required<VoiceTarget>) => http.post<VoiceSessionToken>('/api/orchestrator/voice/session', { query: { ...t } }),
};
