/**
 * REST shapes (inventory 01 §3; `api/models.py`). Field names are the backend's. History pages
 * (`MessagesPage`) and `ModelInfo` come from `@/protocol`.
 */
import type { LiveStatus, ModelInfo, Provider } from '@/protocol';

/** `SessionInfoResponse` (api/models.py:12-22). `session_id` is the sdk id / jsonl id, NOT local_id. */
export interface SessionInfo {
  session_id: string;
  started_at: string;
  last_activity: string;
  title: string;
  message_count: number;
  is_orchestrator: boolean;
  provider: Provider | string;
  local_id: string | null;
}

/** `PoolSessionResponse` (api/models.py:82-90). The orchestrator row always says `idle` (G-15). */
export interface PoolSession {
  local_id: string;
  sdk_session_id: string | null;
  status: LiveStatus | string;
  cost: number;
  turns: number;
  title: string | null;
  is_orchestrator: boolean;
}

/** `GET/PUT /api/sessions/{sdk}/config`: `null` = inherit global. */
export interface SessionConfig {
  working_directory: string | null;
  enabled_mcps: string[] | null;
  chrome_extension: boolean | null;
  provider: string | null;
  harness_model: string | null;
}

export interface WorkingDirectoryEntry {
  id: string;
  path: string;
  label: string;
  ssh_host: string | null;
  ssh_user: string | null;
  ssh_key: string | null;
  claude_config_dir: string | null;
}

/** `GET /api/config` (inventory 01 §3.6). Unknown keys are kept. */
export interface ServerConfig {
  working_directory: string;
  working_directory_history: WorkingDirectoryEntry[];
  enabled_mcps: string[];
  chrome_extension: boolean;
  provider: string;
  default_model: string;
  /** Model for Archie turns that carry audio (voice messages); "" = server default. Absent on older servers. */
  default_audio_model?: string;
  summarizer_model: string;
  harness_model: Record<string, string>;
  default_voice_provider: string;
  default_voice_model: string;
  default_voice_name: string;
  default_voice_transcription_language: string;
  default_voice_endpoint: string;
  voice_recording_enabled: boolean;
  voice_vad_threshold: number;
  voice_vad_min_silence_ms: number;
  voice_mic_gain: number;
  [key: string]: unknown;
}

export type ServerConfigUpdate = Partial<ServerConfig>;

export interface ProviderInfo {
  id: string;
  label: string;
  description?: string;
}

export interface OrchestratorModels {
  models: ModelInfo[];
  audio_capable_models: string[];
  default_model: string;
  /** The model the server uses for voice messages (Settings value or its fallback). */
  default_audio_model?: string;
}

export interface VoiceModelVoice {
  id: string;
  label?: string;
  description?: string;
}

export interface VoiceModelEntry {
  id: string;
  label?: string;
  voices?: VoiceModelVoice[];
  transcription_languages?: string[];
  default_transcription_language?: string;
  default?: boolean;
  description?: string;
  [key: string]: unknown;
}

export interface VoiceModels {
  providers: Record<string, VoiceModelEntry[]>;
  default_provider: string;
  default_model: string;
}

export interface McpServers {
  servers: Record<string, Record<string, unknown>>;
  project_dir?: string;
}

export interface SkillInfo {
  name: string;
  description: string;
  dir?: string;
}

export interface AgentInfo {
  name: string;
  description: string;
  file?: string;
}

export interface MemoryNode {
  name: string;
  path: string;
  is_dir: boolean;
  children: MemoryNode[] | null;
}

export interface VisualizationInfo {
  path: string;
  url: string;
  title: string;
  created: string;
  modified: string;
  size: number;
}

export interface AuthStatus {
  authenticated: boolean;
  auth_url: string | null;
  headless: boolean;
}

export interface UploadResult {
  filename: string;
  path: string;
  url: string;
  size: number;
  content_type: string;
}

/** BX-2 `GET /api/visualizations/cast`. */
export interface CastProbe {
  available: boolean;
  reason?: string | null;
}

/** BX-2 `POST /api/visualizations/cast`. */
export interface CastResult {
  ok: boolean;
  message: string;
}

/** `POST /api/orchestrator/voice/session` (fallback only, V-3). */
export interface VoiceSessionToken {
  connection_info?: Record<string, unknown>;
  [key: string]: unknown;
}
