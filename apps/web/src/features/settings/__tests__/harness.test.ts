/**
 * Harness configuration logic (spec 12 §6.14, §8.1): gating of options / choices by the effective
 * model, the inherit / CLI default / value states, Select rows and labels, map diffs, the session
 * draft reset on a harness change, and the fallback catalogs for older servers.
 */
import { describe, expect, it } from 'vitest';
import { harnessesFromProviders, providersFromHarnesses, qwenCatalogFromModels, type HarnessCatalog, type SessionConfig } from '@/services';
import {
  CLI_DEFAULT,
  cliDefaultLabel,
  CUSTOM,
  draftForProvider,
  effectiveSessionModel,
  formatContextWindow,
  gatingModel,
  globalModelPatch,
  globalOptionPatch,
  globalOptionState,
  globalOptionValue,
  harnessDefaultsSummary,
  harnessLabel,
  INHERIT,
  inheritLabel,
  modelItems,
  modelLabel,
  modelSelectValue,
  modelTraits,
  normalizeOptionsMap,
  numberRange,
  optionItems,
  optionSelectValue,
  parseModelSelect,
  parseNumberInput,
  parseOptionSelect,
  sameOptionsMap,
  sessionOptionState,
  valueLabel,
  visibleOptions,
  withSessionOption,
} from '../harness';
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
const keys = (c: HarnessCatalog, model: string | null) => visibleOptions(c, model).map((v) => v.option.key);
const choiceValues = (c: HarnessCatalog, model: string | null, key: string) =>
  visibleOptions(c, model)
    .find((v) => v.option.key === key)
    ?.choices.map((x) => x.value);
const opt = (c: HarnessCatalog, key: string) => {
  const o = c.options.find((x) => x.key === key);
  if (!o) throw new Error(key);
  return o;
};

describe('option gating by the effective model', () => {
  it('unknown model (CLI default, custom id): every option and every choice', () => {
    expect(keys(CLAUDE, '')).toEqual(['effort', 'thinking', 'thinking_budget', 'fallback_model', 'todo_tools']);
    expect(keys(CLAUDE, 'my-custom-claude')).toEqual(keys(CLAUDE, null));
    expect(choiceValues(CLAUDE, '', 'thinking')).toEqual(['adaptive', 'enabled', 'disabled']);
    expect(choiceValues(CODEX, null, 'effort')).toEqual(['low', 'medium', 'high', 'xhigh', 'max', 'ultra']);
  });

  it('option.models hides an option for other models (thinking on adaptive-only models, budget on 4.6/4.5)', () => {
    expect(keys(CLAUDE, 'claude-opus-5-5')).toEqual(['effort', 'fallback_model', 'todo_tools']);
    expect(keys(CLAUDE, 'claude-opus-4-6')).toEqual(['effort', 'thinking', 'thinking_budget', 'fallback_model', 'todo_tools']);
    expect(keys(GEMINI, 'gemini-2.5-pro')).toEqual(['thinking_budget', 'approval_mode']);
    expect(keys(GEMINI, 'gemini-3.1-pro-preview')).toEqual(['thinking_level', 'approval_mode']);
  });

  it('choice.models hides a choice for other models', () => {
    expect(choiceValues(CLAUDE, 'claude-haiku-5-5', 'thinking')).toEqual(['adaptive', 'disabled']);
    expect(choiceValues(CLAUDE, 'claude-sonnet-4-6', 'thinking')).toEqual(['adaptive', 'enabled', 'disabled']);
  });

  it('effort is narrowed to the model efforts; [] hides it', () => {
    expect(choiceValues(CLAUDE, 'claude-opus-4-6', 'effort')).toEqual(['low', 'medium', 'high', 'max']);
    expect(choiceValues(CLAUDE, 'claude-opus-4-5-20251101', 'effort')).toEqual(['low', 'medium', 'high']);
    expect(keys(CLAUDE, 'claude-haiku-4-5-20251001')).not.toContain('effort');
    expect(keys(CLAUDE, 'haiku')).not.toContain('effort');
    expect(choiceValues(CODEX, 'gpt-5.6-terra', 'effort')).toContain('ultra');
    expect(choiceValues(CODEX, 'gpt-6-luna', 'effort')).not.toContain('ultra');
    expect(choiceValues(CODEX, 'codex-mini', 'effort')).toEqual(['low', 'medium', 'high']);
  });

  it('a level a model lists that the option lacks is still offered', () => {
    const c: HarnessCatalog = { ...CODEX, models: [{ id: 'm', label: 'M', source: 'cli', efforts: ['low', 'turbo_max'] }] };
    expect(visibleOptions(c, 'm').find((v) => v.option.key === 'effort')?.choices).toEqual([
      { value: 'low', label: 'Low' },
      { value: 'turbo_max', label: 'Turbo max' },
    ]);
  });

  it('supports_thinking === false hides thinking / thinking_* options', () => {
    expect(keys(QWEN, 'qwen3-coder-flash')).toEqual([]);
    expect(keys(QWEN, 'qwen3.6-plus')).toEqual(['thinking', 'thinking_budget']);
  });

  it('no catalog → no options; a select left without choices is hidden', () => {
    expect(visibleOptions(null, 'x')).toEqual([]);
    const c: HarnessCatalog = {
      ...CLAUDE,
      models: [{ id: 'm', label: 'M', source: 'live' }],
      options: [{ key: 'x', label: 'X', kind: 'select', choices: [{ value: 'a', label: 'A', models: ['other'] }] }],
    };
    expect(keys(c, 'm')).toEqual([]);
    expect(keys(c, '')).toEqual(['x']);
  });
});

describe('labels', () => {
  it('context windows, model traits and labels', () => {
    expect(formatContextWindow(1_000_000)).toBe('1M');
    expect(formatContextWindow(1_048_576)).toBe('1M');
    expect(formatContextWindow(1_500_000)).toBe('1.5M');
    expect(formatContextWindow(272_000)).toBe('272K');
    expect(modelTraits({ id: 'opus', label: 'Opus', source: 'builtin', description: 'Alias → Claude Opus 5.5', context_window: 1_000_000, supports_thinking: true, supports_vision: true })).toBe(
      'opus · Alias → Claude Opus 5.5 · 1M context · thinking · vision',
    );
    expect(modelTraits({ id: 'x', label: 'x', source: 'live' })).toBe('live list');
    expect(modelTraits({ id: 'q', label: 'Q', source: 'settings', supports_thinking: false })).toBe('q · CLI settings');
    expect(modelLabel(CLAUDE, 'claude-opus-5-5')).toBe('Claude Opus 5.5');
    expect(modelLabel(CLAUDE, 'whatever')).toBe('whatever');
    expect(modelLabel(CLAUDE, '')).toBe('CLI default');
    expect(harnessLabel(HARNESS_SAMPLES, 'codex')).toBe('Codex');
    expect(harnessLabel(HARNESS_SAMPLES, 'nope')).toBe('nope');
  });

  it('values, CLI defaults (model default_effort first) and the inherit row', () => {
    const effort = opt(CODEX, 'effort');
    expect(valueLabel(effort, 'xhigh')).toBe('Extra high');
    expect(valueLabel(opt(CLAUDE, 'todo_tools'), false)).toBe('Off');
    expect(valueLabel(opt(CLAUDE, 'thinking_budget'), 32000)).toBe('32000');
    expect(cliDefaultLabel(effort, null)).toBe('CLI default · Medium');
    expect(cliDefaultLabel(effort, gatingModel(CODEX, 'codex-mini'))).toBe('CLI default · Low');
    expect(cliDefaultLabel(opt(CODEX, 'web_search'), null)).toBe('CLI default');
    expect(cliDefaultLabel(opt(CLAUDE, 'todo_tools'), null)).toBe('CLI default · On');
    expect(inheritLabel(effort, 'high', null)).toBe('Default (High)');
    expect(inheritLabel(effort, undefined, null)).toBe('Default (CLI default · Medium)');
  });

  it('collapsed summary of a harness on the global page', () => {
    expect(harnessDefaultsSummary(CLAUDE, '', {})).toBe('CLI default');
    expect(harnessDefaultsSummary(CLAUDE, 'opus', { effort: 'max' })).toBe('Opus · 1 option');
    expect(harnessDefaultsSummary(CODEX, 'gpt-6-luna', { effort: 'low', verbosity: 'high', gone: 'x' })).toBe('GPT-6-Luna · 2 options');
    expect(harnessDefaultsSummary(null, 'm', null)).toBe('m');
  });
});

describe('option states', () => {
  it('session: absent → inherit, null → CLI default, value; global: absent/null → CLI default', () => {
    expect(sessionOptionState(null, 'effort')).toEqual({ kind: 'inherit' });
    expect(sessionOptionState({ thinking: 'x' }, 'effort')).toEqual({ kind: 'inherit' });
    expect(sessionOptionState({ effort: null }, 'effort')).toEqual({ kind: 'cli' });
    expect(sessionOptionState({ effort: 'low' }, 'effort')).toEqual({ kind: 'value', value: 'low' });
    expect(sessionOptionState({ todo_tools: false }, 'todo_tools')).toEqual({ kind: 'value', value: false });
    expect(globalOptionState(undefined, 'effort')).toEqual({ kind: 'cli' });
    expect(globalOptionState({ effort: null }, 'effort')).toEqual({ kind: 'cli' });
    expect(globalOptionState({ thinking_budget: 0 }, 'thinking_budget')).toEqual({ kind: 'value', value: 0 });
    expect(globalOptionValue({ harness_options: { claude: { effort: 'high' } } }, 'claude', 'effort')).toBe('high');
    expect(globalOptionValue({}, 'claude', 'effort')).toBeUndefined();
  });

  it('session overlay edits: inherit removes the key, an empty map is null', () => {
    expect(withSessionOption(null, 'effort', { kind: 'value', value: 'max' })).toEqual({ effort: 'max' });
    expect(withSessionOption({ effort: 'max' }, 'thinking', { kind: 'cli' })).toEqual({ effort: 'max', thinking: null });
    expect(withSessionOption({ effort: 'max' }, 'effort', { kind: 'inherit' })).toBeNull();
    expect(withSessionOption({ effort: 'max', thinking: null }, 'effort', { kind: 'inherit' })).toEqual({ thinking: null });
  });

  it('global PUT bodies: per key, null = CLI default', () => {
    expect(globalOptionPatch('claude', 'effort', { kind: 'value', value: 'xhigh' })).toEqual({ harness_options: { claude: { effort: 'xhigh' } } });
    expect(globalOptionPatch('codex', 'web_search', { kind: 'cli' })).toEqual({ harness_options: { codex: { web_search: null } } });
    expect(globalModelPatch('gemini', '')).toEqual({ harness_model: { gemini: '' } });
  });

  it('maps compare structurally; null and {} are equal; junk is dropped', () => {
    expect(sameOptionsMap(null, {})).toBe(true);
    expect(sameOptionsMap({ a: 1, b: null }, { b: null, a: 1 })).toBe(true);
    expect(sameOptionsMap({ a: 1 }, { a: 1, b: null })).toBe(false);
    expect(sameOptionsMap({ a: null }, null)).toBe(false);
    expect(sameOptionsMap({ a: '1' }, { a: 1 })).toBe(false);
    expect(normalizeOptionsMap({})).toBeNull();
    expect(normalizeOptionsMap([1])).toBeNull();
    expect(normalizeOptionsMap({ a: { x: 1 }, b: 2, c: null })).toEqual({ b: 2, c: null });
  });
});

describe('option Select rows', () => {
  const vo = (c: HarnessCatalog, model: string | null, key: string) => {
    const v = visibleOptions(c, model).find((x) => x.option.key === key);
    if (!v) throw new Error(key);
    return v;
  };

  it('session select: Default (global) · CLI default · choices; a stale value is shown disabled', () => {
    const rows = optionItems(vo(CLAUDE, 'claude-opus-4-5-20251101', 'effort'), {
      scope: 'session',
      state: { kind: 'value', value: 'max' },
      inherited: 'high',
      row: gatingModel(CLAUDE, 'claude-opus-4-5-20251101'),
    });
    expect(rows.map((r) => r.label)).toEqual(['Default (High)', 'CLI default', 'Low', 'Medium', 'High', 'Max (not for this model)']);
    expect(rows[0]?.value).toBe(INHERIT);
    expect(rows[1]?.value).toBe(CLI_DEFAULT);
    expect(rows.at(-1)?.disabled).toBe(true);
  });

  it('global toggle and number rows; Select values round-trip', () => {
    const toggle = optionItems(vo(QWEN, null, 'thinking'), { scope: 'global', state: { kind: 'cli' }, row: null });
    expect(toggle.map((r) => r.label)).toEqual(['CLI default · On', 'On', 'Off']);
    const num = optionItems(vo(QWEN, null, 'thinking_budget'), { scope: 'session', state: { kind: 'inherit' }, row: null });
    expect(num.map((r) => r.label)).toEqual(['Default (CLI default)', 'CLI default', 'Custom value…']);
    expect(num[2]?.description).toBe('1–32768');
    const tb = opt(QWEN, 'thinking_budget');
    expect(optionSelectValue(tb, { kind: 'value', value: 2048 })).toBe(CUSTOM);
    expect(parseOptionSelect(tb, CUSTOM)).toBe('custom');
    const th = opt(QWEN, 'thinking');
    expect(optionSelectValue(th, { kind: 'value', value: false })).toBe('false');
    expect(parseOptionSelect(th, 'false')).toEqual({ kind: 'value', value: false });
    expect(parseOptionSelect(th, INHERIT)).toEqual({ kind: 'inherit' });
    expect(parseOptionSelect(th, CLI_DEFAULT)).toEqual({ kind: 'cli' });
    expect(parseOptionSelect(opt(CODEX, 'effort'), 'ultra')).toEqual({ kind: 'value', value: 'ultra' });
  });

  it('number input validation against min / max / step', () => {
    const tb = opt(CLAUDE, 'thinking_budget');
    expect(numberRange(tb)).toBe('1024–128000, step 1024');
    expect(parseNumberInput(tb, '')).toEqual({ error: 'Enter a number' });
    expect(parseNumberInput(tb, 'abc')).toEqual({ error: 'Not a number' });
    expect(parseNumberInput(tb, '100')).toEqual({ error: 'At least 1024' });
    expect(parseNumberInput(tb, '200000')).toEqual({ error: 'At most 128000' });
    expect(parseNumberInput(tb, '2048.5')).toEqual({ error: 'Whole numbers only' });
    expect(parseNumberInput(tb, ' 32000 ')).toEqual({ value: 32000 });
    expect(parseNumberInput(opt(GEMINI, 'thinking_budget'), '-1')).toEqual({ value: -1 });
  });
});

describe('model Select rows', () => {
  it('session: Default (inherited) · CLI default (catalog default) · models · Custom', () => {
    const rows = modelItems(CLAUDE, { scope: 'session', current: null, inherited: 'opus' });
    expect(rows[0]).toEqual({ value: INHERIT, label: 'Default (Opus)' });
    expect(rows[1]?.label).toBe('CLI default (Claude Sonnet 5.5)');
    expect(rows.find((r) => r.value === 'claude-opus-4-6')?.description).toBe('claude-opus-4-6 · 1M context · thinking · vision · live list');
    expect(rows.at(-1)).toEqual({ value: CUSTOM, label: 'Custom model id…', description: 'Any id the CLI accepts' });
    expect(modelItems(CLAUDE, { scope: 'session', current: null, inherited: '' })[0]?.label).toBe('Default (CLI default (Claude Sonnet 5.5))');
  });

  it('global: no inherit row; no catalog → CLI default + custom', () => {
    expect(modelItems(CODEX, { scope: 'global', current: '' })[0]?.value).toBe(CLI_DEFAULT);
    expect(modelItems(null, { scope: 'global', current: '' }).map((r) => r.value)).toEqual([CLI_DEFAULT, CUSTOM]);
  });

  it('a catalog without custom ids shows an unknown saved id as unavailable', () => {
    const strict: HarnessCatalog = { ...CODEX, allow_custom_model: false };
    const rows = modelItems(strict, { scope: 'global', current: 'old-model' });
    expect(rows.at(-1)).toEqual({ value: 'old-model', label: 'old-model (unavailable)', disabled: true });
    expect(modelSelectValue(strict, 'old-model')).toBe('old-model');
  });

  it('Select values: inherit / CLI default / catalog id / custom id', () => {
    expect(modelSelectValue(CLAUDE, null)).toBe(INHERIT);
    expect(modelSelectValue(CLAUDE, '')).toBe(CLI_DEFAULT);
    expect(modelSelectValue(CLAUDE, '', true)).toBe(CUSTOM);
    expect(modelSelectValue(CLAUDE, 'opus')).toBe('opus');
    expect(modelSelectValue(CLAUDE, 'claude-opus-5-5[1m]')).toBe(CUSTOM);
    expect(parseModelSelect(INHERIT)).toBeNull();
    expect(parseModelSelect(CLI_DEFAULT)).toBe('');
    expect(parseModelSelect(CUSTOM)).toBe('custom');
    expect(parseModelSelect('opus')).toBe('opus');
    expect(effectiveSessionModel(null, 'opus')).toBe('opus');
    expect(effectiveSessionModel('', 'opus')).toBe('');
    expect(effectiveSessionModel(null, undefined)).toBe('');
  });
});

describe('session draft: changing the harness', () => {
  const saved: SessionConfig = {
    working_directory: null,
    enabled_mcps: null,
    chrome_extension: null,
    provider: 'claude',
    harness_model: 'opus',
    harness_options: { effort: 'max' },
  };

  it('resets the model and options to inherit', () => {
    expect(draftForProvider(saved, { chrome_extension: true }, 'codex', 'claude')).toEqual({
      chrome_extension: true,
      provider: 'codex',
      harness_model: null,
      harness_options: null,
    });
  });

  it('going back to the saved harness (explicitly or via inherit) restores the saved values', () => {
    const away = draftForProvider(saved, {}, 'codex', 'claude');
    expect(draftForProvider(saved, away, 'claude', 'claude')).toEqual({ provider: 'claude' });
    expect(draftForProvider(saved, away, null, 'claude')).toEqual({ provider: null });
  });
});

describe('fallback catalogs (older servers)', () => {
  it('Qwen rows from /api/config/harness/qwen/models', () => {
    const c = qwenCatalogFromModels(['a', '', 'a', { id: 'b', display_name: 'B', context_window: 1_000_000, supports_thinking: true, supports_vision: false }, { x: 1 }, null]);
    expect(c.models).toEqual([
      { id: 'a', label: 'a', source: 'settings' },
      { id: 'b', label: 'B', source: 'settings', context_window: 1_000_000, supports_thinking: true, supports_vision: false },
    ]);
    expect(c.options).toEqual([]);
    expect(c.warnings).toEqual([]);
    expect(qwenCatalogFromModels([]).warnings[0]).toMatch(/No models listed/);
  });

  it('providers ↔ harness rows', () => {
    const rows = harnessesFromProviders(
      [
        { id: 'claude', label: 'Claude Code', description: 'd' },
        { id: 'qwen', label: '' },
      ],
      ['q1'],
    );
    expect(rows.map((r) => [r.id, r.label, r.catalog?.models.length ?? null])).toEqual([
      ['claude', 'Claude Code', null],
      ['qwen', 'qwen', 1],
    ]);
    expect(harnessesFromProviders([{ id: 'qwen', label: 'Qwen' }], null)[0]?.catalog).toBeNull();
    expect(providersFromHarnesses(rows)).toEqual([
      { id: 'claude', label: 'Claude Code', description: 'd' },
      { id: 'qwen', label: 'qwen' },
    ]);
  });
});
