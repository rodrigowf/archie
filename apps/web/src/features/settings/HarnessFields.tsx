/**
 * The model + options of one harness, rendered from its catalog (`GET /api/config/harnesses`).
 * Used by Settings → Agent sessions (global defaults, `scope="global"`) and by the session
 * settings sheet (`scope="session"`, with a "Default (…)" inherit row on every control).
 * Every option is a Select (tri-state: [inherit], CLI default, values); a number option adds a
 * field for its value, the model a field for a custom id. The rules live in `./harness.ts`.
 *
 * Text fields commit live in the session sheet (it is a draft) and on blur / Enter on the global
 * page (each commit is a PUT).
 */
import { useState, type KeyboardEvent } from 'react';
import type { HarnessCatalogModel, HarnessInfo, HarnessOptionsMap } from '@/services';
import { Select, TextField } from '@/ui/controls';
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
import { Field, Notice } from './parts';

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

export function HarnessFields({ harness, scope, model, inheritedModel, options, inheritedOptions, disabled, onModel, onOption }: HarnessFieldsProps) {
  const catalog = harness.catalog;
  const effective = scope === 'session' && model === null ? (inheritedModel ?? '') : (model ?? '');
  const row = gatingModel(catalog, effective);
  const shown = visibleOptions(catalog, effective);
  const hidden = (catalog?.options.length ?? 0) - shown.length;
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
          scope={scope}
          state={scope === 'session' ? sessionOptionState(options, vo.option.key) : globalOptionState(options, vo.option.key)}
          inherited={inheritedOptions ? globalOptionState(inheritedOptions, vo.option.key) : undefined}
          row={row}
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

interface OptionFieldProps {
  vo: VisibleOption;
  scope: Scope;
  state: OptionState;
  /** Session: the global state of this key. */
  inherited: OptionState | undefined;
  row: HarnessCatalogModel | null;
  disabled: boolean;
  onOption: (key: string, state: OptionState) => void;
}

function OptionField({ vo, scope, state, inherited, row, disabled, onOption }: OptionFieldProps) {
  const { option } = vo;
  const live = scope === 'session';
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
    <Field help={selectedChoice?.description} info={option.help ? <p>{option.help}</p> : undefined}>
      <Select
        label={option.label}
        options={items}
        value={value}
        disabled={disabled}
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
          disabled={disabled}
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
