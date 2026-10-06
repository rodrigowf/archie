import { act, fireEvent, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { touch } from '@/ui/a11y/touch.testutil';
import { matchTabShortcut, resolveTabShortcut, useTabShortcuts } from './shortcuts';
import { TabPanel, Tabs, type TabItem } from './Tabs';

const SESSIONS: TabItem[] = [
  { id: 'archie', label: 'Living-room TV', pinned: true, closable: true, statusLabel: 'Waiting for approval' },
  { id: 'voice', label: 'Refactor voice module', icon: 'terminal', closable: true, meta: <span>Claude</span> },
  { id: 'energy', label: 'Energy dashboard', icon: 'terminal', closable: true, unseen: true },
  { id: 'doc', label: 'voice_subsystem.md', icon: 'description', closable: true },
];

function Strip(props: { onClose?: (id: string) => void; onRename?: (id: string) => void; onReorder?: (id: string, i: number) => void; activation?: 'automatic' | 'manual' }) {
  const [value, setValue] = useState<string | null>('voice');
  const [items, setItems] = useState(SESSIONS);
  useTabShortcuts({
    ids: items.map((t) => t.id),
    active: value,
    onActivate: setValue,
    onClose: (id) => props.onClose?.(id),
  });
  return (
    <>
      <Tabs
        variant="strip"
        aria-label="Open sessions"
        items={items}
        value={value}
        onChange={setValue}
        activation={props.activation}
        onClose={props.onClose}
        onRename={props.onRename}
        onReorder={(id, to) => {
          props.onReorder?.(id, to);
          setItems((list) => {
            const from = list.findIndex((t) => t.id === id);
            const next = list.slice();
            const [moved] = next.splice(from, 1);
            if (moved) next.splice(to, 0, moved);
            return next;
          });
        }}
      />
      <output data-testid="active">{value}</output>
    </>
  );
}

beforeEach(() => {
  resetOverlayStackForTests();
});

describe('Tabs (strip)', () => {
  it('has tablist/tab roles, one tab stop, descriptions, and no nested interactive content; axe clean', async () => {
    const { getByRole, getAllByRole, container } = renderUi(<Strip onClose={vi.fn()} />);
    const list = getByRole('tablist', { name: 'Open sessions' });
    const tabs = within(list).getAllByRole('tab');
    expect(tabs).toHaveLength(4);
    expect(tabs.map((t) => t.tabIndex)).toEqual([-1, 0, -1, -1]);
    expect(getByRole('tab', { name: 'Refactor voice module' }).getAttribute('aria-selected')).toBe('true');
    expect(getByRole('tab', { name: 'Living-room TV' }).getAttribute('aria-keyshortcuts')).toBe('Delete');
    expect(getByRole('tab', { name: 'Living-room TV', description: 'Waiting for approval' })).toBeTruthy();
    expect(getByRole('tab', { name: 'Energy dashboard', description: 'New activity' })).toBeTruthy();
    // The × is mouse-only: hidden from the accessibility tree, not a tab stop.
    expect(getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['All tabs (4)']);
    await expectNoAxeViolations(container);
  });

  it('arrows move focus and activate (automatic); Home/End jump', async () => {
    const { getByRole, getByTestId, user } = renderUi(<Strip />);
    act(() => {
      getByRole('tab', { name: 'Refactor voice module' }).focus();
    });
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(getByRole('tab', { name: 'Energy dashboard' }));
    expect(getByTestId('active').textContent).toBe('energy');
    await user.keyboard('{End}');
    expect(getByTestId('active').textContent).toBe('doc');
    await user.keyboard('{ArrowRight}');
    expect(getByTestId('active').textContent).toBe('archie'); // wraps
    await user.keyboard('{Home}{ArrowLeft}');
    expect(getByTestId('active').textContent).toBe('doc');
  });

  it('manual activation: arrows only move focus, Enter activates', async () => {
    const { getByRole, getByTestId, user } = renderUi(<Strip activation="manual" />);
    act(() => {
      getByRole('tab', { name: 'Refactor voice module' }).focus();
    });
    await user.keyboard('{ArrowRight}');
    expect(getByTestId('active').textContent).toBe('voice');
    await user.keyboard('{Enter}');
    expect(getByTestId('active').textContent).toBe('energy');
  });

  it('closes with Delete, the × (click), and middle-click; renames with F2, double-click and long-press', async () => {
    const onClose = vi.fn();
    const onRename = vi.fn();
    const { getByRole, user } = renderUi(<Strip onClose={onClose} onRename={onRename} />);
    const tab = getByRole('tab', { name: 'Energy dashboard' });
    act(() => {
      tab.focus();
    });
    await user.keyboard('{Delete}');
    expect(onClose).toHaveBeenLastCalledWith('energy');
    await user.keyboard('{F2}');
    expect(onRename).toHaveBeenLastCalledWith('energy');

    const wrap = tab.parentElement as HTMLElement;
    fireEvent.click(wrap.querySelector('[data-tab-close]') as HTMLElement);
    expect(onClose).toHaveBeenCalledTimes(2);
    const down = fireEvent.mouseDown(wrap, { button: 1 });
    expect(down).toBe(false); // default prevented: no autoscroll
    fireEvent.mouseUp(wrap, { button: 1 });
    expect(onClose).toHaveBeenCalledTimes(3);

    fireEvent.doubleClick(wrap);
    expect(onRename).toHaveBeenCalledTimes(2);
    fireEvent.contextMenu(wrap);
    expect(onRename).toHaveBeenCalledTimes(3);

    vi.useFakeTimers();
    try {
      touch(wrap, 'touchstart', 10, 10);
      act(() => {
        vi.advanceTimersByTime(520);
      });
      expect(onRename).toHaveBeenCalledTimes(4);
    } finally {
      vi.useRealTimers();
    }
  });

  it('Ctrl+Shift+arrows reorder; pinned tabs stay first', async () => {
    const onReorder = vi.fn();
    const { getByRole, getAllByRole, user } = renderUi(<Strip onReorder={onReorder} />);
    act(() => {
      getByRole('tab', { name: 'Energy dashboard' }).focus();
    });
    await user.keyboard('{Control>}{Shift>}{ArrowLeft}{/Shift}{/Control}');
    expect(onReorder).toHaveBeenLastCalledWith('energy', 1);
    expect(getAllByRole('tab').map((t) => t.textContent)).toEqual(['Living-room TV', 'Energy dashboard', 'Refactor voice module', 'voice_subsystem.md']);
    act(() => {
      getByRole('tab', { name: 'Energy dashboard' }).focus();
    });
    await user.keyboard('{Control>}{Shift>}{ArrowLeft}{/Shift}{/Control}');
    expect(onReorder).toHaveBeenCalledTimes(1); // cannot pass the pinned Archie tab
  });

  it('P-7 shortcuts: Ctrl+Alt+→/←, Ctrl+Alt+1…9, Ctrl+Alt+W', async () => {
    const onClose = vi.fn();
    const { getByTestId, user } = renderUi(<Strip onClose={onClose} />);
    await user.keyboard('{Control>}{Alt>}{ArrowRight}{/Alt}{/Control}');
    expect(getByTestId('active').textContent).toBe('energy');
    await user.keyboard('{Control>}{Alt>}{ArrowLeft}{ArrowLeft}{/Alt}{/Control}');
    expect(getByTestId('active').textContent).toBe('archie');
    fireEvent.keyDown(document, { key: '3', code: 'Digit3', ctrlKey: true, altKey: true });
    expect(getByTestId('active').textContent).toBe('energy');
    fireEvent.keyDown(document, { key: '9', code: 'Digit9', ctrlKey: true, altKey: true });
    expect(getByTestId('active').textContent).toBe('doc');
    fireEvent.keyDown(document, { key: '∑', code: 'KeyW', ctrlKey: true, altKey: true });
    expect(onClose).toHaveBeenCalledWith('doc');
  });

  it('the all-tabs menu lists every tab (checked = active), filters by search, and activates', async () => {
    const { getByRole, findByRole, queryByRole, getByTestId, user } = renderUi(<Strip />);
    await user.click(getByRole('button', { name: 'All tabs (4)' }));
    const menu = await findByRole('menu', { name: 'All tabs' });
    const search = getByRole('searchbox', { name: 'Search tabs' });
    await waitFor(() => {
      expect(document.activeElement).toBe(search);
    });
    expect(within(menu).getAllByRole('menuitemradio')).toHaveLength(4);
    expect(within(menu).getByRole('menuitemradio', { name: /^Refactor voice module/ }).getAttribute('aria-checked')).toBe('true');
    await user.type(search, 'energy');
    expect(within(menu).getAllByRole('menuitemradio')).toHaveLength(1);
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(within(menu).getByRole('menuitemradio', { name: /^Energy dashboard/ }));
    await user.keyboard('{Enter}');
    expect(getByTestId('active').textContent).toBe('energy');
    expect(queryByRole('menu')).toBeNull();
  });
});

describe('Tabs (primary / secondary)', () => {
  it('M3 tabs with panels: aria-controls, roving focus, automatic activation; axe clean', async () => {
    function Primary() {
      const [v, setV] = useState('device');
      return (
        <>
          <Tabs
            aria-label="Settings"
            items={[
              { id: 'device', label: 'This device', panelId: 'p-device', icon: 'mobile' },
              { id: 'server', label: 'Archie (server)', panelId: 'p-server', icon: 'dns' },
              { id: 'about', label: 'About', panelId: 'p-about', disabled: true },
            ]}
            value={v}
            onChange={setV}
          />
          <TabPanel id="p-device" hidden={v !== 'device'}>
            Device
          </TabPanel>
          <TabPanel id="p-server" hidden={v !== 'server'}>
            Server
          </TabPanel>
          <TabPanel id="p-about" hidden>
            About
          </TabPanel>
        </>
      );
    }
    const { getByRole, user, container } = renderUi(<Primary />);
    const device = getByRole('tab', { name: 'This device' });
    expect(device.getAttribute('aria-controls')).toBe('p-device');
    expect(getByRole('tabpanel', { name: 'This device' }).textContent).toBe('Device');
    act(() => {
      device.focus();
    });
    await user.keyboard('{ArrowRight}');
    expect(getByRole('tab', { name: 'Archie (server)' }).getAttribute('aria-selected')).toBe('true');
    expect(getByRole('tabpanel', { name: 'Archie (server)' }).textContent).toBe('Server');
    await user.keyboard('{ArrowRight}'); // skips the disabled tab, wraps
    expect(document.activeElement).toBe(device);
    await expectNoAxeViolations(container);
  });
});

describe('shortcut matcher', () => {
  const k = (key: string, code: string, mods: Partial<KeyboardEvent> = {}) => ({ key, code, ctrlKey: true, altKey: true, shiftKey: false, metaKey: false, ...mods });
  it('matches only Ctrl+Alt combinations', () => {
    expect(matchTabShortcut(k('ArrowRight', 'ArrowRight'))).toEqual({ type: 'next' });
    expect(matchTabShortcut(k('1', 'Digit1'))).toEqual({ type: 'goto', index: 0 });
    expect(matchTabShortcut(k('¡', 'Digit1'))).toEqual({ type: 'goto', index: 0 });
    expect(matchTabShortcut(k('9', 'Digit9'))).toEqual({ type: 'last' });
    expect(matchTabShortcut(k('w', 'KeyW', { shiftKey: true }))).toBe(null);
    expect(matchTabShortcut({ ...k('w', 'KeyW'), altKey: false })).toBe(null);
  });
  it('resolves against the open tabs', () => {
    const ids = ['a', 'b', 'c'];
    expect(resolveTabShortcut({ type: 'next' }, ids, 'c')).toEqual({ activate: 'a' });
    expect(resolveTabShortcut({ type: 'prev' }, ids, 'a')).toEqual({ activate: 'c' });
    expect(resolveTabShortcut({ type: 'goto', index: 5 }, ids, 'a')).toBe(null);
    expect(resolveTabShortcut({ type: 'close' }, ids, null)).toBe(null);
    expect(resolveTabShortcut({ type: 'next' }, [], null)).toBe(null);
  });
});
