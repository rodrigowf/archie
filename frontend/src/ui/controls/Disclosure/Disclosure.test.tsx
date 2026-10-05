import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Disclosure } from './Disclosure';

describe('Disclosure', () => {
  it('header button has aria-expanded + aria-controls; the body is outside the button', async () => {
    const onOpenChange = vi.fn();
    const { user, getByRole, getByText, container } = renderUi(
      <Disclosure summary="Thought for 4 s" icon="info" onOpenChange={onOpenChange}>
        <p>Body text</p>
      </Disclosure>,
    );
    const header = getByRole('button', { name: 'Thought for 4 s' });
    expect(header.getAttribute('aria-expanded')).toBe('false');
    const body = document.getElementById(header.getAttribute('aria-controls') ?? '') as HTMLElement;
    expect(body.hidden).toBe(true);
    expect(header.contains(body)).toBe(false);
    expect(header.querySelector('div, p')).toBeNull();
    await user.click(header);
    expect(header.getAttribute('aria-expanded')).toBe('true');
    expect(body.hidden).toBe(false);
    expect(getByText('Body text')).toBeTruthy();
    await user.keyboard('{Enter}');
    expect(header.getAttribute('aria-expanded')).toBe('false');
    await user.keyboard(' ');
    expect(onOpenChange.mock.calls.map((c) => c[0] as boolean)).toEqual([true, false, true]);
    await expectNoAxeViolations(container);
  });

  it('headingLevel wraps the button in a heading; disabled does not toggle', async () => {
    const { user, getByRole } = renderUi(
      <Disclosure summary="Frontmatter" headingLevel={3} disabled>
        x
      </Disclosure>,
    );
    expect(getByRole('heading', { level: 3, name: 'Frontmatter' })).toBeTruthy();
    const header = getByRole('button');
    await user.click(header);
    expect(header.getAttribute('aria-expanded')).toBe('false');
  });
});
