/** Fake backend for the settings tests: `/api/config` (PUT merges like the backend) + catalogs. */
import type { ServerConfig } from '@/services';
import { jsonResponse, type FakeFetch, type RecordedRequest } from '../../../services/__tests__/fakes';

export const CONFIG: ServerConfig = {
  working_directory: '192.168.0.28:/home/rodrigo/assistant',
  working_directory_history: [
    { id: '/home/rodrigo/assistant', path: '/home/rodrigo/assistant', label: 'Jetson (local)', ssh_host: null, ssh_user: null, ssh_key: null, claude_config_dir: null },
    {
      id: '192.168.0.28:/home/rodrigo/assistant',
      path: '/home/rodrigo/assistant',
      label: 'Laptop (Desktop)',
      ssh_host: '192.168.0.28',
      ssh_user: 'rodrigo',
      ssh_key: null,
      claude_config_dir: '/home/rodrigo/assistant/.claude_config',
    },
  ],
  enabled_mcps: [],
  chrome_extension: true,
  provider: 'claude',
  default_model: 'gpt-audio-mini',
  summarizer_model: '',
  harness_model: { claude: '', qwen: '' },
  default_voice_provider: 'openai',
  default_voice_model: 'gpt-realtime-2',
  default_voice_name: 'cedar',
  default_voice_transcription_language: '',
  default_voice_endpoint: 'aistudio',
  voice_recording_enabled: false,
  voice_vad_threshold: 0.28,
  voice_vad_min_silence_ms: 1800,
  voice_mic_gain: 1,
};

const voice = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  label: id,
  voice: 'cedar',
  voices: [
    { id: 'cedar', label: 'Cedar', description: 'Recommended' },
    { id: 'marin', label: 'Marin' },
  ],
  transcription_languages: [],
  default_transcription_language: '',
  default: false,
  ...extra,
});

export const CATALOGS = {
  providers: {
    providers: [
      { id: 'claude', label: 'Claude Code', description: "Anthropic's Claude Code CLI." },
      { id: 'qwen', label: 'Qwen Code', description: "Alibaba's Qwen Code CLI." },
      { id: 'gemini', label: 'Gemini CLI', description: "Google's Gemini CLI." },
    ],
  },
  qwen: { models: [{ id: 'qwen3.6-plus', display_name: 'qwen3.6-plus', context_window: 1000000, supports_thinking: true }] },
  orchestrator: {
    models: [
      { provider: 'anthropic', model_id: 'claude-sonnet-4-5-20250929', display_name: 'Claude Sonnet 4.5', supports_audio: false, supports_vision: true, context_window: 200000 },
      { provider: 'openai', model_id: 'gpt-audio-mini', display_name: 'GPT Audio Mini', supports_audio: true, supports_vision: false, context_window: 128000 },
      { provider: 'openai', model_id: 'gpt-audio', display_name: 'GPT Audio', supports_audio: true, supports_vision: false, context_window: 128000 },
    ],
    audio_capable_models: ['gpt-audio-mini', 'gpt-audio'],
    default_model: 'claude-sonnet-4-5-20250929',
  },
  voice: {
    providers: {
      openai: [voice('gpt-realtime-2'), voice('gpt-realtime', { default: true })],
      qwen: [voice('qwen3.5-omni-plus-realtime', { voice: 'Aiden', voices: [{ id: 'Aiden', label: 'Aiden' }], transcription_languages: ['en', 'pt'] })],
    },
    default_provider: 'openai',
    default_model: 'gpt-realtime',
  },
  google: {
    models: [
      voice('gemini-3.5-transcribe-live', { voice: 'Puck', voices: [{ id: 'Puck', label: 'Puck' }, { id: 'Kore', label: 'Kore' }], default: true }),
      voice('gemini-2.5-flash-native-audio-latest', { voice: 'Puck', voices: [{ id: 'Puck', label: 'Puck' }] }),
    ],
  },
  mcp: {
    servers: {
      'chrome-devtools': { type: 'stdio', command: '/usr/bin/npx', args: ['-y', 'chrome-devtools-mcp@latest'] },
      filesystem: { type: 'stdio', command: 'npx', args: ['@modelcontextprotocol/server-filesystem'] },
      github: { type: 'stdio', command: 'gh-mcp' },
    },
    project_dir: '/home/rodrigo/assistant',
  },
  skills: { skills: [{ name: 'recall', description: 'Search memory', dir: 'recall' }] },
  agents: { agents: [{ name: 'researcher', description: 'Web research', file: 'researcher.md' }] },
};

export interface ConfigServer {
  config: ServerConfig;
  puts: () => Record<string, unknown>[];
}

/**
 * Serve config + catalogs. PUT merges like the backend (harness_model shallow) unless `reject`
 * returns a `{status, detail}` for that body.
 */
export function serveConfig(
  f: FakeFetch,
  opts: { config?: Partial<ServerConfig>; reject?: (body: Record<string, unknown>) => { status: number; detail: string } | null } = {},
): ConfigServer {
  const state: ConfigServer = {
    config: { ...CONFIG, ...opts.config },
    puts: () => f.calls('PUT', '/api/config').map((r) => r.body as Record<string, unknown>),
  };
  f.on('GET', '/api/config', () => jsonResponse(state.config))
    .on('PUT', '/api/config', (req: RecordedRequest) => {
      const body = req.body as Record<string, unknown>;
      const no = opts.reject?.(body);
      if (no) return jsonResponse({ detail: no.detail }, no.status);
      const next = { ...state.config, ...body } as ServerConfig;
      if (body.harness_model) next.harness_model = { ...state.config.harness_model, ...(body.harness_model as Record<string, string>) };
      state.config = next;
      return jsonResponse(next);
    })
    .on('GET', '/api/config/providers', CATALOGS.providers)
    .on('GET', '/api/config/harness/qwen/models', CATALOGS.qwen)
    .on('GET', '/api/orchestrator/models', CATALOGS.orchestrator)
    .on('GET', '/api/orchestrator/voice/models', CATALOGS.voice)
    .on('GET', /^\/api\/config\/voice\/google\/models/, CATALOGS.google)
    .on('GET', '/api/mcp/servers', CATALOGS.mcp)
    .on('GET', '/api/skills', CATALOGS.skills)
    .on('GET', '/api/agents', CATALOGS.agents)
    .on('GET', '/api/auth/status', { authenticated: true, auth_url: null, headless: true });
  return state;
}
