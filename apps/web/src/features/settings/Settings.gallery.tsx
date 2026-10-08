/**
 * W-13 gallery board: the Settings home + a detail page in both layouts, a page pushed over the
 * list (compact), and the sign-in screen. Stores are seeded with realistic values (no backend);
 * the session sheet needs a live session, so it is shown in the running app (mock backend). The
 * Agent sessions page renders the harness catalogs of `harnessSamples.ts`; "Harness option
 * controls" shows every option control (switch, segmented, levels, slider with log scale /
 * presets / `requires`, dropdown) in both scopes.
 */
import { useState, type ReactNode } from 'react';
import { authStore, SignInScreen } from '@/features/auth';
import { patchServerConfig, type ServerConfigState } from '@/stores';
import type { HarnessInfo, HarnessOptionsMap, ServerConfig } from '@/services';
import { withSessionOption } from './harness';
import { HarnessFields } from './HarnessFields';
import { HARNESS_SAMPLES } from './harnessSamples';
import { FieldStack } from './parts';
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

/** One option per control kind (hints as the backend sends them). */
const CONTROLS_HARNESS: HarnessInfo = {
  id: 'gallery',
  label: 'Every control',
  catalog: {
    provider: 'gallery',
    models: [{ id: 'm1', label: 'Model one', source: 'builtin', default_effort: 'medium' }],
    default_model: 'm1',
    allow_custom_model: true,
    warnings: [],
    options: [
      { key: 'todo_tools', label: 'Checklist tools (switch)', kind: 'toggle', default: true, help: 'A toggle with a known default.' },
      { key: 'thinking', label: 'Thinking (segmented toggle)', kind: 'toggle', help: 'A toggle whose default depends on the model.' },
      {
        key: 'effort',
        label: 'Reasoning effort (levels)',
        kind: 'select',
        ordered: true,
        choices: [
          { value: 'low', label: 'Low' },
          { value: 'medium', label: 'Medium' },
          { value: 'high', label: 'High' },
          { value: 'xhigh', label: 'Extra high' },
          { value: 'max', label: 'Max' },
        ],
      },
      {
        key: 'approval_mode',
        label: 'Tool approval (segmented, wraps)',
        kind: 'select',
        control: 'segmented',
        default: 'yolo',
        choices: [
          { value: 'yolo', label: 'Run every tool', description: "Archie's default" },
          { value: 'auto_edit', label: 'Edits only', description: 'File edits run; shell and other tools are denied' },
          { value: 'plan', label: 'Plan (read-only)' },
          { value: 'default', label: 'Read-only' },
        ],
      },
      {
        key: 'thinking_budget',
        label: 'Thinking budget (log slider, requires)',
        kind: 'number',
        default: 16000,
        min: 1024,
        max: 128000,
        step: 1024,
        unit: 'tokens',
        scale: 'log',
        requires: { thinking: [true] },
      },
      {
        key: 'gemini_budget',
        label: 'Budget (presets)',
        kind: 'number',
        default: 8192,
        min: -1,
        max: 32768,
        step: 1,
        unit: 'tokens',
        scale: 'log',
        custom_min: 128,
        presets: [
          { value: -1, label: 'Dynamic', description: 'The model decides' },
          { value: 0, label: 'Off', description: 'Flash models only' },
        ],
      },
      { key: 'temperature', label: 'Temperature (linear slider)', kind: 'number', min: 0, max: 2, step: 0.1, control: 'slider' },
      {
        key: 'fallback_model',
        label: 'Fallback model (dropdown)',
        kind: 'select',
        control: 'dropdown',
        choices: [
          { value: 'sonnet', label: 'Sonnet' },
          { value: 'opus', label: 'Opus' },
        ],
      },
    ],
  },
};

function ControlsBoard({ scope }: { scope: 'global' | 'session' }) {
  const global: HarnessOptionsMap = { effort: 'high', thinking: true, gemini_budget: -1 };
  const [opts, setOpts] = useState<HarnessOptionsMap | null>(scope === 'global' ? global : { todo_tools: false });
  const [model, setModel] = useState<string | null>(scope === 'global' ? '' : null);
  return (
    <FieldStack label={scope === 'global' ? 'Global defaults' : 'Session (inherits the global column)'}>
      <HarnessFields
        harness={CONTROLS_HARNESS}
        scope={scope}
        model={model}
        options={opts}
        {...(scope === 'session' ? { inheritedModel: '', inheritedOptions: global } : {})}
        disabled={false}
        onModel={setModel}
        onOption={(key, st) => {
          setOpts((cur) => {
            if (scope === 'session') return withSessionOption(cur, key, st);
            const next: HarnessOptionsMap = {};
            for (const k of Object.keys(cur ?? {})) if (k !== key) next[k] = cur?.[k] ?? null;
            if (st.kind === 'value') next[key] = st.value;
            return next;
          });
        }}
      />
    </FieldStack>
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
      <h3>Harness option controls (global · session)</h3>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <Frame width={412} height={1500}>
          <div style={{ padding: 16 }}>
            <ControlsBoard scope="global" />
          </div>
        </Frame>
        <div style={{ width: 24 }} />
        <Frame width={412} height={1500}>
          <div style={{ padding: 16 }}>
            <ControlsBoard scope="session" />
          </div>
        </Frame>
      </div>
      <h3>Sign-in screen (headless server)</h3>
      <Frame width={520} height={620}>
        <SignInScreen inline />
      </Frame>
    </div>
  );
}
