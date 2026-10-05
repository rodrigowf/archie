/**
 * What the shell shows about a tab: kind icon, provider chip, ONE status indicator and status
 * text (spec 13 §4.2: spinner working, dot idle, hand waiting, warning disconnected; connection
 * state is styled, fixing inv02 §6.1), plus the counters for the status tooltip.
 *
 * Status for every tab comes from W-06's live status store (`useTabLiveStatus`), which keeps
 * updating while a panel is hidden (spec 13 §4.4 frozen snapshots). The active tab adds detail
 * from its (visible, so live) session store: the running tool's name ("Using Bash…") and the
 * turns · cost · context counters.
 */
import { useMemo } from 'react';
import { useStore } from 'zustand';
import { contextUsage, type Conversation, type Provider } from '@/protocol';
import { deriveLiveStatus, getLiveStatus, liveStatusStore, useSession, useShallow, type Tab, type TabLiveStatus } from '@/stores';
import type { IconName } from '@/ui/icons';

export type TabIndicator = 'idle' | 'working' | 'warning' | 'disconnected' | 'off';

export interface TabSummary {
  readonly indicator: TabIndicator;
  /** "Ready", "Thinking…", "Using Bash…", "Waiting for approval", "Disconnected"… */
  readonly label: string;
  readonly busy: boolean;
  readonly turns: number;
  readonly cost: number;
  readonly contextPercent: number | null;
  readonly readOnly: boolean;
}

export const PROVIDER_LABEL: Record<Provider, string> = { claude: 'Claude', qwen: 'Qwen', gemini: 'Gemini' };

export function kindIcon(kind: Tab['kind']): IconName {
  switch (kind) {
    case 'memory':
      return 'description';
    case 'visual':
      return 'bar_chart';
    default:
      return 'terminal';
  }
}

interface Detail {
  readonly tool: string | null;
  readonly turns: number;
  readonly cost: number;
  readonly contextPercent: number | null;
}

const NO_DETAIL: Detail = { tool: null, turns: 0, cost: 0, contextPercent: null };

/** The tool currently running in the turn, if any (for "Using Bash…"). */
function runningTool(conv: Conversation): string | null {
  for (let i = conv.entries.length - 1; i >= 0; i--) {
    const e = conv.entries[i];
    if (!e || e.kind !== 'assistant') continue;
    for (let j = e.blocks.length - 1; j >= 0; j--) {
      const b = e.blocks[j];
      if (b && b.type === 'tool' && b.status === 'running') return b.tool_name;
    }
    return null;
  }
  return null;
}

function detailOf(conv: Conversation): Detail {
  return {
    tool: runningTool(conv),
    turns: conv.counters.turns,
    cost: conv.counters.cost,
    contextPercent: contextUsage(conv).percent,
  };
}

/** Pure: live status (+ optional detail) → what the tab shows. One indicator, by priority. */
export function fromLive(live: TabLiveStatus, readOnly: boolean, d: Detail = NO_DETAIL): TabSummary {
  const base = { busy: live.busy, turns: d.turns, cost: d.cost, contextPercent: d.contextPercent, readOnly };
  const s = (indicator: TabIndicator, label: string): TabSummary => ({ ...base, indicator, label });
  if (readOnly) return s('off', 'Read-only');
  if (live.status === 'terminated' || (live.error && live.conn !== 'failed')) return s('off', 'Ended');
  if (live.permissionPending) return s('warning', 'Waiting for approval');
  if (live.status === 'connecting') return s('working', 'Connecting…');
  if (live.conn === 'failed') return s('disconnected', "Couldn't connect");
  if (live.disconnected) return s('disconnected', 'Disconnected');
  if (live.stalled) return s('warning', 'No response');
  switch (live.status) {
    case 'processing':
      return s('working', 'Working…');
    case 'streaming':
      return s('working', 'Writing…');
    case 'thinking':
      return s('working', 'Thinking…');
    case 'tool_use':
      return s('working', d.tool ? `Using ${d.tool}…` : 'Using tools…');
    case 'retrying':
      return s('working', 'Retrying…');
    case 'compacting':
      return s('working', 'Compacting…');
    case 'stopped':
      return s('off', 'Stopped');
    default:
      return live.busy ? s('working', 'Working…') : s('idle', 'Ready');
  }
}

/** Pure, from a conversation (tests, and the same rules the live store applies). */
export function summarize(conv: Conversation, readOnly: boolean): TabSummary {
  return fromLive(deriveLiveStatus(conv), readOnly, detailOf(conv));
}

const isChat = (t: Tab): boolean => t.kind === 'archie' || t.kind === 'agent';

/** Summaries of the open chat tabs, keyed by tab id; live while hidden. */
export function useTabSummaries(tabs: readonly Tab[]): Readonly<Record<string, TabSummary>> {
  const byId = useStore(liveStatusStore, (s) => s.byId);
  return useMemo(() => {
    const out: Record<string, TabSummary> = {};
    for (const t of tabs) {
      const live = isChat(t) ? byId[t.id] : undefined;
      if (live) out[t.id] = fromLive(live, t.readOnly === true);
    }
    return out;
  }, [tabs, byId]);
}

/** Summary of one tab with the detail of its visible store (the active tab's status text). */
export function useTabSummary(tab: Tab | undefined): TabSummary | null {
  const chat = !!tab && isChat(tab);
  const id = chat && tab ? tab.id : '';
  const live = useStore(liveStatusStore, (s) => (id ? s.byId[id] : undefined));
  const detail = useSession(id, useShallow((st) => (id ? detailOf(st.conv) : NO_DETAIL)));
  if (!chat || !live || !tab) return null;
  return fromLive(live, tab.readOnly === true, detail);
}

/** Synchronous live check used by the close flow. */
export function liveSummary(tab: Tab): TabSummary | null {
  const live = getLiveStatus(tab.id);
  return live ? fromLive(live, tab.readOnly === true) : null;
}

/** "14 turns · $0.82 · context 42%" */
export function countersText(s: TabSummary): string {
  const parts: string[] = [];
  if (s.turns > 0) parts.push(`${s.turns} ${s.turns === 1 ? 'turn' : 'turns'}`);
  if (s.cost > 0) parts.push(`$${s.cost.toFixed(2)}`);
  if (s.contextPercent !== null) parts.push(`context ${s.contextPercent}%`);
  return parts.join(' · ');
}
