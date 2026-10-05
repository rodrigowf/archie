/**
 * Shell dialogs: close confirmation and tab rename.
 *
 * Close (**[LOAD-BEARING]** inv02 §1.6, frontend/src/components/ConfirmCloseModal.tsx): a running
 * session asks before closing; Archie always asks, because an explicit close stops it for every
 * device (P-1). Rename (inv02 F-22): the title goes to the server (`PATCH …/rename`), and the tab
 * re-derives it from the refreshed session list (§7 #9).
 */
import { useState, type FormEvent } from 'react';
import { findTab, tabTitle } from '@/stores';
import { displayTabTitle, NEW_CONVERSATION } from './titles';
import { Button, TextField } from '@/ui/controls';
import { ConfirmDialog, Dialog } from '@/ui/overlays';
import { cancelCloseTab, commitRename, confirmCloseTab } from '../shell/actions';
import { setShell, useShell } from '../shell/shellState';
import styles from './workspace.module.css';

function CloseConfirm() {
  const id = useShell((s) => s.confirmCloseId);
  const tab = id ? findTab(id) : undefined;
  const title = tab ? displayTabTitle(tab) : '';
  const archie = tab?.kind === 'archie';
  return (
    <ConfirmDialog
      open={!!tab}
      title={archie ? 'Stop Archie on all devices?' : 'Close this session?'}
      confirmLabel={archie ? 'Stop Archie' : 'Close session'}
      destructive
      onConfirm={confirmCloseTab}
      onCancel={cancelCloseTab}
    >
      {archie
        ? `Closing “${title}” ends the Archie conversation for every device. You can resume it later from the history.`
        : `“${title}” is still working. Closing it stops the current reply for every device.`}
    </ConfirmDialog>
  );
}

function RenameForm({ id, initial }: { id: string; initial: string }) {
  const [value, setValue] = useState(initial);
  const [busy, setBusy] = useState(false);
  const close = (): void => {
    setShell({ renameId: null });
  };
  const submit = (e?: FormEvent): void => {
    e?.preventDefault();
    const t = value.trim();
    if (!t || t === initial) {
      close();
      return;
    }
    setBusy(true);
    void commitRename(id, t).then((ok) => {
      setBusy(false);
      if (ok) close();
    });
  };
  return (
    <Dialog
      open
      onClose={close}
      title="Rename session"
      actions={
        <>
          <Button variant="text" onClick={close}>
            Cancel
          </Button>
          <Button variant="text" loading={busy} disabled={!value.trim()} onClick={() => submit()}>
            Save
          </Button>
        </>
      }
    >
      <form onSubmit={submit} className={styles.renameForm}>
        <TextField label="Title" value={value} onValueChange={setValue} data-autofocus="" autoComplete="off" />
      </form>
    </Dialog>
  );
}

function RenameDialog() {
  const id = useShell((s) => s.renameId);
  const tab = id ? findTab(id) : undefined;
  if (!id || !tab) return null;
  const shown = displayTabTitle(tab);
  // A generic Archie title is not a name to edit: start empty (the user types the first title).
  return <RenameForm key={id} id={id} initial={tab.kind === 'archie' && shown === NEW_CONVERSATION ? '' : tabTitle(tab)} />;
}

export function ShellDialogs() {
  return (
    <>
      <CloseConfirm />
      <RenameDialog />
    </>
  );
}
