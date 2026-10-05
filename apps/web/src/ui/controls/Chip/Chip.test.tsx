import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Chip } from './Chip';
import { Tag } from './Tag';

describe('Chip', () => {
  it('assist and suggestion chips are buttons; filter chips toggle aria-pressed and show a check', async () => {
    const onSelected = vi.fn();
    const { user, getByRole, container, rerender } = renderUi(
      <div>
        <Chip icon="tv">Plan the TV setup</Chip>
        <Chip variant="suggestion" icon="bolt">
          Energy
        </Chip>
        <Chip variant="filter" selected={false} onSelectedChange={onSelected}>
          Archie
        </Chip>
      </div>,
    );
    expect(getByRole('button', { name: 'Plan the TV setup' }).hasAttribute('aria-pressed')).toBe(false);
    const filter = getByRole('button', { name: 'Archie' });
    expect(filter.getAttribute('aria-pressed')).toBe('false');
    await user.click(filter);
    expect(onSelected).toHaveBeenCalledWith(true);
    rerender(
      <div>
        <Chip variant="filter" selected onSelectedChange={onSelected}>
          Archie
        </Chip>
      </div>,
    );
    expect(getByRole('button', { name: 'Archie' }).getAttribute('aria-pressed')).toBe('true');
    expect(getByRole('button', { name: 'Archie' }).querySelector('svg')?.getAttribute('data-icon')).toBe('check');
    await expectNoAxeViolations(container);
  });

  it('input chip: text plus a named remove button; click, Backspace and Delete remove', async () => {
    const onRemove = vi.fn();
    const { user, getByRole, getByText, container } = renderUi(
      <Chip variant="input" onRemove={onRemove}>
        refactor.md
      </Chip>,
    );
    expect(getByText('refactor.md').closest('button')).toBeNull();
    const remove = getByRole('button', { name: 'Remove refactor.md' });
    await user.click(remove);
    await user.keyboard('{Backspace}');
    await user.keyboard('{Delete}');
    expect(onRemove).toHaveBeenCalledTimes(3);
    await expectNoAxeViolations(container);
  });

  it('disabled chips never fire', async () => {
    const onSelected = vi.fn();
    const onRemove = vi.fn();
    const { user, getAllByRole } = renderUi(
      <div>
        <Chip variant="filter" disabled onSelectedChange={onSelected}>
          A
        </Chip>
        <Chip variant="input" disabled onRemove={onRemove}>
          B
        </Chip>
      </div>,
    );
    for (const b of getAllByRole('button')) {
      expect(b.getAttribute('aria-disabled')).toBe('true');
      await user.click(b);
    }
    expect(onSelected).not.toHaveBeenCalled();
    expect(onRemove).not.toHaveBeenCalled();
  });

  it('Tag is plain text (provider and scope)', async () => {
    const { container, getByText } = renderUi(
      <div>
        <Tag>Claude</Tag>
        <Tag variant="scope" icon="dns">
          Archie (server)
        </Tag>
      </div>,
    );
    expect(getByText('Claude').closest('button')).toBeNull();
    expect(container.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    await expectNoAxeViolations(container);
  });
});
