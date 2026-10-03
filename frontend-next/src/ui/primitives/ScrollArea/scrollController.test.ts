import { beforeEach, describe, expect, it } from 'vitest';
import { QUIET_MS, ScrollController, type ScrollEnv } from './scrollController';

/** Deterministic clock, frames and timers. */
class FakeEnv implements ScrollEnv {
  t = 1000;
  private nextId = 1;
  frames = new Map<number, () => void>();
  timers = new Map<number, { at: number; cb: () => void }>();
  now = (): number => this.t;
  raf = (cb: () => void): number => {
    const id = this.nextId++;
    this.frames.set(id, cb);
    return id;
  };
  cancelRaf = (id: number): void => {
    this.frames.delete(id);
  };
  setTimeout = (cb: () => void, ms: number): number => {
    const id = this.nextId++;
    this.timers.set(id, { at: this.t + ms, cb });
    return id;
  };
  clearTimeout = (id: number): void => {
    this.timers.delete(id);
  };
  frame(): void {
    const cbs = [...this.frames.values()];
    this.frames.clear();
    for (const cb of cbs) cb();
  }
  advance(ms: number): void {
    const end = this.t + ms;
    for (;;) {
      const due = [...this.timers.entries()].filter(([, v]) => v.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      this.timers.delete(due[0]);
      this.t = due[1].at;
      due[1].cb();
    }
    this.t = end;
  }
}

/** A scroll container with controllable geometry (jsdom does no layout). */
function makeContainer(opts: { scrollHeight: number; clientHeight: number }) {
  const el = document.createElement('div');
  let scrollHeight = opts.scrollHeight;
  let top = 0;
  const assignments: { top: number; overflowScrolling: string | undefined }[] = [];
  Object.defineProperty(el, 'scrollHeight', { get: () => scrollHeight });
  Object.defineProperty(el, 'clientHeight', { get: () => opts.clientHeight });
  Object.defineProperty(el, 'scrollTop', {
    get: () => top,
    set: (v: number) => {
      top = Math.min(Math.max(0, v), Math.max(0, scrollHeight - opts.clientHeight));
      assignments.push({ top, overflowScrolling: (el.style as unknown as Record<string, string | undefined>).webkitOverflowScrolling });
    },
  });
  el.getBoundingClientRect = () => ({ top: 100 }) as DOMRect;
  document.body.appendChild(el);
  return {
    el,
    assignments,
    setScrollHeight: (h: number) => {
      scrollHeight = h;
    },
    /** A user scroll: moves without going through the controller, then fires `scroll`. */
    userScrollTo: (v: number) => {
      top = v;
      el.dispatchEvent(new Event('scroll'));
    },
  };
}

const iosStyle = (el: HTMLElement): string | undefined =>
  (el.style as unknown as Record<string, string | undefined>).webkitOverflowScrolling;

describe('ScrollController', () => {
  let env: FakeEnv;
  let c: ScrollController;

  beforeEach(() => {
    document.body.innerHTML = '';
    env = new FakeEnv();
    c = new ScrollController(env);
  });

  it('scrolls to the bottom immediately when idle, iOS-safe (auto → assign → touch next frame)', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    await c.scrollToBottom();
    expect(box.el.scrollTop).toBe(1500);
    expect(box.assignments).toEqual([{ top: 1500, overflowScrolling: 'auto' }]);
    expect(iosStyle(box.el)).toBe('auto');
    env.frame();
    expect(iosStyle(box.el)).toBe('touch');
    expect(c.isNearBottom()).toBe(true);
  });

  it('defers a programmatic scroll while a finger is down, then until momentum is quiet', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    box.el.dispatchEvent(new Event('touchstart'));
    let done = false;
    const p = c.scrollToBottom().then(() => {
      done = true;
    });
    expect(c.isUserScrolling()).toBe(true);
    env.advance(500);
    expect(box.assignments).toHaveLength(0); // still touching: never fight the finger

    box.el.dispatchEvent(new Event('touchend'));
    // Momentum: scroll events keep arriving, each pushing the flush back.
    env.advance(60);
    box.userScrollTo(300);
    env.advance(60);
    box.userScrollTo(340);
    env.advance(QUIET_MS - 1);
    expect(box.assignments).toHaveLength(0);
    env.advance(1); // quiet for QUIET_MS since the last scroll event
    await p;
    expect(done).toBe(true);
    expect(box.assignments).toEqual([{ top: 1500, overflowScrolling: 'auto' }]);
    env.frame();
    expect(iosStyle(box.el)).toBe('touch');
  });

  it('collapses repeated deferred scroll requests into the latest one', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    box.userScrollTo(100); // momentum
    const a = c.scrollTo(200);
    const b = c.scrollTo(400);
    const d = c.scrollToBottom();
    env.advance(QUIET_MS);
    await Promise.all([a, b, d]);
    expect(box.assignments.map((x) => x.top)).toEqual([1500]);
  });

  it('ignores the scroll event caused by its own assignment', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    await c.scrollTo(700);
    box.el.dispatchEvent(new Event('scroll')); // the echo of our own write
    expect(c.isUserScrolling()).toBe(false);
    await c.scrollTo(100);
    expect(box.assignments.map((x) => x.top)).toEqual([700, 100]);
  });

  it('a lifted finger with no momentum flushes QUIET_MS after touchend', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    box.el.dispatchEvent(new Event('touchstart'));
    const p = c.scrollToBottom();
    box.el.dispatchEvent(new Event('touchend'));
    env.advance(QUIET_MS);
    await p;
    expect(box.el.scrollTop).toBe(1500);
  });

  describe('preserveAnchor', () => {
    it('keeps the viewport on the same content after a prepend (scrollHeight delta)', async () => {
      const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
      c.attach(box.el);
      box.userScrollTo(40); // near the top: load-more trigger
      env.advance(QUIET_MS);
      let mutated = false;
      await c.preserveAnchor(() => {
        expect(box.el.style.visibility).toBe('hidden'); // hidden while the content jumps
        box.setScrollHeight(2600); // 600 px of older messages inserted above
        mutated = true;
      });
      expect(mutated).toBe(true);
      expect(box.el.scrollTop).toBe(640);
      expect(box.assignments.at(-1)).toEqual({ top: 640, overflowScrolling: 'auto' });
      expect(box.el.style.visibility).toBe('hidden');
      env.frame(); // shown again on the next frame, momentum scrolling restored
      expect(box.el.style.visibility).toBe('');
      expect(iosStyle(box.el)).toBe('touch');
    });

    it('uses the anchor element offset when given (first old item)', async () => {
      const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
      c.attach(box.el);
      await c.scrollTo(30);
      const anchor = document.createElement('div');
      box.el.appendChild(anchor);
      let anchorTop = 116; // 16 px below the container top
      anchor.getBoundingClientRect = () => ({ top: anchorTop }) as DOMRect;
      await c.preserveAnchor(
        () => {
          box.setScrollHeight(2450);
          anchorTop = 116 + 450; // pushed down by the prepend (scrollTop unchanged)
        },
        { anchor: () => anchor },
      );
      expect(box.el.scrollTop).toBe(30 + 450);
    });

    it('waits for momentum to end before mutating, so the prepend never fights the finger', async () => {
      const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
      c.attach(box.el);
      box.el.dispatchEvent(new Event('touchstart'));
      box.userScrollTo(10);
      let mutated = false;
      const p = c.preserveAnchor(() => {
        box.setScrollHeight(2300);
        mutated = true;
      });
      env.advance(1000);
      expect(mutated).toBe(false);
      box.el.dispatchEvent(new Event('touchend'));
      env.advance(QUIET_MS);
      await p;
      expect(mutated).toBe(true);
      expect(box.el.scrollTop).toBe(310);
    });
  });

  it('isNearBottom uses the 150 px default threshold', () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    c.attach(box.el);
    box.userScrollTo(1350);
    expect(c.distanceFromBottom()).toBe(150);
    expect(c.isNearBottom()).toBe(true);
    box.userScrollTo(1349);
    expect(c.isNearBottom()).toBe(false);
    expect(c.isNearBottom(200)).toBe(true);
  });

  it('detach settles pending requests and removes listeners', async () => {
    const box = makeContainer({ scrollHeight: 2000, clientHeight: 500 });
    const detach = c.attach(box.el);
    box.el.dispatchEvent(new Event('touchstart'));
    const p = c.scrollToBottom();
    detach();
    await p;
    expect(box.assignments).toHaveLength(0);
    box.userScrollTo(5);
    expect(c.isUserScrolling()).toBe(false);
  });
});
