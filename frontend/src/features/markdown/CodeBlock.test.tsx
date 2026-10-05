import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setLowEndForced } from '@/platform';
import { expectNoAxeViolations } from '@/test/axe';
import { longCode } from './__fixtures__/languageSamples';
import { CodeBlock, LOW_END_HIGHLIGHT_LIMIT } from './CodeBlock';
import { loadHighlighter } from './highlight/loader';
import { pendingHighlightJobs } from './highlight/scheduler';
import { Markdown } from './Markdown';
import { MarkdownPrefsProvider } from './prefs';

const PY = 'def greet(name):\n    return f"Hi {name}"  # hello';

let execCommand: ReturnType<typeof vi.fn>;

beforeEach(() => {
  execCommand = vi.fn(() => true);
  Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true, writable: true });
});

afterEach(() => {
  setLowEndForced(false);
  vi.useRealTimers();
});

describe('CodeBlock', () => {
  it('highlights a known language once the highlighter chunk loads', async () => {
    const { container } = render(<CodeBlock code={PY} lang="python" />);
    await waitFor(() => expect(container.querySelector('code[data-highlighted="true"]')).not.toBeNull());
    const code = container.querySelector('code');
    expect(code?.classList.contains('hljs')).toBe(true);
    expect(code?.querySelector('.hljs-keyword')?.textContent).toBe('def');
    expect(code?.querySelector('.hljs-comment')?.textContent).toBe('# hello');
    expect(code?.textContent).toBe(PY);
  });

  it('highlights synchronously after the chunk is loaded (no plain flash)', async () => {
    await loadHighlighter();
    const { container } = render(<CodeBlock code="let x = 1;" lang="js" />);
    expect(container.querySelector('code[data-highlighted="true"] .hljs-keyword')?.textContent).toBe('let');
  });

  it('renders a fence without a language as a plain block with a Copy button', async () => {
    const { container, getByRole } = render(<CodeBlock code="plain" />);
    expect(container.querySelector('[data-language="text"]')).not.toBeNull();
    expect(getByRole('button', { name: 'Copy text code' })).toBeTruthy();
    await new Promise((r) => setTimeout(r, 0));
    expect(container.querySelector('code[data-highlighted]')).toBeNull();
  });

  it('leaves unknown languages plain (main: not in core or extra set)', async () => {
    const { container } = render(<CodeBlock code="x" lang="no-such-lang" />);
    await loadHighlighter();
    await new Promise((r) => setTimeout(r, 0));
    expect(container.querySelector('code[data-highlighted]')).toBeNull();
    expect(container.querySelector('code')?.textContent).toBe('x');
  });

  it('loads main-only extra languages lazily (swift)', async () => {
    const { container } = render(<CodeBlock code={'let s: String = "a"'} lang="swift" />);
    await waitFor(() => expect(container.querySelector('code[data-highlighted="true"]')).not.toBeNull());
  });

  it('does not highlight when highlight=false (streaming) or the pref is off', async () => {
    await loadHighlighter();
    const off = render(<CodeBlock code={PY} lang="python" highlight={false} />).container;
    expect(off.querySelector('code[data-highlighted]')).toBeNull();
    const pref = render(
      <MarkdownPrefsProvider value={{ syntaxHighlight: false }}>
        <CodeBlock code={PY} lang="python" />
      </MarkdownPrefsProvider>,
    ).container;
    expect(pref.querySelector('code[data-highlighted]')).toBeNull();
  });

  it('copies with the textarea fallback and shows "Copied" for 2 s', async () => {
    vi.useFakeTimers();
    const { getByRole } = render(<CodeBlock code={PY} lang="python" />);
    const button = getByRole('button', { name: 'Copy python code' });
    let copied = '';
    execCommand.mockImplementation(() => {
      copied = (document.activeElement as HTMLTextAreaElement | null)?.value ?? '';
      return true;
    });
    await act(async () => {
      fireEvent.click(button);
      await Promise.resolve();
    });
    expect(execCommand).toHaveBeenCalledWith('copy');
    expect(copied).toBe(PY);
    expect(button.textContent).toBe('Copied');
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(button.textContent).toBe('Copy');
  });

  it('reports a failed copy', async () => {
    execCommand.mockImplementation(() => false);
    const { getByRole } = render(<CodeBlock code="x" />);
    const button = getByRole('button', { name: /copy/i });
    await act(async () => {
      fireEvent.click(button);
      await Promise.resolve();
    });
    expect(button.textContent).toBe('Copy failed');
  });

  it('renders long code in full and copies all of it', async () => {
    const code = longCode(1000); // ~50 KB
    const { container, getByRole } = render(<Markdown source={'```python\n' + code + '\n```'} />);
    expect(container.querySelector('pre code')?.textContent).toBe(code);
    expect(container.querySelector('pre')?.getAttribute('tabindex')).toBe('0');
    let copied = '';
    execCommand.mockImplementation(() => {
      copied = (document.activeElement as HTMLTextAreaElement | null)?.value ?? '';
      return true;
    });
    await act(async () => {
      fireEvent.click(getByRole('button', { name: /copy/i }));
      await Promise.resolve();
    });
    expect(copied).toBe(code);
    // Main highlights it (under the 128 KB cap).
    await waitFor(() => expect(container.querySelector('code[data-highlighted="true"]')).not.toBeNull(), {
      timeout: 5000,
    });
    expect(container.querySelector('pre code')?.textContent).toBe(code);
  });

  it('low-end: defers highlighting to setTimeout slices and skips blocks over 8 KB', async () => {
    setLowEndForced(true);
    await loadHighlighter();
    const small = render(<CodeBlock code={PY} lang="python" />).container;
    // Not synchronous on low-end, even with the chunk loaded.
    expect(small.querySelector('code[data-highlighted]')).toBeNull();
    await waitFor(() => expect(small.querySelector('code[data-highlighted="true"]')).not.toBeNull());

    const big = 'x = 1\n'.repeat(Math.ceil(LOW_END_HIGHLIGHT_LIMIT / 6) + 1);
    expect(big.length).toBeGreaterThan(LOW_END_HIGHLIGHT_LIMIT);
    const large = render(<CodeBlock code={big} lang="python" />).container;
    await new Promise((r) => setTimeout(r, 20));
    expect(large.querySelector('code[data-highlighted]')).toBeNull();
    expect(pendingHighlightJobs()).toBe(0);
  });

  it('has no axe violations', async () => {
    const { container } = render(<CodeBlock code={PY} lang="python" />);
    await expectNoAxeViolations(container);
  });
});
