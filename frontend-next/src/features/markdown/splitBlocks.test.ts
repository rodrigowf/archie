import { renderToStaticMarkup } from 'react-dom/server';
import { createElement } from 'react';
import { describe, expect, it } from 'vitest';
import { corpus } from './__fixtures__/corpus';
import { MarkdownBody } from './Markdown';
import { hashString, splitBlocks } from './splitBlocks';

const sources = (text: string): string[] => {
  const { stable, tail } = splitBlocks(text);
  return [...stable.map((c) => c.source), tail.source];
};

describe('splitBlocks', () => {
  it('splits paragraphs at blank lines once the next line is complete', () => {
    expect(sources('one\n\ntwo\n\nthr')).toEqual(['one', 'two\n\nthr']);
    expect(sources('one\n\ntwo\n\nthree\n')).toEqual(['one', 'two', 'three\n']);
    expect(sources('one\n\n')).toEqual(['one\n\n']); // next line unknown yet
  });

  it('records chunk offsets', () => {
    const { stable, tail } = splitBlocks('aa\n\nbb\n\ncc\n');
    expect(stable.map((c) => c.start)).toEqual([0, 4]);
    expect(tail.start).toBe(8);
  });

  it('never splits inside a ``` fence, closed or open', () => {
    const fence = '```py\na = 1\n\n\nb = 2\n```\n\nafter\n';
    expect(sources(fence)).toEqual(['```py\na = 1\n\n\nb = 2\n```', 'after\n']);
    expect(sources('text\n\n```\nopen\n\nstill open\n\nmore\n')).toEqual(['text', '```\nopen\n\nstill open\n\nmore\n']);
  });

  it('handles ~~~ fences and longer fences containing shorter ones', () => {
    expect(sources('~~~\na\n\nb\n~~~\n\nz\n')).toEqual(['~~~\na\n\nb\n~~~', 'z\n']);
    const nested = '````md\n```js\nx\n\n```\n````\n\nz\n';
    expect(sources(nested)).toEqual(['````md\n```js\nx\n\n```\n````', 'z\n']);
    // ~~~ does not close a ``` fence.
    expect(sources('```\na\n~~~\n\nb\n```\n\nz\n')).toEqual(['```\na\n~~~\n\nb\n```', 'z\n']);
  });

  it('does not treat a backtick info string containing a backtick as a fence', () => {
    expect(sources('``` a`b\n\nz\n')).toEqual(['``` a`b', 'z\n']);
  });

  it('never splits a table', () => {
    const table = '| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\nafter\n';
    expect(sources(table)).toEqual(['| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |', 'after\n']);
  });

  it('keeps a loose list together across blank lines', () => {
    expect(sources('- a\n\n- b\n\n- c\n\nPara\n')).toEqual(['- a\n\n- b\n\n- c', 'Para\n']);
    expect(sources('1. a\n\n2. b\n\nPara\n')).toEqual(['1. a\n\n2. b', 'Para\n']);
  });

  it('keeps indented continuations (list paragraphs, indented code, footnotes)', () => {
    expect(sources('- a\n\n  more of a\n\nPara\n')).toEqual(['- a\n\n  more of a', 'Para\n']);
    expect(sources('    code\n\n    more code\n\nPara\n')).toEqual(['    code\n\n    more code', 'Para\n']);
    expect(sources('[^1]: note\n\n    more note\n\nz\n')).toEqual(['[^1]: note\n\n    more note', 'z\n']);
  });

  it('treats an indented fence inside a list item as a fence', () => {
    const src = '- item\n\n  ```\n  a\n\nb\n  ```\n\nz\n';
    expect(sources(src)).toEqual(['- item\n\n  ```\n  a\n\nb\n  ```', 'z\n']);
  });

  it('is prefix-stable: closed chunks never change as more text arrives', () => {
    for (const [name, text] of Object.entries(corpus)) {
      const full = splitBlocks(text).stable.map((c) => c.source);
      for (let n = 0; n <= text.length; n++) {
        const partial = splitBlocks(text.slice(0, n)).stable.map((c) => c.source);
        expect(full.slice(0, partial.length), `${name} @${n}`).toEqual(partial);
      }
    }
  });

  it('renders the same as one parse on the corpus (except cross-chunk references)', () => {
    for (const name of ['tables.md', 'code.md', 'tasklists.md', 'autolinks.md', 'languages.md']) {
      const text = corpus[name] ?? '';
      const { stable, tail } = splitBlocks(text);
      expect(stable.length, name).toBeGreaterThan(0);
      const html = (src: string): string =>
        renderToStaticMarkup(createElement(MarkdownBody, { source: src, highlight: false }));
      const chunked = [...stable.map((c) => c.source), tail.source].map(html).join('\n');
      expect(chunked.replace(/\n/g, ''), name).toBe(html(text).replace(/\n/g, ''));
    }
  });

  it('hashes deterministically', () => {
    expect(hashString('abc')).toBe(hashString('abc'));
    expect(hashString('abc')).not.toBe(hashString('abd'));
  });
});
