/**
 * Memory-file frontmatter split (inv02 F-37).
 *
 * LOAD-BEARING inv02 F-37 (frontend/src/components/MemoryPanel.tsx:88-100): every file under
 * context/memory/ starts with a YAML block. Rendered as markdown it becomes a run-on paragraph
 * between two horizontal rules, so the block is split off and shown verbatim in a collapsed
 * disclosure (MemoryDocument, W-14). The body is rendered with <Markdown>.
 *
 * Same rule as today (CRLF tolerant, leading block only), plus an empty block (`---\n---`) and a
 * leading BOM are accepted.
 */

export interface FrontmatterSplit {
  /** The YAML text between the fences, verbatim; `null` when the file has no frontmatter. */
  frontmatter: string | null;
  /** The markdown after the closing fence (the whole input when there is no frontmatter). */
  body: string;
}

const FRONTMATTER = /^﻿?---[ \t]*\r?\n(?:([\s\S]*?)\r?\n)?---[ \t]*(?:\r?\n|$)/;

export function splitFrontmatter(raw: string): FrontmatterSplit {
  const match = FRONTMATTER.exec(raw);
  if (!match) return { frontmatter: null, body: raw };
  return { frontmatter: match[1] ?? '', body: raw.slice(match[0].length) };
}

export interface FrontmatterSummary {
  /** Top-level `key: value` scalars (quotes stripped), e.g. `category`, `modified`. */
  fields: Record<string, string>;
  /** Item count of each top-level list (`key:` + `- item` lines, or an inline `[a, b]`). */
  listLengths: Record<string, number>;
}

/**
 * A shallow, forgiving read of the frontmatter for a one-line summary chip (mockup g2:
 * "Frontmatter · architecture · 4 refs"). It is not a YAML parser: nested maps, multi-line
 * strings and anchors are ignored. The disclosure always shows the verbatim text.
 */
export function summarizeFrontmatter(frontmatter: string): FrontmatterSummary {
  const fields: Record<string, string> = {};
  const listLengths: Record<string, number> = {};
  let currentList: string | null = null;

  for (const line of frontmatter.split(/\r?\n/)) {
    if (/^\s*(#|$)/.test(line)) continue;
    const item = /^\s+-\s|^-\s/.test(line);
    if (item && currentList !== null) {
      listLengths[currentList] = (listLengths[currentList] ?? 0) + 1;
      continue;
    }
    const kv = /^([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(line);
    if (!kv) {
      if (!/^\s/.test(line)) currentList = null;
      continue;
    }
    const key = kv[1] ?? '';
    const value = (kv[2] ?? '').trim();
    currentList = null;
    if (value === '') {
      currentList = key;
      listLengths[key] = 0;
    } else if (/^\[.*\]$/.test(value)) {
      const inner = value.slice(1, -1).trim();
      listLengths[key] = inner === '' ? 0 : inner.split(',').length;
    } else {
      fields[key] = value.replace(/^(['"])(.*)\1$/, '$2');
    }
  }
  return { fields, listLengths };
}
