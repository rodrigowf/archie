/**
 * Wording and small formatters of the subagent UI (spec 12 §4.8; PLAN §4.4): tab labels, status
 * text, the "Grep · 3 tools · 12k tokens · 0:42" activity line. Pure, unit-tested.
 */
import type { Subagent, SubagentStatus } from '@/protocol';

export const SUBAGENT_STATUS_TEXT: Readonly<Record<SubagentStatus, string>> = {
  running: 'Running',
  done: 'Done',
  failed: 'Failed',
  stopped: 'Stopped',
};

type Named = Pick<Subagent, 'agent_type' | 'description'>;

/** Tab / row label: the task description, else the agent type, else "Agent". */
export function subagentLabel(s: Named): string {
  return s.description.trim() || s.agent_type.trim() || 'Agent';
}

/** The agent type for headers ("Explore"); "Agent" when unknown. */
export function subagentType(s: Pick<Subagent, 'agent_type'>): string {
  return s.agent_type.trim() || 'Agent';
}

/** "Explore: Find scroll logic" (accessible names, the read-only bar). */
export function subagentTitle(s: Named): string {
  const type = s.agent_type.trim();
  const desc = s.description.trim();
  if (type && desc) return `${type}: ${desc}`;
  return desc || type || 'Agent';
}

/** 42 s → "42s", 102 s → "1:42", 3 723 s → "1:02:03". */
export function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return `${total}s`;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number): string => (n < 10 ? `0${n}` : String(n));
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

/** 950 → "950 tokens", 12 400 → "12.4k tokens", 1 250 000 → "1.3M tokens". */
export function formatTokens(n: number): string {
  if (n < 1000) return `${n} tokens`;
  if (n < 1000000) {
    const k = n / 1000;
    return `${k < 10 ? k.toFixed(1).replace(/\.0$/, '') : Math.round(k)}k tokens`;
  }
  return `${(n / 1000000).toFixed(1).replace(/\.0$/, '')}M tokens`;
}

function parseTime(x: string | null): number | null {
  if (!x) return null;
  const t = Date.parse(x);
  return isNaN(t) ? null : t;
}

/**
 * Elapsed time: live from `started_at` while running; when finished the reported duration, else
 * `ended_at − started_at`. Null when nothing is known.
 */
export function subagentElapsedMs(s: Pick<Subagent, 'status' | 'usage' | 'started_at' | 'ended_at'>, now: number): number | null {
  const duration = s.usage ? s.usage.duration_ms : null;
  const start = parseTime(s.started_at);
  if (s.status === 'running') return start !== null ? Math.max(0, now - start) : duration;
  if (duration !== null) return duration;
  const end = parseTime(s.ended_at);
  return start !== null && end !== null ? Math.max(0, end - start) : null;
}

/** The facts after the activity: "3 tools · 12.4k tokens". */
export function usageParts(s: Pick<Subagent, 'usage'>): string[] {
  const out: string[] = [];
  const u = s.usage;
  if (u && u.tool_uses !== null) out.push(u.tool_uses === 1 ? '1 tool' : `${u.tool_uses} tools`);
  if (u && u.tokens !== null) out.push(formatTokens(u.tokens));
  return out;
}

/** One line per agent: running → the current activity, finished → its status; then usage. */
export function activityLine(s: Pick<Subagent, 'status' | 'activity' | 'usage' | 'background'>): string {
  const head = s.status === 'running' ? s.activity || (s.background ? 'Working in the background' : 'Working') : SUBAGENT_STATUS_TEXT[s.status];
  return [head, ...usageParts(s)].join(' · ');
}

/** Spoken on a status change (polite; failures assertive, PLAN §4.4 item 5). */
export function statusAnnouncement(s: Named & Pick<Subagent, 'status'>): string {
  const name = subagentTitle(s);
  switch (s.status) {
    case 'running':
      return `Agent started: ${name}`;
    case 'done':
      return `Agent finished: ${name}`;
    case 'failed':
      return `Agent failed: ${name}`;
    default:
      return `Agent stopped: ${name}`;
  }
}

/** Depth below the main agent (0 = spawned by it), following `parent_tool_use_id` (SA-6). */
export function subagentDepth(list: readonly Subagent[], s: Subagent): number {
  let depth = 0;
  let cur: Subagent | undefined = s;
  const seen: Subagent[] = [];
  while (cur && cur.parent_tool_use_id && seen.indexOf(cur) < 0) {
    seen.push(cur);
    const pid: string = cur.parent_tool_use_id;
    cur = list.find((x) => x.tool_use_id === pid);
    if (cur) depth += 1;
  }
  return depth;
}

/**
 * Tree order (SA-6): every agent right after its parent's subtree position, spawn order among
 * siblings; agents whose parent is unknown stay at the top level.
 */
export function treeOrder(list: readonly Subagent[]): { sub: Subagent; depth: number }[] {
  const ids = new Set(list.map((s) => s.tool_use_id));
  const children = new Map<string, Subagent[]>();
  const roots: Subagent[] = [];
  for (const s of list) {
    const p = s.parent_tool_use_id;
    if (p && ids.has(p) && p !== s.tool_use_id) {
      const arr = children.get(p) ?? [];
      arr.push(s);
      children.set(p, arr);
    } else roots.push(s);
  }
  const out: { sub: Subagent; depth: number }[] = [];
  const seen = new Set<string>();
  const walk = (s: Subagent, depth: number): void => {
    if (seen.has(s.tool_use_id)) return;
    seen.add(s.tool_use_id);
    out.push({ sub: s, depth });
    for (const c of children.get(s.tool_use_id) ?? []) walk(c, depth + 1);
  };
  for (const r of roots) walk(r, 0);
  for (const s of list) if (!seen.has(s.tool_use_id)) walk(s, 0); // cycles
  return out;
}
