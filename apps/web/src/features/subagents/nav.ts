/**
 * Subagent view actions (spec 12 §4.8 SA-5/SA-9; PLAN §4.4). The selected agent tab and the
 * closed ("Close finished") tabs are per-session UI state in the session store; the transcript
 * itself is materialised by the session runtime (`openSubagent`, LRU of 3).
 */
import type { Subagent } from '@/protocol';
import { getSessionRuntime, SessionRuntime } from '@/services';
import { getSessionEntry } from '@/stores';

function runtime(localId: string): SessionRuntime | null {
  const rt = getSessionRuntime(localId);
  return rt instanceof SessionRuntime ? rt : null;
}

/** Show an agent's tab (null = Main). Opening one brings a closed tab back and loads its transcript. */
export function showSubagentTab(localId: string, toolUseId: string | null): void {
  const entry = getSessionEntry(localId);
  if (!entry) return;
  const st = entry.handle.store.getState();
  if (toolUseId === null) {
    if (st.subagentTab !== null) entry.handle.patch({ subagentTab: null });
    return;
  }
  runtime(localId)?.openSubagent(toolUseId);
  const hidden = st.hiddenSubagentTabs.indexOf(toolUseId) >= 0 ? st.hiddenSubagentTabs.filter((x) => x !== toolUseId) : st.hiddenSubagentTabs;
  if (st.subagentTab !== toolUseId || hidden !== st.hiddenSubagentTabs) entry.handle.patch({ subagentTab: toolUseId, hiddenSubagentTabs: hidden });
}

export function isFinished(s: Pick<Subagent, 'status'>): boolean {
  return s.status !== 'running';
}

/** "Close finished": hide every finished agent's tab (they stay in the Agents list). */
export function closeFinishedSubagentTabs(localId: string): void {
  const entry = getSessionEntry(localId);
  if (!entry) return;
  const st = entry.handle.store.getState();
  const finished = st.conv.subagents.filter(isFinished).map((s) => s.tool_use_id);
  const hidden = st.hiddenSubagentTabs.concat(finished.filter((id) => st.hiddenSubagentTabs.indexOf(id) < 0));
  const tab = st.subagentTab && finished.indexOf(st.subagentTab) >= 0 ? null : st.subagentTab;
  entry.handle.patch({ hiddenSubagentTabs: hidden, subagentTab: tab });
}

/** SA-9 */
export function stopSubagent(localId: string, toolUseId: string): void {
  runtime(localId)?.stopSubagent(toolUseId);
}

/** Retry a failed transcript load: release it and open it again. */
export function reloadSubagent(localId: string, toolUseId: string): void {
  const rt = runtime(localId);
  if (!rt) return;
  rt.releaseSubagent(toolUseId);
  rt.openSubagent(toolUseId);
}

export function loadOlderSubagent(localId: string, toolUseId: string): void {
  void runtime(localId)?.loadOlderSubagent(toolUseId);
}

/** The stop control is offered for running agents of a live (not read-only) session. */
export function canStop(localId: string, s: Pick<Subagent, 'status'>): boolean {
  const entry = getSessionEntry(localId);
  return s.status === 'running' && !!entry && !entry.handle.store.getState().readOnly;
}
