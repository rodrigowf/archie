/**
 * A user prompt (IA §6, mockups `.u`): a tonal bubble on the right, max 80 % wide, plain text with
 * `white-space: pre-wrap` and no markdown (inv02 F-02).
 *
 * - Long prompts fold: more than 25 newline-separated lines show about 10 lines under a fade with
 *   "Show all (N lines)" / "Show less". A single long line never folds.
 *   LOAD-BEARING inv02 F-07 (frontend/src/components/Message.tsx:15-34).
 * - Origins carry a small tag (mockups (d)): speech transcripts "VOICE" ("VOICE · LIVE" with a
 *   caret while a Gemini transcript still grows, spec 12 §4.7), talk-mode audio messages, shared
 *   items. A local inject awaiting its echo is shown as "Sending…".
 */
import { memo, useId, useState, type ReactNode } from 'react';
import type { UserEntry } from '@/protocol';
import { Icon, cx, type IconName } from '@/ui/primitives';
import styles from '../Conversation.module.css';

/** More than this many lines fold (F-07). */
export const FOLD_LINES = 25;

function originTag(entry: UserEntry): { icon: IconName; label: string } | null {
  if (entry.origin === 'voice') return { icon: 'mic', label: entry.streaming ? 'VOICE · LIVE' : 'VOICE' };
  if (entry.origin === 'audio') return { icon: 'graphic_eq', label: 'VOICE MESSAGE' };
  if (entry.origin === 'inject') return { icon: 'attach_file', label: 'SHARED' };
  return null;
}

export interface UserMessageProps {
  readonly entry: UserEntry;
  /** The ⋮ message actions (rewind / fork). */
  readonly actions?: ReactNode;
  /** Queue tray item (I-12): not in the timeline yet. */
  readonly queued?: boolean;
}

function UserMessageImpl({ entry, actions, queued = false }: UserMessageProps) {
  const lines = entry.text.split('\n').length;
  const foldable = lines > FOLD_LINES;
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const tag = originTag(entry);
  const pending = entry.state === 'pending';
  const live = entry.streaming === true;
  return (
    <div className={styles.userRow} data-origin={entry.origin}>
      {actions}
      <div className={cx(styles.bubble, live && styles.bubbleLive, (pending || queued) && styles.bubblePending)}>
        {tag ? (
          <span className={styles.vtag}>
            <Icon name={tag.icon} size={16} />
            <span>{tag.label}</span>
          </span>
        ) : null}
        <div id={`u${id}`} className={cx(styles.userText, foldable && !expanded && styles.folded)}>
          {entry.text}
          {live ? <span className={styles.caret} aria-hidden="true" /> : null}
        </div>
        {foldable ? (
          <button type="button" className={styles.foldToggle} aria-expanded={expanded} aria-controls={`u${id}`} onClick={() => setExpanded(!expanded)}>
            {expanded ? 'Show less' : `Show all (${lines} lines)`}
          </button>
        ) : null}
        {pending ? <span className={styles.bubbleMeta}>Sending…</span> : null}
        {queued ? (
          <span className={styles.bubbleMeta}>
            <Icon name="hourglass_top" size={14} />
            <span>Queued, sends when the reply ends</span>
          </span>
        ) : null}
      </div>
    </div>
  );
}

export const UserMessage = memo(UserMessageImpl);
