import { act, fireEvent, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { touch } from '@/ui/a11y/touch.testutil';
import { NavigationDrawer, NavigationDrawerHeadline, NavigationDrawerItem } from './NavigationDrawer/NavigationDrawer';
import { Fab } from '@/ui/controls';
import { NavigationRail } from './NavigationRail/NavigationRail';
import { TopAppBar } from './TopAppBar/TopAppBar';
import { flattenVisible, Tree, type TreeNode } from './Tree/Tree';

beforeEach(() => {
  resetOverlayStackForTests();
});

/* ---------------------------------------------------------------- rail */

function Rail() {
  const [v, setV] = useState('chats');
  return (
    <NavigationRail
      value={v}
      onChange={setV}
      fab={<Fab lowered icon="add" aria-label="New: Archie conversation or agent session" />}
      destinations={[
        { id: 'chats', label: 'Chats', icon: 'forum' },
        { id: 'memory', label: 'Memory', icon: 'book_2', badge: 3, badgeLabel: '3 changed' },
        { id: 'visuals', label: 'Visuals', icon: 'bar_chart' },
      ]}
      endDestinations={[{ id: 'settings', label: 'Settings', icon: 'settings' }]}
    />
  );
}

describe('NavigationRail', () => {
  it('is a navigation landmark with aria-current, filled active icon, one tab stop and arrow keys; axe clean', async () => {
    const { getByRole, user, container } = renderUi(<Rail />);
    expect(getByRole('navigation', { name: 'Main' })).toBeTruthy();
    const chats = getByRole('button', { name: 'Chats' });
    expect(chats.getAttribute('aria-current')).toBe('page');
    expect(chats.querySelector('svg')?.getAttribute('data-icon')).toBe('forum');
    expect(getByRole('button', { name: 'Memory, 3 changed' }).tabIndex).toBe(-1);
    act(() => {
      chats.focus();
    });
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(getByRole('button', { name: 'Memory, 3 changed' }));
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(getByRole('button', { name: 'Settings' }));
    await user.keyboard('{Enter}');
    expect(getByRole('button', { name: 'Settings' }).getAttribute('aria-current')).toBe('page');
    expect(chats.hasAttribute('aria-current')).toBe(false);
    await user.keyboard('{ArrowDown}'); // wraps to the first destination (the FAB is its own tab stop)
    expect(document.activeElement).toBe(chats);
    await expectNoAxeViolations(container);
  });
});

/* ---------------------------------------------------------------- drawer */

function Drawer() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" aria-label="Open navigation" onClick={() => setOpen(true)}>
        ☰
      </button>
      <NavigationDrawer
        open={open}
        onClose={() => setOpen(false)}
        aria-label="Navigation"
        footer={<NavigationDrawerItem icon="settings" label="Settings" />}
      >
        <NavigationDrawerHeadline trailing="3">Open now</NavigationDrawerHeadline>
        <NavigationDrawerItem icon="terminal" label="Refactor voice module" supporting="Claude · Ready" active />
        <NavigationDrawerItem icon="terminal" label="Energy dashboard" />
      </NavigationDrawer>
    </>
  );
}

describe('NavigationDrawer', () => {
  it('modal: dialog with a nav inside, focus moves in, Escape closes and returns focus; axe clean', async () => {
    const { getByRole, queryByRole, user } = renderUi(<Drawer />);
    const trigger = getByRole('button', { name: 'Open navigation' });
    await user.click(trigger);
    const dlg = getByRole('dialog', { name: 'Navigation' });
    expect(dlg.getAttribute('aria-modal')).toBe('true');
    expect(getByRole('navigation', { name: 'Navigation' })).toBeTruthy();
    expect(getByRole('button', { name: /^Refactor voice module/ }).getAttribute('aria-current')).toBe('page');
    await waitFor(() => {
      expect(dlg.contains(document.activeElement)).toBe(true);
    });
    await expectNoAxeViolations(dlg);
    await user.keyboard('{Escape}');
    expect(queryByRole('dialog')).toBeNull();
    await waitFor(() => {
      expect(document.activeElement).toBe(trigger);
    });
  });

  it('a scrim press closes it; standard variant renders in flow', async () => {
    const { getByRole, queryByRole, user } = renderUi(<Drawer />);
    await user.click(getByRole('button', { name: 'Open navigation' }));
    await user.click(document.querySelector('[data-scrim]') as HTMLElement);
    expect(queryByRole('dialog')).toBeNull();

    const s = renderUi(
      <NavigationDrawer open variant="standard" onClose={vi.fn()} aria-label="Sections">
        <NavigationDrawerItem label="Memory" icon="book_2" />
      </NavigationDrawer>,
    );
    expect(s.getByRole('navigation', { name: 'Sections' })).toBeTruthy();
    expect(s.queryByRole('dialog')).toBeNull();
  });
});

/* ---------------------------------------------------------------- top app bar */

describe('TopAppBar', () => {
  it('title button opens the switcher (aria-expanded), subtitle text, swipe switches; axe clean', async () => {
    const onTitle = vi.fn();
    const onSwipe = vi.fn();
    const { getByRole, rerender, container, user } = renderUi(
      <TopAppBar
        leading={<button type="button" aria-label="Open navigation">☰</button>}
        title="Living-room TV"
        subtitle="Waiting for your approval"
        onTitleClick={onTitle}
        titleButtonLabel="Switch session"
        onTitleSwipe={onSwipe}
        actions={<button type="button" aria-label="Session menu">⋮</button>}
      />,
    );
    const title = getByRole('button', { name: /^Switch session.*Living-room TV/ });
    expect(title.getAttribute('aria-expanded')).toBe('false');
    expect(title.getAttribute('aria-haspopup')).toBe('dialog');
    await user.click(title);
    expect(onTitle).toHaveBeenCalledTimes(1);
    touch(title, 'touchstart', 300, 30, 1);
    touch(title, 'touchmove', 240, 32, 40);
    touch(title, 'touchend', 200, 32, 80);
    expect(onSwipe).toHaveBeenCalledWith('left');
    expect(container.querySelector('svg[data-icon="arrow_drop_down"]')).not.toBeNull();
    rerender(<TopAppBar title="Living-room TV" onTitleClick={onTitle} titleExpanded />);
    expect(getByRole('button', { name: /Living-room TV/ }).getAttribute('aria-expanded')).toBe('true');
    await expectNoAxeViolations(container);
  });

  it('plain variant renders a heading', () => {
    const { getByRole } = renderUi(<TopAppBar variant="plain" title="Memory" />);
    expect(getByRole('heading', { level: 1, name: 'Memory' })).toBeTruthy();
  });
});

/* ---------------------------------------------------------------- tree */

const TREE: TreeNode[] = [
  {
    id: 'assistant',
    label: 'assistant',
    children: [
      { id: 'arch', label: 'architecture', children: [{ id: 'voice', label: 'voice_subsystem.md' }, { id: 'wake', label: 'wakeword_subsystem.md' }] },
      { id: 'devices', label: 'devices', children: [{ id: 'tv', label: 'fire_tv.md' }] },
    ],
  },
  { id: 'memory', label: 'MEMORY.md' },
  { id: 'rodrigo', label: 'rodrigo', children: [] },
];

describe('Tree', () => {
  it('flattens only expanded branches', () => {
    expect(flattenVisible(TREE, new Set(['assistant'])).map((v) => `${v.level}:${v.node.id}`)).toEqual([
      '1:assistant',
      '2:arch',
      '2:devices',
      '1:memory',
      '1:rodrigo',
    ]);
  });

  it('implements the APG keyboard model; axe clean', async () => {
    const onSelect = vi.fn();
    const { getByRole, user, container } = renderUi(<Tree nodes={TREE} aria-label="Memory" onSelect={onSelect} />);
    const tree = getByRole('tree', { name: 'Memory' });
    const assistant = getByRole('treeitem', { name: /^assistant/ });
    expect(assistant.getAttribute('aria-expanded')).toBe('false');
    expect(assistant.getAttribute('aria-level')).toBe('1');
    expect(assistant.getAttribute('aria-setsize')).toBe('3');
    expect(assistant.tabIndex).toBe(0);
    act(() => {
      assistant.focus();
    });
    await user.keyboard('{ArrowRight}'); // expand
    expect(assistant.getAttribute('aria-expanded')).toBe('true');
    await user.keyboard('{ArrowRight}'); // first child
    const arch = getByRole('treeitem', { name: /^architecture/ });
    expect(document.activeElement).toBe(arch);
    expect(arch.getAttribute('aria-level')).toBe('2');
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(getByRole('treeitem', { name: /^devices/ }));
    await user.keyboard('{ArrowLeft}'); // collapsed → parent
    expect(document.activeElement).toBe(assistant);
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(getByRole('treeitem', { name: 'rodrigo' }));
    await user.keyboard('{Home}');
    await user.keyboard('m'); // type-ahead
    expect(document.activeElement).toBe(getByRole('treeitem', { name: 'MEMORY.md' }));
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'memory' }));
    await user.keyboard('{Home}{ArrowRight}{ArrowRight}*'); // expand all siblings at level 2
    expect(getByRole('treeitem', { name: /^architecture/ }).getAttribute('aria-expanded')).toBe('true');
    expect(getByRole('treeitem', { name: /^devices/ }).getAttribute('aria-expanded')).toBe('true');
    await user.keyboard('{ArrowLeft}'); // expanded → collapse
    expect(getByRole('treeitem', { name: /^architecture/ }).getAttribute('aria-expanded')).toBe('false');
    expect(tree.querySelectorAll('[tabindex="0"]')).toHaveLength(1);
    await expectNoAxeViolations(container);
  });

  it('click selects leaves and toggles folders', () => {
    const onSelect = vi.fn();
    const { getByText, getByRole } = renderUi(<Tree nodes={TREE} aria-label="Memory" defaultExpanded={['assistant', 'arch']} selectedId="voice" onSelect={onSelect} />);
    expect(getByRole('treeitem', { name: 'voice_subsystem.md' }).getAttribute('aria-selected')).toBe('true');
    fireEvent.click(getByText('wakeword_subsystem.md'));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'wake' }));
    fireEvent.click(getByText('architecture'));
    expect(getByRole('treeitem', { name: /^architecture/ }).getAttribute('aria-expanded')).toBe('false');
  });
});
