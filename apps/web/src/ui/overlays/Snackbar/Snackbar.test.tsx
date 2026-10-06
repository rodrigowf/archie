import { act, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { BusyOverlay } from '../BusyOverlay/BusyOverlay';
import { SNACKBAR_ACTION_MS, SNACKBAR_MS, SnackbarHost, snackbarDuration, useSnackbarQueue } from './Snackbar';

function QueueHarness() {
  const q = useSnackbarQueue();
  return (
    <>
      <button type="button" onClick={() => q.show({ message: 'Saved' })}>
        save
      </button>
      <button type="button" onClick={() => q.show({ message: 'Server said: "voice model not available"', action: { label: 'Retry', onAction: () => q.show({ message: 'Retrying' }) } })}>
        fail
      </button>
      <SnackbarHost snackbar={q.current} onDismiss={q.dismiss} />
    </>
  );
}

describe('Snackbar', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: false });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('durations: 4 s, 8 s with an action, null = until dismissed', () => {
    expect(snackbarDuration({ id: 1, message: 'x' })).toBe(SNACKBAR_MS);
    expect(snackbarDuration({ id: 1, message: 'x', action: { label: 'Undo', onAction: vi.fn() } })).toBe(SNACKBAR_ACTION_MS);
    expect(snackbarDuration({ id: 1, message: 'x', duration: null })).toBe(null);
  });

  it('the host is a persistent polite live region; snackbars queue and auto-hide', () => {
    const { getByText, getByRole, queryByText } = renderUi(<QueueHarness />);
    const status = getByRole('status');
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.hasAttribute('data-a11y-keep')).toBe(true);
    fireEvent.click(getByText('save'));
    fireEvent.click(getByText('fail'));
    expect(getByText('Saved')).toBeTruthy();
    expect(queryByText(/Server said/)).toBeNull();
    act(() => {
      vi.advanceTimersByTime(SNACKBAR_MS);
    });
    expect(queryByText('Saved')).toBeNull();
    expect(getByText(/Server said/)).toBeTruthy();
  });

  it('pauses while hovered; the action runs and dismisses', () => {
    const { getByText, getByRole, queryByText } = renderUi(<QueueHarness />);
    fireEvent.click(getByText('fail'));
    fireEvent.mouseEnter(getByRole('status'));
    act(() => {
      vi.advanceTimersByTime(SNACKBAR_ACTION_MS * 2);
    });
    expect(getByText(/Server said/)).toBeTruthy();
    fireEvent.mouseLeave(getByRole('status'));
    fireEvent.click(getByRole('button', { name: 'Retry' }));
    expect(queryByText(/Server said/)).toBeNull();
    expect(getByText('Retrying')).toBeTruthy();
  });

  it('a snackbar without auto-hide shows a Dismiss button; axe clean', async () => {
    vi.useRealTimers();
    const onDismiss = vi.fn();
    const { getByRole } = renderUi(<SnackbarHost snackbar={{ id: 'tv', message: 'Showing on Living-room TV', duration: null }} onDismiss={onDismiss} />);
    fireEvent.click(getByRole('button', { name: 'Dismiss' }));
    expect(onDismiss).toHaveBeenCalledWith('tv', 'dismiss');
    await expectNoAxeViolations(document.body);
  });
});

describe('BusyOverlay', () => {
  it('is a busy status with its label, positioned with explicit offsets', async () => {
    const { getByRole } = renderUi(
      <div style={{ position: 'relative' }}>
        <BusyOverlay label="Rewinding…" />
      </div>,
    );
    const status = getByRole('status');
    expect(status.getAttribute('aria-busy')).toBe('true');
    expect(status.textContent).toBe('Rewinding…');
    await expectNoAxeViolations(document.body);
  });
});
