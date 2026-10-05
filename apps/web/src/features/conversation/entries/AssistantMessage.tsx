/**
 * An assistant run (IA §6, mockups `.a`): full-width prose with its blocks **in arrival order**
 * (spec 12 I-3; R4): text above a tool call stays above it, text after a result stays below it.
 * Consecutive tool calls form an "N steps" group (`groupToolSteps`, IA §9.3), expanded while live
 * and collapsed once text follows; a device pref turns grouping off.
 *
 * A solo tool card gets `autoOpen` while it is the live tail of a turn in flight (W-10 contract).
 * Tool cards bind to their `ToolBlock` by identity, so results update them in place wherever the
 * run is in the timeline (R-1, TC-1; R7).
 */
import { memo, useMemo, type ReactNode } from 'react';
import { groupToolSteps, type AssistantEntry, type Block, type SessionKind, type ToolBlock } from '@/protocol';
import { StepGroup, ToolCard } from '@/features/tools';
import styles from '../Conversation.module.css';
import { PermissionBlockView, TextBlockView, ThinkingBlockView } from './blocks';
import { VisualCard, visualFromTool } from './VisualCard';

export interface AssistantMessageProps {
  readonly entry: AssistantEntry;
  /** Tail run of a turn in flight (`isRunLive`). */
  readonly live: boolean;
  readonly sessionKind: SessionKind;
  /** Device pref "tool step grouping". */
  readonly grouping: boolean;
  /** `stall.last_tool_use_id` while the view is stalled. */
  readonly stalledToolUseId: string | null;
  readonly actions?: ReactNode;
}

function visualsOf(blocks: readonly ToolBlock[]): ReactNode[] {
  const out: ReactNode[] = [];
  for (const b of blocks) {
    const v = visualFromTool(b);
    if (v) out.push(<VisualCard key={`viz-${b.id}`} visual={v} />);
  }
  return out;
}

function renderBlock(b: Block, p: AssistantMessageProps, isTail: boolean): ReactNode {
  switch (b.type) {
    case 'text':
      return <TextBlockView key={b.id} block={b} />;
    case 'thinking':
      return <ThinkingBlockView key={b.id} block={b} />;
    case 'permission':
      return <PermissionBlockView key={b.id} block={b} sessionKind={p.sessionKind} />;
    case 'tool':
      return [
        <ToolCard
          key={b.id}
          block={b}
          sessionKind={p.sessionKind}
          autoOpen={p.live && isTail ? true : undefined}
          stalled={p.stalledToolUseId !== null && p.stalledToolUseId === b.tool_use_id}
        />,
        ...visualsOf([b]),
      ];
  }
}

function AssistantMessageImpl(p: AssistantMessageProps) {
  const { entry, live, grouping } = p;
  const items = useMemo(() => groupToolSteps(entry.blocks, { live, enabled: grouping }), [entry.blocks, live, grouping]);
  const lastBlock = entry.blocks[entry.blocks.length - 1];
  return (
    <div className={styles.assistantRow}>
      {p.actions}
      <div className={styles.run}>
        {items.map((item) =>
          item.kind === 'steps'
            ? [
                <StepGroup key={item.key} blocks={item.blocks} live={item.live} sessionKind={p.sessionKind} stalledToolUseId={p.stalledToolUseId} />,
                ...visualsOf(item.blocks),
              ]
            : renderBlock(item.block, p, item.block === lastBlock),
        )}
      </div>
    </div>
  );
}

export const AssistantMessage = memo(AssistantMessageImpl);
