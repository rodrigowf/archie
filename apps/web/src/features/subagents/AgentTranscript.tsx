/**
 * An agent tab's body (PLAN §4.4 item 4; spec 12 §4.8 SA-5/SA-6): the breadcrumb, then the
 * subagent's own conversation in the normal MessageList (same tool cards, step groups, markdown;
 * nested agent cards resolve through the root session, so their Open works here too). The
 * transcript is the `Subagent.conv` of the root session, exposed to the list through a small
 * derived read-only store that follows the root store (frozen while the panel is hidden).
 *
 * States: loading (the first page is in flight, SA-5 `held`), the history error with Retry (the
 * live tail still shows), empty ("No activity recorded").
 */
import { memo, useEffect, useState } from 'react';
import { createStore } from 'zustand/vanilla';
import { initialConversation, type Conversation } from '@/protocol';
import { InlineCard, MessageList } from '@/features/conversation';
import { useSession, type SessionState, type SessionStore } from '@/stores';
import { Button, EmptyState } from '@/ui/controls';
import { Spinner } from '@/ui/primitives';
import { AgentBreadcrumb } from './AgentViewParts';
import { loadOlderSubagent, reloadSubagent } from './nav';
import styles from './SubagentView.module.css';

const blank = new Map<string, Conversation>();
function blankConv(id: string): Conversation {
  let c = blank.get(id);
  if (!c) {
    c = initialConversation({ localId: id, kind: 'agent', subscribed: true });
    if (blank.size > 64) blank.clear();
    blank.set(id, c);
  }
  return c;
}

function derive(root: SessionState, toolUseId: string, prev: SessionState | null): SessionState {
  const id = `${root.localId}/${toolUseId}`;
  const sub = root.conv.subagents.find((s) => s.tool_use_id === toolUseId);
  const conv = sub && sub.conv ? sub.conv : blankConv(id);
  if (prev && prev.conv === conv && prev.hidden === root.hidden && prev.localId === id) return prev;
  return {
    localId: id,
    conv,
    draft: '',
    scrollAnchor: null,
    loadingOlder: false,
    historyError: null,
    readOnly: true,
    modelInfo: null,
    models: null,
    hidden: root.hidden,
    subagentTab: null,
    hiddenSubagentTabs: [],
  };
}

/** A read-only `SessionStore` over one subagent's transcript inside `root`. */
function useSubagentStore(root: SessionStore, toolUseId: string): SessionStore {
  const [store] = useState(() => createStore<SessionState>(() => derive(root.getState(), toolUseId, null)));
  useEffect(() => {
    const sync = (st: SessionState): void => {
      const cur = store.getState();
      const next = derive(st, toolUseId, cur);
      if (next !== cur) store.setState(next, true);
    };
    sync(root.getState());
    return root.subscribe(sync);
  }, [root, toolUseId, store]);
  return store;
}

export interface AgentTranscriptProps {
  /** The root session. */
  readonly localId: string;
  readonly store: SessionStore;
  readonly toolUseId: string;
  readonly hidden: boolean;
}

function AgentTranscriptImpl({ localId, store, toolUseId, hidden }: AgentTranscriptProps) {
  const sub = useSession(localId, (s) => s.conv.subagents.find((x) => x.tool_use_id === toolUseId) ?? null);
  const loading = !!sub && sub.held !== null;
  const error = sub ? sub.loadError : null;
  const finished = !!sub && sub.status !== 'running';
  const derived = useSubagentStore(store, toolUseId);
  const empty = loading ? (
    <div className={styles.loading} role="status">
      <Spinner size={24} />
      <span>Loading the agent’s transcript…</span>
    </div>
  ) : (
    <EmptyState
      icon="smart_toy"
      title={finished ? 'No activity recorded' : 'Waiting for the agent'}
      description={finished ? 'This agent left no transcript.' : 'Its steps appear here as it works.'}
    />
  );
  return (
    <div className={styles.agentBody} data-subagent-view={toolUseId}>
      <AgentBreadcrumb localId={localId} toolUseId={toolUseId} />
      {error ? (
        <div className={styles.agentError}>
          <InlineCard
            tone="error"
            icon="error"
            title="Couldn’t load the agent’s history"
            role="alert"
            actions={
              <Button variant="text" onClick={() => reloadSubagent(localId, toolUseId)}>
                Retry
              </Button>
            }
          >
            {error}
          </InlineCard>
        </div>
      ) : null}
      <MessageList
        localId={`${localId}/${toolUseId}`}
        store={derived}
        hidden={hidden}
        empty={empty}
        variant="subagent"
        onLoadOlder={() => loadOlderSubagent(localId, toolUseId)}
      />
    </div>
  );
}

export const AgentTranscript = memo(AgentTranscriptImpl);
