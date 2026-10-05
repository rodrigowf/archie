/**
 * Code splitting (spec 13 §5.3–§5.4): markdown and the tool cards arrive in the lazy "rich" chunk.
 * Until it has loaded, a conversation with content shows the "Loading conversation…" state (no
 * half-rendered list that would then jump); afterwards it renders in one pass.
 */
import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.doUnmock('../rich');
  vi.resetModules();
});

describe('lazy rich chunk', () => {
  it('shows the loading state until the chunk arrives, then the whole conversation', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => {
      release = r;
    });
    vi.resetModules();
    vi.doMock('../rich', async (orig) => {
      await gate;
      return orig();
    });
    // Fresh module graph (stores included), so the helpers must come from it too.
    const { ConversationPanel } = await import('../ConversationPanel');
    const { fixtureRun, seedSession } = await import('./helpers');
    const { clearSessionRegistry } = await import('@/stores');
    const { conv } = fixtureRun('text_tool_interleaving');
    seedSession(conv);
    render(<ConversationPanel localId={conv.ref.localId} hidden={false} />);
    expect(screen.getByText('Loading conversation…')).toBeTruthy();
    expect(document.querySelectorAll('[data-entry-id]')).toHaveLength(0);
    release();
    for (let i = 0; i < 300 && screen.queryByText('Loading conversation…'); i++) {
      await act(async () => {
        await new Promise((r) => setTimeout(r, 10));
      });
    }
    expect(screen.queryByText('Loading conversation…')).toBeNull();
    expect(document.querySelectorAll('[data-entry-id]')).toHaveLength(conv.entries.length);
    expect(document.querySelectorAll('[data-tool]')).toHaveLength(2);
    clearSessionRegistry();
  });
});
