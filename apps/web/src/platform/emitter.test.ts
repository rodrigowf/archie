import { describe, expect, it, vi } from 'vitest';
import { Emitter } from './emitter';

type Events = { data: number; done: undefined };

describe('Emitter', () => {
  it('delivers payloads and unsubscribes', () => {
    const e = new Emitter<Events>();
    const fn = vi.fn();
    const off = e.on('data', fn);
    e.emit('data', 1);
    off();
    e.emit('data', 2);
    expect(fn.mock.calls).toEqual([[1]]);
    expect(e.listenerCount('data')).toBe(0);
  });

  it('once fires a single time', () => {
    const e = new Emitter<Events>();
    const fn = vi.fn();
    e.once('done', fn);
    e.emit('done', undefined);
    e.emit('done', undefined);
    expect(fn).toHaveBeenCalledOnce();
  });

  it('a throwing listener does not stop the others', () => {
    const e = new Emitter<Events>();
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const after = vi.fn();
    e.on('data', () => {
      throw new Error('boom');
    });
    e.on('data', after);
    e.emit('data', 3);
    expect(after).toHaveBeenCalledWith(3);
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it('listeners added during emit wait for the next emit; clear removes all', () => {
    const e = new Emitter<Events>();
    const late = vi.fn();
    e.on('data', () => {
      e.on('data', late);
    });
    e.emit('data', 1);
    expect(late).not.toHaveBeenCalled();
    e.clear();
    e.emit('data', 2);
    expect(late).not.toHaveBeenCalled();
  });
});
