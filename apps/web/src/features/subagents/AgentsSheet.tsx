/**
 * The agents list (PLAN §4.4 item 2): a side sheet on Medium / Expanded, a bottom sheet on Compact
 * (the SessionSettingsSheet pattern). Grouped Running · Done · Failed / stopped (a "Needs input"
 * group is reserved for when the protocol reports it); nested agents indented under their parent
 * (tree order inside each group); more than three finished agents in a group collapse into "N more".
 * A row opens that agent's tab; Stop (SA-9) on running rows; "Close finished" hides finished tabs.
 * Lazy chunk (spec 13 §5.4).
 */
import { useMemo, useState } from 'react';
import type { Subagent } from '@/protocol';
import { useSession, useShallow } from '@/stores';
import { Button, IconButton } from '@/ui/controls';
import { BottomSheet, SideSheet } from '@/ui/overlays';
import { Icon, cx } from '@/ui/primitives';
import { activityLine, formatElapsed, subagentElapsedMs, subagentLabel, subagentType, treeOrder } from './labels';
import { canStop, closeFinishedSubagentTabs, isFinished, showSubagentTab, stopSubagent } from './nav';
import { SubagentStatusGlyph } from './SubagentStatusGlyph';
import { useCompact } from './useCompact';
import { useNow } from './useNow';
import styles from './SubagentView.module.css';

export interface AgentsSheetProps {
  readonly localId: string;
  readonly open: boolean;
  readonly onClose: () => void;
  /** Gallery: render in place. */
  readonly inline?: boolean;
  /** Gallery / tests: force the sheet kind. */
  readonly kind?: 'side' | 'bottom';
}

/** Finished agents shown per group before "N more". */
export const FINISHED_SHOWN = 3;

type Row = { sub: Subagent; depth: number };

interface Group {
  readonly key: 'running' | 'done' | 'failed';
  readonly title: string;
  readonly rows: Row[];
}

export function groupAgents(list: readonly Subagent[]): Group[] {
  const ordered = treeOrder(list);
  const pick = (pred: (s: Subagent) => boolean): Row[] => ordered.filter((r) => pred(r.sub));
  const groups: Group[] = [
    { key: 'running', title: 'Running', rows: pick((s) => s.status === 'running') },
    { key: 'done', title: 'Done', rows: pick((s) => s.status === 'done') },
    { key: 'failed', title: 'Failed or stopped', rows: pick((s) => s.status === 'failed' || s.status === 'stopped') },
  ];
  return groups.filter((g) => g.rows.length > 0);
}

function AgentRow({ localId, row, now, onOpen }: { localId: string; row: Row; now: number; onOpen: (id: string) => void }) {
  const s = row.sub;
  const elapsed = subagentElapsedMs(s, now);
  const stoppable = canStop(localId, s);
  return (
    <li className={styles.row} style={row.depth ? { paddingLeft: `${8 + Math.min(row.depth, 4) * 20}px` } : undefined} data-status={s.status}>
      <button type="button" className={cx(styles.rowMain, 'has-state-layer')} onClick={() => onOpen(s.tool_use_id)}>
        {row.depth ? (
          <span className={styles.nest} aria-hidden="true">
            ↳
          </span>
        ) : null}
        <span className={styles.rowTile} aria-hidden="true">
          <Icon name="smart_toy" size={18} />
        </span>
        <span className={styles.rowText}>
          <span className={styles.rowHead}>
            <span className={styles.rowType}>{subagentType(s)}</span>
            <span className={styles.rowDesc}>{s.description || ''}</span>
          </span>
          <span className={styles.rowLine}>{activityLine(s)}</span>
        </span>
        <span className={styles.rowMeta}>
          {elapsed !== null ? <span className={styles.tabular}>{formatElapsed(elapsed)}</span> : null}
          <SubagentStatusGlyph status={s.status} />
        </span>
      </button>
      {stoppable ? (
        <IconButton icon="stop" size="small" aria-label={`Stop ${subagentLabel(s)}`} onClick={() => stopSubagent(localId, s.tool_use_id)} />
      ) : null}
    </li>
  );
}

function GroupView({ localId, group, now, onOpen }: { localId: string; group: Group; now: number; onOpen: (id: string) => void }) {
  const [expanded, setExpanded] = useState(false);
  const finished = group.key !== 'running';
  const rows = finished && !expanded && group.rows.length > FINISHED_SHOWN ? group.rows.slice(-FINISHED_SHOWN) : group.rows;
  const more = group.rows.length - rows.length;
  const id = `agents-${group.key}`;
  return (
    <section className={styles.group} aria-labelledby={id}>
      <h3 id={id} className={styles.groupTitle}>
        {group.title}
        <span className={styles.groupCount}>{group.rows.length}</span>
      </h3>
      {/* eslint-disable-next-line jsx-a11y/no-redundant-roles -- Safari/VoiceOver drops list semantics without it */}
      <ul className={styles.rows} role="list">
        {rows.map((r) => (
          <AgentRow key={r.sub.tool_use_id} localId={localId} row={r} now={now} onOpen={onOpen} />
        ))}
      </ul>
      {more > 0 ? (
        <Button variant="text" size="small" icon="keyboard_arrow_down" onClick={() => setExpanded(true)}>
          {`${more} more`}
        </Button>
      ) : null}
    </section>
  );
}

export function AgentsList({ localId, onOpen }: { localId: string; onOpen: (id: string) => void }) {
  const subagents = useSession(localId, (s) => s.conv.subagents);
  const groups = useMemo(() => groupAgents(subagents), [subagents]);
  const now = useNow(subagents.some((s) => s.status === 'running'));
  if (groups.length === 0) return <p className={styles.emptyList}>No agents in this session yet.</p>;
  return (
    <div className={styles.list}>
      {groups.map((g) => (
        <GroupView key={g.key} localId={localId} group={g} now={now} onOpen={onOpen} />
      ))}
    </div>
  );
}

export default function AgentsSheet({ localId, open, onClose, inline, kind }: AgentsSheetProps) {
  const compact = useCompact();
  const bottom = kind ? kind === 'bottom' : compact;
  const { finished, readOnly } = useSession(
    localId,
    useShallow((s) => ({ finished: s.conv.subagents.some(isFinished), readOnly: s.readOnly })),
  );
  const openAgent = (id: string): void => {
    showSubagentTab(localId, id);
    onClose();
  };
  const body = <AgentsList localId={localId} onOpen={openAgent} />;
  const footer = (
    <div className={styles.sheetFooter}>
      <Button variant="text" icon="clear_all" disabled={!finished} onClick={() => closeFinishedSubagentTabs(localId)}>
        Close finished
      </Button>
      <Button
        variant="tonal"
        onClick={() => {
          showSubagentTab(localId, null);
          onClose();
        }}
      >
        Main
      </Button>
    </div>
  );
  const title = readOnly ? 'Agents (read only)' : 'Agents';
  if (bottom)
    return (
      <BottomSheet open={open} onClose={onClose} title={title} inline={inline} footer={footer}>
        {body}
      </BottomSheet>
    );
  return (
    <SideSheet open={open} onClose={onClose} title={title} width={380} inline={inline} footer={footer}>
      {body}
    </SideSheet>
  );
}
