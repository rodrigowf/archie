/**
 * Block views inside an assistant run (spec 12 §2.4): text, thinking, and the permission block.
 * Each is memoized on its block object; the reducer's structural sharing keeps unchanged blocks'
 * identity, so only the streaming block re-renders (spec 13 §5.1 item 2).
 */
import { memo, useState } from 'react';
import type { PermissionBlock, SessionKind, TextBlock, ThinkingBlock } from '@/protocol';
import { Markdown, StreamingMarkdown } from '@/features/markdown';
import { resolveTool } from '@/features/tools';
import { Disclosure } from '@/ui/controls';
import { Icon, Spinner, cx } from '@/ui/primitives';
import styles from '../Conversation.module.css';

export const TextBlockView = memo(function TextBlockView({ block }: { block: TextBlock }) {
  if (!block.text && !block.streaming) return null;
  return (
    <div className={styles.prose} data-block="text" data-scope={block.scope}>
      <StreamingMarkdown source={block.text} streaming={block.streaming} />
    </div>
  );
});

/** Open while it streams; folds once done unless the user toggled it (inv02 F-04, spec 13 §3.6). */
function useStreamingOpen(streaming: boolean): readonly [boolean, (open: boolean) => void] {
  const [manual, setManual] = useState<boolean | null>(null);
  const [prev, setPrev] = useState(streaming);
  if (prev !== streaming) {
    setPrev(streaming);
    if (manual !== null) setManual(null);
  }
  return [manual ?? streaming, setManual] as const;
}

export const ThinkingBlockView = memo(function ThinkingBlockView({ block }: { block: ThinkingBlock }) {
  const [open, setOpen] = useStreamingOpen(block.streaming);
  if (!block.text && !block.streaming) return null;
  return (
    <div data-block="thinking">
      <Disclosure
        className={styles.thinking}
        headerClassName={styles.thinkingHeader}
        bodyClassName={styles.thinkingBody}
        open={open}
        onOpenChange={setOpen}
        icon="star_shine"
        summary={block.streaming ? 'Thinking…' : 'Thought'}
        meta={block.streaming ? <Spinner size={14} /> : undefined}
      >
        {/* Plain text, not markdown (F-04). */}
        <div className={styles.thinkingText}>{block.text}</div>
      </Disclosure>
    </div>
  );
});

const PERM_STATE_CLASS: Record<PermissionBlock['state'], string | undefined> = {
  pending: styles.permPending,
  allowed: styles.permAllowed,
  denied: styles.permDenied,
};

const RESPONDER: Record<string, string> = {
  user: 'you',
  orchestrator: 'Archie',
  system: 'the session',
};

/**
 * A permission request in the timeline (PM-1, PM-2): updated in place when resolved, never a new
 * entry. For ExitPlanMode the plan (`tool_input.plan`) renders here as markdown; the reducer adds
 * no separate text block for it. The Approve / Reject actions are the inline card above the
 * composer (cards/PermissionCard).
 */
export const PermissionBlockView = memo(function PermissionBlockView({ block, sessionKind }: { block: PermissionBlock; sessionKind: SessionKind }) {
  const plan = block.tool_name === 'ExitPlanMode' && typeof block.tool_input.plan === 'string' ? block.tool_input.plan : null;
  let state: string;
  if (block.state === 'pending') state = 'Waiting for your approval';
  else if (block.state === 'allowed') state = block.responder ? `Approved by ${RESPONDER[block.responder] ?? block.responder}` : 'Approved';
  else {
    const who = block.responder ? ` by ${RESPONDER[block.responder] ?? block.responder}` : '';
    state = block.message ? `Rejected${who}: ${block.message}` : `Rejected${who}`;
  }
  const icon = block.state === 'pending' ? 'front_hand' : block.state === 'allowed' ? 'check_circle' : 'close';
  if (plan !== null) {
    return (
      <section className={styles.plan} aria-label="Plan" data-block="permission" data-tool-name={block.tool_name} data-state={block.state}>
        <div className={styles.planHead}>
          <Icon name="checklist" size={18} />
          <span className={styles.planTitle}>Plan</span>
        </div>
        <Markdown source={plan} className={styles.planBody} />
        <div className={cx(styles.permState, PERM_STATE_CLASS[block.state])}>
          <Icon name={icon} size={16} />
          <span>{state}</span>
        </div>
      </section>
    );
  }
  const r = resolveTool(block, sessionKind);
  return (
    <div className={styles.permLine} data-block="permission" data-tool-name={block.tool_name} data-state={block.state}>
      <Icon name="shield" size={18} />
      <span className={styles.permText}>
        <span className={styles.permName}>{r.label}</span>
        {r.summary ? <span className={styles.permSummary}>{r.summary}</span> : null}
      </span>
      <span className={cx(styles.permState, PERM_STATE_CLASS[block.state])}>
        <Icon name={icon} size={16} />
        <span>{state}</span>
      </span>
    </div>
  );
});
