/**
 * Pure harness-configuration logic (spec 12 §6.14, §8.1): the model picker and the harness options
 * (reasoning effort, thinking, …) of every harness, rendered generically from its catalog
 * (`GET /api/config/harnesses`, `backend/manager/harness_catalog.py`).
 *
 * Values have three states:
 * - **inherit** (session only): the session map is `null` or lacks the key → the global value;
 * - **CLI default**: global key absent / session key `null` → nothing is passed, the CLI decides;
 * - **a value**.
 *
 * Gating (what the UI shows for the effective model): an option with `models` is hidden for other
 * models, a choice with `models` likewise; the `effort` choices are narrowed to the model's
 * `efforts` (`[]` hides effort); `supports_thinking === false` hides `thinking` / `thinking_*`.
 * When the effective model is unknown (CLI default, a custom id) everything is shown.
 * No React, no I/O: unit-tested in `__tests__/harness.test.ts`.
 */
import type {
  HarnessCatalog,
  HarnessCatalogModel,
  HarnessChoice,
  HarnessInfo,
  HarnessOption,
  HarnessOptionsMap,
  HarnessOptionValue,
  ServerConfig,
  ServerConfigUpdate,
  SessionConfig,
} from '@/services';

/** Select values that are not catalog values. */
export const INHERIT = '__inherit__';
export const CLI_DEFAULT = '__cli__';
export const CUSTOM = '__custom__';

/** A Select row (structurally a `SelectOption`). */
export interface ChoiceItem {
  value: string;
  label: string;
  description?: string;
  disabled?: boolean;
}

export type Scope = 'session' | 'global';

// ───────────────────────── catalog lookups ─────────────────────────

export function harnessInfo(harnesses: readonly HarnessInfo[] | null | undefined, id: string): HarnessInfo | undefined {
  return (harnesses ?? []).find((h) => h.id === id);
}

export function harnessLabel(harnesses: readonly HarnessInfo[] | null | undefined, id: string): string {
  return harnessInfo(harnesses, id)?.label || id;
}

export function findCatalogModel(catalog: HarnessCatalog | null | undefined, id: string | null | undefined): HarnessCatalogModel | undefined {
  if (!catalog || !id) return undefined;
  return catalog.models.find((m) => m.id === id);
}

/** The label of a model id ("" → "CLI default"; unknown ids as they are). */
export function modelLabel(catalog: HarnessCatalog | null | undefined, id: string): string {
  if (!id) return 'CLI default';
  return findCatalogModel(catalog, id)?.label || id;
}

/** 1_000_000 → "1M", 1_048_576 → "1M", 200_000 → "200K", 272_000 → "272K". */
export function formatContextWindow(n: number): string {
  if (n >= 1_000_000) {
    const m = Math.round(n / 100_000) / 10;
    return `${m % 1 === 0 ? m.toFixed(0) : m.toFixed(1)}M`;
  }
  return `${Math.round(n / 1000)}K`;
}

const SOURCE_LABELS: Readonly<Record<string, string>> = { live: 'live list', settings: 'CLI settings', cli: 'from the CLI', builtin: '' };

/** One line under a model row: id (when the label differs), description, context, badges, source. */
export function modelTraits(m: HarnessCatalogModel): string {
  const t: string[] = [];
  if (m.label && m.label !== m.id) t.push(m.id);
  if (m.description) t.push(m.description);
  if (typeof m.context_window === 'number' && m.context_window > 0) t.push(`${formatContextWindow(m.context_window)} context`);
  if (m.supports_thinking === true) t.push('thinking');
  if (m.supports_vision === true) t.push('vision');
  const src = SOURCE_LABELS[m.source] ?? m.source;
  if (src) t.push(src);
  return t.join(' · ');
}

// ───────────────────────── effective model + gating ─────────────────────────

/** The model a session runs: its own value, else the global one ("" = CLI default). */
export function effectiveSessionModel(sessionModel: string | null | undefined, globalModel: string | null | undefined): string {
  return sessionModel ?? globalModel ?? '';
}

/** The catalog row options are gated on, or null when the model is unknown (CLI default / custom id). */
export function gatingModel(catalog: HarnessCatalog | null | undefined, id: string | null | undefined): HarnessCatalogModel | null {
  return findCatalogModel(catalog, id) ?? null;
}

export interface VisibleOption {
  option: HarnessOption;
  /** The choices shown (select only; already narrowed for the model). */
  choices: HarnessChoice[];
}

const isThinkingKey = (key: string): boolean => key === 'thinking' || key.indexOf('thinking_') === 0;

const capitalize = (s: string): string => (s ? s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ') : s);

/** The options shown for `modelId` (see the gating rules in the header). */
export function visibleOptions(catalog: HarnessCatalog | null | undefined, modelId: string | null | undefined): VisibleOption[] {
  if (!catalog) return [];
  const row = gatingModel(catalog, modelId);
  const out: VisibleOption[] = [];
  for (const option of catalog.options) {
    if (row) {
      if (option.models && option.models.indexOf(row.id) < 0) continue;
      if (row.supports_thinking === false && isThinkingKey(option.key)) continue;
    }
    let choices = (option.choices ?? []).slice();
    if (row) {
      choices = choices.filter((c) => !c.models || c.models.indexOf(row.id) >= 0);
      if (option.key === 'effort' && row.efforts) {
        const efforts = row.efforts;
        if (efforts.length === 0) continue;
        choices = choices.filter((c) => efforts.indexOf(c.value) >= 0);
        // A level the model lists that the option does not know yet.
        for (const lvl of efforts) if (!choices.some((c) => c.value === lvl)) choices.push({ value: lvl, label: capitalize(lvl) });
      }
    }
    if (option.kind === 'select' && choices.length === 0) continue;
    out.push({ option, choices });
  }
  return out;
}

// ───────────────────────── value labels ─────────────────────────

export function valueLabel(option: HarnessOption, v: HarnessOptionValue): string {
  if (option.kind === 'toggle' || typeof v === 'boolean') return v ? 'On' : 'Off';
  if (option.kind === 'select') return option.choices?.find((c) => c.value === v)?.label ?? String(v);
  return String(v);
}

/** What the CLI does when unset: the model's `default_effort` for effort, else the option's `default`. */
export function cliDefaultValue(option: HarnessOption, row: HarnessCatalogModel | null | undefined): HarnessOptionValue | undefined {
  if (option.key === 'effort' && row?.default_effort) return row.default_effort;
  return option.default;
}

/** "CLI default" or "CLI default · High". */
export function cliDefaultLabel(option: HarnessOption, row: HarnessCatalogModel | null | undefined): string {
  const d = cliDefaultValue(option, row);
  return d === undefined ? 'CLI default' : `CLI default · ${valueLabel(option, d)}`;
}

/** The session's "Default (…)" row: the global value, else the CLI default. */
export function inheritLabel(option: HarnessOption, inherited: HarnessOptionValue | undefined, row: HarnessCatalogModel | null | undefined): string {
  return `Default (${inherited === undefined ? cliDefaultLabel(option, row) : valueLabel(option, inherited)})`;
}

// ───────────────────────── option state ─────────────────────────

export type OptionState = { kind: 'inherit' } | { kind: 'cli' } | { kind: 'value'; value: HarnessOptionValue };

const INHERIT_STATE: OptionState = { kind: 'inherit' };
const CLI_STATE: OptionState = { kind: 'cli' };

const isValue = (v: unknown): v is HarnessOptionValue => typeof v === 'string' || typeof v === 'boolean' || (typeof v === 'number' && isFinite(v));

/** Session overlay: absent (or no map) → inherit, `null` → CLI default, else the value. */
export function sessionOptionState(map: HarnessOptionsMap | null | undefined, key: string): OptionState {
  if (!map || !Object.prototype.hasOwnProperty.call(map, key)) return INHERIT_STATE;
  const v = map[key];
  return isValue(v) ? { kind: 'value', value: v } : CLI_STATE;
}

/** Global map: absent or `null` → CLI default. */
export function globalOptionState(map: HarnessOptionsMap | null | undefined, key: string): OptionState {
  const v = map?.[key];
  return isValue(v) ? { kind: 'value', value: v } : CLI_STATE;
}

/** The global value of `key` for `provider` (undefined = unset). */
export function globalOptionValue(cfg: Pick<ServerConfig, 'harness_options'>, provider: string, key: string): HarnessOptionValue | undefined {
  const v = cfg.harness_options?.[provider]?.[key];
  return isValue(v) ? v : undefined;
}

/** Drop non-values (except null) and turn an empty map into `null` (= inherit every key). */
export function normalizeOptionsMap(raw: unknown): HarnessOptionsMap | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const out: HarnessOptionsMap = {};
  const o = raw as Record<string, unknown>;
  for (const k of Object.keys(o)) {
    const v = o[k];
    if (v === null || isValue(v)) out[k] = v;
  }
  return Object.keys(out).length ? out : null;
}

/** Structural equality; `null` and `{}` are the same (both inherit every key). */
export function sameOptionsMap(a: HarnessOptionsMap | null | undefined, b: HarnessOptionsMap | null | undefined): boolean {
  const x = normalizeOptionsMap(a) ?? {};
  const y = normalizeOptionsMap(b) ?? {};
  const kx = Object.keys(x);
  if (kx.length !== Object.keys(y).length) return false;
  return kx.every((k) => Object.prototype.hasOwnProperty.call(y, k) && x[k] === y[k]);
}

/** The session overlay after setting one key (inherit removes it; an empty map becomes `null`). */
export function withSessionOption(map: HarnessOptionsMap | null | undefined, key: string, state: OptionState): HarnessOptionsMap | null {
  const next: HarnessOptionsMap = {};
  for (const k of Object.keys(map ?? {})) if (k !== key) next[k] = (map as HarnessOptionsMap)[k] ?? null;
  if (state.kind !== 'inherit') next[key] = state.kind === 'cli' ? null : state.value;
  return normalizeOptionsMap(next);
}

/** `PUT /api/config` body for one global option (`null` deletes the key = CLI default). */
export function globalOptionPatch(provider: string, key: string, state: OptionState): ServerConfigUpdate {
  return { harness_options: { [provider]: { [key]: state.kind === 'value' ? state.value : null } } };
}

/** `PUT /api/config` body for a global model ("" = CLI default). */
export function globalModelPatch(provider: string, model: string): ServerConfigUpdate {
  return { harness_model: { [provider]: model } };
}

// ───────────────────────── option Select rows ─────────────────────────

export function optionSelectValue(option: HarnessOption, state: OptionState): string {
  if (state.kind === 'inherit') return INHERIT;
  if (state.kind === 'cli') return CLI_DEFAULT;
  if (option.kind === 'number') return CUSTOM;
  return String(state.value);
}

/** A Select value back to a state; `custom` = the number field should show. */
export function parseOptionSelect(option: HarnessOption, value: string): OptionState | 'custom' {
  if (value === INHERIT) return INHERIT_STATE;
  if (value === CLI_DEFAULT) return CLI_STATE;
  if (value === CUSTOM) return 'custom';
  if (option.kind === 'toggle') return { kind: 'value', value: value === 'true' };
  if (option.kind === 'number') {
    const n = Number(value);
    return isFinite(n) ? { kind: 'value', value: n } : CLI_STATE;
  }
  return { kind: 'value', value };
}

export interface OptionItemsContext {
  scope: Scope;
  state: OptionState;
  /** The global value (session scope). */
  inherited?: HarnessOptionValue;
  /** The effective model's catalog row (null = unknown). */
  row: HarnessCatalogModel | null;
}

/** The Select rows of one option: [Default (…)], CLI default, then the values. */
export function optionItems(vo: VisibleOption, ctx: OptionItemsContext): ChoiceItem[] {
  const { option } = vo;
  const items: ChoiceItem[] = [];
  if (ctx.scope === 'session') items.push({ value: INHERIT, label: inheritLabel(option, ctx.inherited, ctx.row) });
  items.push({ value: CLI_DEFAULT, label: cliDefaultLabel(option, ctx.row), description: 'Archie passes nothing; the CLI decides' });
  if (option.kind === 'toggle') {
    items.push({ value: 'true', label: 'On' }, { value: 'false', label: 'Off' });
  } else if (option.kind === 'number') {
    const range = numberRange(option);
    items.push({ value: CUSTOM, label: 'Custom value…', ...(range ? { description: range } : {}) });
  } else {
    for (const c of vo.choices) items.push({ value: c.value, label: c.label, ...(c.description ? { description: c.description } : {}) });
    const cur = ctx.state.kind === 'value' ? String(ctx.state.value) : null;
    if (cur !== null && !items.some((i) => i.value === cur))
      items.push({ value: cur, label: `${valueLabel(option, cur)} (not for this model)`, disabled: true });
  }
  return items;
}

/** "1024–128000, step 1024" (for the number field's helper line). */
export function numberRange(option: HarnessOption): string {
  const parts: string[] = [];
  if (typeof option.min === 'number' && typeof option.max === 'number') parts.push(`${option.min}–${option.max}`);
  else if (typeof option.min === 'number') parts.push(`at least ${option.min}`);
  else if (typeof option.max === 'number') parts.push(`at most ${option.max}`);
  if (typeof option.step === 'number' && option.step !== 1) parts.push(`step ${option.step}`);
  return parts.join(', ');
}

/** Parse the number field against `min` / `max` / an integer `step`. */
export function parseNumberInput(option: HarnessOption, text: string): { value: number } | { error: string } {
  const t = text.trim();
  if (!t) return { error: 'Enter a number' };
  const n = Number(t);
  if (!isFinite(n)) return { error: 'Not a number' };
  if (typeof option.step === 'number' && option.step % 1 === 0 && n % 1 !== 0) return { error: 'Whole numbers only' };
  if (typeof option.min === 'number' && n < option.min) return { error: `At least ${option.min}` };
  if (typeof option.max === 'number' && n > option.max) return { error: `At most ${option.max}` };
  return { value: n };
}

// ───────────────────────── model Select rows ─────────────────────────

export function cliDefaultModelLabel(catalog: HarnessCatalog | null | undefined): string {
  return catalog?.default_model ? `CLI default (${modelLabel(catalog, catalog.default_model)})` : 'CLI default';
}

const allowsCustom = (catalog: HarnessCatalog | null | undefined): boolean => !catalog || catalog.allow_custom_model !== false;

export interface ModelItemsContext {
  scope: Scope;
  /** The saved / draft value: `null` = inherit (session), "" = CLI default. */
  current: string | null;
  /** The global `harness_model[provider]` (session scope). */
  inherited?: string;
}

export function modelItems(catalog: HarnessCatalog | null | undefined, ctx: ModelItemsContext): ChoiceItem[] {
  const items: ChoiceItem[] = [];
  if (ctx.scope === 'session') {
    const inh = ctx.inherited ?? '';
    items.push({ value: INHERIT, label: `Default (${inh ? modelLabel(catalog, inh) : cliDefaultModelLabel(catalog)})` });
  }
  items.push({ value: CLI_DEFAULT, label: cliDefaultModelLabel(catalog), description: 'Archie passes no model; the CLI decides' });
  for (const m of catalog?.models ?? []) {
    const traits = modelTraits(m);
    items.push({ value: m.id, label: m.label || m.id, ...(traits ? { description: traits } : {}) });
  }
  if (allowsCustom(catalog)) items.push({ value: CUSTOM, label: 'Custom model id…', description: 'Any id the CLI accepts' });
  const cur = ctx.current;
  if (cur && !findCatalogModel(catalog, cur) && !allowsCustom(catalog)) items.push({ value: cur, label: `${cur} (unavailable)`, disabled: true });
  return items;
}

/** The Select value for a model (`customMode`: the user picked "Custom model id…" and has not typed yet). */
export function modelSelectValue(catalog: HarnessCatalog | null | undefined, current: string | null, customMode = false): string {
  if (customMode && allowsCustom(catalog)) return CUSTOM;
  if (current === null) return INHERIT;
  if (current === '') return CLI_DEFAULT;
  if (findCatalogModel(catalog, current)) return current;
  return allowsCustom(catalog) ? CUSTOM : current;
}

/** A model Select value back to a model (`null` = inherit, "" = CLI default); `custom` = show the id field. */
export function parseModelSelect(value: string): string | null | 'custom' {
  if (value === INHERIT) return null;
  if (value === CLI_DEFAULT) return '';
  if (value === CUSTOM) return 'custom';
  return value;
}

// ───────────────────────── session draft ─────────────────────────

/**
 * Changing the session's harness: the model and options belong to one harness, so they reset to
 * inherit (`null`). Going back to the saved harness restores the saved values.
 */
export function draftForProvider(saved: SessionConfig, draft: Partial<SessionConfig>, next: string | null, globalProvider: string): Partial<SessionConfig> {
  const out: Partial<SessionConfig> = { ...draft, provider: next };
  const nextEff = next ?? globalProvider;
  const savedEff = saved.provider ?? globalProvider;
  if (nextEff === savedEff) {
    delete out.harness_model;
    delete out.harness_options;
  } else {
    out.harness_model = null;
    out.harness_options = null;
  }
  return out;
}

// ───────────────────────── summaries ─────────────────────────

/** "Opus", "CLI default · 2 options" — the collapsed row of a harness on the global page. */
export function harnessDefaultsSummary(catalog: HarnessCatalog | null | undefined, model: string, options: HarnessOptionsMap | null | undefined): string {
  const set = (catalog?.options ?? []).filter((o) => globalOptionState(options, o.key).kind === 'value').length;
  const m = model ? modelLabel(catalog, model) : 'CLI default';
  return set ? `${m} · ${set} ${set === 1 ? 'option' : 'options'}` : m;
}
