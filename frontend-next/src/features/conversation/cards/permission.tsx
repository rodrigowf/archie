/**
 * Permission and agent-approval cards (spec 12 §6.9, PM-3, PM-5). Part of the lazily loaded
 * "rich" chunk (../rich.tsx): they need the tool registry and markdown.
 */
import { memo, useState } from 'react';
import { pendingPermission, type AgentApproval, type PermissionBlock, type SessionKind } from '@/protocol';
import { Markdown } from '@/features/markdown';
import { resolveTool } from '@/features/tools';
import { getSessionRuntime, respondAgentPermission, SessionRuntime } from '@/services';
import { useCatalog, useSession } from '@/stores';
import { Button } from '@/ui/controls';
import { InlineCard } from './InlineCard';
import { FEEDBACK_HINT, PLAN_HINT, PLAN_TITLE } from './copy';
import styles from './Cards.module.css';

// ───────────────────────── permission (agent view, §6.9, F-14) ─────────────────────────

export function permissionTitle(p: Pick<PermissionBlock, 'tool_name' | 'tool_input'>, kind: SessionKind): { title: string; summary: string } {
  if (p.tool_name === 'ExitPlanMode') return { title: PLAN_TITLE, summary: '' };
  const r = resolveTool(p, kind);
  return { title: `Allow ${r.label}?`, summary: r.summary };
}

/**
 * PM-3: shows the newest pending permission; closes only when **that** request leaves `pending`
 * (by anyone, end of turn, stop), never on another `request_id` (**[LOAD-BEARING]** inv02 F-14
 * request_id matching). Buttons disable after one click (§6.9). Typing a message is the third
 * answer: the server turns it into deny-with-feedback (api/routes/chat.py:164-174), so the hint
 * points at the composer.
 */
export const PermissionCard = memo(function PermissionCard({ localId }: { localId: string }) {
  const perm = useSession(localId, (s) => pendingPermission(s.conv));
  const kind = useSession(localId, (s) => s.conv.ref.kind);
  const readOnly = useSession(localId, (s) => s.readOnly);
  const [answered, setAnswered] = useState<string | null>(null);
  if (!perm) return null;
  const sent = answered === perm.request_id;
  const answer = (decision: 'allow' | 'deny'): void => {
    const rt = getSessionRuntime(localId);
    if (rt instanceof SessionRuntime && rt.respondPermission(perm.request_id, decision)) setAnswered(perm.request_id);
  };
  const { title, summary } = permissionTitle(perm, kind);
  const plan = perm.tool_name === 'ExitPlanMode';
  return (
    <InlineCard
      tone="permission"
      icon="shield"
      role="region"
      aria-label="Permission request"
      title={title}
      hint={plan ? undefined : FEEDBACK_HINT}
      actions={
        <>
          <Button variant="outlined" className={styles.onTone} disabled={sent || readOnly} onClick={() => answer('deny')}>
            Reject
          </Button>
          <Button variant="filled" disabled={sent || readOnly} onClick={() => answer('allow')}>
            Approve
          </Button>
        </>
      }
    >
      {plan ? PLAN_HINT : summary ? <code className={styles.code}>{summary}</code> : null}
    </InlineCard>
  );
});

// ───────────────────────── agent approvals (orchestrator view, PM-5) ─────────────────────────

function useAgentTitle(agentLocalId: string): string {
  return useCatalog((c) => c.sessions.items.find((s) => s.local_id === agentLocalId)?.title ?? 'An agent session');
}

const AgentApprovalCard = memo(function AgentApprovalCard({ approval }: { approval: AgentApproval }) {
  const title = useAgentTitle(approval.localId);
  const [sent, setSent] = useState(false);
  const plan = approval.tool_name === 'ExitPlanMode' && typeof approval.tool_input.plan === 'string' ? approval.tool_input.plan : null;
  const r = plan === null ? resolveTool(approval, 'agent') : null;
  const answer = (decision: 'allow' | 'deny'): void => {
    respondAgentPermission(approval.localId, approval.request_id, decision);
    setSent(true);
  };
  return (
    <InlineCard
      tone="permission"
      icon="shield"
      role="region"
      aria-label={`Permission request from ${title}`}
      title={plan !== null ? `${title} wants to start` : `${title} wants to use ${r?.label ?? approval.tool_name}`}
      hint="Or tell Archie below"
      actions={
        <>
          <Button variant="outlined" className={styles.onTone} disabled={sent} onClick={() => answer('deny')}>
            Reject
          </Button>
          <Button variant="filled" disabled={sent} onClick={() => answer('allow')}>
            Approve
          </Button>
        </>
      }
    >
      {plan !== null ? (
        <>
          <p className={styles.lead}>The agent finished planning and asks to exit plan mode:</p>
          <div className={styles.planScroll}>
            <Markdown source={plan} className={styles.planMd} />
          </div>
        </>
      ) : r?.summary ? (
        <code className={styles.code}>{r.summary}</code>
      ) : null}
    </InlineCard>
  );
});

/** §6.9: the "Agent approvals" of an orchestrator view, newest last (nearest the composer). */
export const AgentApprovalCards = memo(function AgentApprovalCards({ localId }: { localId: string }) {
  const approvals = useSession(localId, (s) => s.conv.agentApprovals);
  if (approvals.length === 0) return null;
  return (
    <>
      {approvals.map((a) => (
        <AgentApprovalCard key={`${a.localId}:${a.request_id}`} approval={a} />
      ))}
    </>
  );
});
