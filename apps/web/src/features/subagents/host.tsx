/**
 * The session whose subagents the inline agent cards look up (spec 12 SA-6: routing is flat, so a
 * nested card inside an agent's transcript still resolves in the root session's map). Provided by
 * the conversation panel; without a provider (gallery, tests) a Task card renders as a plain card.
 */
import { createContext, useContext } from 'react';
import { findSubagent, type Subagent } from '@/protocol';
import { useSession, useShallow } from '@/stores';

export const SubagentHostContext = createContext<string | null>(null);

export type SubagentCardInfo = Pick<
  Subagent,
  'tool_use_id' | 'agent_type' | 'description' | 'background' | 'status' | 'activity' | 'usage' | 'summary' | 'started_at' | 'ended_at' | 'lifecycle'
>;

const NONE: Record<string, never> = {};

/** The host session id (null outside a conversation panel). */
export function useSubagentHost(): string | null {
  return useContext(SubagentHostContext);
}

/** The summary fields of one subagent of the host session, or null (no host / unknown id). */
export function useSubagentInfo(toolUseId: string): { host: string; info: SubagentCardInfo } | null {
  const host = useSubagentHost();
  const info = useSession(
    host ?? '',
    useShallow((st): SubagentCardInfo | Record<string, never> => {
      const s = host && toolUseId ? findSubagent(st.conv, toolUseId) : null;
      if (!s) return NONE;
      return {
        tool_use_id: s.tool_use_id,
        agent_type: s.agent_type,
        description: s.description,
        background: s.background,
        status: s.status,
        activity: s.activity,
        usage: s.usage,
        summary: s.summary,
        started_at: s.started_at,
        ended_at: s.ended_at,
        lifecycle: s.lifecycle,
      };
    }),
  );
  if (!host || !('tool_use_id' in info)) return null;
  return { host, info: info as SubagentCardInfo };
}
