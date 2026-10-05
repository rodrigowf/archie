/**
 * The bounded window model (spec 13 §5.2): freeze buffer (inv02 F-11), trimming to the cap at the
 * bottom, re-expansion from memory, prepend anchoring, and recovery after a canonical reload.
 */
import { describe, expect, it } from 'vitest';
import type { Entry } from '@/protocol';
import {
  EXPAND_STEP,
  expandUp,
  freeze,
  LOAD_MORE_PX,
  NEAR_BOTTOM_PX,
  normalizeWindow,
  OPEN_WINDOW,
  prependAnchor,
  trimToCap,
  unfreeze,
  viewOf,
} from '../messageWindow';

const u = (id: string): Entry => ({ id, kind: 'user', text: id, origin: 'local', state: 'sent' });
const list = (from: number, to: number, prefix = 'e'): Entry[] => Array.from({ length: to - from }, (_, i) => u(`${prefix}${from + i}`));
const ids = (es: readonly Entry[]): string[] => es.map((e) => e.id);

describe('messageWindow', () => {
  it('keeps the load-bearing thresholds (inv02 F-11: 150 px near-bottom, 80 px load-more)', () => {
    expect(NEAR_BOTTOM_PX).toBe(150);
    expect(LOAD_MORE_PX).toBe(80);
  });

  it('renders everything under the cap and trims from the top once over it (at the bottom)', () => {
    const es = list(0, 10);
    expect(ids(viewOf(es, OPEN_WINDOW, 20).entries)).toEqual(ids(es));
    const w = trimToCap(es, OPEN_WINDOW, 4);
    const v = viewOf(es, w, 4);
    expect(ids(v.entries)).toEqual(['e6', 'e7', 'e8', 'e9']);
    expect(v.hiddenAbove).toBe(6);
  });

  it('freeze buffer: entries after the frozen tail are buffered, rendered ones keep updating, unfreeze flushes', () => {
    const es = list(0, 5);
    const w = freeze(es, OPEN_WINDOW, 200);
    expect(w.frozenTailId).toBe('e4');
    const more = [...es.slice(0, 4), { ...(es[4] as Entry), text: 'edited' } as Entry, ...list(5, 8)];
    const v = viewOf(more, w, 200);
    expect(ids(v.entries)).toEqual(['e0', 'e1', 'e2', 'e3', 'e4']);
    expect((v.entries[4] as { text: string }).text).toBe('edited'); // in-place update shows (R7)
    expect(v.buffered).toBe(3);
    expect(ids(viewOf(more, unfreeze(w), 200).entries)).toHaveLength(8);
  });

  it('prepends always show and keep the frozen tail (MessageList.tsx:48-52)', () => {
    const es = list(10, 15);
    const w = freeze(es, OPEN_WINDOW, 200);
    const next = [...list(0, 10), ...es, ...list(15, 17)];
    const v = viewOf(next, w, 200);
    expect(v.start).toBe(0);
    expect(v.entries[0]?.id).toBe('e0');
    expect(v.entries[v.entries.length - 1]?.id).toBe('e14');
    expect(v.buffered).toBe(2);
    expect(prependAnchor(viewOf(es, w, 200), v)).toBe('e10');
  });

  it('re-expands from memory in steps before paging from REST, anchored on the old first entry', () => {
    const es = list(0, 120);
    const w = trimToCap(es, OPEN_WINDOW, 30);
    const before = viewOf(es, w, 30);
    expect(before.start).toBe(90);
    const up = expandUp(es, w, 30);
    const after = viewOf(es, up, 30);
    expect(after.start).toBe(90 - EXPAND_STEP);
    expect(prependAnchor(before, after)).toBe('e90');
    expect(viewOf(es, expandUp(es, expandUp(es, up, 30), 30), 30).start).toBe(0);
  });

  it('an ordinary append or a trim is not an anchored update', () => {
    const es = list(0, 5);
    const v1 = viewOf(es, OPEN_WINDOW, 200);
    expect(prependAnchor(v1, viewOf([...es, u('e5')], OPEN_WINDOW, 200))).toBeNull();
    const trimmed = viewOf([...es, u('e5')], trimToCap([...es, u('e5')], OPEN_WINDOW, 3), 3);
    expect(prependAnchor(v1, trimmed)).toBeNull();
  });

  it('a canonical reload (all ids new) falls back to the tail, unfrozen', () => {
    const es = list(0, 50);
    let w = trimToCap(es, OPEN_WINDOW, 10);
    w = freeze(es, w, 10);
    const reloaded = list(0, 50, 'r');
    w = normalizeWindow(reloaded, w, 10);
    expect(w.frozenTailId).toBeNull();
    const v = viewOf(reloaded, w, 10);
    expect(v.end).toBe(50);
    expect(v.entries).toHaveLength(10);
  });

  it('a voice transcript inserted at its anchor (I-9) inside the window does not move the start', () => {
    const es = list(0, 6);
    const w = trimToCap(es, OPEN_WINDOW, 4);
    const ins = [...es.slice(0, 4), u('voice'), ...es.slice(4)];
    const v = viewOf(ins, w, 4);
    expect(v.entries[0]?.id).toBe('e2');
    expect(ids(v.entries)).toContain('voice');
  });
});
