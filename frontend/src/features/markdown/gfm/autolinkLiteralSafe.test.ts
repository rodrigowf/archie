/**
 * Oracle test for the lookbehind-free autolink fork (spec 13 §7 W-08 DoD): its mdast output must
 * equal stock `mdast-util-gfm-autolink-literal`'s on the corpus, on targeted edge cases and on a
 * random corpus. Stock is loaded by file path (which the bare-specifier alias does not match),
 * and only here, in the main (`dom`) project: Node supports lookbehind; Safari 12 does not.
 */
import { fromMarkdown } from 'mdast-util-from-markdown';
import { gfmFromMarkdown, gfmToMarkdown } from 'mdast-util-gfm';
import * as aliased from 'mdast-util-gfm-autolink-literal';
import { gfm } from 'micromark-extension-gfm';
import { describe, expect, it } from 'vitest';
import * as stock from '../../../../node_modules/mdast-util-gfm-autolink-literal/lib/index.js';
import { corpus } from '../__fixtures__/corpus';
import { targetAliases } from '../../../../vite.shared';
import * as fork from './autolinkLiteralSafe';

function parseWith(source: string, autolink: typeof fork.gfmAutolinkLiteralFromMarkdown) {
  const [, ...rest] = gfmFromMarkdown();
  return fromMarkdown(source, { extensions: [gfm()], mdastExtensions: [autolink(), ...rest] });
}

function expectSameAsStock(source: string): void {
  expect(parseWith(source, fork.gfmAutolinkLiteralFromMarkdown), JSON.stringify(source)).toEqual(
    parseWith(source, stock.gfmAutolinkLiteralFromMarkdown),
  );
}

/** Deterministic PRNG (mulberry32), so failures reproduce. */
function rng(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const EDGE_CASES = [
  'a@b.com',
  'x a@b.com y',
  'foo/bar@x.com',
  'foo.bar@x.com',
  'a.b-c+d@ex-ample.co.uk.',
  'é@x.com and éa@x.com',
  'user@x.c-',
  'user@x.c_',
  'user@x.c1',
  'a@b.co.c@d.com',
  'a@b.c-d@e.com',
  'a@b.com,c@d.com;e@f.com',
  '-a@b.com',
  '+a@b.com',
  '.a@b.com',
  '"a@b.com"',
  '“a@b.com”',
  '«a@b.com»',
  '😀a@b.com 😀 a@b.com',
  '€a@b.com',
  '__a@b.com__',
  '*a@b.com*',
  '[a@b.com](https://x.y)',
  'see <a@b.com>',
  '`a@b.com`',
  'www.example.com/a_(b)_c).',
  'https://example.com/path?q=1&x=2#frag.',
  'xhttps://example.com',
  '(https://example.com)',
  'mailto:a@b.com',
  'a@b',
  '@b.com',
  'a@@b.com',
  'a\nb@c.com\n@d.com',
];

describe('autolinkLiteralSafe (fork) vs stock', () => {
  it('is what the bare specifier resolves to (the vite alias)', () => {
    expect(aliased.gfmAutolinkLiteralFromMarkdown).toBe(fork.gfmAutolinkLiteralFromMarkdown);
    expect(aliased.gfmAutolinkLiteralFromMarkdown).not.toBe(stock.gfmAutolinkLiteralFromMarkdown);
  });

  it('is aliased for every importer in both builds (vite.shared.ts)', () => {
    // Vitest loads node_modules natively, so inside tests remark-gfm → mdast-util-gfm still gets
    // the stock module (harmless: the output is identical, as this file proves). The bundles
    // resolve every import through Vite, where this alias applies; the compat scanner checks it.
    for (const target of ['main', 'compat'] as const) {
      const alias = targetAliases(target).find(
        (a) => a.find instanceof RegExp && a.find.test('mdast-util-gfm-autolink-literal'),
      );
      expect(alias?.replacement).toMatch(/src\/features\/markdown\/gfm\/autolinkLiteralSafe\.ts$/);
    }
  });

  it('keeps the to-markdown extension identical', () => {
    expect(fork.gfmAutolinkLiteralToMarkdown()).toEqual(stock.gfmAutolinkLiteralToMarkdown());
    expect(gfmToMarkdown().extensions?.[0]).toEqual(stock.gfmAutolinkLiteralToMarkdown());
  });

  it.each(Object.entries(corpus))('matches stock on corpus file %s', (_name, source) => {
    expectSameAsStock(source);
  });

  it.each(EDGE_CASES)('matches stock on %j', (source) => {
    expectSameAsStock(source);
  });

  it('matches stock on 3000 random strings', () => {
    const next = rng(0x5eed);
    const alphabet = ['a', 'b', 'w', 'x', '1', '_', '-', '+', '.', '@', '/', ':', ' ', '(', ')', ',', 'é', '“', '😀', '€', 'www.', 'http://', 'https://', '.com', '\n', '*'];
    for (let n = 0; n < 3000; n++) {
      const len = 1 + Math.floor(next() * 24);
      let s = '';
      for (let i = 0; i < len; i++) s += alphabet[Math.floor(next() * alphabet.length)] ?? '';
      expectSameAsStock(s);
    }
  }, 30_000);

  it('actually links emails and URLs (sanity)', () => {
    const tree = parseWith('mail a@b.com or https://x.org', fork.gfmAutolinkLiteralFromMarkdown);
    const links: string[] = [];
    const walk = (node: { type: string; url?: string; children?: unknown[] }): void => {
      if (node.type === 'link' && node.url) links.push(node.url);
      for (const child of node.children ?? []) walk(child as typeof node);
    };
    walk(tree);
    expect(links).toEqual(['mailto:a@b.com', 'https://x.org']);
  });
});
