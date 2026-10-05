import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { overlayStack, pushOverlay, resetOverlayStackForTests } from './overlayStack';

function esc(): KeyboardEvent {
  const e = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
  document.body.dispatchEvent(e);
  return e;
}

function nextPop(): Promise<PopStateEvent> {
  return new Promise((resolve) => {
    window.addEventListener('popstate', (e) => resolve(e), { once: true });
  });
}

describe('overlayStack', () => {
  beforeEach(() => {
    resetOverlayStackForTests();
  });
  afterEach(() => {
    resetOverlayStackForTests();
  });

  it('Escape closes the topmost overlay only', () => {
    const a = vi.fn();
    const b = vi.fn();
    const ha = pushOverlay({ onClose: a });
    const hb = pushOverlay({ onClose: b });
    expect(hb.isTop()).toBe(true);
    expect(ha.isTop()).toBe(false);
    const e = esc();
    expect(b).toHaveBeenCalledWith('escape');
    expect(a).not.toHaveBeenCalled();
    expect(e.defaultPrevented).toBe(true);
    hb.remove();
    esc();
    expect(a).toHaveBeenCalledTimes(1);
    ha.remove();
    expect(overlayStack.size).toBe(0);
  });

  it('an overlay with escape:false swallows Escape instead of passing it down', () => {
    const below = vi.fn();
    const busy = vi.fn();
    const h1 = pushOverlay({ onClose: below });
    const h2 = pushOverlay({ onClose: busy, escape: false });
    esc();
    expect(busy).not.toHaveBeenCalled();
    expect(below).not.toHaveBeenCalled();
    h2.update({ escape: true });
    esc();
    expect(busy).toHaveBeenCalledWith('escape');
    h2.remove();
    h1.remove();
  });

  it('respects a handler that already prevented Escape, and ignores IME composition', () => {
    const fn = vi.fn();
    const h = pushOverlay({ onClose: fn });
    const prevent = (e: Event): void => {
      e.preventDefault();
    };
    document.body.addEventListener('keydown', prevent, { once: true });
    esc();
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, isComposing: true }));
    expect(fn).not.toHaveBeenCalled();
    h.remove();
  });

  it('Back (popstate) closes the topmost history overlay', async () => {
    const start = window.history.length;
    const fn = vi.fn();
    const h = pushOverlay({ onClose: fn, history: true });
    expect(window.history.length).toBe(start + 1);
    const pop = nextPop();
    window.history.back();
    await pop;
    expect(fn).toHaveBeenCalledWith('back');
    h.remove(); // owner reacts; no further history traversal
    await new Promise((r) => setTimeout(r, 20));
    expect(overlayStack.size).toBe(0);
  });

  it('closing programmatically pops its history entry silently (no double pop)', async () => {
    const outer = vi.fn();
    const inner = vi.fn();
    const ho = pushOverlay({ onClose: outer, history: true });
    const hi = pushOverlay({ onClose: inner, history: true });
    const pop = nextPop();
    hi.remove();
    await pop;
    // The silent pop must not close the outer overlay.
    expect(outer).not.toHaveBeenCalled();
    expect(inner).not.toHaveBeenCalled();
    // A real Back now closes the outer one.
    const pop2 = nextPop();
    window.history.back();
    await pop2;
    expect(outer).toHaveBeenCalledWith('back');
    ho.remove();
  });

  it('defers a push requested while a silent pop is in flight', async () => {
    const a = vi.fn();
    const b = vi.fn();
    const ha = pushOverlay({ onClose: a, history: true });
    const pop = nextPop();
    ha.remove();
    await Promise.resolve(); // reconcile queued → history.go(-1) in flight
    await Promise.resolve();
    const hb = pushOverlay({ onClose: b, history: true });
    await pop;
    await new Promise((r) => setTimeout(r, 0));
    expect(b).not.toHaveBeenCalled();
    const pop2 = nextPop();
    window.history.back();
    await pop2;
    expect(b).toHaveBeenCalledWith('back');
    hb.remove();
  });

  it('isTop / ids reflect stack order', () => {
    const h1 = pushOverlay({ onClose: vi.fn() });
    const h2 = pushOverlay({ onClose: vi.fn() });
    expect(overlayStack.ids()).toEqual([h1.id, h2.id]);
    expect(overlayStack.isTop(h2.id)).toBe(true);
    h2.remove();
    h2.remove(); // idempotent
    expect(overlayStack.isTop(h1.id)).toBe(true);
    h1.remove();
  });
});
