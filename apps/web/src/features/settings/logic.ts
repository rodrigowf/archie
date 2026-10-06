/**
 * Pure settings logic (W-13): MCP "all enabled" semantics (CFG-4), working-directory coercion,
 * validation and list edits (F-33, CFG-7), the Google voice-model auto-correct (CFG-6, F-31
 * **[LOAD-BEARING]**), catalog normalisation and the one-line summaries of the Settings home.
 * No React, no I/O: everything here is unit-tested in `__tests__/logic.test.ts`.
 */
import type { ModelInfo } from '@/protocol';
import type { OrchestratorModels, ServerConfig, VoiceModelEntry, VoiceModels, WorkingDirectoryEntry } from '@/services';

// ───────────────────────── MCP servers (CFG-4) ─────────────────────────

/** `enabled_mcps: []` means every server is enabled (`api/routes/config.py:125`). */
export function isAllEnabled(enabled: readonly string[] | null | undefined): boolean {
  return !enabled || enabled.length === 0;
}

export function isMcpEnabled(enabled: readonly string[] | null | undefined, name: string): boolean {
  return isAllEnabled(enabled) || (enabled as readonly string[]).indexOf(name) >= 0;
}

/** Names of the servers shown as on (every server when the list is empty). */
export function enabledMcpNames(enabled: readonly string[] | null | undefined, all: readonly string[]): string[] {
  return all.filter((n) => isMcpEnabled(enabled, n));
}

/**
 * The list to save after switching one server. Unchecking X while the list is empty writes the
 * explicit list of every other server; checking every server writes `[]`. Names not in `all`
 * (servers removed from `.claude.json`) are dropped.
 */
export function toggleMcp(enabled: readonly string[] | null | undefined, all: readonly string[], name: string, on: boolean): string[] {
  const set = enabledMcpNames(enabled, all).filter((n) => n !== name);
  if (on) set.push(name);
  const next = all.filter((n) => set.indexOf(n) >= 0);
  return next.length === all.length ? [] : next;
}

/**
 * The backend cannot express "none enabled" (an empty list means all), so the last server that
 * is on cannot be switched off. Returns the reason, or null when the switch may turn off.
 */
export function mcpOffBlockedReason(enabled: readonly string[] | null | undefined, all: readonly string[], name: string): string | null {
  const on = enabledMcpNames(enabled, all);
  return on.length === 1 && on[0] === name ? 'Last one on (an empty list means all)' : null;
}

export function mcpSummary(enabled: readonly string[] | null | undefined, all: readonly string[]): string {
  if (all.length === 0) return 'None configured';
  const n = enabledMcpNames(enabled, all).length;
  if (n === all.length) return all.length === 1 ? 'All enabled (1 server)' : `All ${all.length} enabled`;
  return `${n} of ${all.length} enabled`;
}

export function mcpCommandLine(cfg: Record<string, unknown> | undefined): string {
  if (!cfg) return '';
  const command = typeof cfg.command === 'string' ? cfg.command : typeof cfg.url === 'string' ? cfg.url : '';
  const args = Array.isArray(cfg.args) ? cfg.args.filter((a): a is string => typeof a === 'string') : [];
  const base = command.split('/').pop() ?? command;
  return [base, ...args].join(' ').trim();
}

// ───────────────────────── working directories (F-33, CFG-7) ─────────────────────────

export const MAX_WORKING_DIRECTORIES = 20;

const str = (v: unknown): string => (typeof v === 'string' ? v : '');
const strOrNull = (v: unknown): string | null => {
  const s = str(v).trim();
  return s ? s : null;
};

/** `host:path` for SSH entries, the path for local ones (the backend's id rule). */
export function workingDirectoryId(e: { path: string; ssh_host?: string | null }): string {
  return e.ssh_host ? `${e.ssh_host}:${e.path}` : e.path;
}

/**
 * Defensive coercion of one history row (**[LOAD-BEARING]** F-33, commit 01b24e3: malformed rows
 * crashed the old panel). Legacy plain strings become local entries; rows without a path are
 * dropped (null).
 */
export function coerceWorkingDirectory(raw: unknown): WorkingDirectoryEntry | null {
  if (typeof raw === 'string') {
    const p = raw.trim();
    return p ? { id: p, path: p, label: '', ssh_host: null, ssh_user: null, ssh_key: null, claude_config_dir: null } : null;
  }
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const path = str(o.path).trim();
  if (!path) return null;
  const ssh_host = strOrNull(o.ssh_host);
  const id = str(o.id).trim() || workingDirectoryId({ path, ssh_host });
  return {
    id,
    path,
    label: str(o.label).trim(),
    ssh_host,
    ssh_user: ssh_host ? strOrNull(o.ssh_user) : null,
    ssh_key: ssh_host ? strOrNull(o.ssh_key) : null,
    claude_config_dir: ssh_host ? strOrNull(o.claude_config_dir) : null,
  };
}

export function coerceWorkingDirectories(raw: unknown): WorkingDirectoryEntry[] {
  if (!Array.isArray(raw)) return [];
  const out: WorkingDirectoryEntry[] = [];
  for (const r of raw) {
    const e = coerceWorkingDirectory(r);
    if (e && !out.some((x) => x.id === e.id)) out.push(e);
  }
  return out;
}

export function workingDirectoryName(e: WorkingDirectoryEntry): string {
  return e.label || (e.ssh_host ? `${e.ssh_host}:${e.path}` : e.path);
}

export function workingDirectoryDetail(e: WorkingDirectoryEntry): string {
  if (!e.ssh_host) return e.path;
  return `${e.ssh_user ? `${e.ssh_user}@` : ''}${e.ssh_host} · ${e.path}`;
}

export interface WorkingDirectoryDraft {
  kind: 'local' | 'ssh';
  path: string;
  label: string;
  host: string;
  user: string;
  key: string;
  configDir: string;
}

export const EMPTY_DRAFT: WorkingDirectoryDraft = { kind: 'local', path: '', label: '', host: '', user: '', key: '', configDir: '' };

/** The backend's default `CLAUDE_CONFIG_DIR` for an SSH entry. */
export function defaultConfigDir(path: string): string {
  return `${path.replace(/\/+$/, '')}/.claude_config`;
}

export function draftFromEntry(e: WorkingDirectoryEntry): WorkingDirectoryDraft {
  const c = coerceWorkingDirectory(e) ?? e;
  const derived = c.ssh_host && c.claude_config_dir === defaultConfigDir(c.path);
  return {
    kind: c.ssh_host ? 'ssh' : 'local',
    path: c.path,
    label: c.label,
    host: c.ssh_host ?? '',
    user: c.ssh_user ?? '',
    key: c.ssh_key ?? '',
    // An auto-derived value is shown as the placeholder, so it follows a path change.
    configDir: derived ? '' : (c.claude_config_dir ?? ''),
  };
}

export type DraftErrors = Partial<Record<'path' | 'host' | 'user', string>>;

export function validateDraft(d: WorkingDirectoryDraft, history: readonly WorkingDirectoryEntry[], editingId: string | null): DraftErrors {
  const errors: DraftErrors = {};
  const path = d.path.trim();
  const host = d.host.trim();
  if (!path) errors.path = d.kind === 'ssh' ? 'Remote path is required' : 'Path is required';
  else if (!/^[/~]/.test(path)) errors.path = 'Use an absolute path (starts with / or ~)';
  if (d.kind === 'ssh') {
    if (!host) errors.host = 'SSH host is required';
    else if (/\s/.test(host)) errors.host = 'The host has no spaces';
    else if (host.indexOf('@') >= 0) errors.host = 'Put the user in the User field';
    if (/\s|@/.test(d.user.trim())) errors.user = 'Just the user name';
  }
  if (!errors.path && !errors.host && editingId !== null) {
    const id = workingDirectoryId({ path, ssh_host: d.kind === 'ssh' ? host : null });
    if (id !== editingId && history.some((e) => e.id === id)) errors.path = 'Another directory already uses this location';
  }
  return errors;
}

export function entryFromDraft(d: WorkingDirectoryDraft): WorkingDirectoryEntry {
  const path = d.path.trim();
  const ssh = d.kind === 'ssh';
  const ssh_host = ssh ? d.host.trim() : null;
  return {
    // Always the computed id: the backend keeps a non-empty id it is given, so an edited SSH
    // entry would otherwise keep its old `host:path`.
    id: workingDirectoryId({ path, ssh_host }),
    path,
    label: d.label.trim(),
    ssh_host,
    ssh_user: ssh ? strOrNull(d.user) : null,
    ssh_key: ssh ? strOrNull(d.key) : null,
    // null → the backend derives `<path>/.claude_config`.
    claude_config_dir: ssh ? strOrNull(d.configDir) : null,
  };
}

export interface HistoryEdit {
  history: WorkingDirectoryEntry[];
  active: string;
}

/** Add: an entry that already exists is just selected (F-33); a new one is appended and becomes active. */
export function addWorkingDirectory(history: readonly WorkingDirectoryEntry[], d: WorkingDirectoryDraft): HistoryEdit {
  const entry = entryFromDraft(d);
  const list = coerceWorkingDirectories(history);
  if (list.some((e) => e.id === entry.id)) return { history: list, active: entry.id };
  return { history: list.concat([entry]), active: entry.id };
}

/** Edit: replace by the old id (the id may change); the edited entry becomes active. */
export function editWorkingDirectory(history: readonly WorkingDirectoryEntry[], oldId: string, d: WorkingDirectoryDraft): HistoryEdit {
  const entry = entryFromDraft(d);
  const list = coerceWorkingDirectories(history).map((e) => (e.id === oldId ? entry : e));
  return { history: list, active: entry.id };
}

/** Delete (never the only entry). If the active one goes, the first remaining becomes active. */
export function deleteWorkingDirectory(history: readonly WorkingDirectoryEntry[], id: string, active: string): HistoryEdit | null {
  const list = coerceWorkingDirectories(history);
  if (list.length <= 1) return null;
  const next = list.filter((e) => e.id !== id);
  if (next.length === list.length) return null;
  return { history: next, active: next.some((e) => e.id === active) ? active : (next[0] as WorkingDirectoryEntry).id };
}

export function workingDirectoriesSummary(history: readonly WorkingDirectoryEntry[], active: string): string {
  const list = coerceWorkingDirectories(history);
  if (!list.length) return 'None';
  const ssh = list.filter((e) => e.ssh_host).length;
  const current = list.find((e) => e.id === active);
  const count = `${list.length} ${list.length === 1 ? 'directory' : 'directories'}${ssh ? ` · ${ssh} over SSH` : ''}`;
  return current ? `${workingDirectoryName(current)} · ${count}` : count;
}

// ───────────────────────── models (P-9, O-7) ─────────────────────────

/** Ids the backend skips (O-7 `RETIRED_MODEL_IDS`): OpenAI answers 404 for them. */
export const RETIRED_MODEL_IDS: readonly string[] = ['gpt-4o-audio-preview', 'gpt-4o-mini-audio-preview'];

export const MODEL_PROVIDER_LABELS: Readonly<Record<string, string>> = { anthropic: 'Anthropic', openai: 'OpenAI', google: 'Google' };

export function modelProviderLabel(p: string): string {
  return MODEL_PROVIDER_LABELS[p] ?? p;
}

export function modelProviders(models: readonly ModelInfo[]): string[] {
  const out: string[] = [];
  for (const m of models) if (m.provider && out.indexOf(m.provider) < 0) out.push(m.provider);
  return out;
}

export function findModel(models: readonly ModelInfo[], id: string): ModelInfo | undefined {
  return models.find((m) => m.model_id === id);
}

export function modelName(models: readonly ModelInfo[], id: string): string {
  return findModel(models, id)?.display_name ?? id;
}

/**
 * Typed vs voice messages (2026-10-04): OpenAI's audio chat models (gpt-audio family) refuse
 * text-only turns and text models refuse audio, so Archie uses one model per input kind.
 * `supports_audio` marks the audio models.
 */
export function textModels(models: readonly ModelInfo[]): ModelInfo[] {
  return models.filter((m) => !m.supports_audio);
}

export function audioModels(models: readonly ModelInfo[]): ModelInfo[] {
  return models.filter((m) => m.supports_audio);
}

export function modelTraits(m: ModelInfo): string {
  const t: string[] = [];
  if (m.supports_audio) t.push('audio');
  if (m.supports_vision) t.push('vision');
  if (typeof m.context_window === 'number' && m.context_window > 0) t.push(`${Math.round(m.context_window / 1000)}K context`);
  return t.join(' · ');
}

export type ModelAvailability = 'ok' | 'retired' | 'unknown';

/** Is the saved Archie model usable? (`unknown` only when a non-empty catalog lacks it.) */
export function modelAvailability(id: string, catalog: OrchestratorModels | null): ModelAvailability {
  if (RETIRED_MODEL_IDS.indexOf(id) >= 0) return 'retired';
  if (catalog && catalog.models.length && !findModel(catalog.models, id)) return 'unknown';
  return 'ok';
}

// ───────────────────────── voice (F-31, CFG-5, CFG-6) ─────────────────────────

export const VOICE_PROVIDER_LABELS: Readonly<Record<string, string>> = { openai: 'OpenAI', qwen: 'Qwen (Alibaba)', google: 'Google Gemini' };

export function voiceProviderLabel(p: string): string {
  return VOICE_PROVIDER_LABELS[p] ?? p;
}

export const GOOGLE_ENDPOINTS: readonly { value: string; label: string }[] = [
  { value: 'vertex', label: 'Vertex AI (recommended)' },
  { value: 'aistudio', label: 'AI Studio (legacy)' },
];

/**
 * Voice catalog per provider. The live `GET /api/orchestrator/voice/models` omits `google`
 * (G-33); a non-empty discovered Google list replaces whatever static one is there (F-31).
 */
export function voiceCatalog(models: VoiceModels | null, google: readonly VoiceModelEntry[] | undefined): Record<string, VoiceModelEntry[]> {
  const out: Record<string, VoiceModelEntry[]> = {};
  const providers = models?.providers ?? {};
  for (const k of Object.keys(providers)) out[k] = (providers[k] ?? []).slice();
  if (google && google.length) out.google = google.slice();
  return out;
}

export interface Option {
  id: string;
  label: string;
  description?: string;
}

/** `transcription_languages` arrives as strings (registry) or `{id,label,description}` (live). */
export function languageOptions(entry: VoiceModelEntry | undefined): Option[] {
  const raw = (entry?.transcription_languages ?? []) as unknown[];
  const out: Option[] = [];
  for (const r of raw) {
    if (typeof r === 'string') out.push({ id: r, label: r ? r : 'Auto-detect' });
    else if (r && typeof r === 'object') {
      const o = r as Record<string, unknown>;
      const id = str(o.id);
      const description = str(o.description);
      out.push({ id, label: str(o.label) || (id ? id : 'Auto-detect'), ...(description ? { description } : {}) });
    }
  }
  if (out.length && !out.some((o) => o.id === '')) out.unshift({ id: '', label: 'Auto-detect' });
  return out;
}

export function voiceOptions(entry: VoiceModelEntry | undefined): Option[] {
  return (entry?.voices ?? []).map((v) => ({
    id: v.id,
    label: v.label || v.id,
    ...(v.description ? { description: v.description } : {}),
  }));
}

export function languageLabel(entry: VoiceModelEntry | undefined, id: string): string {
  if (!id) return 'Auto language';
  return languageOptions(entry).find((o) => o.id === id)?.label ?? id;
}

export interface AutoCorrect {
  from: string;
  to: string;
  patch: { default_voice_model: string; default_voice_name: string };
}

/**
 * **[LOAD-BEARING]** F-31 (commit 6a4712f): Google renames Gemini Live model ids; a stale saved id
 * makes every voice session fail with WS 1008. When the provider is `google` and the saved model
 * is not in a NON-EMPTY discovered list, switch to the discovered default (keeping the voice when
 * the new model still offers it). No list = upstream unhealthy: leave the user's choice alone.
 */
export function googleAutoCorrect(cfg: Pick<ServerConfig, 'default_voice_provider' | 'default_voice_model' | 'default_voice_name'>, discovered: readonly VoiceModelEntry[] | undefined): AutoCorrect | null {
  if (cfg.default_voice_provider !== 'google') return null;
  if (!discovered || discovered.length === 0) return null;
  if (discovered.some((m) => m.id === cfg.default_voice_model)) return null;
  const target = discovered.find((m) => m.default) ?? (discovered[0] as VoiceModelEntry);
  const keepVoice = (target.voices ?? []).some((v) => v.id === cfg.default_voice_name);
  const fallbackVoice = typeof target.voice === 'string' ? target.voice : (target.voices?.[0]?.id ?? cfg.default_voice_name);
  return {
    from: cfg.default_voice_model,
    to: target.id,
    patch: { default_voice_model: target.id, default_voice_name: keepVoice ? cfg.default_voice_name : fallbackVoice },
  };
}

// ───────────────────────── harness models (Agent sessions) ─────────────────────────

export interface HarnessModel {
  id: string;
  label: string;
  traits: string;
}

/** `/api/config/harness/qwen/models` rows: plain ids or `{id, display_name, context_window, supports_*}`. */
export function harnessModels(raw: readonly unknown[] | null | undefined): HarnessModel[] {
  const out: HarnessModel[] = [];
  for (const r of raw ?? []) {
    if (typeof r === 'string') {
      if (r) out.push({ id: r, label: r, traits: '' });
      continue;
    }
    if (!r || typeof r !== 'object') continue;
    const o = r as Record<string, unknown>;
    const id = str(o.id);
    if (!id) continue;
    const t: string[] = [];
    if (typeof o.context_window === 'number' && o.context_window > 0) t.push(`${Math.round(o.context_window / 1000)}K ctx`);
    if (o.supports_thinking === true) t.push('thinking');
    if (o.supports_vision === true) t.push('vision');
    if (o.supports_video === true) t.push('video');
    out.push({ id, label: str(o.display_name) || id, traits: t.join(' · ') });
  }
  return out;
}

// ───────────────────────── formatting ─────────────────────────

export function formatThreshold(v: number): string {
  return v.toFixed(2);
}

export function formatMs(v: number): string {
  return `${Math.round(v)} ms`;
}

export function formatGain(v: number): string {
  return `${v.toFixed(2).replace(/0$/, '')}×`;
}

export const TUNING = {
  vad: { min: 0.15, max: 0.5, step: 0.01 },
  silence: { min: 800, max: 5000, step: 100 },
  gain: { min: 0.5, max: 2, step: 0.05 },
} as const;
