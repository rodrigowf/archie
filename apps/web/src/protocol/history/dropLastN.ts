/**
 * Rewind / fork cut (spec 12 §6.5) and pagination helpers (§5.3).
 *
 * The backend counts `drop_last_n` in visible JSONL lines (G-26), which the rendered timeline
 * cannot count. The cut is anchored on user prompts counted from the end and resolved against a
 * fresh REST listing. The REST fetching loop belongs to the runtime (W-06):
 *
 *   need = promptsNeeded(entries, targetId)            // k + 1
 *   lines = []; before = undefined
 *   do { page = GET …/messages?limit=200&before=…; lines = mergeLines(toIndexedLines(page), lines) }
 *   while (countPromptLines(lines) < need && page.has_more)
 *   result = computeDropLastN(entries, targetId, lines)
 */
import type { Entry, UserEntry, UserOrigin } from '../types';
import type { MessagePreview, MessagesPage } from '../wire/server';
import { classifyUserLine } from './classify';

export interface IndexedLine {
  readonly index: number;
  readonly preview: MessagePreview;
}

export type DropLastNResult = { readonly ok: true; readonly n: number } | { readonly ok: false; readonly reason: string };

/** A REST page → lines with their absolute indices. */
export function toIndexedLines(page: MessagesPage): IndexedLine[] {
  const start = typeof page.start_index === 'number' ? page.start_index : 0;
  return (Array.isArray(page.messages) ? page.messages : []).map((preview, i) => ({ index: start + i, preview }));
}

/** Union of two listings by index, sorted (a newer fetch wins on overlap). */
export function mergeLines(a: readonly IndexedLine[], b: readonly IndexedLine[]): IndexedLine[] {
  const byIndex = new Map<number, IndexedLine>();
  for (const l of a) byIndex.set(l.index, l);
  for (const l of b) byIndex.set(l.index, l);
  return Array.from(byIndex.values()).sort((x, y) => x.index - y.index);
}

function isToolResultWrapper(p: MessagePreview): boolean {
  const blocks = Array.isArray(p.blocks) ? p.blocks : [];
  return blocks.length > 0 && blocks.every((b) => b && b.type === 'tool_result');
}

/** Lines the backend counts in `drop_last_n` (user lines that only wrap tool results are not). */
export function isVisibleLine(p: MessagePreview): boolean {
  return p.role === 'assistant' || !isToolResultWrapper(p);
}

/** A REST line that the timeline shows as a user prompt. */
export function isPromptLine(p: MessagePreview): boolean {
  return p.role === 'user' && isVisibleLine(p) && typeof p.text === 'string' && p.text !== '' && classifyUserLine(p.text).kind === 'user';
}

export function countPromptLines(lines: readonly IndexedLine[]): number {
  return lines.filter((l) => isPromptLine(l.preview)).length;
}

function isSentUser(e: Entry): e is UserEntry {
  return e.kind === 'user' && e.state === 'sent';
}

/** k + 1: how many REST prompt lines the cut needs (`fetchTailLines(sdkId, k + 1)`). */
export function promptsNeeded(entries: readonly Entry[], targetId: string): number {
  const idx = entries.findIndex((e) => e.id === targetId);
  if (idx < 0) return 0;
  return entries.slice(idx + 1).filter(isSentUser).length + 1;
}

type OriginClass = 'text' | 'inject' | 'voice' | 'audio';

function originClass(o: UserOrigin): OriginClass {
  if (o === 'voice' || o === 'audio' || o === 'inject') return o;
  return 'text';
}

function normText(s: string): string {
  return s.trim().replace(/\s+/g, ' ');
}

/** `matches(line, entry)`: same origin class; typed prompts and injects also need equal text. */
export function lineMatchesEntry(line: IndexedLine, entry: Entry | undefined): boolean {
  if (!entry || entry.kind !== 'user') return false;
  const c = classifyUserLine(typeof line.preview.text === 'string' ? line.preview.text : '');
  if (c.kind !== 'user') return false;
  const cls = originClass(c.origin);
  if (cls !== originClass(entry.origin)) return false;
  if (cls === 'text' || cls === 'inject') return normText(c.text) === normText(entry.text);
  return true;
}

/**
 * spec 12 §6.5 `computeDropLastN`. `lines` is the REST tail listing (any order; it is sorted
 * here). Returns `{ok: false}` for ABORT (the caller reloads and toasts "The conversation changed.
 * Try again."). `n === 0` disables rewind for the last entry; fork allows it (a plain copy).
 */
export function computeDropLastN(entries: readonly Entry[], targetId: string, lines: readonly IndexedLine[]): DropLastNResult {
  const idx = entries.findIndex((e) => e.id === targetId);
  if (idx < 0) return { ok: false, reason: 'target not in view' };
  const target = entries[idx] as Entry;
  const after = entries.slice(idx + 1).filter(isSentUser);
  const k = after.length;
  const sorted = lines.slice().sort((a, b) => a.index - b.index);
  const prompts = sorted.filter((l) => isPromptLine(l.preview));
  let cutFrom: number;
  if (target.kind === 'user') {
    // keep the prompt, drop its reply and everything after
    const j = prompts.length - 1 - k;
    const line = prompts[j];
    if (j < 0 || !line || !lineMatchesEntry(line, target)) return { ok: false, reason: 'prompt mismatch' };
    cutFrom = line.index + 1;
  } else {
    // a run or a notice: keep it, cut at the next prompt
    if (k === 0) return { ok: true, n: 0 };
    const next = prompts[prompts.length - k];
    if (!next || !lineMatchesEntry(next, after[0])) return { ok: false, reason: 'prompt mismatch' };
    cutFrom = next.index;
  }
  return { ok: true, n: sorted.filter((l) => l.index >= cutFrom && isVisibleLine(l.preview)).length };
}

/** §5.3: does an older page end exactly where the loaded range starts? Otherwise canonical reload. */
export function pageAbuts(page: MessagesPage, loadedStartIndex: number): boolean {
  const n = Array.isArray(page.messages) ? page.messages.length : 0;
  return page.start_index + n === loadedStartIndex;
}
