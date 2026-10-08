/**
 * The model + options of one harness, rendered from its catalog (`GET /api/config/harnesses`).
 * Used by Settings → Agent sessions (global defaults, `scope="global"`) and by the session
 * settings sheet (`scope="session"`, where "Default" inherits the global value).
 *
 * The model is a Select (plus a field for a custom id). Each option gets the control its catalog
 * hints ask for, or one inferred from its kind (`./harnessControls.ts` `optionControl`):
 * - **switch** — a toggle with a known default; "Use default" when set;
 * - **segmented** / **levels** — a row of segments, "Default" first (the CLI default level is
 *   dotted); wraps into pills when one row would cut a label;
 * - **slider** — a (log) slider + number field; `presets` add Default · <presets> · Custom
 *   segments and the slider shows for Custom only;
 * - **dropdown** — the Select with [Default (…)], CLI default and the values.
 * An option whose `requires` is not met is disabled with the reason (its saved value is kept).
 * The session sheet also offers "Use CLI default" when the global page sets the option.
 *
 * Text fields commit live in the session sheet for the model id (it is a draft) and on blur /
 * Enter for numbers and on the global page (each commit is a PUT).
 */
import { useState, type KeyboardEvent } from 'react';
import type { HarnessCatalogModel, HarnessInfo, HarnessOptionsMap } from '@/services';
import { Button, SegmentedButton, Select, Slider, Switch, TextField, type SegmentOption } from '@/ui/controls';
import {
  CUSTOM,
  findCatalogModel,
  gatingModel,
  modelItems,
  modelSelectValue,
  modelTraits,
  numberRange,
  optionItems,
  optionSelectValue,
  parseModelSelect,
  parseNumberInput,
  parseOptionSelect,
  type OptionState,
  type Scope,
  type VisibleOption,
  visibleOptions,
  globalOptionState,
  sessionOptionState,
} from './harness';
import {
  choiceHelp,
  choiceSegments,
  CUSTOM_SEG,
  customStart,
  displayValue,
  formatNumber,
  fromPosition,
  makeResolver,
  numberScale,
  numberSegments,
  optionControl,
  parseNumberField,
  parseSegment,
  presetsOf,
  resetActions,
  resolvedValue,
  sliderRange,
  sourceLine,
  toPosition,
  unmetRequirement,
  type ControlContext,
  type SegmentItem,
} from './harnessControls';
import { Field, Notice, useFieldId } from './parts';
import { cx } from '@/ui/primitives';
import styles from './settings.module.css';

export interface HarnessFieldsProps {
  harness: HarnessInfo;
  scope: Scope;
  /** Session: `null` = inherit; both scopes: "" = CLI default. */
  model: string | null;
  /** Session: the global `harness_model[provider]`. */
  inheritedModel?: string;
  /** Session: the overlay (`null` = inherit all); global: `harness_options[provider]`. */
  options: HarnessOptionsMap | null;
  /** Session: the global `harness_options[provider]`. */
  inheritedOptions?: HarnessOptionsMap;
  disabled: boolean;
  onModel: (model: string | null) => void;
  onOption: (key: string, state: OptionState) => void;
}

/** The catalog's `warnings` (e.g. "Codex is using the shared login…"); place it outside a FieldStack. */
export function HarnessWarnings({ harness }: { harness: HarnessInfo | undefined }) {
  const warnings = harness?.catalog?.warnings ?? [];
  if (!harness || !warnings.length) return null;
  return (
    <Notice tone="warning" title={`${harness.label}: check the setup`}>
      {warnings.map((w) => (
        <p key={w}>{w}</p>
      ))}
    </Notice>
  );
}

const onEnterBlur = (e: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>): void => {
  if (e.key === 'Enter') (e.target as HTMLElement).blur();
};

const INHERIT_STATE: OptionState = { kind: 'inherit' };
const CLI_STATE: OptionState = { kind: 'cli' };

export function HarnessFields({ harness, scope, model, inheritedModel, options, inheritedOptions, disabled, onModel, onOption }: HarnessFieldsProps) {
  const catalog = harness.catalog;
  const effective = scope === 'session' && model === null ? (inheritedModel ?? '') : (model ?? '');
  const row = gatingModel(catalog, effective);
  const shown = visibleOptions(catalog, effective);
  const hidden = (catalog?.options.length ?? 0) - shown.length;
  const resolve = makeResolver(catalog, scope, options, inheritedOptions, row);
  return (
    <>
      <ModelField
        harness={harness}
        scope={scope}
        model={model}
        inheritedModel={inheritedModel}
        disabled={disabled}
        onModel={onModel}
        note={
          !catalog
            ? `${harness.label} lists no models or options.`
            : hidden > 0 && row
              ? `${hidden} more ${hidden === 1 ? 'option does' : 'options do'} not apply to ${row.label}.`
              : null
        }
      />
      {shown.map((vo) => (
        <OptionField
          key={vo.option.key}
          vo={vo}
          ctx={{
            scope,
            state: scope === 'session' ? sessionOptionState(options, vo.option.key) : globalOptionState(options, vo.option.key),
            inherited: scope === 'session' ? globalOptionState(inheritedOptions, vo.option.key) : undefined,
            row,
          }}
          reason={unmetRequirement(vo.option, catalog, resolve)}
          disabled={disabled}
          onOption={onOption}
        />
      ))}
    </>
  );
}

function ModelField({
  harness,
  scope,
  model,
  inheritedModel,
  disabled,
  onModel,
  note,
}: Pick<HarnessFieldsProps, 'harness' | 'scope' | 'model' | 'inheritedModel' | 'disabled' | 'onModel'> & { note: string | null }) {
  const catalog = harness.catalog;
  const live = scope === 'session';
  const [customMode, setCustomMode] = useState(false);
  const isCustomId = Boolean(model) && !findCatalogModel(catalog, model);
  const [text, setText] = useState(isCustomId ? (model as string) : '');
  const value = modelSelectValue(catalog, model, customMode);
  const items = modelItems(catalog, { scope, current: model, ...(inheritedModel !== undefined ? { inherited: inheritedModel } : {}) });
  const showField = value === CUSTOM;
  const selected: HarnessCatalogModel | undefined = findCatalogModel(catalog, model === null ? inheritedModel : model);
  const commit = (raw: string): void => {
    const id = raw.trim();
    if (id && id !== model) onModel(id);
  };
  return (
    <Field
      help={`${
        model === null && scope === 'session'
          ? 'Uses the default from Settings.'
          : scope === 'session'
            ? 'Set for this session.'
            : 'New sessions on this harness start with this model.'
      }${note ? ` ${note}` : ''}`}
    >
      <Select
        label="Model"
        options={items}
        value={value}
        disabled={disabled}
        supportingText={selected ? modelTraits(selected) || undefined : undefined}
        onChange={(v) => {
          const next = parseModelSelect(v);
          if (next === 'custom') {
            setCustomMode(true);
            if (live && text.trim()) onModel(text.trim());
            return;
          }
          setCustomMode(false);
          if (next !== model) onModel(next);
        }}
      />
      {showField ? (
        <TextField
          label="Model id"
          value={text}
          disabled={disabled}
          placeholder={catalog?.default_model ?? 'model-id'}
          supportingText={live ? 'Passed to the CLI as is.' : 'Passed to the CLI as is. Saved when you leave the field.'}
          onValueChange={(t) => {
            setText(t);
            if (live && t.trim()) onModel(t.trim());
          }}
          onBlur={() => {
            if (!live) commit(text);
          }}
          onKeyDown={onEnterBlur}
        />
      ) : null}
    </Field>
  );
}

// ───────────────────────── options ─────────────────────────

interface OptionFieldProps {
  vo: VisibleOption;
  ctx: ControlContext;
  /** Why the option does not apply now (`requires`), or null. */
  reason: string | null;
  disabled: boolean;
  onOption: (key: string, state: OptionState) => void;
}

function OptionField(props: OptionFieldProps) {
  switch (optionControl(props.vo)) {
    case 'switch':
      return <SwitchOption {...props} />;
    case 'segmented':
    case 'levels':
      return <ChoiceOption {...props} />;
    case 'slider':
      return <NumberOption {...props} />;
    default:
      return <DropdownOption {...props} />;
  }
}

const infoOf = (vo: VisibleOption) => (vo.option.help ? <p>{vo.option.help}</p> : undefined);

/** The small text actions under a control (see `resetActions`); null when there are none. */
function ResetActions({ vo, ctx, reset, disabled, onOption }: { vo: VisibleOption; ctx: ControlContext; reset: boolean; disabled: boolean; onOption: OptionFieldProps['onOption'] }) {
  const a = resetActions(ctx, reset);
  if (!a.useDefault && !a.useCli) return null;
  return (
    <div className={styles.optionActions}>
      {a.useDefault ? (
        <Button variant="text" size="small" icon="refresh" disabled={disabled} onClick={() => onOption(vo.option.key, ctx.scope === 'global' ? CLI_STATE : INHERIT_STATE)}>
          Use default
        </Button>
      ) : null}
      {a.useCli ? (
        <Button variant="text" size="small" disabled={disabled} onClick={() => onOption(vo.option.key, CLI_STATE)}>
          Use CLI default
        </Button>
      ) : null}
    </div>
  );
}

function SwitchOption({ vo, ctx, reason, disabled, onOption }: OptionFieldProps) {
  const { option } = vo;
  const id = useFieldId('hsw');
  const off = disabled || reason !== null;
  return (
    <Field
      label={option.label}
      labelId={id}
      help={reason ?? sourceLine(option, ctx)}
      info={infoOf(vo)}
      trailing={
        <Switch
          aria-labelledby={id}
          checked={resolvedValue(option, ctx) === true}
          disabled={off}
          onCheckedChange={(v) => onOption(option.key, { kind: 'value', value: v })}
        />
      }
      footer={<ResetActions vo={vo} ctx={ctx} reset disabled={off} onOption={onOption} />}
    />
  );
}

const toSegment = (s: SegmentItem): SegmentOption => ({
  value: s.value,
  label: s.label,
  ...(s.dot ? { dot: true } : {}),
  ...(s.title ? { title: s.title } : {}),
  ...(s.disabled ? { disabled: true } : {}),
});

function ChoiceOption({ vo, ctx, reason, disabled, onOption }: OptionFieldProps) {
  const { option } = vo;
  const id = useFieldId('hseg');
  const off = disabled || reason !== null;
  const view = choiceSegments(vo, ctx);
  return (
    <Field
      label={option.label}
      labelId={id}
      help={reason ?? choiceHelp(vo, ctx)}
      info={infoOf(vo)}
      footer={<ResetActions vo={vo} ctx={ctx} reset={false} disabled={off} onOption={onOption} />}
    >
      <SegmentedButton
        aria-labelledby={id}
        fullWidth
        wrap="auto"
        options={view.segments.map(toSegment)}
        value={view.selected ?? ''}
        disabled={off}
        onChange={(v) => {
          if (v !== view.selected) onOption(option.key, parseSegment(option, ctx.scope, v));
        }}
      />
    </Field>
  );
}

function NumberOption({ vo, ctx, reason, disabled, onOption }: OptionFieldProps) {
  const { option } = vo;
  const { state } = ctx;
  const id = useFieldId('hnum');
  const off = disabled || reason !== null;
  const scale = numberScale(option);
  const presets = presetsOf(option);
  const segments = presets.length ? numberSegments(option, ctx) : null;
  const resolved = resolvedValue(option, ctx);
  const isPreset = (n: number): boolean => presets.some((p) => p.value === n);
  const explicit = state.kind === 'value' && typeof state.value === 'number' ? state.value : null;
  const [text, setText] = useState(explicit === null ? '' : String(explicit));
  const [live, setLive] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // A new saved value (slider, preset, reset, another tab) replaces the field's text.
  const [shownExplicit, setShownExplicit] = useState(explicit);
  if (shownExplicit !== explicit) {
    setShownExplicit(explicit);
    setText(explicit === null ? '' : String(explicit));
    setError(null);
  }

  const custom = !segments || segments.selected === CUSTOM_SEG;
  const shownNumber = typeof resolved === 'number' && !isPreset(resolved) ? resolved : null;
  const sliderValue = shownNumber ?? customStart(option, scale);
  const commit = (n: number): void => {
    setError(null);
    setText(String(n));
    if (explicit !== n) onOption(option.key, { kind: 'value', value: n });
  };
  const commitText = (): void => {
    if (!text.trim() && explicit === null) {
      setError(null);
      return;
    }
    const r = parseNumberField(option, text);
    if ('error' in r) setError(r.error);
    else commit(r.value);
  };
  const pos = scale ? toPosition(scale, sliderValue) : 0;
  const valueText =
    live !== null ? displayValue(option, live) : typeof resolved === 'number' ? displayValue(option, resolved) : undefined;

  return (
    <Field
      label={option.label}
      labelId={id}
      value={valueText}
      help={error ?? reason ?? sourceLine(option, ctx)}
      info={infoOf(vo)}
      footer={<ResetActions vo={vo} ctx={ctx} reset={!segments} disabled={off} onOption={onOption} />}
    >
      {segments ? (
        <SegmentedButton
          aria-labelledby={id}
          fullWidth
          wrap="auto"
          options={segments.segments.map(toSegment)}
          value={segments.selected ?? ''}
          disabled={off}
          onChange={(v) => {
            if (v === segments.selected) return;
            if (v === CUSTOM_SEG) commit(shownNumber !== null && (!scale || (shownNumber >= scale.lo && shownNumber <= scale.hi)) ? shownNumber : customStart(option, scale));
            else onOption(option.key, parseSegment(option, ctx.scope, v));
          }}
        />
      ) : null}
      {custom ? (
        <div className={styles.numberRow}>
          {scale ? (
            <Slider
              aria-labelledby={id}
              className={cx(styles.numberSlider, explicit === null && shownNumber === null && styles.numberUnset)}
              {...sliderRange(scale)}
              value={pos}
              disabled={off}
              formatValue={(p) => formatNumber(p === pos ? sliderValue : fromPosition(scale, p), option.step)}
              onValueChange={(p) => setLive(fromPosition(scale, p))}
              onCommit={(p) => {
                setLive(null);
                if (p !== pos || explicit === null) commit(fromPosition(scale, p));
              }}
            />
          ) : null}
          <TextField
            aria-label={option.label}
            className={scale ? styles.numberField : undefined}
            type="number"
            inputMode={scale && scale.step % 1 !== 0 ? 'decimal' : 'numeric'}
            value={text}
            placeholder={shownNumber !== null ? String(shownNumber) : 'Default'}
            disabled={off}
            error={error !== null}
            {...(scale ? { min: scale.lo, max: scale.hi, step: scale.step } : {})}
            onValueChange={(t) => {
              setText(t);
              setError(null);
            }}
            onBlur={commitText}
            onKeyDown={onEnterBlur}
          />
        </div>
      ) : null}
    </Field>
  );
}

/** The Select of an option (tri-state rows); a number adds a field for a custom value. */
function DropdownOption({ vo, ctx, reason, disabled, onOption }: OptionFieldProps) {
  const { option } = vo;
  const { scope, state, inherited, row } = ctx;
  const live = scope === 'session';
  const off = disabled || reason !== null;
  const [customMode, setCustomMode] = useState(false);
  const [text, setText] = useState(state.kind === 'value' ? String(state.value) : '');
  const [error, setError] = useState<string | null>(null);
  const items = optionItems(vo, {
    scope,
    state,
    row,
    ...(inherited?.kind === 'value' ? { inherited: inherited.value } : {}),
  });
  const value = customMode && option.kind === 'number' ? CUSTOM : optionSelectValue(option, state);
  const selectedChoice = vo.choices.find((c) => state.kind === 'value' && c.value === state.value);
  const showNumber = option.kind === 'number' && value === CUSTOM;
  const commitNumber = (raw: string): void => {
    const r = parseNumberInput(option, raw);
    if ('error' in r) {
      setError(r.error);
      return;
    }
    setError(null);
    if (!(state.kind === 'value' && state.value === r.value)) onOption(option.key, { kind: 'value', value: r.value });
  };
  return (
    <Field help={reason ?? selectedChoice?.description} info={infoOf(vo)}>
      <Select
        label={option.label}
        options={items}
        value={value}
        disabled={off}
        onChange={(v) => {
          const next = parseOptionSelect(option, v);
          if (next === 'custom') {
            setCustomMode(true);
            if (live && text.trim()) commitNumber(text);
            return;
          }
          setCustomMode(false);
          setError(null);
          onOption(option.key, next);
        }}
      />
      {showNumber ? (
        <TextField
          label={option.label}
          type="number"
          inputMode="numeric"
          value={text}
          disabled={off}
          {...(typeof option.min === 'number' ? { min: option.min } : {})}
          {...(typeof option.max === 'number' ? { max: option.max } : {})}
          {...(typeof option.step === 'number' ? { step: option.step } : {})}
          error={error ?? undefined}
          supportingText={numberRange(option) || undefined}
          onValueChange={(t) => {
            setText(t);
            if (live) commitNumber(t);
            else setError(null);
          }}
          onBlur={() => {
            if (!live) commitNumber(text);
          }}
          onKeyDown={onEnterBlur}
        />
      ) : null}
    </Field>
  );
}

// ───────────────────────── Claude in Chrome ─────────────────────────

export interface ClaudeInChromeFieldProps {
  scope: Scope;
  /** Global: the saved flag; session: `null` = inherit. */
  value: boolean | null;
  /** Session: the global flag. */
  inherited?: boolean;
  disabled: boolean;
  onChange: (value: boolean | null) => void;
}

/**
 * `chrome_extension`: Claude Code's `--chrome` (Anthropic's Claude in Chrome). Claude Code only,
 * so it sits in the Claude Code block (global page) / the harness section when the session runs
 * Claude (sheet).
 */
export function ClaudeInChromeField({ scope, value, inherited, disabled, onChange }: ClaudeInChromeFieldProps) {
  const id = useFieldId('chrome');
  const session = scope === 'session';
  const on = value ?? inherited ?? false;
  return (
    <Field
      label="Claude in Chrome"
      labelId={id}
      help={
        !session
          ? 'Starts Claude sessions with the --chrome flag.'
          : value === null
            ? `Default from Settings (${inherited ? 'On' : 'Off'})`
            : 'Set for this session'
      }
      info="Anthropic's Claude-in-Chrome integration (needs an Anthropic login). Archie's own browser extension (browser-control) does not need this."
      trailing={<Switch aria-labelledby={id} checked={on} disabled={disabled} onCheckedChange={(v) => onChange(v)} />}
      footer={
        session && value !== null ? (
          <div className={styles.optionActions}>
            <Button variant="text" size="small" icon="refresh" disabled={disabled} onClick={() => onChange(null)}>
              Use default
            </Button>
          </div>
        ) : null
      }
    />
  );
}
