/**
 * Archie (server) → Conversation model and Agent sessions (inv02 F-31; spec 12 §8.1).
 *
 * P-9 / O-7: the Settings `default_model` now decides the model of new Archie conversations
 * (`SETTINGS_DEFAULT_MODEL_FIRST = True`); retired ids (`gpt-4o-audio-preview`, OpenAI 404) are
 * skipped by the server, so the page says so instead of showing a dead choice as healthy.
 *
 * Two models (2026-10-04): no OpenAI chat model takes both typed text and audio, so Archie uses
 * the **text model** (`default_model`) for typed messages and the **audio model**
 * (`default_audio_model`, "" = server default) for voice messages. Live voice is the Voice page.
 */
import type { ServerConfig } from '@/services';
import { useServerConfig } from '@/stores';
import { Select, Switch, type SelectOption } from '@/ui/controls';
import { saveSetting } from '../controller';
import {
  audioModels,
  findModel,
  harnessModels,
  modelAvailability,
  modelProviderLabel,
  modelProviders,
  modelTraits,
  textModels,
} from '../logic';
import { Field, FieldStack, Notice, useFieldId } from '../parts';
import { useSaving, WithConfig } from './shared';
import type { ModelInfo } from '@/protocol';

function modelOptions(models: readonly ModelInfo[], provider: string, current: string): SelectOption[] {
  const opts: SelectOption[] = models
    .filter((m) => m.provider === provider && m.model_id)
    .map((m) => {
      const traits = modelTraits(m);
      return { value: m.model_id as string, label: m.display_name ?? (m.model_id as string), description: traits ? `${m.model_id} · ${traits}` : m.model_id };
    });
  if (current && !opts.some((o) => o.value === current)) opts.unshift({ value: current, label: `${current} (unavailable)`, disabled: true });
  return opts;
}

function firstModelOf(models: readonly ModelInfo[], provider: string): string | null {
  return models.find((m) => m.provider === provider && m.model_id)?.model_id ?? null;
}

const SERVER_DEFAULT = '';

export function ConversationModelPage() {
  return <WithConfig>{(cfg) => <ConversationModelForm cfg={cfg} />}</WithConfig>;
}

function ConversationModelForm({ cfg }: { cfg: ServerConfig }) {
  const catalog = useServerConfig((s) => s.orchestratorModels);
  const saving = useSaving();
  const models = catalog?.models ?? [];
  const typed = textModels(models);
  const providers = modelProviders(typed);
  const current = cfg.default_model;
  const provider = findModel(models, current)?.provider ?? providers[0] ?? '';
  const availability = modelAvailability(current, catalog);
  const currentIsAudioOnly = findModel(models, current)?.supports_audio === true;
  const providerOptions: SelectOption[] = providers.map((p) => ({ value: p, label: modelProviderLabel(p) }));

  const audio = audioModels(models);
  const audioCurrent = cfg.default_audio_model ?? '';
  const serverAudio = catalog?.default_audio_model;
  const serverDefault: SelectOption =
    serverAudio && !audioCurrent
      ? { value: SERVER_DEFAULT, label: 'Server default', description: `Now ${serverAudio}` }
      : { value: SERVER_DEFAULT, label: 'Server default' };
  const audioOptions: SelectOption[] = [serverDefault, ...modelOptions(audio, 'openai', audioCurrent)];
  const audioSupported = cfg.default_audio_model !== undefined;

  const summ = cfg.summarizer_model;
  const summProvider = summ ? (findModel(models, summ)?.provider ?? providers[0] ?? '') : SERVER_DEFAULT;
  const summProviderOptions: SelectOption[] = [{ value: SERVER_DEFAULT, label: 'Server default' }].concat(providerOptions);

  return (
    <>
      {availability !== 'ok' ? (
        <Notice tone="warning" title={availability === 'retired' ? 'This model was retired' : 'This model is not in the catalog'}>
          <p>
            “{current}” {availability === 'retired' ? 'answers 404 at OpenAI' : 'is not offered by the server'}, so new conversations skip it and use
            the server&apos;s fallback. Pick another model below.
          </p>
        </Notice>
      ) : null}
      {currentIsAudioOnly ? (
        <Notice tone="info" title="The text model is an audio model">
          <p>
            “{current}” can&apos;t answer typed messages, so the server answers them with gpt-4o. Pick a text model below, and choose the
            audio model separately.
          </p>
        </Notice>
      ) : null}
      <FieldStack label="Archie">
        <Field
          help="Answers typed messages. New Archie conversations start on it."
          info={
            <>
              <p>You can still switch models inside a conversation.</p>
              <p>If this model can&apos;t be used, the server falls back to its ORCHESTRATOR_MODEL setting, then gpt-audio.</p>
            </>
          }
        >
          <Select
            label="Text model provider"
            options={providerOptions}
            value={provider}
            disabled={saving || !providers.length}
            onChange={(p) => {
              const first = firstModelOf(typed, p);
              if (first && p !== provider) void saveSetting({ default_model: first }, 'default_model');
            }}
          />
          <Select
            label="Text model"
            options={modelOptions(typed, provider, current)}
            value={current}
            disabled={saving || !models.length}
            supportingText={catalog ? undefined : 'Loading the model list…'}
            onChange={(id) => {
              if (id !== current) void saveSetting({ default_model: id }, 'default_model');
            }}
          />
        </Field>
        <Field
          help="Answers voice messages (recorded clips)."
          info={
            <>
              <p>Audio models take audio but refuse typed messages, and text models refuse audio, so Archie uses one of each.</p>
              <p>Live voice conversations use the Voice page instead.</p>
            </>
          }
        >
          <Select
            label="Audio model"
            options={audioOptions}
            value={audioCurrent}
            disabled={saving || !audioSupported || !models.length}
            supportingText={audioSupported ? undefined : 'This server has no separate audio model setting yet.'}
            onChange={(id) => {
              if (id !== audioCurrent) void saveSetting({ default_audio_model: id }, 'default_audio_model');
            }}
          />
        </Field>
      </FieldStack>
      <FieldStack label="History summaries">
        <Field help="Summarizes older history for Archie's voice prompt.">
          <Select
            label="Summarizer provider"
            options={summProviderOptions}
            value={summProvider}
            disabled={saving}
            onChange={(p) => {
              if (p === summProvider) return;
              if (p === SERVER_DEFAULT) void saveSetting({ summarizer_model: '' }, 'summarizer_model');
              else {
                const first = firstModelOf(models, p);
                if (first) void saveSetting({ summarizer_model: first }, 'summarizer_model');
              }
            }}
          />
          {summProvider !== SERVER_DEFAULT ? (
            <Select
              label="Summarizer model"
              options={modelOptions(models, summProvider, summ)}
              value={summ}
              disabled={saving}
              onChange={(id) => {
                if (id !== summ) void saveSetting({ summarizer_model: id }, 'summarizer_model');
              }}
            />
          ) : null}
        </Field>
      </FieldStack>
    </>
  );
}

export function AgentSessionsPage() {
  return <WithConfig>{(cfg) => <AgentSessionsForm cfg={cfg} />}</WithConfig>;
}

function AgentSessionsForm({ cfg }: { cfg: ServerConfig }) {
  const providers = useServerConfig((s) => s.providers);
  const qwenRaw = useServerConfig((s) => s.qwenModels);
  const saving = useSaving();
  const chromeId = useFieldId('chrome');
  const list = providers ?? [];
  const options: SelectOption[] = list.map((p) => ({ value: p.id, label: p.label || p.id }));
  if (cfg.provider && !options.some((o) => o.value === cfg.provider)) options.unshift({ value: cfg.provider, label: cfg.provider });
  const selected = list.find((p) => p.id === cfg.provider);
  const qwenModels = harnessModels(qwenRaw);
  const qwenCurrent = cfg.harness_model?.qwen ?? '';
  const qwenOptions: SelectOption[] = [{ value: '', label: 'CLI default' }].concat(
    qwenModels.map((m) => ({ value: m.id, label: m.label, ...(m.traits ? { description: m.traits } : {}) })),
  );
  if (qwenCurrent && !qwenOptions.some((o) => o.value === qwenCurrent)) qwenOptions.push({ value: qwenCurrent, label: qwenCurrent });

  return (
    <>
      <FieldStack label="New sessions">
        <Field help="Applies to new agent tabs. Per session: ⋮ → Session settings.">
          <Select
            label="Default harness"
            options={options}
            value={cfg.provider}
            disabled={saving || !options.length}
            supportingText={selected?.description}
            onChange={(id) => {
              if (id !== cfg.provider) void saveSetting({ provider: id }, 'provider');
            }}
          />
          {cfg.provider === 'qwen' ? (
            <Select
              label="Qwen model"
              options={qwenOptions}
              value={qwenCurrent}
              disabled={saving}
              supportingText={qwenModels.length ? undefined : 'No models listed. Run qwen once on the server to create ~/.qwen/settings.json.'}
              onChange={(id) => {
                if (id !== qwenCurrent) void saveSetting({ harness_model: { qwen: id } }, 'harness_model');
              }}
            />
          ) : null}
        </Field>
        <Field
          label="Claude in Chrome"
          labelId={chromeId}
          help="Starts Claude sessions with the --chrome flag."
          info="Anthropic's Claude-in-Chrome integration. Archie's own browser extension (browser-control) does not need this."
          trailing={
            <Switch
              aria-labelledby={chromeId}
              checked={cfg.chrome_extension}
              disabled={saving}
              onCheckedChange={(v) => {
                void saveSetting({ chrome_extension: v }, 'chrome_extension');
              }}
            />
          }
        />
      </FieldStack>
    </>
  );
}
