import { describe, expect, it } from 'vitest';
import { corpus } from './__fixtures__/corpus';
import { splitFrontmatter, summarizeFrontmatter } from './frontmatter';

describe('splitFrontmatter (inv02 F-37)', () => {
  it('splits a leading YAML block off verbatim', () => {
    const { frontmatter, body } = splitFrontmatter(corpus['memory-doc.md'] ?? '');
    expect(frontmatter?.startsWith('category: architecture\n')).toBe(true);
    expect(frontmatter?.endsWith('  - wakeword_subsystem.md')).toBe(true);
    expect(body.startsWith('# Voice subsystem\n')).toBe(true);
  });

  it('is CRLF tolerant', () => {
    expect(splitFrontmatter('---\r\na: 1\r\nb: 2\r\n---\r\n# Body\r\n')).toEqual({
      frontmatter: 'a: 1\r\nb: 2',
      body: '# Body\r\n',
    });
  });

  it('accepts an empty block, a BOM, and a file that is only frontmatter', () => {
    expect(splitFrontmatter('---\n---\nbody')).toEqual({ frontmatter: '', body: 'body' });
    expect(splitFrontmatter('﻿---\na: 1\n---\nbody')).toEqual({ frontmatter: 'a: 1', body: 'body' });
    expect(splitFrontmatter('---\na: 1\n---')).toEqual({ frontmatter: 'a: 1', body: '' });
  });

  it('leaves files without leading frontmatter untouched', () => {
    for (const text of ['# Title\n\n---\na: 1\n---\n', 'text', ' ---\na\n---\n', '---\nunclosed']) {
      expect(splitFrontmatter(text)).toEqual({ frontmatter: null, body: text });
    }
  });

  it('does not stop at a "---" inside a value line', () => {
    expect(splitFrontmatter('---\ntitle: a---b\n---\nbody').frontmatter).toBe('title: a---b');
  });
});

describe('summarizeFrontmatter', () => {
  it('reads top-level scalars and list lengths for the summary chip', () => {
    const { frontmatter } = splitFrontmatter(corpus['memory-doc.md'] ?? '');
    const summary = summarizeFrontmatter(frontmatter ?? '');
    expect(summary.fields.category).toBe('architecture');
    expect(summary.fields.source).toBe('curated (refactor docs)');
    expect(summary.listLengths.references).toBe(2);
    expect(summary.listLengths.tags).toBe(2);
  });

  it('strips quotes and ignores comments and nested maps', () => {
    const s = summarizeFrontmatter('# c\ntitle: "Hi"\nmeta:\n  a: 1\nrefs: []\n');
    expect(s.fields).toEqual({ title: 'Hi' });
    expect(s.listLengths).toEqual({ meta: 0, refs: 0 });
  });
});
