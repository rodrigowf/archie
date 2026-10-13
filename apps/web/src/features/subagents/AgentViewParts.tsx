/**
 * The agent tab's chrome (PLAN §4.4 item 4): the breadcrumb (Main › parent › agent, SA-6) above
 * the transcript, and the read-only bar that replaces the composer ("Viewing <agent> · Stop ·
 * Back to Main"). Messaging a subagent is out of scope (v1).
 */
import { memo, useMemo } from 'react';
import { subagentPath, type Subagent } from '@/protocol';
import { useSession } from '@/stores';
import { Button } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import { AgentsButton } from './AgentsButton';
import { SUBAGENT_STATUS_TEXT, activityLine, subagentLabel, subagentTitle } from './labels';
import { canStop, showSubagentTab, stopSubagent } from './nav';
import { SubagentStatusGlyph } from './SubagentStatusGlyph';
import styles from './SubagentView.module.css';

export const AgentBreadcrumb = memo(function AgentBreadcrumb({ localId, toolUseId }: { localId: string; toolUseId: string }) {
  const subagents = useSession(localId, (s) => s.conv.subagents);
  const path = useMemo(() => subagentPath({ subagents }, toolUseId), [subagents, toolUseId]);
  const current = path[path.length - 1];
  if (!current) return null;
  return (
    <nav className={styles.crumbs} aria-label="Agent path">
      <ol className={styles.crumbList}>
        <li className={styles.crumb}>
          <button type="button" className={styles.crumbLink} onClick={() => showSubagentTab(localId, null)}>
            Main
          </button>
        </li>
        {path.slice(0, -1).map((p: Subagent) => (
          <li key={p.tool_use_id} className={styles.crumb}>
            <Icon name="chevron_right" size={16} className={styles.crumbSep} />
            <button type="button" className={styles.crumbLink} onClick={() => showSubagentTab(localId, p.tool_use_id)}>
              {subagentLabel(p)}
            </button>
          </li>
        ))}
        <li className={styles.crumb} aria-current="page">
          <Icon name="chevron_right" size={16} className={styles.crumbSep} />
          <span className={styles.crumbHere}>{subagentLabel(current)}</span>
        </li>
      </ol>
      <span className={styles.crumbStatus}>
        <SubagentStatusGlyph status={current.status} size={16} />
        <span>{current.status === 'running' ? activityLine(current) : SUBAGENT_STATUS_TEXT[current.status]}</span>
      </span>
    </nav>
  );
});

/** Replaces the composer while an agent's tab is shown. */
export const AgentViewBar = memo(function AgentViewBar({ localId, toolUseId }: { localId: string; toolUseId: string }) {
  const sub = useSession(localId, (s) => s.conv.subagents.find((x) => x.tool_use_id === toolUseId) ?? null);
  const readOnly = useSession(localId, (s) => s.readOnly);
  if (!sub) return null;
  const stoppable = !readOnly && canStop(localId, sub);
  return (
    <div className={styles.viewBar} role="region" aria-label="Agent transcript, read only">
      <Icon name="visibility" size={20} className={styles.viewBarIcon} />
      <span className={styles.viewBarText}>
        Viewing <strong>{subagentTitle(sub)}</strong>
      </span>
      <AgentsButton localId={localId} />
      {stoppable ? (
        <Button variant="text" tone="error" icon="stop" onClick={() => stopSubagent(localId, toolUseId)}>
          Stop
        </Button>
      ) : null}
      <Button variant="tonal" icon="arrow_back" onClick={() => showSubagentTab(localId, null)}>
        Back to Main
      </Button>
    </div>
  );
});
