/**
 * Per-build language registries (spec 13 §2.4). This file runs in the main (`dom`) project, where
 * the alias resolves `@/features/markdown/highlight/languages` to languages.main.ts. The compat
 * side is covered by `markdown.compat.test.tsx`.
 */
import hljs from 'highlight.js/lib/core';
import { describe, expect, it } from 'vitest';
import { languageSamples } from '../__fixtures__/languageSamples';
import { languageRegistry as compatRegistry } from './languages.compat';
import { coreLanguages } from './languages.core';
import { EXTRA_LANGUAGE_ALIASES, EXTRA_LANGUAGE_LOADERS, languageRegistry as mainRegistry } from './languages.main';
import * as hl from './highlighter';

const SPEC_CORE = [
  'bash', 'shell', 'javascript', 'typescript', 'json', 'python', 'kotlin', 'java', 'css', 'scss',
  'xml', 'yaml', 'markdown', 'diff', 'sql', 'go', 'rust', 'c', 'cpp', 'csharp', 'dockerfile', 'ini',
  'makefile', 'nginx', 'php', 'ruby', 'lua', 'powershell', 'plaintext',
];

function namesAndAliases(grammars: typeof coreLanguages): string[] {
  return Object.entries(grammars).flatMap(([name, fn]) => [name, ...(fn(hljs).aliases ?? [])]);
}

describe('language registries', () => {
  it('core = the spec 13 §2.4 list, on both builds', () => {
    expect(Object.keys(coreLanguages).sort()).toEqual([...SPEC_CORE].sort());
    expect(compatRegistry.core).toBe(coreLanguages);
  });

  it('compat has no extra languages', () => {
    expect(compatRegistry.target).toBe('compat');
    expect(compatRegistry.extraNames).toEqual([]);
    expect(compatRegistry.loadExtra).toBeNull();
  });

  it('main uses the main registry through the alias', () => {
    expect(hl.target).toBe('main');
  });

  it('extra languages: every name and alias loads a grammar that declares it, none clash with core', async () => {
    const core = new Set(namesAndAliases(coreLanguages));
    for (const name of mainRegistry.extraNames) expect(core.has(name), name).toBe(false);
    for (const [name, load] of Object.entries(EXTRA_LANGUAGE_LOADERS)) {
      const grammar = (await load()).default(hljs);
      const declared = (grammar.aliases ?? []).filter((a) => !core.has(a));
      const listed = Object.entries(EXTRA_LANGUAGE_ALIASES)
        .filter(([, target]) => target === name)
        .map(([alias]) => alias);
      expect(listed.sort(), name).toEqual([...new Set(declared)].sort());
    }
  });

  it('includes the lookbehind grammars only as main extras', () => {
    for (const name of ['swift', 'scala', 'haskell', 'fsharp', 'r', 'gcode']) {
      expect(name in EXTRA_LANGUAGE_LOADERS, name).toBe(true);
      expect(name in coreLanguages, name).toBe(false);
    }
  });
});

describe('highlighter', () => {
  it('highlights every core language sample', () => {
    for (const [lang, code] of Object.entries(languageSamples)) {
      expect(hl.isRegistered(lang), lang).toBe(true);
      expect(hl.highlightToReact(code, lang), lang).not.toBeNull();
    }
  });

  it('resolves aliases and normalizes case', () => {
    for (const alias of ['js', 'TS', 'py', 'sh', 'yml', 'html', 'toml', 'docker', 'golang', 'rs', 'c++', 'cs', 'kt', 'ps1', 'md', 'patch']) {
      expect(hl.isRegistered(alias), alias).toBe(true);
    }
  });

  it('loads extras on demand and reports unknown languages', async () => {
    expect(hl.needsExtra('nosuchlang')).toBe(false);
    expect(await hl.ensureLanguage('nosuchlang')).toBe(false);
    expect(hl.needsExtra('hs')).toBe(true);
    expect(await hl.ensureLanguage('hs')).toBe(true);
    expect(hl.isRegistered('haskell')).toBe(true);
    expect(hl.needsExtra('swift')).toBe(true); // one chunk per language
    expect(await hl.ensureLanguage('Swift')).toBe(true);
  });

  it('caches results', () => {
    const a = hl.highlightToReact('const a = 1;', 'javascript');
    expect(hl.highlightToReact('const a = 1;', 'JavaScript')).toBe(a);
  });
});
