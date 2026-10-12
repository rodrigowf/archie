/**
 * Polite live-region announcements for agent state changes (PLAN §4.4 item 5): started, finished,
 * stopped; failures assertive. Never for streamed content. Subscribes to the session store (which
 * is frozen while the panel is hidden), renders nothing. Agents already known when the view mounts
 * (history, snapshot) are not announced.
 */
import { useEffect } from 'react';
import type { Subagent, SubagentStatus } from '@/protocol';
import { getSessionEntry, useSessionRegistryVersion } from '@/stores';
import { announce } from '@/ui/a11y';
import { statusAnnouncement } from './labels';

export function SubagentAnnouncer({ localId }: { localId: string }) {
  const version = useSessionRegistryVersion();
  useEffect(() => {
    const store = getSessionEntry(localId)?.handle.store;
    if (!store) return undefined;
    const seen = new Map<string, SubagentStatus>();
    for (const s of store.getState().conv.subagents) seen.set(s.tool_use_id, s.status);
    let last: readonly Subagent[] = store.getState().conv.subagents;
    return store.subscribe((st) => {
      const list = st.conv.subagents;
      if (list === last) return;
      last = list;
      if (st.conv.reloading) return;
      for (const s of list) {
        const prev = seen.get(s.tool_use_id);
        seen.set(s.tool_use_id, s.status);
        if (prev === s.status || (prev === undefined && s.status !== 'running')) continue;
        announce(statusAnnouncement(s), s.status === 'failed' ? 'assertive' : 'polite');
      }
    });
  }, [localId, version]);
  return null;
}
