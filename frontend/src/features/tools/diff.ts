/**
 * Edit diffs (inv02 F-06) with a lazily loaded `diff` 9 (spec 13 §3.6 "lazy DiffView"): the line
 * differ is a separate chunk fetched the first time an Edit card renders. Only the line module is
 * imported, so the rest of the package (incl. the sentence tokenizer) never reaches a bundle.
 */
import { useEffect, useMemo, useState } from 'react';

type DiffLinesFn = (oldStr: string, newStr: string) => ReadonlyArray<{ value: string; added?: boolean; removed?: boolean }>;

let loaded: DiffLinesFn | null = null;
let loading: Promise<DiffLinesFn> | null = null;

export function loadDiff(): Promise<DiffLinesFn> {
  if (loaded) return Promise.resolve(loaded);
  if (!loading) {
    loading = import('diff/lib/diff/line.js').then((m) => {
      const fn: DiffLinesFn = (a, b) => m.diffLines(a, b);
      loaded = fn;
      return fn;
    });
    loading.catch(() => {
      loading = null; // offline: allow a later retry
    });
  }
  return loading;
}

export type DiffLineKind = 'add' | 'del' | 'ctx' | 'fold';

export interface DiffLine {
  readonly kind: DiffLineKind;
  readonly text: string;
}

export interface DiffResult {
  readonly lines: readonly DiffLine[];
  readonly added: number;
  readonly removed: number;
}

/** Unchanged lines kept around each change; longer unchanged runs fold into one line. */
export const DIFF_CONTEXT = 3;

function splitLines(value: string): string[] {
  const lines = value.split('\n');
  if (lines[lines.length - 1] === '') lines.pop(); // trailing newline (F-06)
  return lines;
}

/** Line diff → add/del/context lines, folding unchanged runs longer than 2 × context. */
export function computeDiff(diffLines: DiffLinesFn, oldStr: string, newStr: string, context = DIFF_CONTEXT): DiffResult {
  const raw: DiffLine[] = [];
  let added = 0;
  let removed = 0;
  for (const part of diffLines(oldStr, newStr)) {
    const kind: DiffLineKind = part.added ? 'add' : part.removed ? 'del' : 'ctx';
    for (const text of splitLines(part.value)) {
      raw.push({ kind, text });
      if (kind === 'add') added += 1;
      else if (kind === 'del') removed += 1;
    }
  }
  const lines: DiffLine[] = [];
  let i = 0;
  while (i < raw.length) {
    if ((raw[i] as DiffLine).kind !== 'ctx') {
      lines.push(raw[i] as DiffLine);
      i += 1;
      continue;
    }
    let j = i;
    while (j < raw.length && (raw[j] as DiffLine).kind === 'ctx') j += 1;
    const run = raw.slice(i, j);
    const head = i === 0 ? 0 : context;
    const tail = j === raw.length ? 0 : context;
    if (run.length > head + tail + 1) {
      lines.push(...run.slice(0, head));
      const hidden = run.length - head - tail;
      lines.push({ kind: 'fold', text: `${hidden} unchanged ${hidden === 1 ? 'line' : 'lines'}` });
      lines.push(...run.slice(run.length - tail));
    } else {
      lines.push(...run);
    }
    i = j;
  }
  return { lines, added, removed };
}

/** The diff of two strings, or null until the differ chunk has loaded. */
export function useDiff(oldStr: string, newStr: string): DiffResult | null {
  const [fn, setFn] = useState<DiffLinesFn | null>(() => loaded);
  useEffect(() => {
    if (fn) return undefined;
    let alive = true;
    loadDiff()
      .then((f) => {
        if (alive) setFn(() => f);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [fn]);
  return useMemo(() => (fn ? computeDiff(fn, oldStr, newStr) : null), [fn, oldStr, newStr]);
}
