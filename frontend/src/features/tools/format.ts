/**
 * Pure text helpers for tool cards (spec 13 §3.6 W-10): input coercion, path shortening, ANSI
 * stripping, output truncation, durations, exit codes. No React, no DOM.
 */

export type ToolInput = Readonly<Record<string, unknown>>;

/** Output shown before "Show all" (spec 13 §3.6): the first 200 lines or 20 KB. */
export const OUTPUT_MAX_LINES = 200;
export const OUTPUT_MAX_CHARS = 20 * 1024;

/** A string field, or `undefined` for anything else (numbers are stringified). */
export function str(input: ToolInput, key: string): string | undefined {
  const v = input[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' && Number.isFinite(v)) return String(v);
  return undefined;
}

/** A finite number field (numeric strings accepted), or `undefined`. */
export function num(input: ToolInput, key: string): number | undefined {
  const v = input[key];
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  return undefined;
}

export function bool(input: ToolInput, key: string): boolean | undefined {
  const v = input[key];
  return typeof v === 'boolean' ? v : undefined;
}

export function arr(input: ToolInput, key: string): readonly unknown[] | undefined {
  const v = input[key];
  return Array.isArray(v) ? v : undefined;
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Truncates to `max` characters with a trailing ellipsis. */
export function clip(text: string, max = 60): string {
  return text.length > max ? `${text.slice(0, max).trimEnd()}…` : text;
}

/** First non-empty line, clipped. */
export function firstLine(text: string, max = 60): string {
  const line = text.split('\n').find((l) => l.trim() !== '') ?? '';
  const more = text.trim().indexOf('\n') >= 0;
  const clipped = clip(line.trim(), max);
  return more && !clipped.endsWith('…') ? `${clipped} …` : clipped;
}

/**
 * Shortens deep paths to their last two segments (inv02 F-05 `formatFilePath`):
 * `/home/u/proj/src/a.ts` → `…/src/a.ts`; paths with ≤ 3 segments are unchanged.
 */
export function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.length > 3 ? `…/${parts.slice(-2).join('/')}` : path;
}

export function baseName(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

/** `https://example.com/a?b` → `example.com/a?b` (header summaries only). */
export function stripScheme(url: string): string {
  return url.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '');
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Language id for a file path (inv02 F-05 extension map), or null for plain text. */
export function languageFromPath(path: string): string | null {
  const name = baseName(path).toLowerCase();
  if (name === 'dockerfile') return 'dockerfile';
  const dot = name.lastIndexOf('.');
  const ext = dot >= 0 ? name.slice(dot + 1) : '';
  return LANG_BY_EXT[ext] ?? null;
}

const LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript',
  tsx: 'tsx',
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  jsx: 'jsx',
  py: 'python',
  rs: 'rust',
  go: 'go',
  rb: 'ruby',
  java: 'java',
  kt: 'kotlin',
  kts: 'kotlin',
  c: 'c',
  cpp: 'cpp',
  h: 'c',
  hpp: 'cpp',
  css: 'css',
  scss: 'scss',
  html: 'html',
  json: 'json',
  yaml: 'yaml',
  yml: 'yaml',
  md: 'markdown',
  sh: 'bash',
  bash: 'bash',
  zsh: 'bash',
  sql: 'sql',
  graphql: 'graphql',
  dockerfile: 'dockerfile',
  toml: 'toml',
  xml: 'xml',
};

/* ANSI: CSI sequences (colours, cursor moves) and OSC sequences (titles, hyperlinks). */
const ESC = '\u001b';
const ANSI_CSI = new RegExp(`${ESC}\\[[0-9;?]*[ -/]*[@-~]`, 'g');
const ANSI_OSC = new RegExp(`${ESC}\\][^\\u0007${ESC}]*(?:\\u0007|${ESC}\\\\)`, 'g');
const ANSI_LONE = new RegExp(`${ESC}[@-Z\\\\-_]`, 'g');

/** Removes terminal escape sequences and normalises CRLF (spec 13 §3.6: "ANSI stripped"). */
export function stripAnsi(text: string): string {
  if (text.indexOf(ESC) < 0) return text.indexOf('\r') < 0 ? text : text.replace(/\r\n?/g, '\n');
  return text.replace(ANSI_OSC, '').replace(ANSI_CSI, '').replace(ANSI_LONE, '').replace(/\r\n?/g, '\n');
}

export interface TruncatedText {
  readonly text: string;
  readonly truncated: boolean;
  readonly totalLines: number;
  readonly shownLines: number;
}

/** The first `maxLines` lines and at most `maxChars` characters (cut at a line end when possible). */
export function truncateOutput(text: string, maxLines = OUTPUT_MAX_LINES, maxChars = OUTPUT_MAX_CHARS): TruncatedText {
  const totalLines = countLines(text);
  let end = text.length;
  let truncated = false;
  if (totalLines > maxLines) {
    let idx = -1;
    for (let i = 0; i < maxLines; i++) idx = text.indexOf('\n', idx + 1);
    end = idx;
    truncated = true;
  }
  if (end > maxChars) {
    const nl = text.lastIndexOf('\n', maxChars);
    end = nl > maxChars / 2 ? nl : maxChars;
    truncated = true;
  }
  const shown = truncated ? text.slice(0, end) : text;
  return { text: shown, truncated, totalLines, shownLines: countLines(shown) };
}

export function countLines(text: string): number {
  if (text === '') return 0;
  let n = 1;
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) n += 1;
  return text.endsWith('\n') ? n - 1 : n;
}

/** Running clock: `0:12`, `2:14`, `1:02:03`. */
export function formatClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** Finished duration: `0.2s`, `3.8s`, `42s`, `1:05`. */
export function formatDuration(ms: number): string {
  const s = Math.max(0, ms) / 1000;
  if (s < 10) return `${s.toFixed(1)}s`;
  if (s < 60) return `${Math.round(s)}s`;
  return formatClock(s);
}

/** Exit code printed in a shell result (Claude "Exit code 2", Qwen "Exit Code: 2"), or null. */
export function parseExitCode(output: string): number | null {
  const m = /exit[ _]code:?\s*(-?\d+)/i.exec(output);
  return m && m[1] !== undefined ? Number(m[1]) : null;
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Pretty JSON for the generic input view; never throws. */
export function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    return String(value);
  }
}

/** The first short string value of an input, for summaries of unknown tools. */
export function firstStringArg(input: ToolInput, max = 60): string {
  for (const k of Object.keys(input)) {
    const v = input[k];
    if (typeof v === 'string' && v.trim() !== '') return firstLine(v, max);
  }
  return '';
}
