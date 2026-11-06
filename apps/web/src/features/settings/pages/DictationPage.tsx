/**
 * Archie (server) → Dictation (spec 12 §8.4; mockup `context/public/composer-dictation/` §4).
 *
 * Speaking into the composer of an agent session: the audio goes to the backend, the text comes
 * back. A server setting rather than a device one, so every client agrees about whether the
 * empty-field primary button is a mic.
 *
 * The transcriber list is `GET /api/transcribe/providers`, rendered verbatim — entries with no
 * API key on this server are shown **disabled with the reason** rather than hidden, so the list
 * explains itself instead of silently lacking an option Rodrigo remembers reading about.
 */
import type { ServerConfig, TranscriberInfo } from '@/services';
import { useServerConfig } from '@/stores';
import { Select, Switch, type SelectOption } from '@/ui/controls';
import { saveSetting } from '../controller';
import { Field, FieldStack, Notice, useFieldId } from '../parts';
import { useSaving, WithConfig } from './shared';

export function DictationPage() {
  return <WithConfig>{(cfg) => <DictationForm cfg={cfg} />}</WithConfig>;
}

/** "live" or "on stop", plus the cost — the two things that decide the choice. */
export function transcriberDescription(t: TranscriberInfo): string {
  if (!t.available) return t.unavailable_reason;
  const when = t.streaming ? 'Live, as you speak' : 'Transcribes on stop';
  return `${when} · ${t.description} ${t.cost_note}`.replace(/\s+/g, ' ').trim();
}

function DictationForm({ cfg }: { cfg: ServerConfig }) {
  const transcribers = useServerConfig((s) => s.transcribers);
  const transcriberLanguages = useServerConfig((s) => s.transcriberLanguages);
  const saving = useSaving();
  const onId = useFieldId('dict');

  const enabled = cfg.dictation_enabled !== false;
  const current = `${cfg.dictation_provider ?? ''}/${cfg.dictation_model ?? ''}`;
  const list = transcribers ?? [];
  const chosen = list.find((t) => t.id === current);

  const options: SelectOption[] = list.map((t) => ({
    value: t.id,
    label: t.label,
    description: transcriberDescription(t),
    disabled: !t.available,
  }));
  if (!chosen && current !== '/') options.unshift({ value: current, label: `${current} (not listed)` });

  const languages: SelectOption[] = (transcriberLanguages ?? []).map((l) => ({ value: l.id, label: l.label }));

  return (
    <>
      {chosen && !chosen.available ? (
        <Notice tone="warning" title="No API key for this transcriber">
          {chosen.unavailable_reason}. Dictation stays off until you pick one with a key, or add the key under Accounts.
        </Notice>
      ) : null}
      <FieldStack label="Dictation">
        <Field
          label="Dictation"
          labelId={onId}
          help="A microphone on the empty composer, in every agent session."
          info={
            <p>
              The mic replaces the disabled Send when the field is empty. On the Archie tab the primary button stays realtime voice and
              dictation moves into the ＋ menu.
            </p>
          }
          trailing={
            <Switch
              aria-labelledby={onId}
              checked={enabled}
              disabled={saving}
              onCheckedChange={(v) => {
                void saveSetting({ dictation_enabled: v }, 'dictation_enabled');
              }}
            />
          }
        />
        <Field help="Applies to the next dictation; one in progress keeps its transcriber.">
          <Select
            label="Transcriber"
            options={options}
            value={current}
            disabled={saving || !options.length}
            onChange={(id) => {
              const picked = list.find((t) => t.id === id);
              if (!picked || id === current) return;
              void saveSetting({ dictation_provider: picked.provider, dictation_model: picked.model }, 'dictation_model');
            }}
          />
          <Select
            label="Language"
            options={languages}
            value={cfg.dictation_language ?? ''}
            disabled={saving || !languages.length}
            supportingText="Auto-detect is best when you mix languages."
            onChange={(l) => {
              if (l !== (cfg.dictation_language ?? '')) void saveSetting({ dictation_language: l }, 'dictation_language');
            }}
          />
        </Field>
      </FieldStack>
    </>
  );
}
