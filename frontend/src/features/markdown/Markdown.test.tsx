import { fireEvent, render, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { corpus } from './__fixtures__/corpus';
import { createMemoryLinkResolver } from './links';
import { Markdown } from './Markdown';

describe('Markdown', () => {
  it('renders GFM tables with inline formatting in cells, inside a scroll region', () => {
    const { container } = render(<Markdown source={corpus['tables.md'] ?? ''} />);
    const tables = container.querySelectorAll('table');
    expect(tables).toHaveLength(2);
    const scroller = tables[1]?.parentElement;
    expect(scroller?.getAttribute('tabindex')).toBe('0');

    const cells = Array.from(tables[0]?.querySelectorAll('td') ?? []);
    expect(cells[0]?.querySelector('strong')?.textContent).toBe('Read');
    expect(cells[1]?.querySelector('code')?.textContent).toBe('ok');
    expect(cells[2]?.querySelector('a')?.getAttribute('href')).toBe('https://example.com/read');
    expect(cells[3]?.querySelector('em')?.textContent).toBe('Grep');
    expect(cells[4]?.querySelector('del')?.textContent).toBe('failed');
    expect(cells[5]?.querySelector('code')?.textContent).toBe('a|b'); // escaped pipe
    expect(cells[7]?.querySelector('strong code')?.textContent).toBe('exit 0');
    const ths = tables[0]?.querySelectorAll('th');
    expect([ths?.[0], ths?.[1], ths?.[2]].map((th) => th?.style.textAlign)).toEqual(['left', 'center', 'right']);
  });

  it('keeps inline formatting in ordinary paragraphs (fixes the compat shim regression)', () => {
    const { container } = render(<Markdown source={corpus['inline.md'] ?? ''} />);
    const p = container.querySelector('p');
    expect(p?.querySelector('strong')?.textContent).toBe('bold');
    expect(p?.querySelector('em')?.textContent).toBe('italic');
    expect(p?.querySelector('code')?.textContent).toBe('inline code');
    expect(p?.querySelector('del')?.textContent).toBe('strikethrough');
    expect(p?.querySelector('a')?.getAttribute('title')).toBe('Title');
    expect(p?.querySelector('strong > a')?.textContent).toBe('a bold link');
    expect(container.textContent).not.toContain('**');
    expect(container.querySelector('blockquote strong')?.textContent).toBe('bold');
    expect(container.querySelector('ul ul a')?.textContent).toBe('link');
  });

  it('opens external links in a new tab with the md-link class', () => {
    const { getByRole } = render(<Markdown source="See [docs](https://example.com/docs)." />);
    const link = getByRole('link', { name: 'docs' });
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.classList.contains('md-link')).toBe(true);
  });

  it('autolinks URLs and emails (GFM autolink literals)', () => {
    const { container } = render(<Markdown source={corpus['autolinks.md'] ?? ''} />);
    const hrefs = Array.from(container.querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('https://example.com/path?q=1');
    expect(hrefs).toContain('http://www.example.org/docs');
    expect(hrefs).toContain('mailto:rodrigo@example.com');
    expect(hrefs).toContain('mailto:ops@example.co.uk');
    expect(hrefs).toContain('https://en.wikipedia.org/wiki/Foo_(bar)');
    expect(hrefs).toContain('https://example.com/end');
    expect(hrefs).not.toContain('mailto:user@example.com'); // preceded by "/"
  });

  it('drops javascript: URLs and renders no raw HTML', () => {
    const { container } = render(<Markdown source={'[x](javascript:alert(1)) <b>raw</b>\n\n<script>alert(1)</script>'} />);
    expect(container.querySelector('a')?.getAttribute('href') ?? '').not.toContain('javascript');
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });

  it('renders task lists with a read-only box and a text alternative', () => {
    const { container } = render(<Markdown source={corpus['tasklists.md'] ?? ''} />);
    expect(container.querySelector('input')).toBeNull();
    const items = Array.from(container.querySelectorAll('li.task-list-item'));
    expect(items).toHaveLength(4);
    const boxes = items.map((li) => li.querySelector('[data-checked]')?.getAttribute('data-checked'));
    expect(boxes).toEqual(['true', 'false', 'false', 'true']);
    expect(items[0]?.textContent).toMatch(/^Done:\s+Port the markdown pipeline/);
    expect(items[1]?.textContent).toMatch(/^To do:\s+Highlight closed fences only/);
    expect(items[1]?.querySelector('strong')?.textContent).toBe('closed');
  });

  it('renders fenced code with and without a language as code blocks', () => {
    const { container } = render(<Markdown source={corpus['code.md'] ?? ''} highlight={false} />);
    const blocks = Array.from(container.querySelectorAll('[data-language]'));
    expect(blocks.map((b) => b.getAttribute('data-language'))).toEqual([
      'python',
      'text',
      'ts',
      'markdown',
      'text', // indented code
      'unknown-lang',
    ]);
    expect(within(blocks[1] as HTMLElement).getByRole('button', { name: /copy/i })).toBeTruthy();
    expect(blocks[1]?.querySelector('code')?.textContent).toBe('plain fence without a language\nsecond line');
    expect(blocks[3]?.querySelector('code')?.textContent).toBe("```js\nconsole.log('nested fence');\n```");
    // Inline code is not a block.
    const inline = render(<Markdown source="use `npm ci` here" />).container;
    expect(inline.querySelector('[data-language]')).toBeNull();
    expect(inline.querySelector('p > code')?.textContent).toBe('npm ci');
  });

  it('keeps footnote anchors in the same tab', () => {
    const { container } = render(<Markdown source={corpus['inline.md'] ?? ''} />);
    const ref = container.querySelector('a[data-footnote-ref]');
    expect(ref?.getAttribute('href')).toMatch(/^#/);
    expect(ref?.getAttribute('target')).toBeNull();
  });

  it('resolves relative memory links in-app through a link resolver', () => {
    const open = vi.fn();
    const resolver = createMemoryLinkResolver('assistant/architecture/voice_subsystem.md', open);
    const { getByRole } = render(<Markdown source={corpus['memory-doc.md'] ?? ''} linkResolver={resolver} />);

    const sibling = getByRole('link', { name: 'wakeword_subsystem.md' });
    expect(sibling.getAttribute('href')).toBe('/memory/assistant/architecture/wakeword_subsystem.md');
    expect(sibling.getAttribute('target')).toBeNull();
    fireEvent.click(sibling);
    expect(open).toHaveBeenCalledWith('assistant/architecture/wakeword_subsystem.md');

    fireEvent.click(getByRole('link', { name: 'SSH' }));
    expect(open).toHaveBeenLastCalledWith('assistant/infrastructure/ssh-remote-execution.md');

    fireEvent.click(getByRole('link', { name: 'the root index' }));
    expect(open).toHaveBeenLastCalledWith('MEMORY.md');

    // Modified clicks keep the browser behaviour (new tab on the raw file).
    open.mockClear();
    const stopNavigation = (e: Event): void => e.preventDefault(); // jsdom cannot navigate
    window.addEventListener('click', stopNavigation);
    fireEvent.click(sibling, { ctrlKey: true });
    window.removeEventListener('click', stopNavigation);
    expect(open).not.toHaveBeenCalled();

    const external = getByRole('link', { name: 'OpenAI docs' });
    expect(external.getAttribute('target')).toBe('_blank');
  });

  it('memoizes by props: same source → no re-render of the parsed tree', () => {
    const { rerender, container } = render(<Markdown source="**x**" />);
    const strong = container.querySelector('strong');
    rerender(<Markdown source="**x**" />);
    expect(container.querySelector('strong')).toBe(strong);
  });

  it('has no axe violations on the corpus', async () => {
    for (const [name, source] of Object.entries(corpus)) {
      const { container, unmount } = render(<Markdown source={source} highlight={false} />);
      await expectNoAxeViolations(container).catch((err: unknown) => {
        throw new Error(`${name}: ${String(err)}`);
      });
      unmount();
    }
  });
});
