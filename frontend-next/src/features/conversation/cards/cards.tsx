/**
 * The inline cards above the composer (IA §6, spec 13 §3.6): permission, agent approvals
 * (orchestrator view), stall, connection / history errors, termination. Each reads only its own
 * slice of the session store.
 */
import { memo, useState } from 'react';
import {
  busy,
  pendingPermission,
  type AgentApproval,
  type ConnectionBanner,
  type PermissionBlock,
  type SessionKind,
  type StallInfo,
  type TerminationInfo,
} from '@/protocol';
import { Markdown } from '@/features/markdown';
import { resolveTool } from '@/features/tools';
import { continueTerminated, getSessionRuntime, respondAgentPermission, SessionRuntime } from '@/services';
import { getSessionEntry, useCatalog, useSession, useShallow } from '@/stores';
import { Button } from '@/ui/controls';
import { InlineCard } from './InlineCard';
import styles from './Cards.module.css';

// ───────────────────────── permission (agent view, §6.9, F-14) ─────────────────────────

/** ExitPlanMode wording (inv02 F-14, frontend/src/components/PermissionBar.tsx). */
export const PLAN_TITLE = 'Exit plan mode and start implementing?';
export const PLAN_HINT = 'Approve to execute the plan above. Reject, or type below to keep planning.';
export const FEEDBACK_HINT = 'Or type below to give feedback';

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

// ───────────────────────── stall (§6.12, F-13) ─────────────────────────

/** `Ns` under 90 s, else `XmYs` (spec 12 §6.12; inv02 F-13, ChatPanel.tsx:9-14). */
export function formatStall(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  if (s < 90) return `${s}s`;
  return `${Math.floor(s / 60)}m${s % 60}s`;
}

/** Provider-neutral copy (fixes "No response from Claude" for Qwen and Gemini, inv02 §6.2). */
export function stallText(stall: StallInfo): { title: string; body: string } {
  const t = formatStall(stall.elapsed_seconds);
  if (stall.last_tool_name) {
    return { title: `${stall.last_tool_name} silent for ${t}`, body: `${stall.last_tool_name} has been running for ${t} with no response.` };
  }
  return { title: `No response for ${t}`, body: `No response from the agent for ${t}.` };
}

/** Shown while `stall` is set and the view is busy. "Keep waiting" hides it until the next stall report. */
export const StallCard = memo(function StallCard({ localId }: { localId: string }) {
  const { stall, isBusy, readOnly } = useSession(
    localId,
    useShallow((s) => ({ stall: s.conv.stall, isBusy: busy(s.conv.status), readOnly: s.readOnly })),
  );
  const [dismissed, setDismissed] = useState<StallInfo | null>(null);
  if (!stall || !isBusy || dismissed === stall) return null;
  const { title, body } = stallText(stall);
  return (
    <InlineCard
      tone="stall"
      icon="hourglass_top"
      role="status"
      title={title}
      actions={
        <>
          <Button variant="text" className={styles.onTone} onClick={() => setDismissed(stall)}>
            Keep waiting
          </Button>
          <Button variant="filled" tone="warning" disabled={readOnly} onClick={() => getSessionRuntime(localId)?.interrupt()}>
            Interrupt
          </Button>
        </>
      }
    >
      {body} Interrupt stops it and lets the agent continue.
    </InlineCard>
  );
});

// ───────────────────────── connection / history errors (§4.4.4, §6.13) ─────────────────────────

/** Human copy for `connectionBanner` codes (never raw codes, W-6.2). */
export function bannerText(b: ConnectionBanner, kind: SessionKind): { title: string; body: string } {
  const who = kind === 'orchestrator' ? 'Archie' : 'the session';
  switch (b.code) {
    case 'disconnected':
      return { title: 'Connection lost', body: 'Reconnecting automatically. What you send is kept until the connection is back.' };
    case 'start_timeout':
      return { title: `Couldn't start ${who}`, body: b.detail || 'The server did not answer in time.' };
    case 'start_failed':
      return { title: `Couldn't start ${who}`, body: b.detail || 'The server could not start it.' };
    case 'orchestrator_active':
      return { title: 'Archie is already running', body: b.detail || 'Another Archie conversation is active. Open it, or stop it to start this one.' };
    case 'orchestrator_stopping':
      return { title: 'Archie is still stopping', body: b.detail || 'Try again in a moment.' };
    default:
      return { title: 'Connection problem', body: b.detail || 'The connection to the server failed.' };
  }
}

function ConnectionErrorCard({ localId, banner, kind, readOnly }: { localId: string; banner: ConnectionBanner; kind: SessionKind; readOnly: boolean }) {
  const [details, setDetails] = useState(false);
  const { title, body } = bannerText(banner, kind);
  return (
    <InlineCard
      tone="error"
      icon="error"
      role="alert"
      title={title}
      onDismiss={() => getSessionRuntime(localId)?.dismissBanner()}
      actions={
        <>
          <Button variant="text" className={styles.onTone} aria-expanded={details} onClick={() => setDetails(!details)}>
            {details ? 'Hide details' : 'Details'}
          </Button>
          {readOnly ? null : (
            <Button variant="filled" tone="error" onClick={() => getSessionRuntime(localId)?.retry()}>
              Retry
            </Button>
          )}
        </>
      }
    >
      {body}
      {details ? <span className={styles.details}>{`Code: ${banner.code}${banner.detail ? ` · ${banner.detail}` : ''}`}</span> : null}
    </InlineCard>
  );
}

/**
 * Transport and start errors (`connectionBanner`, never a timeline entry, I-15) with Retry
 * (reconnect now / restart the handshake, §6.13) and dismiss; history load failures quote the
 * server's detail verbatim with Retry. "disconnected" is shown as the "Connection lost at …" line
 * at the end of the list instead (mockups (k)).
 */
export const ErrorCards = memo(function ErrorCards({ localId }: { localId: string }) {
  const { banner, kind, historyError, readOnly } = useSession(
    localId,
    useShallow((s) => ({ banner: s.conv.connectionBanner, kind: s.conv.ref.kind, historyError: s.historyError, readOnly: s.readOnly })),
  );
  return (
    <>
      {banner && banner.code !== 'disconnected' ? <ConnectionErrorCard localId={localId} banner={banner} kind={kind} readOnly={readOnly} /> : null}
      {historyError ? (
        <InlineCard
          tone="error"
          icon="error"
          role="alert"
          title="Couldn't load the conversation"
          onDismiss={() => getSessionEntry(localId)?.handle.patch({ historyError: null })}
          actions={
            <Button variant="filled" tone="error" onClick={() => void getSessionRuntime(localId)?.reload()}>
              Retry
            </Button>
          }
        >
          {historyError}
        </InlineCard>
      ) : null}
    </>
  );
});

// ───────────────────────── termination (§6.13, F-15) ─────────────────────────

/** Headline by reason (inv02 F-15, ChatPanel.tsx:21-34). */
export function terminationHeadline(t: TerminationInfo): string {
  switch (t.reason) {
    case 'subprocess_crashed':
      return 'This session crashed';
    case 'subprocess_lost':
      return 'The session ended unexpectedly';
    case 'unreachable':
      return 'The host is unreachable';
    case 'replaced':
      return 'This session was replaced';
    case 'closed_by_user':
      return 'This session was closed';
    default:
      return 'Session ended';
  }
}

/**
 * The view stays open with this card (W-9: the old tab closed right after the banner, inv02
 * §6.3 #9). "Continue in new session" replaces the view in place: new local id, the same sdk id,
 * the same kind, canonical cold open (§6.13, A-8.5).
 */
export const TerminationCard = memo(function TerminationCard({ localId }: { localId: string }) {
  const term = useSession(localId, (s) => s.conv.termination);
  if (!term) return null;
  return (
    <InlineCard
      tone="ended"
      icon="logout"
      role="status"
      title={terminationHeadline(term)}
      actions={
        <Button variant="tonal" disabled={!term.sdk_session_id} onClick={() => continueTerminated(localId)}>
          Continue in new session
        </Button>
      }
    >
      <span className={styles.muted}>{term.detail || 'The agent process is no longer running. The conversation is kept.'}</span>
    </InlineCard>
  );
});
