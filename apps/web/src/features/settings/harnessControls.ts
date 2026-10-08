/**
 * Pure decisions behind the harness option controls (`HarnessFields.tsx`): which control an option
 * renders as, the value it resolves to, whether it applies (`requires`), the segments of a
 * segmented / levels row, the slider's (log) scale and number formatting.
 *
 * The catalog may carry presentation hints (`backend/manager/harness_catalog.py` `HarnessOption`:
 * `control`, `ordered`, `unit`, `scale`, `presets`, `custom_min`, `requires`); older servers send
 * none and the control is inferred:
 *
 * | kind   | condition                                   | control   |
 * |--------|---------------------------------------------|-----------|
 * | toggle | `default` is true/false                     | switch    |
 * | toggle | no `default` (model-dependent)              | segmented (Default · On · Off) |
 * | select | `ordered`                                   | levels    |
 * | select | ≤ 4 visible choices                         | segmented |
 * | select | otherwise                                   | dropdown  |
 * | number | any                                         | slider (+ number field) |
 *
 * No React, no I/O: unit-tested in `__tests__/harnessControls.test.ts`.
 */
import type { HarnessCatalog, HarnessCatalogModel, HarnessControl, HarnessOption, HarnessOptionsMap, HarnessOptionValue, HarnessPreset } from '@/services';
import { cliDefaultValue, globalOptionState, sessionOptionState, type OptionState, type Scope, type VisibleOption } from './harness';

export type ControlKind = HarnessControl;

/** A select with at most this many visible choices (and no hint) renders as segments. */
export const SEGMENTED_MAX = 4;

const CONTROLS: readonly string[] = ['switch', 'segmented', 'levels', 'slider', 'dropdown'];

function fits(control: string, kind: HarnessOption['kind']): boolean {
  if (CONTROLS.indexOf(control) < 0) return false;
  if (control === 'switch') return kind === 'toggle';
  if (control === 'slider') return kind === 'number';
  if (control === 'dropdown') return true;
  return kind !== 'number'; // segmented, levels
}

/** The control of an option: its `control` hint when it suits the kind, else inferred (table above). */
export function optionControl(vo: VisibleOption): ControlKind {
  const { option } = vo;
  const hint = option.control;
  if (typeof hint === 'string' && fits(hint, option.kind)) return hint;
  if (option.kind === 'toggle') return typeof option.default === 'boolean' ? 'switch' : 'segmented';
  if (option.kind === 'number') return 'slider';
  if (option.ordered === true) return 'levels';
  return vo.choices.length <= SEGMENTED_MAX ? 'segmented' : 'dropdown';
}

// ───────────────────────── values ─────────────────────────

export interface ControlContext {
  scope: Scope;
  /** This scope's state (session: the overlay; global: the saved default). */
  state: OptionState;
  /** Session: the global state of the key. */
  inherited?: OptionState | undefined;
  /** The effective model's catalog row (null = unknown). */
  row: HarnessCatalogModel | null;
}

/** The value a session would run with: session value → global value → CLI default → null (unknown). */
export function resolvedValue(option: HarnessOption, ctx: ControlContext): HarnessOptionValue | null {
  if (ctx.state.kind === 'value') return ctx.state.value;
  if (ctx.scope === 'session' && ctx.state.kind === 'inherit' && ctx.inherited?.kind === 'value') return ctx.inherited.value;
  const d = cliDefaultValue(option, ctx.row);
  return d === undefined ? null : d;
}

/** The global value of the key in session scope (undefined = unset). */
const inheritedValue = (ctx: ControlContext): HarnessOptionValue | undefined =>
  ctx.scope === 'session' && ctx.inherited?.kind === 'value' ? ctx.inherited.value : undefined;

/** Resolve every key of a catalog for one scope (the `requires` lookups). */
export function makeResolver(
  catalog: HarnessCatalog | null | undefined,
  scope: Scope,
  options: HarnessOptionsMap | null | undefined,
  inheritedOptions: HarnessOptionsMap | null | undefined,
  row: HarnessCatalogModel | null,
): (key: string) => HarnessOptionValue | null {
  return (key) => {
    const option = catalog?.options.find((o) => o.key === key);
    if (!option) return null;
    const state = scope === 'session' ? sessionOptionState(options, key) : globalOptionState(options, key);
    const inherited = scope === 'session' ? globalOptionState(inheritedOptions, key) : undefined;
    return resolvedValue(option, { scope, state, inherited, row });
  };
}

/**
 * Why the option does not apply now ("Applies when Thinking is Fixed budget"), or null when it
 * does. Keys the catalog does not know are ignored; `null` in a list = unset / unknown.
 */
export function unmetRequirement(
  option: HarnessOption,
  catalog: HarnessCatalog | null | undefined,
  resolve: (key: string) => HarnessOptionValue | null,
): string | null {
  const req = option.requires;
  if (!req || typeof req !== 'object') return null;
  for (const key of Object.keys(req)) {
    const allowed = req[key];
    if (!Array.isArray(allowed)) continue;
    const other = catalog?.options.find((o) => o.key === key);
    if (!other) continue;
    const v = resolve(key);
    if (allowed.some((a) => a === v)) continue;
    const named = allowed.filter((a): a is HarnessOptionValue => a !== null).map((a) => displayValue(other, a));
    return named.length ? `Applies when ${other.label} is ${joinOr(named)}` : `Applies when ${other.label} is unset`;
  }
  return null;
}

const joinOr = (xs: string[]): string => (xs.length <= 1 ? (xs[0] ?? '') : `${xs.slice(0, -1).join(', ')} or ${xs[xs.length - 1] ?? ''}`);

// ───────────────────────── labels ─────────────────────────

/** decimals of a step: 0.1 → 1, 0.25 → 2, 1024 → 0. */
export function decimalsOf(step: number | undefined): number {
  if (typeof step !== 'number' || !isFinite(step) || step % 1 === 0) return 0;
  const s = String(step);
  const i = s.indexOf('.');
  return i < 0 ? 0 : Math.min(6, s.length - i - 1);
}

/** 16000 → "16,000"; 0.7 (step 0.1) → "0.7"; -1 → "-1". */
export function formatNumber(n: number, step?: number): string {
  const d = decimalsOf(step);
  const fixed = Math.abs(n).toFixed(d);
  const dot = fixed.indexOf('.');
  const int = dot < 0 ? fixed : fixed.slice(0, dot);
  const frac = dot < 0 ? '' : fixed.slice(dot);
  let grouped = '';
  for (let i = 0; i < int.length; i++) {
    if (i > 0 && (int.length - i) % 3 === 0) grouped += ',';
    grouped += int.charAt(i);
  }
  return `${n < 0 && Number(fixed) !== 0 ? '-' : ''}${grouped}${frac}`;
}

/** 16000 → "16k", 16384 → "16.4k", 1_000_000 → "1M", 512 → "512". */
export function formatCompact(n: number): string {
  const a = Math.abs(n);
  const one = (x: number): string => {
    const r = Math.round(x * 10) / 10;
    return r % 1 === 0 ? r.toFixed(0) : r.toFixed(1);
  };
  if (a >= 1_000_000) return `${n < 0 ? '-' : ''}${one(a / 1_000_000)}M`;
  if (a >= 1000) return `${n < 0 ? '-' : ''}${one(a / 1000)}k`;
  return String(n);
}

/** The valid presets of a number option. */
export function presetsOf(option: HarnessOption): HarnessPreset[] {
  if (option.kind !== 'number' || !Array.isArray(option.presets)) return [];
  return option.presets.filter((p) => p && typeof p.value === 'number' && isFinite(p.value) && typeof p.label === 'string');
}

/** A value as the UI shows it: choice label, On/Off, a preset's label, or "16,000 tokens". */
export function displayValue(option: HarnessOption, v: HarnessOptionValue): string {
  if (typeof v === 'boolean') return v ? 'On' : 'Off';
  if (option.kind === 'select') return option.choices?.find((c) => c.value === v)?.label ?? String(v);
  if (typeof v === 'number') {
    const preset = presetsOf(option).find((p) => p.value === v);
    if (preset) return preset.label;
    return option.unit ? `${formatNumber(v, option.step)} ${option.unit}` : formatNumber(v, option.step);
  }
  return String(v);
}

/**
 * Where the shown value comes from (the control's supporting line):
 * global — "CLI default · On" / "Overrides the CLI default (On)";
 * session — "Default from Settings (Off)" / "Default (CLI default · On)" /
 * "CLI default for this session (On)" / "Set for this session".
 */
export function sourceLine(option: HarnessOption, ctx: ControlContext): string {
  const d = cliDefaultValue(option, ctx.row);
  const dl = d === undefined ? null : displayValue(option, d);
  if (ctx.scope === 'global') {
    if (ctx.state.kind === 'value') return dl === null ? 'Overrides the CLI default' : `Overrides the CLI default (${dl})`;
    return dl === null ? 'CLI default' : `CLI default · ${dl}`;
  }
  if (ctx.state.kind === 'value') return 'Set for this session';
  if (ctx.state.kind === 'cli') return dl === null ? 'CLI default for this session' : `CLI default for this session (${dl})`;
  const g = inheritedValue(ctx);
  if (g !== undefined) return `Default from Settings (${displayValue(option, g)})`;
  return dl === null ? 'Default (CLI default)' : `Default (CLI default · ${dl})`;
}

/**
 * The text actions under a control: "Use default" (`reset` controls only — switch, slider without
 * presets — whose state is not already the unset one: global → drop the key, session → inherit),
 * and in the session sheet "Use CLI default" whenever the global page sets the option.
 */
export function resetActions(ctx: ControlContext, reset: boolean): { useDefault: boolean; useCli: boolean } {
  return {
    useDefault: reset && (ctx.scope === 'global' ? ctx.state.kind === 'value' : ctx.state.kind !== 'inherit'),
    useCli: ctx.scope === 'session' && ctx.inherited?.kind === 'value' && ctx.state.kind !== 'cli',
  };
}

// ───────────────────────── segmented / levels ─────────────────────────

/** The first segment: unset (global: CLI default; session: inherit the global value). */
export const UNSET = '__unset__';
/** The number control's "Custom" segment. */
export const CUSTOM_SEG = '__custom__';
const PRESET_PREFIX = 'preset:';

export interface SegmentItem {
  value: string;
  label: string;
  /** Marks the CLI default level. */
  dot?: boolean;
  title?: string;
  disabled?: boolean;
  description?: string;
}

export interface SegmentsView {
  segments: SegmentItem[];
  /** The selected segment (undefined = none: a session forcing the CLI default over a global value). */
  selected: string | undefined;
}

/** Which unset state the "Default" segment stands for; none selected for a session forcing the CLI default over a global value. */
function unsetSelected(ctx: ControlContext): string | undefined {
  if (ctx.state.kind === 'inherit') return UNSET;
  if (ctx.state.kind === 'cli') return ctx.scope === 'session' && inheritedValue(ctx) !== undefined ? undefined : UNSET;
  return undefined;
}

/** Segments of a toggle / select: Default, then On · Off or the visible choices (CLI default marked). */
export function choiceSegments(vo: VisibleOption, ctx: ControlContext): SegmentsView {
  const { option } = vo;
  const d = cliDefaultValue(option, ctx.row);
  const segments: SegmentItem[] = [{ value: UNSET, label: 'Default' }];
  const mark = (v: HarnessOptionValue): Pick<SegmentItem, 'dot' | 'title'> => (d !== undefined && d === v ? { dot: true, title: 'CLI default' } : {});
  if (option.kind === 'toggle') {
    segments.push({ value: 'true', label: 'On', ...mark(true) }, { value: 'false', label: 'Off', ...mark(false) });
  } else {
    for (const c of vo.choices) segments.push({ value: c.value, label: c.label, ...mark(c.value), ...(c.description ? { description: c.description } : {}) });
  }
  if (ctx.state.kind !== 'value') return { segments, selected: unsetSelected(ctx) };
  const cur = String(ctx.state.value);
  if (!segments.some((s) => s.value === cur))
    segments.push({ value: cur, label: displayValue(option, ctx.state.value), disabled: true, title: 'Not for this model' });
  return { segments, selected: cur };
}

/** A segment back to a state (`UNSET` → global: CLI default, session: inherit). */
export function parseSegment(option: HarnessOption, scope: Scope, value: string): OptionState {
  if (value === UNSET) return scope === 'global' ? { kind: 'cli' } : { kind: 'inherit' };
  if (option.kind === 'toggle') return { kind: 'value', value: value === 'true' };
  if (option.kind === 'number') {
    if (value.indexOf(PRESET_PREFIX) === 0) {
      const n = Number(value.slice(PRESET_PREFIX.length));
      if (isFinite(n)) return { kind: 'value', value: n };
    }
    return scope === 'global' ? { kind: 'cli' } : { kind: 'inherit' };
  }
  return { kind: 'value', value };
}

/** The supporting line of a segmented / levels row: the selected choice's description, else where the value comes from. */
export function choiceHelp(vo: VisibleOption, ctx: ControlContext): string {
  if (ctx.state.kind === 'value') {
    const v = ctx.state.value;
    const choice = vo.choices.find((c) => c.value === v);
    if (choice?.description) return choice.description;
    if (vo.option.kind === 'select' && !choice) return `${displayValue(vo.option, v)} is not available for this model`;
  }
  return sourceLine(vo.option, ctx);
}

// ───────────────────────── numbers ─────────────────────────

export interface NumberScale {
  /** Lowest value offered (`custom_min ?? min`). */
  lo: number;
  hi: number;
  /** > 0. */
  step: number;
  log: boolean;
}

/** Positions of a log slider (its native range is 0…LOG_POSITIONS). */
export const LOG_POSITIONS = 1000;

/** The slider's scale, or null when the range is unknown (field only). Log needs lo > 0. */
export function numberScale(option: HarnessOption): NumberScale | null {
  const lo = typeof option.custom_min === 'number' && isFinite(option.custom_min) ? option.custom_min : option.min;
  const hi = option.max;
  if (typeof lo !== 'number' || typeof hi !== 'number' || !isFinite(lo) || !isFinite(hi) || hi <= lo) return null;
  const step = typeof option.step === 'number' && option.step > 0 ? option.step : 1;
  return { lo, hi, step, log: option.scale === 'log' && lo > 0 };
}

/** The native range attributes of the slider (log: positions; linear: the values themselves). */
export function sliderRange(s: NumberScale): { min: number; max: number; step: number } {
  return s.log ? { min: 0, max: LOG_POSITIONS, step: 1 } : { min: s.lo, max: s.hi, step: s.step };
}

const clamp = (n: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, n));

const roundTo = (n: number, decimals: number): number => {
  const f = Math.pow(10, decimals);
  return Math.round(n * f) / f;
};

/** Two significant digits: 1371 → 1400, 15360 → 15000, 128 → 130. */
export function niceRound(v: number): number {
  if (v === 0 || !isFinite(v)) return v;
  const mag = Math.pow(10, Math.floor(Math.log10(Math.abs(v))) - 1);
  return Math.round(v / mag) * mag;
}

/**
 * Snap a value onto the scale. Linear: `lo + k·step`. Log: two significant digits, then a multiple
 * of `step` (so 1024-step budgets land on 2048, 16384 …); the ends stay exact.
 */
export function snapNumber(s: NumberScale, v: number): number {
  if (!isFinite(v)) return s.lo;
  if (v <= s.lo) return s.lo;
  if (v >= s.hi) return s.hi;
  const d = decimalsOf(s.step);
  if (!s.log) return clamp(roundTo(s.lo + Math.round((v - s.lo) / s.step) * s.step, d), s.lo, s.hi);
  let n = niceRound(v);
  if (s.step > 1 || d > 0) n = roundTo(Math.round(n / s.step) * s.step, d);
  return clamp(n, s.lo, s.hi);
}

/** Value → native slider position. */
export function toPosition(s: NumberScale, v: number): number {
  const x = clamp(v, s.lo, s.hi);
  if (!s.log) return x;
  return Math.round(((Math.log(x) - Math.log(s.lo)) / (Math.log(s.hi) - Math.log(s.lo))) * LOG_POSITIONS);
}

/** Native slider position → snapped value. */
export function fromPosition(s: NumberScale, pos: number): number {
  if (!s.log) return snapNumber(s, pos);
  const p = clamp(pos, 0, LOG_POSITIONS);
  if (p <= 0) return s.lo;
  if (p >= LOG_POSITIONS) return s.hi;
  return snapNumber(s, Math.exp(Math.log(s.lo) + (p / LOG_POSITIONS) * (Math.log(s.hi) - Math.log(s.lo))));
}

/** Where "Custom" starts: the option's default when it is in range, else the lowest custom value. */
export function customStart(option: HarnessOption, s: NumberScale | null): number {
  const d = option.default;
  if (typeof d === 'number' && (!s || (d >= s.lo && d <= s.hi))) return d;
  return s ? s.lo : typeof option.min === 'number' ? option.min : 0;
}

/** Segments of a number with presets: Default · <presets> · Custom. */
export function numberSegments(option: HarnessOption, ctx: ControlContext): SegmentsView {
  const presets = presetsOf(option);
  const segments: SegmentItem[] = [{ value: UNSET, label: 'Default' }];
  for (const p of presets) segments.push({ value: `${PRESET_PREFIX}${String(p.value)}`, label: p.label, ...(p.description ? { title: p.description, description: p.description } : {}) });
  segments.push({ value: CUSTOM_SEG, label: 'Custom' });
  if (ctx.state.kind !== 'value') return { segments, selected: unsetSelected(ctx) };
  const v = ctx.state.value;
  const preset = presets.find((p) => p.value === v);
  return { segments, selected: preset ? `${PRESET_PREFIX}${String(preset.value)}` : CUSTOM_SEG };
}

/**
 * The number field: empty → error, not a number → error; a preset value is kept as is, otherwise
 * whole-number steps round and the value is clamped to the slider range.
 */
export function parseNumberField(option: HarnessOption, text: string): { value: number } | { error: string } {
  const t = text.trim().replace(/,/g, '');
  if (!t) return { error: 'Enter a number' };
  const n = Number(t);
  if (!isFinite(n)) return { error: 'Not a number' };
  if (presetsOf(option).some((p) => p.value === n)) return { value: n };
  const s = numberScale(option);
  const step = typeof option.step === 'number' && option.step > 0 ? option.step : undefined;
  let v = step !== undefined && step % 1 === 0 ? Math.round(n) : step !== undefined ? roundTo(n, decimalsOf(step)) : n;
  if (s) v = clamp(v, s.lo, s.hi);
  else {
    if (typeof option.min === 'number') v = Math.max(option.min, v);
    if (typeof option.max === 'number') v = Math.min(option.max, v);
  }
  return { value: v };
}

/** The field's hint: "128–32,768 tokens". */
export function rangeHint(option: HarnessOption): string {
  const s = numberScale(option);
  if (!s) return '';
  const unit = option.unit ? ` ${option.unit}` : '';
  return `${formatNumber(s.lo, option.step)}–${formatNumber(s.hi, option.step)}${unit}`;
}
