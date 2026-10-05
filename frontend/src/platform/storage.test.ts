import { describe, expect, it } from 'vitest';
import { createSafeStorage, localStore } from './storage';

function throwingStorage(): Storage {
  return {
    getItem: () => {
      throw new Error('denied');
    },
    setItem: () => {
      throw new Error('QuotaExceededError');
    },
    removeItem: () => {
      throw new Error('denied');
    },
    clear: () => undefined,
    key: () => null,
    length: 0,
  };
}

describe('createSafeStorage', () => {
  it('reads and writes the real storage', () => {
    localStore.set('t:a', '1');
    expect(window.localStorage.getItem('t:a')).toBe('1');
    expect(localStore.get('t:a')).toBe('1');
    localStore.remove('t:a');
    expect(localStore.get('t:a')).toBeNull();
    expect(localStore.persistent).toBe(true);
  });

  it('round-trips JSON and returns the fallback on bad JSON', () => {
    localStore.setJSON('t:j', { a: [1, 2] });
    expect(localStore.getJSON('t:j', null)).toEqual({ a: [1, 2] });
    window.localStorage.setItem('t:j', '{oops');
    expect(localStore.getJSON('t:j', 'fb')).toBe('fb');
    expect(localStore.getJSON('t:missing', 42)).toBe(42);
  });

  it('never throws when storage access throws, and keeps values in memory', () => {
    const s = createSafeStorage(() => {
      throw new Error('SecurityError');
    });
    expect(s.set('k', 'v')).toBe(false);
    expect(s.get('k')).toBe('v');
    expect(s.persistent).toBe(false);
    s.remove('k');
    expect(s.get('k')).toBeNull();
  });

  it('survives a storage whose methods throw (quota, private mode)', () => {
    const s = createSafeStorage(throwingStorage);
    expect(s.set('k', 'v')).toBe(false);
    expect(s.get('k')).toBe('v');
    expect(() => {
      s.remove('k');
    }).not.toThrow();
  });

  it('handles a missing storage', () => {
    const s = createSafeStorage(() => null);
    expect(s.setJSON('k', { x: 1 })).toBe(false);
    expect(s.getJSON('k', null)).toEqual({ x: 1 });
  });

  it('refuses unserialisable JSON without throwing', () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(localStore.setJSON('t:c', cyclic)).toBe(false);
  });
});
