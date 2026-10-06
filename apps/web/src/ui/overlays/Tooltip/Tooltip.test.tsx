import { act, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { touch } from '@/ui/a11y/touch.testutil';
import { TOOLTIP_DELAY_MS, TOOLTIP_TOUCH_MS, Tooltip } from './Tooltip';

function T() {
  return (
    <Tooltip label="14 turns · $0.82 · context 42%">
      <button type="button" aria-label="Session status">
        Ready
      </button>
    </Tooltip>
  );
}

afterEach(() => {
  vi.useRealTimers();
});

describe('Tooltip', () => {
  it('shows after the hover delay, describes the trigger, and hides on leave', () => {
    vi.useFakeTimers();
    const { getByRole, queryByRole } = renderUi(<T />);
    const btn = getByRole('button', { name: 'Session status' });
    fireEvent.mouseEnter(btn.parentElement as HTMLElement);
    act(() => {
      vi.advanceTimersByTime(TOOLTIP_DELAY_MS - 1);
    });
    expect(queryByRole('tooltip')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    const tip = getByRole('tooltip');
    expect(btn.getAttribute('aria-describedby')).toBe(tip.id);
    // Never the only label.
    expect(btn.getAttribute('aria-label')).toBe('Session status');
    fireEvent.mouseLeave(btn.parentElement as HTMLElement);
    expect(queryByRole('tooltip')).toBeNull();
    expect(btn.hasAttribute('aria-describedby')).toBe(false);
  });

  it('keyboard focus shows it at once; Escape hides it', async () => {
    const { getByRole, queryByRole, user, container } = renderUi(<T />);
    await user.tab();
    await waitFor(() => {
      expect(queryByRole('tooltip')).not.toBeNull();
    });
    await expectNoAxeViolations(container);
    await expectNoAxeViolations(getByRole('tooltip'));
    await user.keyboard('{Escape}');
    expect(queryByRole('tooltip')).toBeNull();
    expect(getByRole('button')).toBe(document.activeElement);
  });

  it('touch long-press shows it for 1.5 s', () => {
    vi.useFakeTimers();
    const { getByRole, queryByRole } = renderUi(<T />);
    const wrap = getByRole('button').parentElement as HTMLElement;
    touch(wrap, 'touchstart', 5, 5);
    act(() => {
      vi.advanceTimersByTime(510);
    });
    expect(queryByRole('tooltip')).not.toBeNull();
    act(() => {
      vi.advanceTimersByTime(TOOLTIP_TOUCH_MS + 10);
    });
    expect(queryByRole('tooltip')).toBeNull();
  });
});
