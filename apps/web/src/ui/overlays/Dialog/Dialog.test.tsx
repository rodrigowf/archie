import { act, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { ConfirmDialog, Dialog } from './Dialog';

function Harness({ fullScreen }: { fullScreen?: boolean | 'compact' }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  return (
    <main>
      <button type="button" onClick={() => setOpen(true)}>
        Open settings
      </button>
      <p data-testid="reason">{reason}</p>
      <Dialog
        open={open}
        fullScreen={fullScreen}
        onClose={(r) => {
          setReason(r);
          setOpen(false);
        }}
        title="Rename session"
        actions={
          <button type="button" onClick={() => setOpen(false)}>
            Save
          </button>
        }
      >
        The new name shows in tabs and history.
      </Dialog>
    </main>
  );
}

beforeEach(() => {
  resetOverlayStackForTests();
});

describe('Dialog', () => {
  it('is a labelled, described modal dialog with focus inside, background hidden, axe clean', async () => {
    const { getByText, getByRole, user, container } = renderUi(<Harness />);
    await user.click(getByText('Open settings'));
    const dlg = getByRole('dialog', { name: 'Rename session' });
    expect(dlg.getAttribute('aria-modal')).toBe('true');
    expect(dlg.getAttribute('aria-describedby')).toBeTruthy();
    expect(document.getElementById(dlg.getAttribute('aria-describedby') ?? '')?.textContent).toContain('new name');
    await waitFor(() => {
      expect(dlg.contains(document.activeElement)).toBe(true);
    });
    // The app root is hidden from assistive tech while the dialog is open.
    expect(container.closest('[aria-hidden="true"]') ?? container.parentElement?.closest('[aria-hidden]')).toBeTruthy();
    // Safari 12: the layer is positioned with explicit offsets, never `inset`.
    const layer = dlg.parentElement as HTMLElement;
    expect(layer.getAttribute('style') ?? '').not.toContain('inset');
    await expectNoAxeViolations(document.body);
  });

  it('Escape closes it and returns focus to the trigger', async () => {
    const { getByText, queryByRole, getByTestId, user } = renderUi(<Harness />);
    const trigger = getByText('Open settings');
    await user.click(trigger);
    await user.keyboard('{Escape}');
    expect(queryByRole('dialog')).toBeNull();
    expect(getByTestId('reason').textContent).toBe('escape');
    await waitFor(() => {
      expect(document.activeElement).toBe(trigger);
    });
  });

  it('a scrim press closes a dismissible dialog', async () => {
    const { getByText, queryByRole, getByTestId, user } = renderUi(<Harness />);
    await user.click(getByText('Open settings'));
    const scrim = document.querySelector('[data-scrim]') as HTMLElement;
    await user.click(scrim);
    expect(queryByRole('dialog')).toBeNull();
    expect(getByTestId('reason').textContent).toBe('outside');
  });

  it('Back (popstate) closes it', async () => {
    const { getByText, queryByRole, getByTestId, user } = renderUi(<Harness />);
    await user.click(getByText('Open settings'));
    act(() => {
      window.history.back();
    });
    await waitFor(() => {
      expect(queryByRole('dialog')).toBeNull();
    });
    expect(getByTestId('reason').textContent).toBe('back');
  });

  it('full-screen dialogs get a close button and a top bar', async () => {
    const { getByText, getByRole, queryByRole, user } = renderUi(<Harness fullScreen />);
    await user.click(getByText('Open settings'));
    const dlg = getByRole('dialog', { name: 'Rename session' });
    await user.click(getByRole('button', { name: 'Close' }));
    expect(queryByRole('dialog')).toBeNull();
    expect(dlg.isConnected).toBe(false);
  });

  it('inline previews render the surface without modal behaviour', () => {
    const { getByRole } = renderUi(
      <Dialog open inline title="Preview" onClose={vi.fn()}>
        Body
      </Dialog>,
    );
    expect(getByRole('dialog', { name: 'Preview' }).hasAttribute('aria-modal')).toBe(false);
    expect(document.body.style.position).toBe('');
  });
});

describe('ConfirmDialog', () => {
  it('destructive: alertdialog, initial focus on Cancel, confirm runs onConfirm', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { getByRole, user } = renderUi(
      <ConfirmDialog open title="Delete this session?" confirmLabel="Delete" destructive onConfirm={onConfirm} onCancel={onCancel}>
        “Refactor voice module” and its 14 turns are removed.
      </ConfirmDialog>,
    );
    const dlg = getByRole('alertdialog', { name: 'Delete this session?' });
    await waitFor(() => {
      expect(document.activeElement).toBe(getByRole('button', { name: 'Cancel' }));
    });
    await user.click(getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await user.click(getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledWith('cancel');
    await expectNoAxeViolations(dlg);
  });

  it('non-destructive: initial focus on the confirm action; Escape cancels', async () => {
    const onCancel = vi.fn();
    const { getByRole, user } = renderUi(
      <ConfirmDialog open title="Fork here?" confirmLabel="Fork" onConfirm={vi.fn()} onCancel={onCancel} />,
    );
    await waitFor(() => {
      expect(document.activeElement).toBe(getByRole('button', { name: 'Fork' }));
    });
    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledWith('escape');
  });
});
