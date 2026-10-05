/**
 * Session settings (IA §7, inv02 F-32, spec 12 §6.14): a side sheet on Expanded / Medium, a
 * bottom sheet on Compact, opened from the session ⋮ menu. Fields: working directory, MCP servers,
 * skills & agents (read-only: the backend has no per-session selection, inv02 F-35), and under
 * "Advanced" the harness, its model and the Chrome flag.
 *
 * Every field is `null` = inherit the global value (shown as "Default"); "Use default" writes
 * `null`. Changes are a draft until saved: **Save** PUTs only the changed keys; **Save and restart**
 * also restarts the session (close → `start` with the same `local_id` + `resume_sdk_id`), because
 * the backend applies session config only to a new pool entry. Restart always closes first, so an
 * idle session is no longer mistaken for a stopped one (fixes inv02 F-32 / §6.2). It is refused
 * while a reply is running.
 */
import { useEffect, useState, type ReactNode } from 'react';
import { useSessionActions } from '@/features/session-actions';
import { api, errorMessage, getSessionRuntime, SessionRuntime, type ServerConfig, type SessionConfig } from '@/services';
import { showSnackbar, useServerConfig } from '@/stores';
import { Button, Disclosure, Select, Switch, type SelectOption } from '@/ui/controls';
import { BottomSheet, SideSheet } from '@/ui/overlays';
import { refreshSettings } from '../controller';
import { coerceWorkingDirectories, harnessModels } from '../logic';
import { Field, FieldStack, Loading, Notice, useFieldId } from '../parts';
import { McpServerList } from '../pages/McpServersPage';
import { WorkingDirectoryList } from '../pages/WorkingDirectoriesPage';
import { useCompactWindow } from './useCompactWindow';
import styles from '../settings.module.css';

export type SessionConfigKey = keyof SessionConfig;
const KEYS: readonly SessionConfigKey[] = ['working_directory', 'enabled_mcps', 'provider', 'harness_model', 'chrome_extension'];

const EMPTY: SessionConfig = { working_directory: null, enabled_mcps: null, chrome_extension: null, provider: null, harness_model: null };

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** Only the keys whose draft value differs from the saved one (spec 12 §6.14). */
export function changedKeys(saved: SessionConfig, draft: Partial<SessionConfig>): Partial<SessionConfig> {
  const out: Partial<SessionConfig> = {};
  for (const k of KEYS) if (k in draft && !same(draft[k], saved[k])) (out as Record<string, unknown>)[k] = draft[k];
  return out;
}

export interface SessionSettingsSheetProps {
  localId: string;
  open: boolean;
  onClose: () => void;
  /** Open Settings at a page ("Manage directories"). */
  onOpenSettings?: (page: 'working-directories' | 'mcp-servers') => void;
  /** Gallery: render in place. */
  inline?: boolean;
  /** Gallery / tests: force the sheet kind. */
  kind?: 'side' | 'bottom';
}

export default function SessionSettingsSheet({ localId, open, onClose, onOpenSettings, inline, kind }: SessionSettingsSheetProps) {
  const compact = useCompactWindow();
  const bottom = kind ? kind === 'bottom' : compact;
  const { body, footer } = useSessionSettingsView(localId, onClose, onOpenSettings);
  const title = 'Session settings';
  if (bottom)
    return (
      <BottomSheet open={open} onClose={onClose} title={title} inline={inline} footer={footer}>
        {body}
      </BottomSheet>
    );
  return (
    <SideSheet open={open} onClose={onClose} title={title} width={400} inline={inline} footer={footer}>
      {body}
    </SideSheet>
  );
}

type Phase = 'loading' | 'ready' | 'error';

interface SheetView {
  body: ReactNode;
  footer: ReactNode;
}

/** State + parts of the sheet: the body scrolls, the footer (Save / Save and restart) stays put. */
function useSessionSettingsView(localId: string, onDone: () => void, onOpenSettings?: SessionSettingsSheetProps['onOpenSettings']): SheetView {
  const a = useSessionActions(localId);
  const sdkId = a.sdkId;
  const global = useServerConfig((s) => s.config);
  const [phase, setPhase] = useState<Phase>('loading');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saved, setSaved] = useState<SessionConfig>(EMPTY);
  const [draft, setDraft] = useState<Partial<SessionConfig>>({});
  const [busySave, setBusySave] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!sdkId) return undefined;
    let live = true;
    void refreshSettings();
    api.sessions
      .getConfig(sdkId)
      .then((c) => {
        if (!live) return;
        setSaved({ ...EMPTY, ...c });
        setDraft({});
        setPhase('ready');
      })
      .catch((err: unknown) => {
        if (!live) return;
        setLoadError(errorMessage(err));
        setPhase('error');
      });
    return () => {
      live = false;
    };
  }, [sdkId, attempt]);
  const load = (): void => {
    setPhase('loading');
    setLoadError(null);
    setAttempt((n) => n + 1);
  };

  if (!sdkId)
    return {
      body: <Notice title="Available after the first reply">Session settings are saved per conversation, which gets its id with the first reply.</Notice>,
      footer: null,
    };
  if (a.readOnly) return { body: <Notice title="Read-only view">Open the session to change its settings.</Notice>, footer: null };
  if (phase === 'error')
    return {
      body: (
        <Notice tone="error" title="Couldn't load the session settings" action={<Button variant="tonal" icon="refresh" onClick={load}>Retry</Button>}>
          {loadError}
        </Notice>
      ),
      footer: null,
    };
  if (phase === 'loading' || !global) return { body: <Loading label="Loading session settings…" />, footer: null };

  const value = <K extends SessionConfigKey>(k: K): SessionConfig[K] => (k in draft ? (draft[k] as SessionConfig[K]) : saved[k]);
  const set = <K extends SessionConfigKey>(k: K, v: SessionConfig[K]): void => {
    setDraft((d) => ({ ...d, [k]: v }));
    setSaveError(null);
  };
  const changes = changedKeys(saved, draft);
  const dirty = Object.keys(changes).length > 0;

  const run = async (restart: boolean): Promise<void> => {
    setBusySave(true);
    setSaveError(null);
    try {
      if (dirty) {
        const next = await api.sessions.putConfig(sdkId, changes);
        setSaved({ ...EMPTY, ...next });
        setDraft({});
      }
      if (restart) {
        const rt = getSessionRuntime(localId);
        if (rt instanceof SessionRuntime) await rt.restart();
        showSnackbar(dirty ? 'Saved. Restarting the session…' : 'Restarting the session…', { durationMs: 3000 });
        onDone();
      } else {
        showSnackbar('Saved. Applies when the session restarts.', { durationMs: 3000 });
      }
    } catch (err) {
      const msg = errorMessage(err);
      setSaveError(msg);
      showSnackbar(msg, { tone: 'error', action: { label: 'Retry', run: () => void run(restart) } });
    } finally {
      setBusySave(false);
    }
  };

  return {
    body: (
      <div className={styles.sessionBody}>
        <SessionFields global={global} value={value} set={set} disabled={busySave} {...(onOpenSettings ? { onOpenSettings } : {})} />
        {saveError ? (
          <Notice tone="error" title="Not saved">
            {saveError}
          </Notice>
        ) : null}
      </div>
    ),
    footer: (
      <div className={styles.sessionFooter}>
        <p className={styles.help} role="status">
          {a.busy ? 'A reply is running: stop it to restart.' : dirty ? 'Changes apply after a restart.' : 'The conversation is kept when the session restarts.'}
        </p>
        <div className={styles.actionsRow}>
          <Button variant="text" disabled={!dirty || busySave} onClick={() => void run(false)}>
            Save
          </Button>
          <Button variant="filled" icon="restart_alt" loading={busySave} disabled={a.busy} onClick={() => void run(true)}>
            {dirty ? 'Save and restart' : 'Restart'}
          </Button>
        </div>
      </div>
    ),
  };
}

interface FieldsProps {
  global: ServerConfig;
  value: <K extends SessionConfigKey>(k: K) => SessionConfig[K];
  set: <K extends SessionConfigKey>(k: K, v: SessionConfig[K]) => void;
  disabled: boolean;
  onOpenSettings?: SessionSettingsSheetProps['onOpenSettings'];
}

function UseDefault({ shown, onClick, disabled }: { shown: boolean; onClick: () => void; disabled: boolean }) {
  return shown ? (
    <Button variant="text" size="small" icon="refresh" disabled={disabled} onClick={onClick}>
      Use default
    </Button>
  ) : null;
}

function SessionFields({ global, value, set, disabled, onOpenSettings }: FieldsProps) {
  const mcpServers = useServerConfig((s) => s.mcpServers);
  const providers = useServerConfig((s) => s.providers) ?? [];
  const qwenRaw = useServerConfig((s) => s.qwenModels);
  const skills = useServerConfig((s) => s.skills) ?? [];
  const agents = useServerConfig((s) => s.agents) ?? [];
  const chromeId = useFieldId('schrome');

  const wd = value('working_directory');
  const history = coerceWorkingDirectories(global.working_directory_history);
  const mcps = value('enabled_mcps');
  const provider = value('provider');
  const effectiveProvider = provider ?? global.provider;
  const harness = value('harness_model');
  const chrome = value('chrome_extension');

  const providerLabel = (id: string): string => providers.find((p) => p.id === id)?.label ?? id;
  const providerOptions: SelectOption[] = [{ value: '', label: `Default (${providerLabel(global.provider)})` }].concat(
    providers.map((p) => ({ value: p.id, label: p.label || p.id })),
  );
  const qwen = harnessModels(qwenRaw);
  const inheritedModel = global.harness_model?.[effectiveProvider] ?? '';
  const harnessOptions: SelectOption[] = [
    { value: '__inherit__', label: `Default (${inheritedModel || 'CLI default'})` },
    { value: '', label: 'CLI default' },
  ].concat(qwen.map((m) => ({ value: m.id, label: m.label, ...(m.traits ? { description: m.traits } : {}) })));
  if (harness && !harnessOptions.some((o) => o.value === harness)) harnessOptions.push({ value: harness, label: harness });

  return (
    <>
      <div className={styles.stackBlock}>
        <h3 className={styles.stackLabel}>Working directory</h3>
        <WorkingDirectoryList
          label="Working directory for this session"
          history={history}
          value={wd ?? global.working_directory}
          selectedBadge={wd === null ? 'Default' : 'This session'}
          disabled={disabled}
          onSelect={(id) => set('working_directory', id)}
        />
        <p className={styles.help}>
          {wd === null ? 'Uses the default from Settings.' : 'Pinned for this session.'} Adding or editing directories changes the list for every session.
        </p>
        <div className={styles.actionsRow}>
          <UseDefault shown={wd !== null} disabled={disabled} onClick={() => set('working_directory', null)} />
          {onOpenSettings ? (
            <Button variant="text" size="small" icon="folder" onClick={() => onOpenSettings('working-directories')}>
              Manage directories
            </Button>
          ) : null}
        </div>
      </div>

      <div className={styles.stackBlock}>
        <h3 className={styles.stackLabel}>MCP servers · {mcps === null ? 'default from Settings' : 'chosen for this session'}</h3>
        {mcpServers && Object.keys(mcpServers.servers).length ? (
          <McpServerList servers={mcpServers} enabled={mcps ?? global.enabled_mcps ?? []} disabled={disabled} onChange={(next) => set('enabled_mcps', next)} />
        ) : (
          <p className={styles.help}>No MCP servers configured in .claude.json.</p>
        )}
        {mcps !== null ? (
          <div className={styles.actionsRow}>
            <UseDefault shown disabled={disabled} onClick={() => set('enabled_mcps', null)} />
          </div>
        ) : null}
      </div>

      <FieldStack label="Skills & agents">
        <div className={styles.field}>
          <Disclosure summary={`${skills.length} ${skills.length === 1 ? 'skill' : 'skills'} · ${agents.length} ${agents.length === 1 ? 'agent' : 'agents'}`} icon="build">
            <p className={styles.help}>Every session can use all of them; the server has no per-session selection.</p>
            <ul className={styles.licenses}>
              {skills.map((s) => (
                <li key={`s:${s.name}`}>
                  <b>/{s.name}</b>
                  {s.description ? ` · ${s.description}` : ''}
                </li>
              ))}
              {agents.map((g) => (
                <li key={`a:${g.name}`}>
                  <b>{g.name}</b> (agent){g.description ? ` · ${g.description}` : ''}
                </li>
              ))}
            </ul>
          </Disclosure>
        </div>
      </FieldStack>

      <FieldStack label="Advanced">
        <Field help="Switching the CLI behind an existing conversation can corrupt it.">
          <Select
            label="Harness"
            options={providerOptions}
            value={provider ?? ''}
            disabled={disabled}
            onChange={(v) => set('provider', v === '' ? null : v)}
          />
          {effectiveProvider === 'qwen' ? (
            <Select
              label="Qwen model"
              options={harnessOptions}
              value={harness === null ? '__inherit__' : harness}
              disabled={disabled}
              onChange={(v) => set('harness_model', v === '__inherit__' ? null : v)}
            />
          ) : null}
        </Field>
        <Field
          label="Claude in Chrome"
          labelId={chromeId}
          help={chrome === null ? `Default (${global.chrome_extension ? 'on' : 'off'})` : 'Set for this session.'}
          trailing={
            <Switch aria-labelledby={chromeId} checked={chrome ?? global.chrome_extension} disabled={disabled} onCheckedChange={(v) => set('chrome_extension', v)} />
          }
        >
          {chrome !== null ? (
            <div className={styles.actionsRow}>
              <UseDefault shown disabled={disabled} onClick={() => set('chrome_extension', null)} />
            </div>
          ) : null}
        </Field>
      </FieldStack>
    </>
  );
}
