/**
 * Default confirmation + execution of the message actions (rewind / fork) for a conversation,
 * until W-11's session-actions takes them over (`ConversationPanel.onMessageAction`).
 *
 * Rewind: confirm with the corrected copy (fixes inv02 §6.2: the old dialog said "reopen from the
 * sidebar" although the view reopens itself), then `rewindSession` (close → truncate → reopen in
 * place, **[LOAD-BEARING]** inv02 F-09 order, implemented in W-06). Fork: `forkSession` opens the
 * copy focused (user-initiated). Both show the busy overlay (F-12) and report failures with the
 * verbatim reason in a snackbar (spec 12 §6.5).
 */
import { useState } from 'react';
import { errorMessage, forkSession, rewindSession } from '@/services';
import { showSnackbar } from '@/stores';
import { BusyOverlay, ConfirmDialog, Portal } from '@/ui/overlays';
import type { MessageActionRequest } from './MessageActions';

export const ACTION_COPY = {
  rewind: {
    title: 'Rewind to here?',
    body: 'Messages after this one will be removed. This cannot be undone.',
    confirm: 'Rewind',
    busy: 'Rewinding…',
  },
  fork: {
    title: 'Fork from here?',
    body: 'A copy of this conversation up to this message opens in a new tab. The original is unchanged.',
    confirm: 'Fork',
    busy: 'Forking…',
  },
} as const;

export interface MessageActionHostProps {
  readonly pending: MessageActionRequest | null;
  readonly onDone: () => void;
}

export function MessageActionHost({ pending, onDone }: MessageActionHostProps) {
  const [running, setRunning] = useState<MessageActionRequest | null>(null);
  const shown = pending && !running ? pending : null;
  const copy = shown ? ACTION_COPY[shown.kind] : null;

  const run = async (r: MessageActionRequest): Promise<void> => {
    setRunning(r);
    onDone();
    try {
      if (r.kind === 'rewind') await rewindSession(r.localId, r.targetId);
      else await forkSession(r.localId, r.targetId);
    } catch (err) {
      showSnackbar(errorMessage(err));
    } finally {
      setRunning(null);
    }
  };

  return (
    <>
      <ConfirmDialog
        open={shown !== null}
        title={copy?.title ?? ''}
        confirmLabel={copy?.confirm ?? 'OK'}
        destructive={shown?.kind === 'rewind'}
        icon={shown?.kind === 'rewind' ? 'history' : 'call_split'}
        onConfirm={() => {
          if (shown) void run(shown);
        }}
        onCancel={() => {
          onDone();
        }}
      >
        {copy?.body}
      </ConfirmDialog>
      {running ? (
        <Portal>
          <BusyOverlay fixed label={ACTION_COPY[running.kind].busy} />
        </Portal>
      ) : null}
    </>
  );
}
