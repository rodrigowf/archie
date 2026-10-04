/**
 * Scroll behaviour of the message list (spec 13 §5.1 item 5, §5.2; inv02 F-11 load-bearing):
 * freeze buffer while scrolled up, flush on return / "Jump to latest", 150 px near-bottom and
 * 80 px load-more thresholds, prepend anchor restore, re-expansion from memory before REST,
 * trimming to the window cap, auto-scroll keyed on content, tab activation.
 *
 * jsdom has no layout, so a tiny layout model stands in: every row is 100 px tall, the viewport
 * is 300 px, rows sit at `index × 100 − scrollTop`.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initialConversation, type Conversation, type Entry } from '@/protocol';
import { clearSessionRegistry, createManualScheduler, type ManualScheduler, type SessionStoreHandle } from '@/stores';
import { ScrollController } from '@/ui/primitives';
import { ConversationPanel } from '../ConversationPanel';
import { MessageList } from '../MessageList';
import { seedSession, type InertRuntime } from './helpers';

const ROW = 100;
const VIEW = 300;

let scheduler: ManualScheduler;
beforeEach(() => {
  scheduler = createManualScheduler();
});
afterEach(() => {
  clearSessionRegistry();
  vi.restoreAllMocks();
});

const u = (id: string, text = id): Entry => ({ id, kind: 'user', text, origin: 'history', state: 'sent' });
const range = (a: number, b: number, p = 'e'): Entry[] => Array.from({ length: b - a }, (_, i) => u(`${p}${a + i}`));

function conv(entries: Entry[], over: Partial<Conversation> = {}): Conversation {
  const c = initialConversation({ localId: 'S1', kind: 'agent', sdkId: 'sdk-1', provider: 'claude', subscribed: true });
  return { ...c, entries, history: { loaded: true, startIndex: 0, totalCount: entries.length, hasMore: false }, ...over };
}

/** The layout model on the scroll element. */
function layout(el: HTMLElement): { top(): number; setTop(v: number): void } {
  let top = 0;
  const rows = (): Element[] => Array.from(el.querySelectorAll('[data-entry-id]'));
  const height = (): number => rows().length * ROW;
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: height });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => VIEW });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = Math.max(0, Math.min(v, height() - VIEW));
    },
  });
  const rect = (y: number, h: number): DOMRect => ({ top: y, bottom: y + h, left: 0, right: 0, width: 0, height: h, x: 0, y, toJSON: () => ({}) }) as DOMRect;
  const orig = Element.prototype.getBoundingClientRect;
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this === el) return rect(0, VIEW);
    const i = rows().indexOf(this);
    return i >= 0 ? rect(i * ROW - top, ROW) : orig.call(this);
  });
  return {
    top: () => top,
    setTop: (v: number) => {
      top = v;
    },
  };
}

function rowIds(): string[] {
  return Array.from(document.querySelectorAll('[data-entry-id]')).map((r) => r.getAttribute('data-entry-id') ?? '');
}

async function publish(handle: SessionStoreHandle, c: Conversation): Promise<void> {
  await act(async () => {
    handle.setConv(c);
    scheduler.flush();
    await Promise.resolve();
  });
}

/** User scroll to `top`, then let momentum settle (QUIET_MS = 100). */
async function userScroll(el: HTMLElement, l: ReturnType<typeof layout>, top: number): Promise<void> {
  l.setTop(top);
  fireEvent.scroll(el);
  await act(async () => {
    await new Promise((r) => setTimeout(r, 130));
  });
}

function setup(
  entries: Entry[],
  over: Partial<Conversation> = {},
  cap?: number,
): { handle: SessionStoreHandle; runtime: InertRuntime; el: HTMLElement; l: ReturnType<typeof layout>; c: Conversation } {
  const c = conv(entries, over);
  const { handle, runtime } = seedSession(c, { scheduler });
  if (cap === undefined) render(<ConversationPanel localId="S1" hidden={false} />);
  else render(<MessageList localId="S1" store={handle.store} hidden={false} cap={cap} />);
  const el = screen.getByLabelText('Conversation');
  const l = layout(el);
  return { handle, runtime, el, l, c };
}

describe('freeze buffer (inv02 F-11)', () => {
  it('holds new entries while scrolled up and flushes on "Jump to latest"', async () => {
    const { handle, el, l, c } = setup(range(0, 6));
    await userScroll(el, l, 300); // at the bottom (600 − 300)
    expect(screen.queryByRole('button', { name: /Jump to latest|new message/ })).toBeNull();
    await userScroll(el, l, 100); // 200 px from the bottom > 150
    expect(screen.getByRole('button', { name: 'Jump to latest' })).toBeTruthy();
    await publish(handle, { ...c, entries: [...c.entries, u('n1'), u('n2')] });
    expect(rowIds()).toEqual(['e0', 'e1', 'e2', 'e3', 'e4', 'e5']);
    expect(l.top()).toBe(100); // the viewport did not move
    fireEvent.click(screen.getByRole('button', { name: '2 new messages' }));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 130));
    });
    expect(rowIds()).toEqual(['e0', 'e1', 'e2', 'e3', 'e4', 'e5', 'n1', 'n2']);
    expect(l.top()).toBe(8 * ROW - VIEW);
    expect(screen.queryByRole('button', { name: /Jump to latest|new message/ })).toBeNull();
  });

  it('returning within 150 px of the bottom flushes the buffer; 151 px does not', async () => {
    const { handle, el, l, c } = setup(range(0, 6));
    await userScroll(el, l, 100);
    await publish(handle, { ...c, entries: [...c.entries, u('n1')] });
    await userScroll(el, l, 600 - VIEW - 151);
    expect(rowIds()).toHaveLength(6);
    await userScroll(el, l, 600 - VIEW - 150);
    expect(rowIds()).toHaveLength(7);
  });

  it('rendered entries still update in place while frozen (a tool result is never held back)', async () => {
    const { handle, el, l, c } = setup(range(0, 6));
    await userScroll(el, l, 100);
    const edited = c.entries.map((e) => (e.id === 'e5' ? u('e5', 'edited text') : e));
    await publish(handle, { ...c, entries: [...edited, u('n1')] });
    expect(document.querySelector('[data-entry-id="e5"]')?.textContent).toContain('edited text');
    expect(rowIds()).not.toContain('n1');
  });
});

describe('pagination and the window', () => {
  it('loads older history at scrollTop ≤ 80 px, not above (H-4)', async () => {
    const { el, l, runtime } = setup(range(0, 6), { history: { loaded: true, startIndex: 50, totalCount: 56, hasMore: true } });
    await userScroll(el, l, 81);
    expect(runtime.calls.filter((x) => x === 'loadOlder')).toHaveLength(0);
    await userScroll(el, l, 80);
    expect(runtime.calls.filter((x) => x === 'loadOlder')).toHaveLength(1);
  });

  it('a prepended page keeps the first old message where it was on screen', async () => {
    const preserve = vi.spyOn(ScrollController.prototype, 'preserveAnchor');
    const { handle, el, l, c } = setup(range(10, 15), { history: { loaded: true, startIndex: 10, totalCount: 15, hasMore: true } });
    await userScroll(el, l, 40);
    await publish(handle, { ...c, entries: [...range(0, 10), ...c.entries], history: { loaded: true, startIndex: 0, totalCount: 15, hasMore: false } });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 130));
    });
    expect(preserve).toHaveBeenCalledTimes(1);
    expect(rowIds()[0]).toBe('e0');
    expect(rowIds()).toHaveLength(15);
    expect(l.top()).toBe(10 * ROW + 40); // e10 is still 40 px above the viewport top
  });

  it('re-expands from memory before asking REST, through the same anchored path', async () => {
    const preserve = vi.spyOn(ScrollController.prototype, 'preserveAnchor');
    const { el, l, runtime } = setup(range(0, 12), { history: { loaded: true, startIndex: 0, totalCount: 12, hasMore: true } }, 5);
    expect(rowIds()).toEqual(['e7', 'e8', 'e9', 'e10', 'e11']);
    await userScroll(el, l, 0);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 130));
    });
    expect(preserve).toHaveBeenCalledTimes(1);
    expect(rowIds()).toHaveLength(12);
    expect(runtime.calls).not.toContain('loadOlder');
    expect(l.top()).toBe(7 * ROW);
    await userScroll(el, l, 0); // now at the real top: REST
    expect(runtime.calls).toContain('loadOlder');
  });

  it('trims to the cap from the top while at the bottom', async () => {
    const { handle, c } = setup(range(0, 5), {}, 5);
    await publish(handle, { ...c, entries: [...c.entries, u('e5'), u('e6')] });
    expect(rowIds()).toEqual(['e2', 'e3', 'e4', 'e5', 'e6']);
  });
});

describe('auto-scroll and tab activation', () => {
  it('follows content growth while pinned (keyed on content, not on a block count), not while scrolled up', async () => {
    const toBottom = vi.spyOn(ScrollController.prototype, 'scrollToBottom');
    const { handle, el, l, c } = setup(range(0, 6));
    toBottom.mockClear();
    await publish(handle, { ...c, entries: c.entries.map((e) => (e.id === 'e5' ? u('e5', 'longer and longer') : e)) });
    expect(toBottom).toHaveBeenCalled();
    await userScroll(el, l, 0);
    toBottom.mockClear();
    await publish(handle, { ...c, entries: c.entries.map((e) => (e.id === 'e5' ? u('e5', 'even longer text') : e)) });
    expect(toBottom).not.toHaveBeenCalled();
  });

  it('on hidden → visible scrolls to the bottom when it was pinned', () => {
    const toBottom = vi.spyOn(ScrollController.prototype, 'scrollToBottom');
    seedSession(conv(range(0, 3)), { scheduler });
    const { rerender } = render(<ConversationPanel localId="S1" hidden />);
    toBottom.mockClear();
    rerender(<ConversationPanel localId="S1" hidden={false} />);
    expect(toBottom).toHaveBeenCalled();
  });
});
