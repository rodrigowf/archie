/**
 * Archie (server) → Voice and Voice tuning (inv02 F-31, spec 12 §8.1).
 *
 * Voice defaults cascade on the server (CFG-5): changing the provider snaps model / voice /
 * language to its defaults, changing the model snaps voice / language. So each control PUTs only
 * its own key and the page re-renders from the returned object. Google's catalog is discovered
 * per endpoint and auto-corrected (CFG-6, **[LOAD-BEARING]** F-31). Sliders commit on release
 * (fixes inv02 §6.2 "PUT on every slider tick").
 */
import { useState } from 'react';
import type { ServerConfig } from '@/services';
import { useServerConfig } from '@/stores';
import { Select, Slider, Switch, type SelectOption } from '@/ui/controls';
import { dismissAutoCorrect, saveSetting, useSettingsUi } from '../controller';
import {
  formatGain,
  formatMs,
  formatThreshold,
  GOOGLE_ENDPOINTS,
  languageOptions,
  TUNING,
  voiceCatalog,
  voiceOptions,
  voiceProviderLabel,
} from '../logic';
import { Field, FieldStack, Notice, useFieldId } from '../parts';
import { useSaving, WithConfig } from './shared';

export function VoicePage() {
  return <WithConfig>{(cfg) => <VoiceForm cfg={cfg} />}</WithConfig>;
}

function VoiceForm({ cfg }: { cfg: ServerConfig }) {
  const voiceModels = useServerConfig((s) => s.voiceModels);
  const google = useServerConfig((s) => s.googleVoiceModels[cfg.default_voice_endpoint]);
  const corrected = useSettingsUi((s) => s.autoCorrected);
  const saving = useSaving();
  const recId = useFieldId('rec');
  const catalog = voiceCatalog(voiceModels, google);
  const providerIds = Object.keys(catalog);
  if (cfg.default_voice_provider && providerIds.indexOf(cfg.default_voice_provider) < 0) providerIds.unshift(cfg.default_voice_provider);
  const provider = cfg.default_voice_provider;
  const entries = catalog[provider] ?? [];
  const entry = entries.find((m) => m.id === cfg.default_voice_model);

  const providerOptions: SelectOption[] = providerIds.map((p) => ({ value: p, label: voiceProviderLabel(p) }));
  const modelOptions: SelectOption[] = entries.map((m) => ({
    value: m.id,
    label: m.label || m.id,
    ...(m.description && m.description !== m.label ? { description: m.description } : {}),
  }));
  if (!entry && cfg.default_voice_model) modelOptions.unshift({ value: cfg.default_voice_model, label: `${cfg.default_voice_model} (not listed)` });
  const voices: SelectOption[] = voiceOptions(entry).map((v) => ({ value: v.id, label: v.label, ...(v.description ? { description: v.description } : {}) }));
  if (cfg.default_voice_name && !voices.some((v) => v.value === cfg.default_voice_name)) voices.unshift({ value: cfg.default_voice_name, label: cfg.default_voice_name });
  const langs: SelectOption[] = languageOptions(entry).map((l) => ({ value: l.id, label: l.label, ...(l.description ? { description: l.description } : {}) }));
  const lang = cfg.default_voice_transcription_language ?? '';
  if (langs.length && !langs.some((l) => l.value === lang)) langs.push({ value: lang, label: lang });

  const save = (patch: Partial<ServerConfig>): void => {
    void saveSetting(patch, 'voice');
  };

  return (
    <>
      {corrected ? (
        <Notice tone="warning" title="Gemini Live model switched" onDismiss={dismissAutoCorrect}>
          <p>
            The previously saved Gemini Live model {corrected.from} is no longer available from Google. Switched to {corrected.to}.
          </p>
        </Notice>
      ) : null}
      <FieldStack label="Voice conversations">
        <Field help="Applies to the next voice conversation; one in progress keeps its settings.">
          <Select
            label="Provider"
            options={providerOptions}
            value={provider}
            disabled={saving || !providerOptions.length}
            onChange={(p) => {
              if (p !== provider) save({ default_voice_provider: p });
            }}
          />
          {provider === 'google' ? (
            <Select
              label="Google backend"
              options={GOOGLE_ENDPOINTS}
              value={cfg.default_voice_endpoint}
              disabled={saving}
              supportingText="Vertex AI is the stable path; AI Studio may refuse preview models (error 1008)."
              onChange={(e) => {
                if (e !== cfg.default_voice_endpoint) save({ default_voice_endpoint: e });
              }}
            />
          ) : null}
          <Select
            label="Model"
            options={modelOptions}
            value={cfg.default_voice_model}
            disabled={saving || !modelOptions.length}
            onChange={(m) => {
              if (m !== cfg.default_voice_model) save({ default_voice_model: m });
            }}
          />
          {voices.length ? (
            <Select
              label="Voice"
              options={voices}
              value={cfg.default_voice_name}
              disabled={saving}
              onChange={(v) => {
                if (v !== cfg.default_voice_name) save({ default_voice_name: v });
              }}
            />
          ) : null}
          {langs.length ? (
            <Select
              label="Transcription language"
              options={langs}
              value={lang}
              disabled={saving}
              supportingText="Auto-detect is best when you mix languages."
              onChange={(l) => {
                if (l !== lang) save({ default_voice_transcription_language: l });
              }}
            />
          ) : null}
        </Field>
        <Field
          label="Record voice conversations"
          labelId={recId}
          help="Keeps the audio on the server."
          info={
            <p>
              Saved in <code>context/recordings/</code>.
            </p>
          }
          trailing={
            <Switch
              aria-labelledby={recId}
              checked={cfg.voice_recording_enabled}
              disabled={saving}
              onCheckedChange={(v) => {
                void saveSetting({ voice_recording_enabled: v }, 'voice_recording_enabled');
              }}
            />
          }
        />
      </FieldStack>
    </>
  );
}

export function VoiceTuningPage() {
  return <WithConfig>{(cfg) => <VoiceTuningForm cfg={cfg} />}</WithConfig>;
}

interface TuningSliderProps {
  label: string;
  help: string;
  info?: string;
  value: number;
  min: number;
  max: number;
  step: number;
  format: (v: number) => string;
  disabled: boolean;
  onCommit: (v: number) => void;
}

/** A labelled slider whose value shows live while dragging and saves once, on release. */
function TuningSlider({ label, help, info, value, min, max, step, format, disabled, onCommit }: TuningSliderProps) {
  const id = useFieldId('tune');
  const [live, setLive] = useState<number | null>(null);
  return (
    <Field label={label} labelId={id} value={format(live ?? value)} help={help} info={info}>
      <Slider
        aria-labelledby={id}
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        formatValue={format}
        onValueChange={setLive}
        onCommit={(v) => {
          setLive(null);
          if (Math.abs(v - value) > step / 1000) onCommit(v);
        }}
      />
    </Field>
  );
}

function VoiceTuningForm({ cfg }: { cfg: ServerConfig }) {
  const saving = useSaving();
  return (
    <FieldStack>
      <TuningSlider
        label="Speech detection threshold"
        help="Higher ignores more background noise. 0.15 to 0.50."
        info="Voice activity detection (VAD) threshold. Raise it in noisy rooms; lower it if Archie misses quiet speech."
        value={cfg.voice_vad_threshold}
        {...TUNING.vad}
        format={formatThreshold}
        disabled={saving}
        onCommit={(v) => {
          void saveSetting({ voice_vad_threshold: Math.round(v * 100) / 100 }, 'voice_vad_threshold');
        }}
      />
      <TuningSlider
        label="Pause before replying"
        help="Silence that ends your turn. 800 to 5000 ms."
        info="Minimum silence (VAD min silence) before Archie treats your turn as finished. Longer lets you pause mid-sentence."
        value={cfg.voice_vad_min_silence_ms}
        {...TUNING.silence}
        format={formatMs}
        disabled={saving}
        onCommit={(v) => {
          void saveSetting({ voice_vad_min_silence_ms: Math.round(v) }, 'voice_vad_min_silence_ms');
        }}
      />
      <TuningSlider
        label="Server mic gain"
        help="Saved, but the server does not use it yet."
        info="Reserved: the backend stores voice_mic_gain but does not apply it (G-37). Device mic levels are on Android."
        value={cfg.voice_mic_gain}
        {...TUNING.gain}
        format={formatGain}
        disabled={saving}
        onCommit={(v) => {
          void saveSetting({ voice_mic_gain: Math.round(v * 100) / 100 }, 'voice_mic_gain');
        }}
      />
    </FieldStack>
  );
}
