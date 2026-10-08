/**
 * Harness option controls (`../harnessControls.ts`): control inference vs hints, resolved values
 * and `requires`, source lines, segments for segmented / levels / presets, the log slider scale
 * (mapping, snapping) and number parsing / formatting.
 */
import { describe, expect, it } from 'vitest';
import type { HarnessCatalog, HarnessOption } from '@/services';
import { gatingModel, visibleOptions, type OptionState, type VisibleOption } from '../harness';
import {
  choiceHelp,
  choiceSegments,
  CUSTOM_SEG,
  customStart,
  decimalsOf,
  displayValue,
  formatCompact,
  formatNumber,
  fromPosition,
  LOG_POSITIONS,
  makeResolver,
  niceRound,
  numberScale,
  numberSegments,
  optionControl,
  parseNumberField,
  parseSegment,
  presetsOf,
  rangeHint,
  resetActions,
  resolvedValue,
  sliderRange,
  snapNumber,
  sourceLine,
  toPosition,
  UNSET,
  unmetRequirement,
  type ControlContext,
} from '../harnessControls';
import { HARNESS_SAMPLES } from '../harnessSamples';

const cat = (id: string): HarnessCatalog => {
  const c = HARNESS_SAMPLES.find((h) => h.id === id)?.catalog;
  if (!c) throw new Error(id);
  return c;
};
const CLAUDE = cat('claude');
const CODEX = cat('codex');
const QWEN = cat('qwen');
const GEMINI = cat('gemini');
const opt = (c: HarnessCatalog, key: string): HarnessOption => {
  const o = c.options.find((x) => x.key === key);
  if (!o) throw new Error(key);
  return o;
};
const vo = (c: HarnessCatalog, key: string, model: string | null = null): VisibleOption => {
  const v = visibleOptions(c, model).find((x) => x.option.key === key);
  if (!v) throw new Error(key);
  return v;
};
const val = (value: string | number | boolean): OptionState => ({ kind: 'value', value });
const INH: OptionState = { kind: 'inherit' };
const CLI: OptionState = { kind: 'cli' };
const g = (state: OptionState): ControlContext => ({ scope: 'global', state, row: null });
const s = (state: OptionState, inherited: OptionState = CLI): ControlContext => ({ scope: 'session', state, inherited, row: null });

describe('optionControl', () => {
  it('follows the catalog hints of the real harnesses', () => {
    expect(optionControl(vo(CLAUDE, 'effort'))).toBe('levels');
    expect(optionControl(vo(CLAUDE, 'thinking'))).toBe('segmented');
    expect(optionControl(vo(CLAUDE, 'thinking_budget'))).toBe('slider');
    expect(optionControl(vo(CLAUDE, 'fallback_model'))).toBe('dropdown');
    expect(optionControl(vo(CLAUDE, 'todo_tools'))).toBe('switch');
    expect(optionControl(vo(GEMINI, 'approval_mode'))).toBe('segmented');
    expect(optionControl(vo(GEMINI, 'thinking_level'))).toBe('levels');
    expect(optionControl(vo(CODEX, 'verbosity'))).toBe('levels');
    expect(optionControl(vo(CODEX, 'web_search'))).toBe('segmented');
  });

  it('infers a control without hints (older servers)', () => {
    const bare = (o: Partial<HarnessOption>, choices = 0): VisibleOption => ({
      option: { key: 'k', label: 'K', kind: 'select', ...o },
      choices: Array.from({ length: choices }, (_, i) => ({ value: `v${String(i)}`, label: `V${String(i)}` })),
    });
    expect(optionControl(bare({ kind: 'toggle', default: false }))).toBe('switch');
    expect(optionControl(bare({ kind: 'toggle' }))).toBe('segmented');
    expect(optionControl(bare({ kind: 'number' }))).toBe('slider');
    expect(optionControl(bare({ ordered: true }, 6))).toBe('levels');
    expect(optionControl(bare({}, 4))).toBe('segmented');
    expect(optionControl(bare({}, 5))).toBe('dropdown');
    // a hint that does not suit the kind (or is unknown) is ignored
    expect(optionControl(bare({ kind: 'number', control: 'switch' }))).toBe('slider');
    expect(optionControl(bare({ kind: 'toggle', default: true, control: 'slider' }))).toBe('switch');
    expect(optionControl(bare({ control: 'knob' as never }, 2))).toBe('segmented');
    expect(optionControl(bare({ kind: 'number', control: 'dropdown' }))).toBe('dropdown');
  });
});

describe('resolved values and requires', () => {
  it('session value → global value → CLI default (model default_effort first) → null', () => {
    const effort = opt(CODEX, 'effort');
    expect(resolvedValue(effort, s(val('low'), val('high')))).toBe('low');
    expect(resolvedValue(effort, s(INH, val('high')))).toBe('high');
    expect(resolvedValue(effort, s(CLI, val('high')))).toBe('medium');
    expect(resolvedValue(effort, { ...g(CLI), row: gatingModel(CODEX, 'codex-mini') })).toBe('low');
    expect(resolvedValue(opt(CLAUDE, 'thinking'), g(CLI))).toBeNull();
  });

  it('a budget applies only with the required Thinking value (null = unset)', () => {
    const budget = opt(CLAUDE, 'thinking_budget');
    const r = (opts: Record<string, string | boolean | number | null> | null, inh: Record<string, string | boolean | number | null> | null = null, scope: 'global' | 'session' = 'global') =>
      unmetRequirement(budget, CLAUDE, makeResolver(CLAUDE, scope, opts, inh, null));
    expect(r(null)).toBe('Applies when Thinking is Fixed budget');
    expect(r({ thinking: 'adaptive' })).toBe('Applies when Thinking is Fixed budget');
    expect(r({ thinking: 'enabled' })).toBeNull();
    // session: inherits the global Thinking, or overrides it
    expect(r(null, { thinking: 'enabled' }, 'session')).toBeNull();
    expect(r({ thinking: 'disabled' }, { thinking: 'enabled' }, 'session')).toBe('Applies when Thinking is Fixed budget');
    expect(r({ thinking: null }, { thinking: 'enabled' }, 'session')).toBe('Applies when Thinking is Fixed budget');

    const qb = opt(QWEN, 'thinking_budget');
    const q = (opts: Record<string, boolean> | null) => unmetRequirement(qb, QWEN, makeResolver(QWEN, 'global', opts, null, null));
    // fixture: Qwen thinking defaults to On; [true, null] → applies unless turned off
    expect(q(null)).toBeNull();
    expect(q({ thinking: false })).toBe('Applies when Thinking is On');
    const noDefault: HarnessCatalog = { ...QWEN, options: QWEN.options.map((o) => (o.key === 'thinking' ? { key: o.key, label: o.label, kind: o.kind } : o)) };
    expect(unmetRequirement(qb, noDefault, makeResolver(noDefault, 'global', null, null, null))).toBeNull();
  });

  it('ignores unknown keys, bad shapes and options without requires; names several values and "unset"', () => {
    const resolve = makeResolver(CLAUDE, 'global', { thinking: 'adaptive' }, null, null);
    expect(unmetRequirement(opt(CLAUDE, 'effort'), CLAUDE, resolve)).toBeNull();
    expect(unmetRequirement({ ...opt(CLAUDE, 'effort'), requires: { nope: ['x'] } }, CLAUDE, resolve)).toBeNull();
    expect(unmetRequirement({ ...opt(CLAUDE, 'effort'), requires: { thinking: 'enabled' as never } }, CLAUDE, resolve)).toBeNull();
    expect(unmetRequirement({ ...opt(CLAUDE, 'effort'), requires: { thinking: ['enabled', 'disabled'] } }, CLAUDE, resolve)).toBe(
      'Applies when Thinking is Fixed budget or Off',
    );
    expect(unmetRequirement({ ...opt(CLAUDE, 'effort'), requires: { thinking: [null] } }, CLAUDE, resolve)).toBe('Applies when Thinking is unset');
    expect(makeResolver(CLAUDE, 'global', null, null, null)('nope')).toBeNull();
  });
});

describe('labels', () => {
  it('displayValue: choices, On/Off, presets, numbers with unit', () => {
    expect(displayValue(opt(CLAUDE, 'effort'), 'xhigh')).toBe('Extra high');
    expect(displayValue(opt(CLAUDE, 'todo_tools'), false)).toBe('Off');
    expect(displayValue(opt(CLAUDE, 'thinking_budget'), 16000)).toBe('16,000 tokens');
    expect(displayValue(opt(GEMINI, 'thinking_budget'), -1)).toBe('Dynamic');
    expect(displayValue(opt(GEMINI, 'thinking_budget'), 0)).toBe('Off');
    expect(displayValue({ key: 't', label: 'T', kind: 'number', step: 0.1 }, 0.7)).toBe('0.7');
  });

  it('sourceLine says where the value comes from', () => {
    const todo = opt(CLAUDE, 'todo_tools');
    expect(sourceLine(todo, g(CLI))).toBe('CLI default · On');
    expect(sourceLine(todo, g(val(false)))).toBe('Overrides the CLI default (On)');
    expect(sourceLine(todo, s(INH, val(false)))).toBe('Default from Settings (Off)');
    expect(sourceLine(todo, s(INH))).toBe('Default (CLI default · On)');
    expect(sourceLine(todo, s(CLI, val(false)))).toBe('CLI default for this session (On)');
    expect(sourceLine(todo, s(val(true)))).toBe('Set for this session');
    const ws = opt(CODEX, 'web_search');
    expect(sourceLine(ws, g(CLI))).toBe('CLI default');
    expect(sourceLine(ws, g(val('live')))).toBe('Overrides the CLI default');
    expect(sourceLine(ws, s(INH))).toBe('Default (CLI default)');
    expect(sourceLine(ws, s(CLI, val('live')))).toBe('CLI default for this session');
    expect(sourceLine(opt(CLAUDE, 'thinking_budget'), g(CLI))).toBe('CLI default · 16,000 tokens');
  });

  it('resetActions: "Use default" on reset controls that are set; "Use CLI default" when Settings sets it', () => {
    expect(resetActions(g(val(true)), true)).toEqual({ useDefault: true, useCli: false });
    expect(resetActions(g(CLI), true)).toEqual({ useDefault: false, useCli: false });
    expect(resetActions(g(val(true)), false)).toEqual({ useDefault: false, useCli: false });
    expect(resetActions(s(INH, val(true)), true)).toEqual({ useDefault: false, useCli: true });
    expect(resetActions(s(CLI, val(true)), true)).toEqual({ useDefault: true, useCli: false });
    expect(resetActions(s(val(false), val(true)), true)).toEqual({ useDefault: true, useCli: true });
    expect(resetActions(s(val(false)), false)).toEqual({ useDefault: false, useCli: false });
  });
});

describe('segments', () => {
  it('levels: Default first, the visible levels in order, the CLI default dotted', () => {
    const v = choiceSegments(vo(CODEX, 'effort', 'gpt-6-luna'), { ...g(CLI), row: gatingModel(CODEX, 'gpt-6-luna') });
    expect(v.segments.map((x) => x.label)).toEqual(['Default', 'Low', 'Medium', 'High', 'Extra high', 'Max']);
    expect(v.segments.filter((x) => x.dot).map((x) => x.value)).toEqual(['medium']);
    expect(v.segments.find((x) => x.dot)?.title).toBe('CLI default');
    expect(v.selected).toBe(UNSET);
    expect(choiceSegments(vo(CODEX, 'effort'), g(val('ultra'))).selected).toBe('ultra');
  });

  it('a toggle without default: Default · On · Off', () => {
    const toggle: VisibleOption = { option: { key: 'thinking', label: 'Thinking', kind: 'toggle' }, choices: [] };
    expect(choiceSegments(toggle, g(CLI)).segments.map((x) => x.label)).toEqual(['Default', 'On', 'Off']);
    expect(choiceSegments(toggle, g(val(false))).selected).toBe('false');
    expect(parseSegment(toggle.option, 'global', 'false')).toEqual(val(false));
    expect(parseSegment(toggle.option, 'global', UNSET)).toEqual(CLI);
    expect(parseSegment(toggle.option, 'session', UNSET)).toEqual(INH);
  });

  it('selection per scope: session inherit → Default; session CLI default over a global value → none', () => {
    const t = vo(CLAUDE, 'thinking');
    expect(choiceSegments(t, s(INH, val('adaptive'))).selected).toBe(UNSET);
    expect(choiceSegments(t, s(CLI, val('adaptive'))).selected).toBeUndefined();
    expect(choiceSegments(t, s(CLI)).selected).toBe(UNSET);
    expect(parseSegment(t.option, 'session', 'enabled')).toEqual(val('enabled'));
  });

  it('a saved value the model lacks shows as a disabled segment; the help says so', () => {
    const effort = vo(CLAUDE, 'effort', 'claude-opus-4-6');
    const v = choiceSegments(effort, g(val('xhigh')));
    expect(v.segments.slice(-1)[0]).toMatchObject({ value: 'xhigh', label: 'Extra high', disabled: true });
    expect(v.selected).toBe('xhigh');
    expect(choiceHelp(effort, g(val('xhigh')))).toBe('Extra high is not available for this model');
  });

  it('choiceHelp: the selected choice description, else the source line', () => {
    const summary = vo(CODEX, 'reasoning_summary');
    expect(choiceHelp(summary, g(val('none')))).toBe('No thinking shown in the UI');
    expect(choiceHelp(summary, g(val('auto')))).toBe('Overrides the CLI default (Concise)');
    expect(choiceHelp(summary, g(CLI))).toBe('CLI default · Concise');
  });

  it('presets: Default · Dynamic · Off · Custom', () => {
    const budget = opt(GEMINI, 'thinking_budget');
    expect(presetsOf(budget).map((p) => p.label)).toEqual(['Dynamic', 'Off']);
    expect(presetsOf(opt(CLAUDE, 'thinking_budget'))).toEqual([]);
    expect(presetsOf({ ...budget, presets: [{ value: 'x' as never, label: 'X' }, { value: 5, label: 'Five' }] }).map((p) => p.value)).toEqual([5]);
    const v = numberSegments(budget, g(CLI));
    expect(v.segments.map((x) => x.label)).toEqual(['Default', 'Dynamic', 'Off', 'Custom']);
    expect(v.selected).toBe(UNSET);
    expect(numberSegments(budget, g(val(-1))).selected).toBe('preset:-1');
    expect(numberSegments(budget, g(val(4096))).selected).toBe(CUSTOM_SEG);
    expect(numberSegments(budget, s(CLI, val(0))).selected).toBeUndefined();
    expect(parseSegment(budget, 'global', 'preset:0')).toEqual(val(0));
    expect(parseSegment(budget, 'global', 'preset:-1')).toEqual(val(-1));
    expect(parseSegment(budget, 'session', UNSET)).toEqual(INH);
    expect(parseSegment(budget, 'session', 'preset:x')).toEqual(INH);
  });
});

describe('numbers', () => {
  it('numberScale: custom_min, log only above 0, unknown range → null', () => {
    expect(numberScale(opt(CLAUDE, 'thinking_budget'))).toEqual({ lo: 1024, hi: 128000, step: 1024, log: true });
    expect(numberScale(opt(GEMINI, 'thinking_budget'))).toEqual({ lo: 128, hi: 32768, step: 1, log: true });
    expect(numberScale({ key: 't', label: 'T', kind: 'number', min: 0, max: 2, step: 0.1 })).toEqual({ lo: 0, hi: 2, step: 0.1, log: false });
    expect(numberScale({ key: 't', label: 'T', kind: 'number', min: 0, max: 10, scale: 'log' })?.log).toBe(false);
    expect(numberScale({ key: 't', label: 'T', kind: 'number', min: 1 })).toBeNull();
    expect(numberScale({ key: 't', label: 'T', kind: 'number', min: 5, max: 5 })).toBeNull();
  });

  it('log slider: positions 0…1000, ends exact, the middle is the geometric mean (snapped)', () => {
    const c = numberScale(opt(CLAUDE, 'thinking_budget'));
    if (!c) throw new Error('scale');
    expect(sliderRange(c)).toEqual({ min: 0, max: LOG_POSITIONS, step: 1 });
    expect(fromPosition(c, 0)).toBe(1024);
    expect(fromPosition(c, LOG_POSITIONS)).toBe(128000);
    expect(fromPosition(c, 500)).toBe(11264); // √(1024·128000) ≈ 11449 → 11000 → 11·1024
    expect(toPosition(c, 1024)).toBe(0);
    expect(toPosition(c, 128000)).toBe(LOG_POSITIONS);
    expect(toPosition(c, 500)).toBe(0);
    // every position lands on a 1024 multiple (or an end)
    for (let p = 0; p <= LOG_POSITIONS; p += 37) expect(fromPosition(c, p) % 1024 === 0 || fromPosition(c, p) === 128000).toBe(true);
    // round trip stays close
    expect(Math.abs(fromPosition(c, toPosition(c, 16384)) - 16384)).toBeLessThanOrEqual(1024);

    const gem = numberScale(opt(GEMINI, 'thinking_budget'));
    if (!gem) throw new Error('scale');
    expect(fromPosition(gem, 0)).toBe(128);
    expect(fromPosition(gem, LOG_POSITIONS)).toBe(32768);
    expect(fromPosition(gem, 500)).toBe(2000); // √(128·32768) = 2048 → two significant digits
    // positions are monotonic
    let last = 0;
    for (let p = 0; p <= LOG_POSITIONS; p += 10) {
      const v = fromPosition(gem, p);
      expect(v).toBeGreaterThanOrEqual(last);
      last = v;
    }
  });

  it('linear slider: values snap to lo + k·step without float noise', () => {
    const t = numberScale({ key: 't', label: 'T', kind: 'number', min: 0, max: 2, step: 0.1 });
    if (!t) throw new Error('scale');
    expect(sliderRange(t)).toEqual({ min: 0, max: 2, step: 0.1 });
    expect(fromPosition(t, 0.30000000000000004)).toBe(0.3);
    expect(snapNumber(t, 1.26)).toBe(1.3);
    expect(snapNumber(t, 7)).toBe(2);
    expect(snapNumber(t, Number.NaN)).toBe(0);
    expect(toPosition(t, 0.7)).toBe(0.7);
  });

  it('niceRound keeps two significant digits', () => {
    expect(niceRound(1371)).toBe(1400);
    expect(niceRound(15360)).toBe(15000);
    expect(niceRound(128)).toBe(130);
    expect(niceRound(0)).toBe(0);
  });

  it('customStart: the default when in range, else the lowest custom value', () => {
    expect(customStart(opt(GEMINI, 'thinking_budget'), numberScale(opt(GEMINI, 'thinking_budget')))).toBe(8192);
    expect(customStart(opt(QWEN, 'thinking_budget'), numberScale(opt(QWEN, 'thinking_budget')))).toBe(1);
    expect(customStart({ key: 'x', label: 'X', kind: 'number', default: 5, min: 10, max: 20 }, numberScale({ key: 'x', label: 'X', kind: 'number', min: 10, max: 20 }))).toBe(10);
    expect(customStart({ key: 'x', label: 'X', kind: 'number' }, null)).toBe(0);
  });

  it('parseNumberField: rejects non-numbers, keeps presets, rounds whole steps, clamps', () => {
    const budget = opt(CLAUDE, 'thinking_budget');
    expect(parseNumberField(budget, '')).toEqual({ error: 'Enter a number' });
    expect(parseNumberField(budget, 'abc')).toEqual({ error: 'Not a number' });
    expect(parseNumberField(budget, '100')).toEqual({ value: 1024 });
    expect(parseNumberField(budget, '200000')).toEqual({ value: 128000 });
    expect(parseNumberField(budget, '16,000')).toEqual({ value: 16000 });
    expect(parseNumberField(budget, '2048.6')).toEqual({ value: 2049 });
    const gem = opt(GEMINI, 'thinking_budget');
    expect(parseNumberField(gem, '-1')).toEqual({ value: -1 });
    expect(parseNumberField(gem, '0')).toEqual({ value: 0 });
    expect(parseNumberField(gem, '5')).toEqual({ value: 128 });
    expect(parseNumberField({ key: 't', label: 'T', kind: 'number', min: 0, max: 2, step: 0.1 }, '0.75')).toEqual({ value: 0.8 });
    expect(parseNumberField({ key: 't', label: 'T', kind: 'number', min: 3 }, '1')).toEqual({ value: 3 });
    expect(parseNumberField({ key: 't', label: 'T', kind: 'number' }, '1.5')).toEqual({ value: 1.5 });
  });

  it('formatting', () => {
    expect(formatNumber(16000)).toBe('16,000');
    expect(formatNumber(1234567)).toBe('1,234,567');
    expect(formatNumber(999)).toBe('999');
    expect(formatNumber(-1)).toBe('-1');
    expect(formatNumber(0.7, 0.1)).toBe('0.7');
    expect(formatNumber(-0.04, 0.1)).toBe('0.0');
    expect(decimalsOf(0.25)).toBe(2);
    expect(decimalsOf(1024)).toBe(0);
    expect(decimalsOf(undefined)).toBe(0);
    expect(formatCompact(16000)).toBe('16k');
    expect(formatCompact(16384)).toBe('16.4k');
    expect(formatCompact(1_000_000)).toBe('1M');
    expect(formatCompact(512)).toBe('512');
    expect(rangeHint(opt(GEMINI, 'thinking_budget'))).toBe('128–32,768 tokens');
    expect(rangeHint({ key: 't', label: 'T', kind: 'number' })).toBe('');
  });
});
