import { describe, expect, it, vi } from 'vitest';
import { mediaMatches, watchMedia } from './media';

function fakeWindow(mql: Partial<MediaQueryList> & Record<string, unknown>): Window {
  return { matchMedia: vi.fn(() => mql) } as unknown as Window;
}

describe('media helpers', () => {
  it('uses addEventListener when available', () => {
    const add = vi.fn();
    const remove = vi.fn();
    const win = fakeWindow({ matches: true, addEventListener: add, removeEventListener: remove });
    const cb = vi.fn();
    const off = watchMedia('(min-width: 840px)', cb, win);
    expect(add).toHaveBeenCalledWith('change', expect.any(Function));
    (add.mock.calls[0]?.[1] as (e: { matches: boolean }) => void)({ matches: false });
    expect(cb).toHaveBeenCalledWith(false);
    off();
    expect(remove).toHaveBeenCalled();
    expect(mediaMatches('(min-width: 840px)', win)).toBe(true);
  });

  it('falls back to addListener/removeListener (Safari 12)', () => {
    const addListener = vi.fn();
    const removeListener = vi.fn();
    const win = fakeWindow({ matches: false, addListener, removeListener });
    const cb = vi.fn();
    const off = watchMedia('(prefers-color-scheme: dark)', cb, win);
    (addListener.mock.calls[0]?.[0] as (e: { matches: boolean }) => void)({ matches: true });
    expect(cb).toHaveBeenCalledWith(true);
    off();
    expect(removeListener).toHaveBeenCalled();
  });

  it('is a no-op without matchMedia', () => {
    const win = {} as Window;
    expect(mediaMatches('x', win)).toBe(false);
    expect(() => {
      watchMedia('x', () => undefined, win)();
    }).not.toThrow();
  });
});
