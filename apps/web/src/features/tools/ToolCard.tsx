/**
 * One tool call (spec 13 §3.6, IA §6): a single header line (category tile + name + argument +
 * status) and an expandable body (input details + the output region). The header is a real
 * `<button aria-expanded aria-controls>`; the body is its sibling (fixes inv02 §6.2 "button
 * contains div"). Every tool is collapsible, including Task and TodoWrite.
 *
 * R7 / TC-1: the body is bound to the `ToolBlock` by identity, so the status and output update in
 * place when the result arrives, also while expanded; a card collapsed before its result shows it
 * on expand. The body mounts on first open and stays mounted.
 */
import { memo, useId, useState } from 'react';
import type { SessionKind, ToolBlock } from '@/protocol';
import { Icon, Spinner, VisuallyHidden, cx } from '@/ui/primitives';
import { formatClock, formatDuration } from './format';
import { resolveTool, headline } from './registry';
import { CATEGORY_CLASS } from './categoryClass';
import { useToolTiming } from './timing';
import { useAutoOpen } from './useAutoOpen';
import styles from './ToolCard.module.css';

export interface ToolCardProps {
  readonly block: ToolBlock;
  /** Resolves orchestrator tool names before the Qwen mapping (TC-4). Default "agent". */
  readonly sessionKind?: SessionKind;
  /**
   * Open by default while true (the card is in the live tail of a turn, or its step group is
   * open); folds by itself when it turns false. Default: open while the tool runs.
   */
  readonly autoOpen?: boolean;
  /** The conversation's stall names this tool: hourglass instead of the spinner (mockups). */
  readonly stalled?: boolean;
  /** Live lines while running (e.g. the delegated agent's activity for send_to_agent_session). */
  readonly feed?: readonly string[];
  readonly feedTitle?: string;
  readonly className?: string;
}

export const STATUS_TEXT: Record<ToolBlock['status'], string> = {
  running: 'running',
  done: 'done',
  error: 'failed',
  no_result: 'no output',
};

/** The status glyph used by cards and step groups. */
export function StatusGlyph({ status, stalled = false, size = 18 }: { status: ToolBlock['status']; stalled?: boolean; size?: number }) {
  if (status === 'running') {
    return stalled ? (
      <Icon name="hourglass_top" size={size} className={styles.warnIcon} />
    ) : (
      <Spinner size={size <= 16 ? 12 : 14} className={styles.spin} />
    );
  }
  if (status === 'done') return <Icon name="check_circle" size={size} className={styles.okIcon} />;
  if (status === 'error') return <Icon name="error" size={size} className={styles.errIcon} />;
  return <Icon name="more_horiz" size={size} className={styles.neutralIcon} />;
}

function ToolCardImpl({ block, sessionKind = 'agent', autoOpen, stalled = false, feed, feedTitle, className }: ToolCardProps) {
  const resolved = resolveTool(block, sessionKind);
  const { spec, input, label, summary, name } = resolved;
  const auto = spec.defaultOpen === 'always' || (autoOpen ?? block.status === 'running');
  const [open, setOpen] = useAutoOpen(auto);
  // The body mounts on first open and then stays mounted (keeps "Show all" etc.).
  const [mounted, setMounted] = useState(open);
  if (open && !mounted) setMounted(true);
  const id = useId();
  const bodyId = `tc${id}`;
  const timing = useToolTiming(block);
  const { Body, Meta } = spec;

  let time: string | null = null;
  if (block.status === 'running') {
    if (timing.runningSeconds !== null && timing.runningSeconds >= 1) time = formatClock(timing.runningSeconds);
  } else if (timing.durationMs !== null) {
    time = formatDuration(timing.durationMs);
  }

  const defaultFeedTitle = name === 'send_to_agent_session' && typeof input.session_id === 'string' ? `Live · session ${input.session_id.slice(0, 8)}` : undefined;

  return (
    <div
      className={cx(styles.card, CATEGORY_CLASS[spec.category], className)}
      data-tool={name}
      data-category={spec.category}
      data-status={block.status}
    >
      <button
        type="button"
        className={cx(styles.header, 'has-state-layer')}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen(!open)}
      >
        <span className={styles.tile} aria-hidden="true">
          <Icon name={spec.icon} size={18} />
        </span>
        <span className={styles.name}>{label}</span>
        {summary ? (
          <span className={styles.summary} title={headline(resolved)}>
            {summary}
          </span>
        ) : (
          <span className={styles.summary} />
        )}
        <span className={styles.status}>
          {time ? <span className={styles.tabular}>{time}</span> : null}
          {Meta ? <Meta input={input} /> : null}
          <StatusGlyph status={block.status} stalled={stalled} />
          <VisuallyHidden>{`, ${stalled && block.status === 'running' ? 'waiting' : STATUS_TEXT[block.status]}`}</VisuallyHidden>
        </span>
      </button>
      <div id={bodyId} className={styles.body} hidden={!open}>
        {mounted || open ? (
          <Body
            block={block}
            input={input}
            name={name}
            out={{
              runningSeconds: timing.runningSeconds,
              runningText: spec.runningText ?? 'Running…',
              ...(feed ? { feed } : {}),
              ...(feedTitle ?? defaultFeedTitle ? { feedTitle: feedTitle ?? defaultFeedTitle } : {}),
            }}
          />
        ) : null}
      </div>
    </div>
  );
}

export const ToolCard = memo(ToolCardImpl);
