/**
 * History list model (spec 13 §3.6 `features/history`; fixes inv02 §6.1: no search, no date
 * grouping): client-side title filter, then the sessions sorted by last activity and bucketed
 * Today / Yesterday / Previous 7 days / Earlier in local time. Pure, so it is tested with a fixed
 * clock.
 */
import type { SessionInfo } from '@/services';
import { HISTORY_GROUPS, historyGroupOf, parseServerTime, type HistoryGroup } from './time';

export const NEW_CONVERSATION = 'New conversation';

const GENERIC_ARCHIE = /^\s*(orchestrator|archie)?\s*$/i;

/** IA §1 vocabulary: an untitled Archie conversation is "New conversation" (never "Orchestrator"). */
export function conversationTitle(raw: string | null | undefined, isArchie: boolean): string {
  const t = (raw ?? '').trim();
  if (isArchie) return GENERIC_ARCHIE.test(t) ? NEW_CONVERSATION : t;
  return t || 'Untitled';
}

export function sessionTitleOf(s: Pick<SessionInfo, 'title' | 'is_orchestrator'>): string {
  return conversationTitle(s.title, s.is_orchestrator);
}

/** Case-insensitive; every word of the query must appear in the text (any order). */
export function matchesQuery(text: string, query: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const hay = text.toLowerCase();
  return words.every((w) => hay.indexOf(w) >= 0);
}

export interface HistorySection {
  readonly group: HistoryGroup;
  readonly items: readonly SessionInfo[];
}

export interface GroupOptions {
  readonly query?: string;
  /** Sessions shown elsewhere (the "Open now" section). */
  readonly exclude?: (s: SessionInfo) => boolean;
  readonly now: number;
}

export function lastActivityMs(s: SessionInfo): number {
  const t = parseServerTime(s.last_activity);
  return isFinite(t) ? t : parseServerTime(s.started_at);
}

/** Filtered, newest first, in the four date groups; empty groups are left out. */
export function groupSessions(items: readonly SessionInfo[], { query = '', exclude, now }: GroupOptions): HistorySection[] {
  const rows = items
    .filter((s) => !(exclude && exclude(s)) && matchesQuery(sessionTitleOf(s), query))
    .map((s) => ({ s, t: lastActivityMs(s) }))
    .sort((a, b) => (isFinite(b.t) ? b.t : -Infinity) - (isFinite(a.t) ? a.t : -Infinity));
  const buckets: Record<HistoryGroup, SessionInfo[]> = { Today: [], Yesterday: [], 'Previous 7 days': [], Earlier: [] };
  for (const r of rows) buckets[historyGroupOf(r.t, now)].push(r.s);
  return HISTORY_GROUPS.filter((g) => buckets[g].length > 0).map((g) => ({ group: g, items: buckets[g] }));
}
