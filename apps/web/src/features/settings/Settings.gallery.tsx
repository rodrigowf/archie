/**
 * W-13 gallery board: the Settings home + a detail page in both layouts, a page pushed over the
 * list (compact), and the sign-in screen. Stores are seeded with realistic values (no backend);
 * the session sheet needs a live session, so it is shown in the running app (mock backend). The
 * Agent sessions page renders the harness catalogs of `harnessSamples.ts`.
 */
import { useState, type ReactNode } from 'react';
import { authStore, SignInScreen } from '@/features/auth';
import { patchServerConfig, type ServerConfigState } from '@/stores';
import type { ServerConfig } from '@/services';
import { HARNESS_SAMPLES } from './harnessSamples';
import SettingsView from './SettingsView';
import type { SettingsPageId } from './pages';

const CONFIG: ServerConfig = {
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
  harness_model: { claude: 'opus', qwen: '', gemini: '', codex: 'gpt-5.6-terra' },
  harness_options: { claude: { effort: 'xhigh', todo_tools: true }, codex: { effort: 'ultra', reasoning_summary: 'detailed' } },
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

const voices = [
  { id: 'cedar', label: 'Cedar', description: 'Realtime-exclusive, recommended' },
  { id: 'marin', label: 'Marin', description: 'Realtime-exclusive, recommended' },
];

export const GALLERY_STATE: Partial<ServerConfigState> = {
  config: CONFIG,
  loading: false,
  error: null,
  providers: [
    { id: 'claude', label: 'Claude Code', description: "Anthropic's official Claude Code CLI (the canonical harness)." },
    { id: 'qwen', label: 'Qwen Code', description: "Alibaba's Qwen Code CLI." },
    { id: 'gemini', label: 'Gemini CLI', description: "Google's Gemini CLI." },
    { id: 'codex', label: 'Codex', description: 'OpenAI Codex CLI (app-server).' },
  ],
  harnesses: HARNESS_SAMPLES,
  orchestratorModels: {
    models: [
      { provider: 'anthropic', model_id: 'claude-sonnet-4-5-20250929', display_name: 'Claude Sonnet 4.5', supports_audio: false, supports_vision: true, context_window: 200000 },
      { provider: 'openai', model_id: 'gpt-audio-mini', display_name: 'GPT Audio Mini', supports_audio: true, supports_vision: false, context_window: 128000 },
    ],
    audio_capable_models: ['gpt-audio-mini'],
    default_model: 'claude-sonnet-4-5-20250929',
  },
  voiceModels: {
    providers: {
      openai: [
        { id: 'gpt-realtime-2', label: 'GPT realtime 2', voice: 'cedar', voices, transcription_languages: [] },
        { id: 'gpt-realtime', label: 'GPT Realtime', voice: 'cedar', voices, transcription_languages: [], default: true },
      ],
      qwen: [{ id: 'qwen3.5-omni-plus-realtime', label: 'Qwen Omni Realtime', voice: 'Aiden', voices: [{ id: 'Aiden', label: 'Aiden' }], transcription_languages: ['en', 'pt'] }],
    },
    default_provider: 'openai',
    default_model: 'gpt-realtime',
  },
  mcpServers: {
    servers: {
      'chrome-devtools': { type: 'stdio', command: 'npx', args: ['-y', 'chrome-devtools-mcp@latest', '--isolated'] },
      filesystem: { type: 'stdio', command: 'npx', args: ['@modelcontextprotocol/server-filesystem'] },
    },
    project_dir: '/home/rodrigo/assistant',
  },
  skills: [],
  agents: [],
};

function seed(): true {
  patchServerConfig(GALLERY_STATE);
  if (!authStore.getState().status) authStore.setState({ status: { authenticated: true, auth_url: null, headless: true } });
  return true;
}

function Frame({ width, height, children }: { width: number; height: number; children: ReactNode }) {
  return (
    <div
      style={{
        position: 'relative',
        width,
        maxWidth: '100%',
        height,
        overflow: 'auto',
        borderRadius: 24,
        background: 'var(--md-sys-color-surface)',
        outline: '1px solid var(--md-sys-color-outline-variant)',
      }}
    >
      {children}
    </div>
  );
}

export function SettingsGallery() {
  useState(seed); // seed the stores once, before the first render of the pages
  const [page, setPage] = useState<SettingsPageId | null>('voice-tuning');
  const [phonePage, setPhonePage] = useState<SettingsPageId | null>(null);
  const [agentPage, setAgentPage] = useState<SettingsPageId | null>('agent-sessions');
  return (
    <div>
      <h3>Expanded: two panes</h3>
      <Frame width={1100} height={640}>
        <SettingsView page={page} layout="two-pane" load={false} onNavigate={setPage} />
      </Frame>
      <h3>Compact: list, then a pushed page (click a row)</h3>
      <Frame width={412} height={760}>
        <SettingsView page={phonePage} layout="pushed" load={false} onNavigate={setPhonePage} />
      </Frame>
      <h3>Compact: Agent sessions (harness catalogs: model + options per harness)</h3>
      <Frame width={412} height={900}>
        <SettingsView page={agentPage} layout="pushed" load={false} onNavigate={setAgentPage} />
      </Frame>
      <h3>Sign-in screen (headless server)</h3>
      <Frame width={520} height={620}>
        <SignInScreen inline />
      </Frame>
    </div>
  );
}
