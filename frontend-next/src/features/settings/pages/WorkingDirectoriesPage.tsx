/**
 * Working directories (inv02 F-33, spec 12 CFG-7): pick the active directory, add / edit / remove
 * entries, local or over SSH. `working_directory_history` is a full replacement on the server;
 * local paths must exist there (a 400 "Directory does not exist: …" is shown verbatim). Rows are
 * coerced defensively (**[LOAD-BEARING]** F-33: malformed rows crashed the old panel).
 */
import { useId, useState } from 'react';
import type { WorkingDirectoryEntry } from '@/services';
import { useServerConfig } from '@/stores';
import { Button, IconButton, Radio, RadioGroup, SegmentedButton, TextField } from '@/ui/controls';
import { ConfirmDialog, Dialog } from '@/ui/overlays';
import { Icon } from '@/ui/primitives';
import { saveSetting } from '../controller';
import {
  addWorkingDirectory,
  coerceWorkingDirectories,
  defaultConfigDir,
  deleteWorkingDirectory,
  draftFromEntry,
  editWorkingDirectory,
  EMPTY_DRAFT,
  MAX_WORKING_DIRECTORIES,
  validateDraft,
  workingDirectoryDetail,
  workingDirectoryName,
  type HistoryEdit,
  type WorkingDirectoryDraft,
} from '../logic';
import { Notice } from '../parts';
import { useSaving, WithConfig } from './shared';
import styles from '../settings.module.css';

export function WorkingDirectoriesPage() {
  return (
    <WithConfig>
      {(cfg) => <WorkingDirectoriesForm history={coerceWorkingDirectories(cfg.working_directory_history)} active={cfg.working_directory} />}
    </WithConfig>
  );
}

type Editing = { mode: 'add' } | { mode: 'edit'; entry: WorkingDirectoryEntry } | null;

function save(edit: HistoryEdit): Promise<boolean> {
  return saveSetting({ working_directory_history: edit.history, working_directory: edit.active }, 'working_directory_history').then((c) => c !== null);
}

export interface WorkingDirectoryListProps {
  history: readonly WorkingDirectoryEntry[];
  /** The selected id. */
  value: string;
  onSelect: (id: string) => void;
  disabled?: boolean;
  /** Badge on the selected row (default "Active"). */
  selectedBadge?: string;
  label: string;
  onEdit?: (e: WorkingDirectoryEntry) => void;
  onDelete?: (e: WorkingDirectoryEntry) => void;
}

/** The radio list (also used by Session settings, without edit / delete). */
export function WorkingDirectoryList({ history, value, onSelect, disabled, selectedBadge = 'Active', label, onEdit, onDelete }: WorkingDirectoryListProps) {
  const only = history.length <= 1;
  return (
    <RadioGroup aria-label={label} value={value} onChange={onSelect} disabled={disabled} className={styles.wdList}>
      {history.map((e) => (
        <WorkingDirectoryRow
          key={e.id}
          entry={e}
          selected={e.id === value}
          badge={selectedBadge}
          onlyOne={only}
          {...(onEdit ? { onEdit } : {})}
          {...(onDelete ? { onDelete } : {})}
        />
      ))}
    </RadioGroup>
  );
}

function WorkingDirectoryRow({
  entry,
  selected,
  badge,
  onlyOne,
  onEdit,
  onDelete,
}: {
  entry: WorkingDirectoryEntry;
  selected: boolean;
  badge: string;
  onlyOne: boolean;
  onEdit?: (e: WorkingDirectoryEntry) => void;
  onDelete?: (e: WorkingDirectoryEntry) => void;
}) {
  const id = useId();
  const name = workingDirectoryName(entry);
  return (
    <div className={styles.listRow} data-wd={entry.id}>
      <div className={styles.listRowMain}>
        <Radio id={`${id}-r`} value={entry.id} />
        <label htmlFor={`${id}-r`} className={styles.listRowText}>
          <span className={styles.listRowTitle}>
            <span>{name}</span>
            {entry.ssh_host ? <span className={styles.badge}>SSH</span> : null}
            {selected ? <span className={`${styles.badge} ${styles.badgeActive}`}>{badge}</span> : null}
          </span>
          <span className={styles.listRowSub}>{workingDirectoryDetail(entry)}</span>
        </label>
      </div>
      {onEdit || onDelete ? (
        <span className={styles.listRowActions}>
          {onEdit ? <IconButton icon="edit" size="small" aria-label={`Edit ${name}`} onClick={() => onEdit(entry)} /> : null}
          {onDelete ? (
            <IconButton
              icon="delete"
              size="small"
              aria-label={onlyOne ? `Remove ${name} (the only directory can't be removed)` : `Remove ${name}`}
              title={onlyOne ? "Can't remove the only directory" : undefined}
              disabled={onlyOne}
              onClick={() => onDelete(entry)}
            />
          ) : null}
        </span>
      ) : null}
    </div>
  );
}

function WorkingDirectoriesForm({ history, active }: { history: WorkingDirectoryEntry[]; active: string }) {
  const saving = useSaving();
  const [editing, setEditing] = useState<Editing>(null);
  const [removing, setRemoving] = useState<WorkingDirectoryEntry | null>(null);
  const full = history.length >= MAX_WORKING_DIRECTORIES;
  return (
    <>
      <div className={styles.stackBlock}>
        <h3 className={styles.stackLabel}>New agent sessions start in</h3>
        {history.length ? (
          <WorkingDirectoryList
            label="Active working directory"
            history={history}
            value={active}
            disabled={saving}
            onSelect={(id) => {
              if (id !== active) void saveSetting({ working_directory: id }, 'working_directory');
            }}
            onEdit={(e) => setEditing({ mode: 'edit', entry: e })}
            onDelete={(e) => setRemoving(e)}
          />
        ) : (
          <Notice tone="warning">No directories yet. Add one to start agent sessions.</Notice>
        )}
        <div className={styles.actionsRow}>
          <Button variant="tonal" icon="add" disabled={saving || full} onClick={() => setEditing({ mode: 'add' })}>
            Add directory
          </Button>
        </div>
        <p className={styles.help}>
          {full ? `Up to ${MAX_WORKING_DIRECTORIES} directories. Remove one to add another.` : 'SSH entries run Claude Code on that machine.'}
        </p>
      </div>
      {editing ? (
        <WorkingDirectoryDialog
          key={editing.mode === 'edit' ? editing.entry.id : 'add'}
          editing={editing}
          history={history}
          onClose={() => setEditing(null)}
        />
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        title={`Remove ${removing ? workingDirectoryName(removing) : ''}?`}
        confirmLabel="Remove"
        destructive
        busy={saving}
        onCancel={() => setRemoving(null)}
        onConfirm={() => {
          const target = removing;
          if (!target) return;
          const edit = deleteWorkingDirectory(history, target.id, active);
          if (!edit) {
            setRemoving(null);
            return;
          }
          void save(edit).then(() => setRemoving(null));
        }}
      >
        It leaves this list only; nothing is deleted on disk. Sessions already running keep their directory.
      </ConfirmDialog>
    </>
  );
}

type Touched = Partial<Record<'path' | 'host' | 'user', boolean>>;

function WorkingDirectoryDialog({ editing, history, onClose }: { editing: NonNullable<Editing>; history: WorkingDirectoryEntry[]; onClose: () => void }) {
  const [draft, setDraft] = useState<WorkingDirectoryDraft>(() => (editing.mode === 'edit' ? draftFromEntry(editing.entry) : EMPTY_DRAFT));
  const [touched, setTouched] = useState<Touched>({});
  const [submitted, setSubmitted] = useState(false);
  const saving = useSaving();
  const saveError = useServerConfig((s) => s.saveError);
  const [failed, setFailed] = useState(false);
  const formId = useId();
  const editingId = editing.mode === 'edit' ? editing.entry.id : null;
  const errors = validateDraft(draft, history, editingId);
  const ssh = draft.kind === 'ssh';
  const show = (k: keyof Touched): string | undefined => (submitted || touched[k] ? errors[k] : undefined);
  const set = (patch: Partial<WorkingDirectoryDraft>): void => {
    setDraft((d) => ({ ...d, ...patch }));
    setFailed(false);
  };
  const blur = (k: keyof Touched) => () => setTouched((t) => ({ ...t, [k]: true }));
  const requiredMissing = !draft.path.trim() || (ssh && !draft.host.trim());

  const submit = (): void => {
    setSubmitted(true);
    if (Object.keys(errors).length) return;
    const edit = editing.mode === 'edit' ? editWorkingDirectory(history, editing.entry.id, draft) : addWorkingDirectory(history, draft);
    void save(edit).then((ok) => {
      if (ok) onClose();
      else setFailed(true);
    });
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={editing.mode === 'edit' ? 'Edit directory' : 'Add directory'}
      fullScreen="compact"
      maxWidth={560}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form={formId} variant="text" loading={saving} disabled={requiredMissing}>
            {editing.mode === 'edit' ? 'Save' : 'Add'}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        noValidate
        className={styles.formGrid}
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {/* Enter in a field submits: the visible button lives in the dialog's action bar. */}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
        <SegmentedButton
          aria-label="Where the directory is"
          options={[
            { value: 'local', label: 'On the server', icon: 'dns' },
            { value: 'ssh', label: 'Over SSH', icon: 'terminal' },
          ]}
          value={draft.kind}
          fullWidth
          onChange={(kind) => set({ kind })}
        />
        {ssh ? (
          <div className={styles.formRow}>
            <TextField
              label="SSH host"
              required
              value={draft.host}
              placeholder="192.168.0.200"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              onValueChange={(host) => set({ host })}
              onBlur={blur('host')}
              error={show('host')}
            />
            <TextField
              label="User"
              value={draft.user}
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              onValueChange={(user) => set({ user })}
              onBlur={blur('user')}
              error={show('user')}
              supportingText="Optional"
            />
          </div>
        ) : null}
        <TextField
          label={ssh ? 'Remote path' : 'Path'}
          required
          value={draft.path}
          placeholder="/home/rodrigo/project"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          onValueChange={(path) => set({ path })}
          onBlur={blur('path')}
          error={show('path')}
          supportingText={ssh ? 'On the remote machine.' : 'Must exist on the server.'}
        />
        {ssh ? (
          <>
            <TextField
              label="SSH key"
              value={draft.key}
              placeholder="~/.ssh/id_ed25519"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              onValueChange={(key) => set({ key })}
              supportingText="Optional. A key file on the server."
            />
            <TextField
              label="CLAUDE_CONFIG_DIR on the remote"
              value={draft.configDir}
              placeholder={draft.path.trim() ? defaultConfigDir(draft.path.trim()) : '<path>/.claude_config'}
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              onValueChange={(configDir) => set({ configDir })}
              supportingText="Optional. Defaults to <path>/.claude_config."
            />
          </>
        ) : null}
        <TextField label="Label" value={draft.label} onValueChange={(label) => set({ label })} supportingText="Optional. Shown instead of the path." />
        {failed && saveError ? (
          <p className={styles.help} role="alert">
            <Icon name="error" size={16} /> {saveError}
          </p>
        ) : null}
      </form>
    </Dialog>
  );
}
