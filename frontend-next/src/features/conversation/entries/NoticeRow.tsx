/**
 * Notice entries (spec 12 §2.4 `NoticeEntry`): system lines in the timeline, never bubbles.
 *
 * - `compaction` → the compaction divider: "Context compacted" (+ token counts for Archie); the
 *   agent's summary expands in place as plain text (inv02 F-10 CompactDivider).
 * - `background` → the background-update divider (spec 12 BG-1): the reply below was not prompted
 *   from this view. A CLI `<task-notification>` line (BG-2) can be expanded.
 * - `interrupted` → a system line.
 * - `error` → a turn error, quoting the server text.
 * - `command` → a CLI slash-command echo, monospace.
 */
import { memo, useId, useState } from 'react';
import type { NoticeEntry } from '@/protocol';
import { Icon, cx, type IconName } from '@/ui/primitives';
import styles from '../Conversation.module.css';

function tokens(n: unknown): string | null {
  if (typeof n !== 'number' || !isFinite(n)) return null;
  return n >= 1000 ? `${Math.round(n / 1000)}k` : String(n);
}

function Divider({ icon, label, sub, detail }: { icon: IconName; label: string; sub?: string; detail?: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const pill = detail ? (
    <button type="button" className={cx(styles.dividerPill, styles.dividerButton)} aria-expanded={open} aria-controls={`n${id}`} onClick={() => setOpen(!open)}>
      <Icon name={icon} size={16} />
      <span>{label}</span>
      <Icon name={open ? 'keyboard_arrow_up' : 'keyboard_arrow_down'} size={16} />
    </button>
  ) : (
    <span className={styles.dividerPill}>
      <Icon name={icon} size={16} />
      <span>{label}</span>
    </span>
  );
  return (
    <div className={styles.divider}>
      <div className={styles.dividerLine}>{pill}</div>
      {sub ? <p className={styles.dividerSub}>{sub}</p> : null}
      {detail ? (
        <div id={`n${id}`} className={styles.dividerDetail} hidden={!open}>
          {detail}
        </div>
      ) : null}
    </div>
  );
}

function NoticeRowImpl({ entry }: { entry: NoticeEntry }) {
  switch (entry.notice) {
    case 'compaction': {
      const before = tokens(entry.data?.tokens_before);
      const after = tokens(entry.data?.tokens_after);
      const label = before && after ? `Context compacted · ${before} → ${after} tokens` : 'Context compacted';
      return <Divider icon="compress" label={label} detail={entry.text || undefined} />;
    }
    case 'background':
      return (
        <Divider
          icon="bolt"
          label="Background update"
          sub="The orchestrator replied to a background task or to another device."
          detail={entry.text || undefined}
        />
      );
    case 'interrupted':
      return (
        <div className={styles.sysRow}>
          <span className={styles.sysline} role="note">
            <Icon name="stop" size={16} />
            <span>Interrupted</span>
          </span>
        </div>
      );
    case 'error':
      return (
        <div className={styles.errorNotice} role="note">
          <Icon name="error" size={18} />
          <span className={styles.errorNoticeText}>{entry.text || 'The reply failed.'}</span>
        </div>
      );
    case 'command':
      return (
        <div className={styles.commandNotice}>
          <Icon name="terminal" size={16} />
          <span>{entry.text}</span>
        </div>
      );
  }
}

export const NoticeRow = memo(NoticeRowImpl);
