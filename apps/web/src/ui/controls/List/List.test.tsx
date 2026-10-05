import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { IconButton } from '../IconButton/IconButton';
import { StatusDot } from '../StatusDot/StatusDot';
import { List, ListItem } from './List';

describe('List and ListItem', () => {
  it('actionable rows are real buttons/links; the trailing action sits beside them, not inside', async () => {
    const onOpen = vi.fn();
    const onMenu = vi.fn();
    const { user, getByRole, getAllByRole, container } = renderUi(
      <List aria-label="Sessions">
        <ListItem
          leading="terminal"
          headline="Refactor voice"
          supporting="Ready"
          trailing={<StatusDot status="idle" />}
          selected
          current="page"
          onClick={onOpen}
          action={<IconButton icon="more_vert" size="small" aria-label="Refactor voice menu" onClick={onMenu} />}
        />
        <ListItem leading="description" headline="voice.md" href="#/memory/voice.md" meta="Tue" />
        <ListItem headline="Static row" />
      </List>,
    );
    expect(getByRole('list', { name: 'Sessions' })).toBeTruthy();
    expect(getAllByRole('listitem')).toHaveLength(3);
    const row = getByRole('button', { name: /^Refactor voice Ready/ });
    expect(row.getAttribute('aria-current')).toBe('page');
    const menu = getByRole('button', { name: 'Refactor voice menu' });
    expect(row.contains(menu)).toBe(false);
    await user.click(row);
    await user.click(menu);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onMenu).toHaveBeenCalledTimes(1);
    expect(getByRole('link', { name: /voice\.md/ }).getAttribute('href')).toBe('#/memory/voice.md');
    expect(getByRole('img', { name: 'Open, idle' })).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it('rows are reachable and activated from the keyboard; disabled rows do not fire', async () => {
    const onA = vi.fn();
    const onB = vi.fn();
    const { user } = renderUi(
      <List aria-label="Rows">
        <ListItem headline="A" onClick={onA} />
        <ListItem headline="B" onClick={onB} disabled />
      </List>,
    );
    await user.tab();
    await user.keyboard('{Enter}');
    await user.tab();
    await user.keyboard('{Enter}');
    expect(onA).toHaveBeenCalledTimes(1);
    expect(onB).not.toHaveBeenCalled();
  });
});
