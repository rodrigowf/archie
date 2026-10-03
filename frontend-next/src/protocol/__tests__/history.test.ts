/** Rewind / fork cut (§6.5), pagination (§5.3), line classification and output normalisation. */
import { describe, expect, it } from 'vitest';
import type { Conversation, MessagePreview } from '../index';
import {
  classifyUserLine,
  computeDropLastN,
  countPromptLines,
  isVisibleLine,
  lineMatchesEntry,
  mergeLines,
  normalizeOutput,
  pageAbuts,
  promptsNeeded,
  toIndexedLines,
} from '../index';
import { agent, asstText, feed, page, toolResultLine, toolUseLine, userLine } from './util';

/**
 * JSONL (REST order):
 *  0 user "one"  1 asst "a1"  2 asst tool_use t1  3 user tool_result(t1)  4 asst "a2"
 *  5 user "[voice] two"  6 asst "b"  7 user "three"  8 asst "c"
 */
const LINES: MessagePreview[] = [
  userLine('one'),
  asstText('a1'),
  toolUseLine('t1'),
  toolResultLine('t1', 'ok'),
  asstText('a2'),
  userLine('[voice] two'),
  asstText('b'),
  userLine('three'),
  asstText('c'),
];
const PAGE = { messages: LINES, start_index: 0, total_count: LINES.length, has_more: false };

function view(): Conversation {
  return feed(agent(), page(LINES as never[])).conv;
}

describe('computeDropLastN (§6.5)', () => {
  const lines = toIndexedLines(PAGE);
  const conv = view();
  const ids = conv.entries.map((e) => e.id);
  // entries: U one, A[a1 t1 a2], U(voice) two, A[b], U three, A[c]

  it('a user target keeps the prompt and drops its reply and everything after', () => {
    expect(computeDropLastN(conv.entries, ids[0] as string, lines)).toEqual({ ok: true, n: 7 }); // 1,2,4,5,6,7,8 (3 is a wrapper)
    expect(computeDropLastN(conv.entries, ids[2] as string, lines)).toEqual({ ok: true, n: 3 }); // 6,7,8
    expect(computeDropLastN(conv.entries, ids[4] as string, lines)).toEqual({ ok: true, n: 1 }); // 8
  });

  it('a run target keeps the run and cuts at the next prompt; the last run gives 0', () => {
    expect(computeDropLastN(conv.entries, ids[1] as string, lines)).toEqual({ ok: true, n: 4 }); // 5,6,7,8
    expect(computeDropLastN(conv.entries, ids[5] as string, lines)).toEqual({ ok: true, n: 0 });
  });

  it('aborts when the listing does not match the view', () => {
    const changed = toIndexedLines({ ...PAGE, messages: LINES.map((l, i) => (i === 7 ? userLine('THREE?') : l)) });
    expect(computeDropLastN(conv.entries, ids[4] as string, changed)).toEqual({ ok: false, reason: 'prompt mismatch' });
    expect(computeDropLastN(conv.entries, ids[3] as string, changed)).toEqual({ ok: false, reason: 'prompt mismatch' });
    expect(computeDropLastN(conv.entries, 'nope', lines)).toEqual({ ok: false, reason: 'target not in view' });
    expect(computeDropLastN(conv.entries, ids[0] as string, lines.slice(5))).toEqual({ ok: false, reason: 'prompt mismatch' });
  });

  it('helpers: promptsNeeded, countPromptLines, mergeLines, visibility, matching', () => {
    expect(promptsNeeded(conv.entries, ids[0] as string)).toBe(3);
    expect(promptsNeeded(conv.entries, 'nope')).toBe(0);
    expect(countPromptLines(lines)).toBe(3);
    const merged = mergeLines(lines.slice(4), lines.slice(0, 6));
    expect(merged.map((l) => l.index)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8]);
    expect(isVisibleLine(toolResultLine('x', 'y'))).toBe(false);
    expect(isVisibleLine({ role: 'assistant', blocks: [{ type: 'tool_result' }] })).toBe(true);
    expect(lineMatchesEntry(lines[5] as never, conv.entries[2])).toBe(true); // voice: class only
    expect(lineMatchesEntry(lines[5] as never, conv.entries[0])).toBe(false);
    expect(lineMatchesEntry({ index: 0, preview: userLine('[Request interrupted by user]') }, conv.entries[0])).toBe(false);
    expect(lineMatchesEntry({ index: 0, preview: userLine('  one  ') }, conv.entries[0])).toBe(true); // whitespace-normalised
    expect(lineMatchesEntry(lines[0] as never, conv.entries[1])).toBe(false);
    expect(toIndexedLines({ messages: null, start_index: undefined } as never)).toEqual([]);
  });

  it('pageAbuts (§5.3)', () => {
    expect(pageAbuts({ messages: [userLine('a'), asstText('b')], start_index: 8, total_count: 20, has_more: true }, 10)).toBe(true);
    expect(pageAbuts({ messages: [userLine('a')], start_index: 8, total_count: 20, has_more: true }, 10)).toBe(false);
    expect(pageAbuts({ messages: null, start_index: 10 } as never, 10)).toBe(true);
  });
});

describe('classifyUserLine and normalizeOutput', () => {
  it('plain lines are history prompts', () => {
    expect(classifyUserLine('hello')).toEqual({ kind: 'user', text: 'hello', origin: 'history' });
    expect(classifyUserLine('[shared text] Note\nbody')).toEqual({ kind: 'user', text: '[shared text] Note\nbody', origin: 'inject' });
    expect(classifyUserLine('<local-command-stdout>x</local-command-stdout>')).toMatchObject({ kind: 'notice', notice: 'command' });
  });

  it('R-3 output normalisation', () => {
    expect(normalizeOutput('s')).toBe('s');
    expect(normalizeOutput(null)).toBe('');
    expect(normalizeOutput(undefined)).toBe('');
    expect(normalizeOutput({ b: 1, a: [true] })).toBe('{"b":1,"a":[true]}');
    expect(normalizeOutput(3)).toBe('3');
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(normalizeOutput(cyclic)).toBe('[object Object]');
    expect(normalizeOutput(() => 1)).toContain('=>');
  });
});
