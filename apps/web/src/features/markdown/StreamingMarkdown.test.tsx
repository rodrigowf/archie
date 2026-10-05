/**
 * Streaming render cost (spec 13 §5.1 item 3): while a reply streams, a delta re-parses only the
 * unfinished tail (plus, once, a chunk that just closed). react-markdown is wrapped so every
 * parse is recorded with its source.
 */
import { render } from '@testing-library/react';
import { createElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { corpus } from './__fixtures__/corpus';
import { splitBlocks } from './splitBlocks';
import { StreamingMarkdown } from './StreamingMarkdown';

const parses = vi.hoisted(() => ({ sources: [] as string[] }));

vi.mock('react-markdown', async (importOriginal) => {
  const mod = await importOriginal<typeof import('react-markdown')>();
  const Original = mod.default;
  function RecordingMarkdown(props: Parameters<typeof Original>[0]) {
    parses.sources.push(props.children ?? '');
    return createElement(Original, props);
  }
  return { ...mod, default: RecordingMarkdown };
});

function takeParses(): string[] {
  const out = parses.sources;
  parses.sources = [];
  return out;
}

const REPLY = [corpus['inline.md'], corpus['tables.md'], corpus['code.md'], corpus['tasklists.md']].join('\n\n');

beforeEach(() => {
  parses.sources = [];
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('StreamingMarkdown', () => {
  it('re-parses only the tail (and each newly closed chunk once) per delta', () => {
    const { rerender } = render(<StreamingMarkdown source="" streaming />);
    takeParses();

    const parsedStable = new Map<string, number>();
    let totalParsedChars = 0;
    const DELTA = 7;

    for (let end = DELTA; end < REPLY.length + DELTA; end += DELTA) {
      const source = REPLY.slice(0, Math.min(end, REPLY.length));
      rerender(<StreamingMarkdown source={source} streaming />);
      const calls = takeParses();
      const { stable, tail } = splitBlocks(source);
      const stableSources = new Set(stable.map((c) => c.source));

      // At most: the tail, plus one chunk that closed on this delta.
      expect(calls.length, `delta ending at ${end}`).toBeLessThanOrEqual(2);
      for (const parsed of calls) {
        if (parsed === tail.source) continue;
        expect(stableSources.has(parsed), `unexpected parse of ${JSON.stringify(parsed.slice(0, 40))}`).toBe(true);
        parsedStable.set(parsed, (parsedStable.get(parsed) ?? 0) + 1);
      }
      totalParsedChars += calls.reduce((n, s) => n + s.length, 0);
    }

    // Each closed chunk was parsed exactly once.
    const finalStable = splitBlocks(REPLY).stable;
    expect(finalStable.length).toBeGreaterThan(10);
    for (const chunk of finalStable) expect(parsedStable.get(chunk.source), chunk.source.slice(0, 40)).toBe(1);

    // Work is far below "re-parse everything on every delta" (quadratic).
    const deltas = Math.ceil(REPLY.length / DELTA);
    const naive = (deltas * REPLY.length) / 2;
    expect(totalParsedChars).toBeLessThan(naive / 5);
  });

  it('does one full parse when the reply completes', () => {
    const { rerender, container } = render(<StreamingMarkdown source={REPLY} streaming />);
    expect(container.querySelector('[data-streaming="true"]')).not.toBeNull();
    takeParses();
    rerender(<StreamingMarkdown source={REPLY} streaming={false} />);
    expect(takeParses()).toEqual([REPLY]);
    expect(container.querySelector('[data-streaming]')).toBeNull();
  });

  it('renders an open fence as a plain code block while streaming', () => {
    const { container } = render(<StreamingMarkdown source={'Intro\n\n```python\ndef f():\n    return 1'} streaming />);
    const code = container.querySelector('pre code');
    expect(code?.textContent).toBe('def f():\n    return 1');
    expect(code?.getAttribute('data-highlighted')).toBeNull();
  });

  it('has the same DOM shape as a full parse (no per-chunk wrappers)', () => {
    const src = 'Para one\n\n- a\n- b\n\nPara two\n';
    const streaming = render(<StreamingMarkdown source={src} streaming />).container;
    const done = render(<StreamingMarkdown source={src} streaming={false} />).container;
    const shape = (root: Element): string[] =>
      Array.from(root.firstElementChild?.children ?? []).map((el) => el.tagName);
    expect(shape(streaming)).toEqual(['P', 'UL', 'P']);
    expect(shape(streaming)).toEqual(shape(done));
  });
});
