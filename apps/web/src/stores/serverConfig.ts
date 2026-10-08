/**
 * Server-global configuration and read-only catalogs (spec 12 §8.1, spec 13 §3.3). Config is
 * not broadcast (G-27): screens refetch on open (CFG-3). After a PUT the returned object
 * replaces the local copy (CFG-1, CFG-5).
 */
import { createStore } from 'zustand/vanilla';
import type {
  AgentInfo,
  HarnessInfo,
  McpServers,
  OrchestratorModels,
  ProviderInfo,
  ServerConfig,
  SkillInfo,
  VoiceModelEntry,
  VoiceModels,
} from '@/services';

export interface ServerConfigState {
  readonly config: ServerConfig | null;
  readonly loading: boolean;
  /** Verbatim error of the last load. */
  readonly error: string | null;
  /** The key (or section name) whose save is in flight: its controls are disabled (CFG-1). */
  readonly saving: string | null;
  /** Verbatim backend `detail` of the last failed save (CFG-2). */
  readonly saveError: string | null;
  readonly providers: readonly ProviderInfo[] | null;
  /**
   * `GET /api/config/harnesses`: every harness with its catalog (models + options). On an older
   * server (no endpoint) it is built from `/api/config/providers` + the Qwen model list.
   */
  readonly harnesses: readonly HarnessInfo[] | null;
  readonly orchestratorModels: OrchestratorModels | null;
  readonly voiceModels: VoiceModels | null;
  /** `GET /api/config/voice/google/models?endpoint=…` per endpoint. */
  readonly googleVoiceModels: Readonly<Record<string, readonly VoiceModelEntry[]>>;
  readonly mcpServers: McpServers | null;
  readonly skills: readonly SkillInfo[] | null;
  readonly agents: readonly AgentInfo[] | null;
}

const initial: ServerConfigState = {
  config: null,
  loading: false,
  error: null,
  saving: null,
  saveError: null,
  providers: null,
  harnesses: null,
  orchestratorModels: null,
  voiceModels: null,
  googleVoiceModels: {},
  mcpServers: null,
  skills: null,
  agents: null,
};

export const serverConfigStore = createStore<ServerConfigState>(() => initial);

export function patchServerConfig(partial: Partial<ServerConfigState>): void {
  serverConfigStore.setState(partial);
}

export function resetServerConfig(): void {
  serverConfigStore.setState(initial, true);
}
