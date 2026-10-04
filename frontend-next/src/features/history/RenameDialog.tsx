/**
 * Title edit dialog shared by the history list and the Visuals list (inv02 F-19 / F-36 inline
 * rename, as a dialog so it works the same with touch, mouse and keyboard). Enter or Save
 * commits a changed, non-empty title; Cancel / Escape / Back leave it unchanged. The commit
 * promise resolves `false` to keep the dialog open (the caller has shown the server error).
 */
import { useState, type FormEvent } from 'react';
import { Button, TextField } from '@/ui/controls';
import { Dialog } from '@/ui/overlays';
import styles from './history.module.css';

export interface RenameDialogProps {
  readonly title: string;
  readonly initial: string;
  readonly onCommit: (value: string) => Promise<boolean>;
  readonly onClose: () => void;
}

export function RenameDialog({ title, initial, onCommit, onClose }: RenameDialogProps) {
  const [value, setValue] = useState(initial);
  const [busy, setBusy] = useState(false);
  const submit = (e?: FormEvent): void => {
    e?.preventDefault();
    const t = value.trim();
    if (!t || t === initial.trim()) {
      onClose();
      return;
    }
    setBusy(true);
    void onCommit(t).then((ok) => {
      setBusy(false);
      if (ok) onClose();
    });
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={title}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
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
