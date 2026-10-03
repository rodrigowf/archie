/**
 * Pure derived views of a `Conversation` (spec 13 §3.2 `selectors/`). No caching here: callers
 * memoize on the inputs, which keep their identity when unchanged (structural sharing).
 */
import {
  busy,
  type Block,
  type Conversation,
  type OrphanResult,
  type PermissionBlock,
  type SessionRef,
  type ToolBlock,
  type ToolResultData,
} from '../types';

/** Busy = a reply is running: the composer queues, compact is disabled (§2.5). */
export function isBusy(conv: Conversation): boolean {
  return busy(conv.status);
}

export type StepItem =
  | { readonly kind: 'block'; readonly block: Block }
  | {
      readonly kind: 'steps';
      /** First block's id: a stable key. */
      readonly key: string;
      readonly blocks: readonly ToolBlock[];
      /** Expanded while live; collapsed to a summary once anything follows (spec 13 §3.6, IA §9.3). */
      readonly live: boolean;
    };

export interface GroupToolStepsOptions {
  /** Device pref "tool step grouping" (default on). */
  readonly enabled?: boolean;
  /** The run is the conversation's tail run and a turn is in flight (see `isRunLive`). */
  readonly live?: boolean;
  /** Minimum consecutive tool calls that form a group (default 2). */
  readonly minSize?: number;
}

/** Consecutive tool blocks (≥ 2) of one run → a step group. Order is never changed (I-3). */
export function groupToolSteps(blocks: readonly Block[], opts: GroupToolStepsOptions = {}): StepItem[] {
  const enabled = opts.enabled !== false;
  const min = opts.minSize ?? 2;
  const out: StepItem[] = [];
  let i = 0;
  while (i < blocks.length) {
    const b = blocks[i] as Block;
    if (!enabled || b.type !== 'tool') {
      out.push({ kind: 'block', block: b });
      i += 1;
      continue;
    }
    let j = i;
    const group: ToolBlock[] = [];
    while (j < blocks.length && (blocks[j] as Block).type === 'tool') {
      group.push(blocks[j] as ToolBlock);
      j += 1;
    }
    if (group.length >= min) {
      out.push({ kind: 'steps', key: (group[0] as ToolBlock).id, blocks: group, live: opts.live === true && j === blocks.length });
    } else {
      for (const t of group) out.push({ kind: 'block', block: t });
    }
    i = j;
  }
  return out;
}

/** The run at `entryIndex` is the tail run of a turn in flight. */
export function isRunLive(conv: Conversation, entryIndex: number): boolean {
  return conv.inTurn && entryIndex === conv.entries.length - 1 && conv.entries[entryIndex]?.kind === 'assistant';
}

/** PM-3: the newest pending permission of the view (the approval bar). */
export function pendingPermission(conv: Conversation): PermissionBlock | null {
  for (let i = conv.entries.length - 1; i >= 0; i--) {
    const e = conv.entries[i];
    if (!e || e.kind !== 'assistant') continue;
    for (let j = e.blocks.length - 1; j >= 0; j--) {
      const b = e.blocks[j];
      if (b && b.type === 'permission' && b.state === 'pending') return b;
    }
  }
  return null;
}

export interface UnmatchedResults {
  readonly orphans: readonly OrphanResult[];
  readonly unattributed: readonly ToolResultData[];
  readonly count: number;
}

/** R-9: what the "N tool results could not be matched" chip lists. History orphans wait while older pages exist. */
export function unmatchedResults(conv: Conversation): UnmatchedResults {
  const orphans = conv.orphanResults.filter((o) => o.origin !== 'history' || !conv.history.hasMore);
  return { orphans, unattributed: conv.unattributed, count: orphans.length + conv.unattributed.length };
}

export interface ContextUsage {
  /** Rounded percent, or null when unknown ("?"). */
  readonly percent: number | null;
  readonly level: 'normal' | 'caution' | 'warning';
}

/** §6.4 compact button: caution ≥ 50 %, warning ≥ 80 %. */
export function contextUsage(conv: Conversation): ContextUsage {
  const { contextTokens, contextWindow } = conv.counters;
  if (contextTokens === null || !contextWindow) return { percent: null, level: 'normal' };
  const percent = Math.round((contextTokens / contextWindow) * 100);
  return { percent, level: percent >= 80 ? 'warning' : percent >= 50 ? 'caution' : 'normal' };
}

/** ID-3: rename, rewind, fork, session config and REST history need an `sdkId`. */
export function hasSdkId(conv: Conversation): boolean {
  return conv.ref.sdkId !== null;
}

export interface SessionListItem {
  readonly session_id: string;
  readonly local_id?: string | null;
  readonly title: string;
}

/** MC-2: titles are derived from the session list, never stored on the view. */
export function deriveTitle(list: readonly SessionListItem[], ref: Pick<SessionRef, 'sdkId' | 'localId'>, placeholder: string): string {
  const bySdk = ref.sdkId ? list.find((s) => s.session_id === ref.sdkId) : undefined;
  if (bySdk) return bySdk.title;
  const byLocal = list.find((s) => s.local_id === ref.localId);
  return byLocal ? byLocal.title : placeholder;
}
