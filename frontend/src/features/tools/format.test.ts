import { describe, expect, it } from 'vitest';
import { diffLines } from 'diff/lib/diff/line.js';
import { computeDiff } from './diff';
import {
  countLines,
  firstLine,
  formatClock,
  formatDuration,
  languageFromPath,
  parseExitCode,
  shortPath,
  stripAnsi,
  stripScheme,
  truncateOutput,
} from './format';

describe('format helpers', () => {
  it('shortPath keeps the last two segments of deep paths (F-05 formatFilePath)', () => {
    expect(shortPath('/home/rodrigo/assistant/orchestrator/session.py')).toBe('…/orchestrator/session.py');
    expect(shortPath('orchestrator/session.py')).toBe('orchestrator/session.py');
    expect(shortPath('a/b/c')).toBe('a/b/c');
  });

  it('stripAnsi removes CSI/OSC sequences and normalises CRLF', () => {
    expect(stripAnsi('\u001b[32m46 passed\u001b[0m in 3.8s')).toBe('46 passed in 3.8s');
    expect(stripAnsi('\u001b]0;title\u0007ok\r\nnext')).toBe('ok\nnext');
    expect(stripAnsi('\u001b[1;31mE\u001b[K')).toBe('E');
    expect(stripAnsi('plain')).toBe('plain');
  });

  it('truncateOutput cuts at 200 lines or 20 KB', () => {
    const many = Array.from({ length: 450 }, (_, i) => `line ${i + 1}`).join('\n');
    const t = truncateOutput(many);
    expect(t.truncated).toBe(true);
    expect(t.totalLines).toBe(450);
    expect(t.shownLines).toBe(200);
    expect(t.text.endsWith('line 200')).toBe(true);

    const wide = 'x'.repeat(50_000);
    const w = truncateOutput(wide);
    expect(w.truncated).toBe(true);
    expect(w.text.length).toBe(20 * 1024);

    expect(truncateOutput('a\nb').truncated).toBe(false);
  });

  it('countLines ignores a trailing newline', () => {
    expect(countLines('')).toBe(0);
    expect(countLines('a')).toBe(1);
    expect(countLines('a\nb\n')).toBe(2);
  });

  it('clock and duration formats', () => {
    expect(formatClock(12)).toBe('0:12');
    expect(formatClock(134)).toBe('2:14');
    expect(formatClock(3723)).toBe('1:02:03');
    expect(formatDuration(200)).toBe('0.2s');
    expect(formatDuration(3820)).toBe('3.8s');
    expect(formatDuration(42_000)).toBe('42s');
    expect(formatDuration(65_000)).toBe('1:05');
  });

  it('parseExitCode reads Claude and Qwen shapes', () => {
    expect(parseExitCode('Error: Exit code 2\nboom')).toBe(2);
    expect(parseExitCode('Exit Code: 1')).toBe(1);
    expect(parseExitCode('all good')).toBeNull();
  });

  it('misc', () => {
    expect(firstLine('\n  first\nsecond')).toBe('first …');
    expect(stripScheme('https://developer.android.com/x')).toBe('developer.android.com/x');
    expect(languageFromPath('/a/b.tsx')).toBe('tsx');
    expect(languageFromPath('Dockerfile')).toBe('dockerfile');
    expect(languageFromPath('notes.unknownext')).toBeNull();
  });
});

describe('computeDiff (F-06)', () => {
  it('marks added/removed lines and counts them', () => {
    const d = computeDiff(diffLines, 'a\nb\nc\n', 'a\nB\nc\nd\n');
    expect(d.added).toBe(2);
    expect(d.removed).toBe(1);
    expect(d.lines.map((l) => `${l.kind}:${l.text}`)).toEqual(['ctx:a', 'del:b', 'add:B', 'ctx:c', 'add:d']);
  });

  it('folds long unchanged runs, keeping 3 lines of context', () => {
    const before = Array.from({ length: 30 }, (_, i) => `l${i}`);
    const after = [...before];
    after[15] = 'changed';
    const d = computeDiff(diffLines, before.join('\n') + '\n', after.join('\n') + '\n');
    const kinds = d.lines.map((l) => l.kind);
    expect(kinds.filter((k) => k === 'fold')).toHaveLength(2);
    expect(d.lines[0]).toEqual({ kind: 'fold', text: '12 unchanged lines' });
    expect(d.lines.find((l) => l.kind === 'add')?.text).toBe('changed');
    expect(d.lines.filter((l) => l.kind === 'ctx')).toHaveLength(6);
  });
});
