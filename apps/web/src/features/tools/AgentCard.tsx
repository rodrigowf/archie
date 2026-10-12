/**
 * The inline agent card (PLAN §4.4 item 1; spec 12 §4.8): the Task / Agent / invoke_agent spawn
 * card, upgraded. Header: task-hue tile, agent type, description, elapsed and the agent's status
 * (from the `Subagent`, so a background agent whose spawn card completed at launch still shows
 * running). Under it, always visible: the live activity line ("Grep · 3 tools · 12k tokens") and
 * **Open**, which shows the agent's own tab. Expanded: the prompt and the agent's report. The
 * child transcript is never rendered inline.
 *
 * Without a host session (gallery, tests, a view without subagent support) the card shows the spawn
 * card's own status and no Open action.
 */
import { memo, useId, useMemo, useState } from 'react';
import type { SubagentStatus, ToolBlock } from '@/protocol';
import {
  SUBAGENT_STATUS_TEXT,
  SubagentStatusGlyph,
  activityLine,
  formatElapsed,
  showSubagentTab,
  subagentElapsedMs,
  subagentTitle,
  useNow,
  useSubagentInfo,
} from '@/features/subagents/card';
import { Button } from '@/ui/controls';
import { Icon, VisuallyHidden, cx } from '@/ui/primitives';
import { formatClock, str } from './format';
import { resolveTool } from './registry';
import { TaskBody } from './renderers/tasks';
import { useToolTiming } from './timing';
import type { ToolCardProps } from './ToolCard';
import styles from './ToolCard.module.css';

const FROM_BLOCK: Readonly<Record<ToolBlock['status'], SubagentStatus>> = {
  running: 'running',
  done: 'done',
  error: 'failed',
  no_result: 'stopped',
};

const TO_BLOCK: Readonly<Record<SubagentStatus, ToolBlock['status']>> = {
  running: 'running',
  done: 'done',
  failed: 'error',
  stopped: 'no_result',
};

function AgentCardImpl({ block, sessionKind = 'agent', className }: ToolCardProps) {
  const resolved = resolveTool(block, sessionKind);
  const { input, spec, name } = resolved;
  const found = useSubagentInfo(block.tool_use_id);
  const info = found ? found.info : null;
  const status: SubagentStatus = info ? info.status : FROM_BLOCK[block.status];
  const type = (info && info.agent_type) || str(input, 'subagent_type') || 'Agent';
  const description = (info && info.description) || str(input, 'description') || '';
  const now = useNow(status === 'running' && !!info && info.started_at !== null);
  const timing = useToolTiming(block);
  let time: string | null = null;
  const elapsed = info ? subagentElapsedMs(info, now) : null;
  if (elapsed !== null) time = formatElapsed(elapsed);
  else if (status === 'running' && timing.runningSeconds !== null && timing.runningSeconds >= 1) time = formatClock(timing.runningSeconds);

  const line = info ? activityLine(info) : status === 'running' ? 'Working' : SUBAGENT_STATUS_TEXT[status];
  // The report: the agent's own summary when the protocol has one (a background agent's spawn card
  // only says "launched"), else the spawn card's result.
  const report = info && info.summary ? info.summary : block.output;
  const reportBlock = useMemo<ToolBlock>(
    () => (report === block.output && TO_BLOCK[status] === block.status ? block : { ...block, output: report, status: TO_BLOCK[status] }),
    [block, report, status],
  );

  const [open, setOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  if (open && !mounted) setMounted(true);
  const id = useId();
  const bodyId = `ac${id}`;
  const title = subagentTitle({ agent_type: type, description });

  return (
    <div
      className={cx(styles.card, styles.kTask, styles.agentCard, className)}
      data-tool={name}
      data-category="task"
      data-status={reportBlock.status}
      data-agent-status={status}
    >
      <button
        type="button"
        className={cx(styles.header, 'has-state-layer')}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen(!open)}
      >
        <span className={styles.tile} aria-hidden="true">
          <Icon name="smart_toy" size={18} />
        </span>
        <span className={styles.name}>{type}</span>
        <span className={styles.agentDesc} title={description || undefined}>
          {description}
        </span>
        <span className={styles.status}>
          {time ? <span className={styles.tabular}>{time}</span> : null}
          <SubagentStatusGlyph status={status} />
          <VisuallyHidden>{`, ${SUBAGENT_STATUS_TEXT[status].toLowerCase()}`}</VisuallyHidden>
        </span>
      </button>
      <div className={styles.agentLine}>
        <span className={styles.agentActivity} data-agent-activity="">
          {line}
        </span>
        {found ? (
          <Button
            variant="text"
            size="small"
            icon="open_in_new"
            className={styles.agentOpen}
            aria-label={`Open ${title}`}
            onClick={() => showSubagentTab(found.host, block.tool_use_id)}
          >
            Open
          </Button>
        ) : null}
      </div>
      <div id={bodyId} className={styles.body} hidden={!open}>
        {mounted || open ? (
          <TaskBody
            block={reportBlock}
            input={input}
            name={name}
            out={{ runningSeconds: timing.runningSeconds, runningText: status === 'running' ? 'The agent is working…' : (spec.runningText ?? 'Running…') }}
          />
        ) : null}
      </div>
    </div>
  );
}

export const AgentCard = memo(AgentCardImpl);
