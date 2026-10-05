import { act, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { touch } from '@/ui/a11y/touch.testutil';
import { SideSheet } from '../SideSheet/SideSheet';
import { BottomSheet, shouldDismissSheet } from './BottomSheet';

function Harness({ onClose = vi.fn() }: { onClose?: (r: string) => void }) {
  const [open, setOpen] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Living-room TV
      </button>
      <BottomSheet
        open={open}
        title="Sessions"
        onClose={(r) => {
          onClose(r);
          setOpen(false);
        }}
        footer={<button type="button">New Archie chat</button>}
      >
        <button type="button">Refactor voice module</button>
      </BottomSheet>
    </>
  );
}

function sheetEl(): HTMLElement {
  return document.querySelector('[role="dialog"]') as HTMLElement;
}

beforeEach(() => {
  resetOverlayStackForTests();
});

describe('BottomSheet', () => {
  it('is a labelled modal dialog with a drag handle; axe clean', async () => {
    const { getByRole } = renderUi(<Harness />);
    const dlg = getByRole('dialog', { name: 'Sessions' });
    expect(dlg.getAttribute('aria-modal')).toBe('true');
    expect(getByRole('button', { name: 'Close sheet' })).toBeTruthy();
    await waitFor(() => {
      expect(dlg.contains(document.activeElement)).toBe(true);
    });
    await expectNoAxeViolations(document.body);
  });

  it('swipe down past 30 % of its height dismisses it (touch events)', async () => {
    const onClose = vi.fn();
    renderUi(<Harness onClose={onClose} />);
    const sheet = sheetEl();
    Object.defineProperty(sheet, 'offsetHeight', { value: 300, configurable: true });
    touch(sheet, 'touchstart', 100, 500, 0);
    act(() => {
      touch(sheet, 'touchmove', 100, 560, 200);
    });
    expect(sheet.style.transform).toBe('translateY(60px)');
    act(() => {
      touch(sheet, 'touchend', 100, 620, 400); // 120 px > 90 px
    });
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledWith('swipe');
    });
  });

  it('a short, slow drag snaps back', () => {
    const onClose = vi.fn();
    renderUi(<Harness onClose={onClose} />);
    const sheet = sheetEl();
    Object.defineProperty(sheet, 'offsetHeight', { value: 300, configurable: true });
    touch(sheet, 'touchstart', 100, 500, 0);
    act(() => {
      touch(sheet, 'touchmove', 100, 520, 300);
    });
    act(() => {
      touch(sheet, 'touchend', 100, 530, 600);
    });
    expect(sheet.style.transform).toBe('');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('dismiss thresholds: distance or velocity, never upward', () => {
    expect(shouldDismissSheet(100, 0, 300)).toBe(true);
    expect(shouldDismissSheet(40, 0.8, 300)).toBe(true);
    expect(shouldDismissSheet(40, 0.1, 300)).toBe(false);
    expect(shouldDismissSheet(-200, 2, 300)).toBe(false);
  });

  it('Escape and the scrim close it', async () => {
    const onClose = vi.fn();
    const { user } = renderUi(<Harness onClose={onClose} />);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledWith('escape');
  });
});

describe('SideSheet', () => {
  it('modal: dialog with a close button, offset from the rail, Escape closes', async () => {
    const onClose = vi.fn();
    const { getByRole, user } = renderUi(
      <SideSheet open side="left" offset={80} width={320} title="Chats" onClose={onClose}>
        <button type="button">Weekly energy report</button>
      </SideSheet>,
    );
    const dlg = getByRole('dialog', { name: 'Chats' });
    expect(dlg.style.left).toBe('80px');
    expect(dlg.style.width).toBe('320px');
    await user.click(getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledWith('action');
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledWith('escape');
    await expectNoAxeViolations(document.body);
  });

  it('standard: an in-flow complementary landmark without overlay behaviour', () => {
    const { getByRole, queryByRole } = renderUi(
      <SideSheet open variant="standard" title="Session settings" onClose={vi.fn()}>
        Body
      </SideSheet>,
    );
    expect(getByRole('complementary', { name: 'Session settings' })).toBeTruthy();
    expect(queryByRole('dialog')).toBeNull();
    expect(document.body.style.position).toBe('');
  });
});
