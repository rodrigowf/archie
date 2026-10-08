/** Global config and catalogs (inventory 01 §3.6, §3.7). `GET /api/config/openai-key` is never called. */
import { http } from '../client';
import type { AgentInfo, HarnessCatalog, HarnessInfo, McpServers, ProviderInfo, ServerConfig, ServerConfigUpdate, SkillInfo, VoiceModelEntry } from '../types';

export const config = {
  get: () => http.get<ServerConfig>('/api/config'),
  /** Partial update; the full object comes back (CFG-1, CFG-5). 400 → `ApiError.detail` verbatim (CFG-2). */
  put: (patch: ServerConfigUpdate) => http.put<ServerConfig>('/api/config', { json: patch }),
  /** Every harness with its catalog (models + options); `refresh` rebuilds the server's cache. 404 on older servers. */
  harnesses: (refresh = false) => http.get<{ harnesses: HarnessInfo[] }>('/api/config/harnesses', { query: { refresh: refresh || undefined } }),
  harnessCatalog: (provider: string, refresh = false) =>
    http.get<HarnessCatalog>(`/api/config/harness/${encodeURIComponent(provider)}/catalog`, { query: { refresh: refresh || undefined } }),
  /** Superseded by `harnesses` (kept for older servers). */
  providers: () => http.get<{ providers: ProviderInfo[] }>('/api/config/providers'),
  /** Superseded by `harnesses` (kept for older servers). */
  qwenModels: () => http.get<{ models: unknown[] }>('/api/config/harness/qwen/models'),
  /** G-33: the live voice catalog omits `google`; this path lists it. */
  googleVoiceModels: (endpoint?: string) =>
    http.get<{ models: VoiceModelEntry[] }>('/api/config/voice/google/models', { query: { endpoint } }),
};

export const catalogs = {
  skills: () => http.get<{ skills: SkillInfo[] }>('/api/skills'),
  agents: () => http.get<{ agents: AgentInfo[] }>('/api/agents'),
  mcpServers: () => http.get<McpServers>('/api/mcp/servers'),
  mcpServer: (name: string) => http.get<{ name: string; config: Record<string, unknown> }>(`/api/mcp/servers/${encodeURIComponent(name)}`),
};
