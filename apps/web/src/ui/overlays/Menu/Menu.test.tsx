import { act, fireEvent, waitFor } from '@testing-library/react';
import { useRef, useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { Dialog } from '../Dialog/Dialog';
import { Popover } from '../Popover/Popover';
import { Menu, MenuItem, MenuSeparator } from './Menu';

function SessionMenu({ onRename = vi.fn(), onDelete = vi.fn() }: { onRename?: () => void; onDelete?: () => void }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  return (
    <div>
      <button ref={anchor} type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        Session menu
      </button>
      <button type="button">outside</button>
      <Menu open={open} onClose={() => setOpen(false)} anchor={anchor} aria-label="Session menu">
        <MenuItem icon="edit" shortcut="F2" onSelect={onRename}>
          Rename
        </MenuItem>
        <MenuItem icon="tune">Session settings</MenuItem>
        <MenuItem icon="call_split" disabled>
          Fork
        </MenuItem>
        <MenuSeparator />
        <MenuItem icon="close">Close</MenuItem>
        <MenuItem icon="delete" destructive onSelect={onDelete}>
          Delete
        </MenuItem>
      </Menu>
    </div>
  );
}

beforeEach(() => {
  resetOverlayStackForTests();
});

describe('Menu', () => {
  it('opens with focus on the first item; arrows, Home/End and type-ahead move focus; axe clean', async () => {
    const { getByText, findByRole, getByRole, user } = renderUi(<SessionMenu />);
    await user.click(getByText('Session menu'));
    const menu = await findByRole('menu', { name: 'Session menu' });
    await waitFor(() => {
      expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Rename' }));
    });
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Session settings' }));
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Fork' })); // disabled stays focusable (APG)
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Delete' }));
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Rename' }));
    await user.keyboard('c');
    expect(document.activeElement).toBe(getByRole('menuitem', { name: 'Close' }));
    expect(getByRole('separator')).toBeTruthy();
    await expectNoAxeViolations(menu);
  });

  it('selecting an item runs it, closes the menu and returns focus to the trigger', async () => {
    const onRename = vi.fn();
    const { getByText, findByRole, queryByRole, user } = renderUi(<SessionMenu onRename={onRename} />);
    const trigger = getByText('Session menu');
    await user.click(trigger);
    await findByRole('menu');
    await user.keyboard('{Enter}');
    expect(onRename).toHaveBeenCalledTimes(1);
    expect(queryByRole('menu')).toBeNull();
    await waitFor(() => {
      expect(document.activeElement).toBe(trigger);
    });
  });

  it('disabled items do nothing', async () => {
    const { getByText, findByRole, getByRole, user } = renderUi(<SessionMenu />);
    await user.click(getByText('Session menu'));
    await findByRole('menu');
    await user.click(getByRole('menuitem', { name: 'Fork' }));
    expect(getByRole('menu')).toBeTruthy();
  });

  it('Escape and Tab close it; outside press closes it; pressing the trigger toggles', async () => {
    const { getByText, findByRole, queryByRole, user } = renderUi(<SessionMenu />);
    const trigger = getByText('Session menu');
    await user.click(trigger);
    await findByRole('menu');
    await user.keyboard('{Escape}');
    expect(queryByRole('menu')).toBeNull();

    await user.click(trigger);
    await findByRole('menu');
    await user.tab();
    expect(queryByRole('menu')).toBeNull();

    await user.click(trigger);
    await findByRole('menu');
    fireEvent.mouseDown(getByText('outside'));
    expect(queryByRole('menu')).toBeNull();

    await user.click(trigger);
    await findByRole('menu');
    await user.click(trigger); // anchor press is not "outside": the trigger toggles it closed
    expect(queryByRole('menu')).toBeNull();
  });

  it('a menu inside a dialog: Escape closes the menu only', async () => {
    function InDialog() {
      const [menu, setMenu] = useState(false);
      const anchor = useRef<HTMLButtonElement>(null);
      return (
        <Dialog open onClose={vi.fn()} title="Settings">
          <button ref={anchor} type="button" onClick={() => setMenu(true)}>
            Model
          </button>
          <Menu open={menu} onClose={() => setMenu(false)} anchor={anchor} aria-label="Models">
            <MenuItem>gpt-audio-mini</MenuItem>
          </Menu>
        </Dialog>
      );
    }
    const { findByRole, queryByRole, getByRole, user } = renderUi(<InDialog />);
    await user.click(getByRole('button', { name: 'Model' }));
    await findByRole('menu');
    await waitFor(() => {
      expect(document.activeElement?.getAttribute('role')).toBe('menuitem');
    });
    await user.keyboard('{Escape}');
    expect(queryByRole('menu')).toBeNull();
    expect(getByRole('dialog', { name: 'Settings' })).toBeTruthy();
  });

  it('radio items expose aria-checked', async () => {
    const { findAllByRole } = renderUi(
      <Menu open inline onClose={vi.fn()} anchor={{ getBoundingClientRect: () => new DOMRect() }} aria-label="Tabs">
        <MenuItem kind="radio" checked>
          Living-room TV
        </MenuItem>
        <MenuItem kind="radio">Energy</MenuItem>
      </Menu>,
    );
    const items = await findAllByRole('menuitemradio');
    expect(items.map((i) => i.getAttribute('aria-checked'))).toEqual(['true', 'false']);
  });
});

describe('Popover', () => {
  it('positions next to the anchor and repositions on scroll and resize', async () => {
    let top = 100;
    function P() {
      const anchor = useRef<HTMLButtonElement>(null);
      return (
        <>
          <button ref={anchor} type="button">
            anchor
          </button>
          <Popover open onClose={vi.fn()} anchor={anchor} aria-label="Info" placement="bottom-start">
            hello
          </Popover>
        </>
      );
    }
    const rect = (): DOMRect => new DOMRect(40, top, 80, 32);
    const spy = vi.spyOn(HTMLButtonElement.prototype, 'getBoundingClientRect').mockImplementation(rect);
    // jsdom has no layout: give the viewport a size so flip() has room below the anchor.
    const html = document.documentElement;
    Object.defineProperty(html, 'clientWidth', { value: 1200, configurable: true });
    Object.defineProperty(html, 'clientHeight', { value: 900, configurable: true });
    try {
      const { findByRole } = renderUi(<P />);
      const pop = await findByRole('dialog', { name: 'Info' });
      await waitFor(() => {
        expect(pop.style.top).toBe('136px'); // 100 + 32 + offset 4
      });
      expect(pop.style.left).toBe('40px');
      expect(pop.style.position).toBe('fixed');
      top = 300;
      act(() => {
        window.dispatchEvent(new Event('resize'));
      });
      await waitFor(() => {
        expect(pop.style.top).toBe('336px');
      });
      top = 200;
      act(() => {
        document.dispatchEvent(new Event('scroll'));
        window.dispatchEvent(new Event('scroll'));
      });
      await waitFor(() => {
        expect(pop.style.top).toBe('236px');
      });
    } finally {
      spy.mockRestore();
      delete (html as unknown as Record<string, unknown>).clientWidth;
      delete (html as unknown as Record<string, unknown>).clientHeight;
    }
  });
});
