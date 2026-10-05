/**
 * Compat project (spec 13 §6.1): renders the markdown corpus with the compat aliases
 * (`languages.compat.ts`) under the RegExp guard, which throws on any lookbehind / named group
 * built at runtime. highlight.js compiles grammars lazily on first use, so every compat language
 * is actually highlighted here, not just registered. Inline formatting must survive in ordinary
 * paragraphs and table cells (fixes inv02 §6.3 #12, the table-only remark-gfm shim).
 */
import { render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { setLowEndForced } from '@/platform';
import { isRegExpGuardInstalled } from '@/test/regexpGuard';
import { corpus, languagesDoc } from './__fixtures__/corpus';
import { languageSamples } from './__fixtures__/languageSamples';
import { loadHighlighter } from './highlight/loader';
import { Markdown } from './Markdown';
import { StreamingMarkdown } from './StreamingMarkdown';

afterEach(() => {
  setLowEndForced(false);
});

describe('markdown on the compat build', () => {
  it('runs under the RegExp guard with the compat registry', async () => {
    expect(isRegExpGuardInstalled()).toBe(true);
    const hl = await loadHighlighter();
    expect(hl.target).toBe('compat');
    expect(await hl.ensureLanguage('swift')).toBe(false);
  });

  it.each(Object.entries(corpus))('renders %s', (_name, source) => {
    const { container } = render(<Markdown source={source} />);
    expect(container.textContent?.length).toBeGreaterThan(0);
  });

  it('highlights every compat language without tripping the guard', async () => {
    // Directly first, so a guard error names its language instead of leaving a block plain.
    const hl = await loadHighlighter();
    for (const [lang, code] of Object.entries(languageSamples)) {
      expect(hl.highlightToReact(code, lang), lang).not.toBeNull();
    }
    const { container } = render(<Markdown source={languagesDoc} />);
    const highlighted = Array.from(container.querySelectorAll('[data-language]'))
      .filter((el) => el.querySelector('code[data-highlighted="true"]'))
      .map((el) => el.getAttribute('data-language'));
    expect(highlighted).toEqual(Object.keys(languageSamples));
  });

  it('leaves main-only languages plain', async () => {
    const { container } = render(<Markdown source={'```swift\nlet x = 1\n```'} />);
    await loadHighlighter();
    await new Promise((r) => setTimeout(r, 10));
    expect(container.querySelector('code[data-highlighted]')).toBeNull();
    expect(container.querySelector('code')?.textContent).toBe('let x = 1');
  });

  it('keeps inline formatting in paragraphs and table cells', () => {
    const { container } = render(<Markdown source={(corpus['inline.md'] ?? '') + '\n\n' + (corpus['tables.md'] ?? '')} />);
    expect(container.textContent).not.toMatch(/\*\*|`/);
    const p = container.querySelector('p');
    expect(p?.querySelector('strong')?.textContent).toBe('bold');
    expect(p?.querySelector('code')?.textContent).toBe('inline code');
    expect(p?.querySelector('a')?.getAttribute('href')).toBe('https://example.com/inline');
    const between = Array.from(container.querySelectorAll('p')).find((el) => el.textContent?.startsWith('A paragraph between'));
    expect(between?.querySelectorAll('strong, em, code, a')).toHaveLength(4);
    const td = container.querySelector('table td');
    expect(td?.querySelector('strong')?.textContent).toBe('Read');
  });

  it('renders autolinks, task lists, strikethrough and footnotes (full GFM)', () => {
    const { container } = render(
      <Markdown source={[corpus['autolinks.md'], corpus['tasklists.md'], corpus['inline.md']].join('\n\n')} />,
    );
    const hrefs = Array.from(container.querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('mailto:rodrigo@example.com');
    expect(hrefs).toContain('http://www.example.org/docs');
    expect(container.querySelectorAll('li.task-list-item')).toHaveLength(4);
    expect(container.querySelector('del')).not.toBeNull();
    expect(container.querySelector('section[data-footnotes]')).not.toBeNull();
  });

  it('low-end: highlights in deferred slices', async () => {
    setLowEndForced(true);
    await loadHighlighter();
    const { container } = render(<Markdown source={'```bash\necho "hi"\n```'} />);
    expect(container.querySelector('code[data-highlighted]')).toBeNull();
    await waitFor(() => expect(container.querySelector('code[data-highlighted="true"]')).not.toBeNull());
  });

  it('streams the corpus and ends with a full parse', () => {
    const text = corpus['code.md'] ?? '';
    const { container, rerender } = render(<StreamingMarkdown source={text.slice(0, 60)} streaming />);
    for (let n = 60; n <= text.length; n += 25) rerender(<StreamingMarkdown source={text.slice(0, n)} streaming />);
    rerender(<StreamingMarkdown source={text} streaming={false} />);
    expect(container.querySelectorAll('[data-language]')).toHaveLength(6);
  });
});
