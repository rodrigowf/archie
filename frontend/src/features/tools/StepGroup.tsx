/**
 * "N steps" group (IA §6, §9.3, approved): consecutive tool calls of one run stack under a pill
 * header with their category tiles, a count, a summary and a status. Expanded while live,
 * collapsed to that one line once text follows; the user can toggle it, and every card inside
 * stays individually expandable. While the group is open its cards open by default (mockups:
 * live group streaming into its cards; a clicked-open group shows its cards' output).
 *
 * Grouping itself (which blocks form a group, and `live`) is `groupToolSteps` in @/protocol.
 */
import { memo, useId } from 'react';
import type { SessionKind, ToolBlock } from '@/protocol';
import { Icon, Spinner, VisuallyHidden, cx } from '@/ui/primitives';
import { CATEGORY_CLASS } from './categoryClass';
import { resolveTool } from './registry';
import { StatusGlyph, ToolCard } from './ToolCard';
import { useAutoOpen } from './useAutoOpen';
import styles from './StepGroup.module.css';

export interface StepGroupProps {
  readonly blocks: readonly ToolBlock[];
  /** The group is the tail of a turn in flight (`StepItem.live`). */
  readonly live: boolean;
  readonly sessionKind?: SessionKind;
  /** `stall.last_tool_use_id`: that card shows the waiting state. */
  readonly stalledToolUseId?: string | null;
  /** Live feeds by tool_use_id (e.g. a delegated agent's activity). */
  readonly feeds?: Readonly<Record<string, readonly string[]>>;
}

/** Group status: running > error > no output > done. */
export function groupStatus(blocks: readonly ToolBlock[]): ToolBlock['status'] {
  if (blocks.some((b) => b.status === 'running')) return 'running';
  if (blocks.some((b) => b.status === 'error')) return 'error';
  if (blocks.some((b) => b.status === 'no_result')) return 'no_result';
  return 'done';
}

/** "Grep, Read ×2": tool labels in first-seen order with repeat counts (mockups). */
export function groupSummary(labels: readonly string[]): string {
  const order: string[] = [];
  const counts = new Map<string, number>();
  for (const l of labels) {
    if (!counts.has(l)) order.push(l);
    counts.set(l, (counts.get(l) ?? 0) + 1);
  }
  return order.map((l) => ((counts.get(l) ?? 1) > 1 ? `${l} ×${counts.get(l) ?? 1}` : l)).join(', ');
}

const MAX_TILES = 4;

function StepGroupImpl({ blocks, live, sessionKind = 'agent', stalledToolUseId = null, feeds }: StepGroupProps) {
  const [open, setOpen] = useAutoOpen(live);
  const id = useId();
  const bodyId = `sg${id}`;
  const resolved = blocks.map((b) => resolveTool(b, sessionKind));
  const status = groupStatus(blocks);
  const running = status === 'running';
  const summary = live && running ? 'running' : groupSummary(resolved.map((r) => r.label));
  const errors = blocks.filter((b) => b.status === 'error').length;

  return (
    <div className={styles.group} data-live={live ? 'true' : 'false'}>
      <button type="button" className={cx(styles.header, 'has-state-layer')} aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen(!open)}>
        <span className={styles.tiles} aria-hidden="true">
          {resolved.slice(0, MAX_TILES).map((r, i) => (
            <span key={i} className={cx(styles.tile, CATEGORY_CLASS[r.spec.category])}>
              <Icon name={r.spec.icon} size={15} />
            </span>
          ))}
          {resolved.length > MAX_TILES ? <span className={cx(styles.tile, styles.more)}>{`+${resolved.length - MAX_TILES}`}</span> : null}
        </span>
        <span className={styles.count}>{`${blocks.length} steps`}</span>
        <span className={styles.summary}>{summary}</span>
        {running ? <Spinner size={14} className={styles.spin} /> : <StatusGlyph status={status} />}
        <VisuallyHidden>{running ? ', running' : errors > 0 ? `, ${errors} failed` : status === 'no_result' ? ', some without output' : ', done'}</VisuallyHidden>
        <Icon name="keyboard_arrow_down" size={20} className={styles.chevron} />
      </button>
      <div id={bodyId} className={styles.body} hidden={!open}>
        {blocks.map((b) => (
          <ToolCard
            key={b.id}
            block={b}
            sessionKind={sessionKind}
            autoOpen={open}
            stalled={stalledToolUseId !== null && b.tool_use_id === stalledToolUseId}
            {...(feeds && feeds[b.tool_use_id] ? { feed: feeds[b.tool_use_id] } : {})}
            className={styles.card}
          />
        ))}
      </div>
    </div>
  );
}

export const StepGroup = memo(StepGroupImpl);
