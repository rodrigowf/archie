/**
 * Subagent helpers of the reducer (spec 12 §4.8): the Task-tool aliases (SA-2), the blank
 * `Subagent`, and tolerant readers for the wire's usage blocks and `SubagentSummary` rows.
 */
import type { Subagent, SubagentStatus, SubagentUsage } from '../types';
import { isPlainObject } from '../wire/decode';

/**
 * Raw tool names that spawn a subagent (spec 12 SA-2 "normalised tool name is Task"): Claude Code /
 * Model Studio `Agent` / `Task`, Qwen `agent` / `task`, Gemini `invoke_agent`. Kept in sync with
 * `features/tools/normalize.ts` (the UI's alias table).
 */
export const SUBAGENT_TOOL_NAMES: readonly string[] = ['Agent', 'Task', 'agent', 'task', 'invoke_agent'];

export function isSubagentTool(name: string): boolean {
  return SUBAGENT_TOOL_NAMES.indexOf(name) >= 0;
}

/** The fields an upsert may carry (spec `upsertSubagent`: null / undefined are skipped). */
export type SubagentPatch = {
  -readonly [K in Exclude<keyof Subagent, 'tool_use_id' | 'conv' | 'held' | 'loadError'>]?: Subagent[K] | null;
};

/** `blankSubagent(id)`: status `running`, everything else empty. */
export function blankSubagent(id: string): Subagent {
  return {
    tool_use_id: id,
    parent_tool_use_id: null,
    agent_type: '',
    description: '',
    background: false,
    status: 'running',
    activity: null,
    usage: null,
    summary: null,
    started_at: null,
    ended_at: null,
    conv: null,
    held: null,
    lifecycle: false,
    loadError: null,
  };
}

const STATUSES: readonly SubagentStatus[] = ['running', 'done', 'failed', 'stopped'];

/** A wire status; unknown values read as `fallback`. */
export function subagentStatus(x: unknown, fallback: SubagentStatus): SubagentStatus {
  return typeof x === 'string' && STATUSES.indexOf(x as SubagentStatus) >= 0 ? (x as SubagentStatus) : fallback;
}

function numOrNull(x: unknown): number | null {
  return typeof x === 'number' && isFinite(x) ? x : null;
}

/** A wire usage block → `SubagentUsage`, or null when absent / not an object. */
export function usageOf(x: unknown): SubagentUsage | null {
  if (!isPlainObject(x)) return null;
  return { tokens: numOrNull(x.tokens), tool_uses: numOrNull(x.tool_uses), duration_ms: numOrNull(x.duration_ms) };
}

export function sameUsage(a: SubagentUsage | null, b: SubagentUsage | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return a.tokens === b.tokens && a.tool_uses === b.tool_uses && a.duration_ms === b.duration_ms;
}

function strOrUndef(x: unknown): string | undefined {
  return typeof x === 'string' ? x : undefined;
}

/** A `SubagentSummary` row (SA-3 snapshot, SA-8 list) → its id and an upsert patch; null when unusable. */
export function rowPatch(row: unknown): { id: string; patch: SubagentPatch } | null {
  if (!isPlainObject(row)) return null;
  const id = row.tool_use_id;
  if (typeof id !== 'string' || id === '') return null;
  const patch: SubagentPatch = {
    parent_tool_use_id: strOrUndef(row.parent_tool_use_id),
    agent_type: strOrUndef(row.agent_type),
    description: strOrUndef(row.description),
    background: typeof row.background === 'boolean' ? row.background : undefined,
    status: typeof row.status === 'string' ? subagentStatus(row.status, 'running') : undefined,
    activity: strOrUndef(row.activity),
    usage: usageOf(row.usage) ?? undefined,
    summary: strOrUndef(row.summary),
    started_at: strOrUndef(row.started_at),
    ended_at: strOrUndef(row.ended_at),
    lifecycle: true, // a server row is authoritative for the status (SA-2)
  };
  return { id, patch };
}

/** SA-2: the subagent fields a spawn card's input carries. */
export function spawnPatch(input: Readonly<Record<string, unknown>>): SubagentPatch {
  const type = typeof input.subagent_type === 'string' ? input.subagent_type : typeof input.agent_name === 'string' ? input.agent_name : '';
  const description = typeof input.description === 'string' ? input.description : '';
  // empty strings never overwrite what lifecycle frames set (a harness without these input keys)
  return { agent_type: type || undefined, description: description || undefined };
}
