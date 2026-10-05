/**
 * Splits streaming markdown into top-level chunks (spec 13 §5.1 item 3).
 *
 * While a reply streams, `StreamingMarkdown` renders every chunk except the last as a memoized
 * <Markdown>, so a delta re-parses only the unfinished tail. A boundary is a blank line that is:
 *
 * - outside a fenced code block (``` or ~~~, closed by the same character with at least the
 *   opening length);
 * - followed by a COMPLETE (newline-terminated) non-blank line, so a decision never rests on a
 *   half-received line;
 * - not inside a list run: when the current chunk has a list item, the next non-blank line must
 *   be neither a list item nor indented (a loose list continues across blank lines);
 * - not followed by an indented line at all (indented code, list continuation, footnote body).
 *
 * Tables cannot contain blank lines, so a table is never split. The rule is conservative: when
 * in doubt it does not split, which only costs re-parsing. The final render after the stream
 * completes is always one full parse (exact CommonMark, e.g. reference links across chunks).
 */

export interface MarkdownChunk {
  /** Chunk source (no trailing blank lines). */
  source: string;
  /** Offset of the chunk in the full source. */
  start: number;
}

export interface SplitResult {
  /** Closed chunks: their text no longer changes as more deltas arrive. */
  stable: MarkdownChunk[];
  /** The unfinished tail ('' when the source ends exactly at a boundary). */
  tail: MarkdownChunk;
}

interface Line {
  text: string;
  start: number;
  /** Ends with "\n" (the last line of a streaming source may still be partial). */
  complete: boolean;
}

const FENCE_OPEN = /^([ \t]*)(`{3,}|~{3,})(.*)$/;
const LIST_ITEM = /^ {0,3}(?:[-+*]|\d{1,9}[.)])(?:[ \t]|$)/;
const BLANK = /^[ \t]*\r?$/;
const INDENTED = /^[ \t]/;

function toLines(source: string): Line[] {
  const lines: Line[] = [];
  let pos = 0;
  while (pos < source.length) {
    const nl = source.indexOf('\n', pos);
    if (nl === -1) {
      lines.push({ text: source.slice(pos), start: pos, complete: false });
      break;
    }
    lines.push({ text: source.slice(pos, nl), start: pos, complete: true });
    pos = nl + 1;
  }
  return lines;
}

function indentWidth(ws: string): number {
  let width = 0;
  for (const ch of ws) width += ch === '\t' ? 4 - (width % 4) : 1;
  return width;
}

/** Trims trailing blank lines (and the final newline) from a chunk's text. */
function trimChunk(text: string): string {
  return text.replace(/(?:\r?\n[ \t]*)+$/, '').replace(/\r$/, '');
}

export function splitBlocks(source: string): SplitResult {
  const lines = toLines(source);
  const stable: MarkdownChunk[] = [];
  let chunkStart = 0;
  let fence: { char: string; length: number } | null = null;
  let listRun = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] as Line;
    if (!line.complete) break; // the partial last line belongs to the tail

    if (fence) {
      const close = FENCE_OPEN.exec(line.text);
      if (
        close &&
        (close[2] ?? '').charAt(0) === fence.char &&
        (close[2] ?? '').length >= fence.length &&
        /^[ \t]*\r?$/.test(close[3] ?? '')
      ) {
        fence = null;
      }
      continue;
    }

    const open = FENCE_OPEN.exec(line.text);
    if (open) {
      const marker = open[2] ?? '';
      const info = open[3] ?? '';
      const indented = indentWidth(open[1] ?? '') >= 4;
      // A backtick fence's info string cannot contain a backtick. At 4+ columns it is indented
      // code, except inside a list item, where fences are indented.
      if (!(marker.charAt(0) === '`' && info.includes('`')) && (!indented || listRun)) {
        fence = { char: marker.charAt(0), length: marker.length };
        continue;
      }
    }

    if (LIST_ITEM.test(line.text)) {
      listRun = true;
      continue;
    }

    if (!BLANK.test(line.text)) continue;

    // A blank line: find the next non-blank line.
    let j = i + 1;
    while (j < lines.length && BLANK.test((lines[j] as Line).text) && (lines[j] as Line).complete) j++;
    const next = lines[j];
    if (!next || !next.complete || BLANK.test(next.text)) break; // undecidable yet: stays in the tail
    if (INDENTED.test(next.text)) continue;
    if (listRun && LIST_ITEM.test(next.text)) continue;

    const text = trimChunk(source.slice(chunkStart, line.start));
    if (text.trim() !== '') stable.push({ source: text, start: chunkStart });
    chunkStart = next.start;
    listRun = false;
    i = j - 1;
  }

  return { stable, tail: { source: source.slice(chunkStart), start: chunkStart } };
}

/** FNV-1a 32-bit hash, for chunk keys. */
export function hashString(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}
