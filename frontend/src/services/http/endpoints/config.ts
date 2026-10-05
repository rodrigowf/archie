/** Global config and catalogs (inventory 01 §3.6, §3.7). `GET /api/config/openai-key` is never called. */
import { http } from '../client';
import type { AgentInfo, McpServers, ProviderInfo, ServerConfig, ServerConfigUpdate, SkillInfo, VoiceModelEntry } from '../types';

export const config = {
  get: () => http.get<ServerConfig>('/api/config'),
  /** Partial update; the full object comes back (CFG-1, CFG-5). 400 → `ApiError.detail` verbatim (CFG-2). */
  put: (patch: ServerConfigUpdate) => http.put<ServerConfig>('/api/config', { json: patch }),
  providers: () => http.get<{ providers: ProviderInfo[] }>('/api/config/providers'),
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
