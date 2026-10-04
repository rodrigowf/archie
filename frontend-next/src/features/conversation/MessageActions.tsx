/**
 * The per-message ⋮ menu: "Rewind to here" and "Fork from here" (inv02 F-08, spec 13 §3.6).
 *
 * - Hidden until the conversation has an sdk id (spec 12 ID-3: rewind and fork need REST).
 * - The cut is **not** counted from the rendered list: the target entry's id is handed on, and
 *   `drop_last_n` is resolved against a fresh REST listing (`computeDropLastN`, spec 12 §6.5,
 *   fixes inv02 §6.3 #6). This replaces the bottom-relative count of F-08
 *   (frontend/src/components/MessageList.tsx:180-195) with the same "count from the end" anchor.
 * - Rewind is offered only on agent sessions (orchestrator history cannot be truncated in place),
 *   disabled while a reply runs ("Stop the current reply first") and on the last entry (nothing
 *   after it, `n == 0`).
 * - Visible on hover / focus on pointer devices, always on touch (F-08).
 *
 * The confirmation and the busy overlay are a separate host (`MessageActionHost`) so W-11's
 * session-actions can take them over through `ConversationPanel`'s `onMessageAction`.
 */
import { createContext, memo, useContext, useRef, useState } from 'react';
import { IconButton } from '@/ui/controls';
import { Menu, MenuItem } from '@/ui/overlays';
import styles from './Conversation.module.css';

export type MessageActionKind = 'rewind' | 'fork';

export interface MessageActionRequest {
  readonly kind: MessageActionKind;
  readonly localId: string;
  /** The entry the conversation is cut at (kept; everything after it goes). */
  readonly targetId: string;
}

export interface MessageActionsContextValue {
  readonly request: (r: MessageActionRequest) => void;
}

export const MessageActionsContext = createContext<MessageActionsContextValue | null>(null);

export interface MessageActionsProps {
  readonly localId: string;
  readonly targetId: string;
  /** The entry is the last one of the conversation. */
  readonly isLast: boolean;
  readonly canRewind: boolean;
  readonly busy: boolean;
  /** "user" bubbles put the button on their left. */
  readonly side: 'user' | 'assistant';
}

function MessageActionsImpl({ localId, targetId, isLast, canRewind, busy, side }: MessageActionsProps) {
  const ctx = useContext(MessageActionsContext);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  if (!ctx) return null;
  const rewindBlocked = busy ? 'Stop the current reply first' : isLast ? 'Nothing after this message' : undefined;
  return (
    <div className={side === 'user' ? styles.actionsUser : styles.actionsAssistant}>
      <IconButton
        ref={ref}
        icon="more_vert"
        size="small"
        iconSize={20}
        aria-label="Message actions"
        aria-haspopup="menu"
        aria-expanded={open}
        className={styles.actionsButton}
        data-open={open ? '' : undefined}
        onClick={() => {
          setOpen((o) => !o);
        }}
      />
      <Menu
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        anchor={ref}
        placement="bottom-end"
        aria-label="Message actions"
      >
        {canRewind ? (
          <MenuItem
            icon="history"
            disabled={rewindBlocked !== undefined}
            description={rewindBlocked}
            onSelect={() => {
              ctx.request({ kind: 'rewind', localId, targetId });
            }}
          >
            Rewind to here
          </MenuItem>
        ) : null}
        <MenuItem
          icon="call_split"
          onSelect={() => {
            ctx.request({ kind: 'fork', localId, targetId });
          }}
        >
          Fork from here
        </MenuItem>
      </Menu>
    </div>
  );
}

export const MessageActions = memo(MessageActionsImpl);
