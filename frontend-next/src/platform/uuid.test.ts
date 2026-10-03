import { describe, expect, it, vi } from 'vitest';
import { UUID_PATTERN, generateUUID } from './uuid';

describe('generateUUID (LOAD-BEARING inv02 F-20)', () => {
  it('uses crypto.randomUUID when present', () => {
    expect(generateUUID({ randomUUID: () => 'from-native' })).toBe('from-native');
  });

  it('falls back to getRandomValues when randomUUID is missing (Safari 12, plain HTTP)', () => {
    const getRandomValues = vi.fn((a: Uint8Array): Uint8Array => a.fill(0xff));
    const id = generateUUID({ getRandomValues });
    expect(getRandomValues).toHaveBeenCalledOnce();
    expect(id).toMatch(UUID_PATTERN);
    expect(id).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
  });

  it('falls back to getRandomValues when randomUUID throws', () => {
    const id = generateUUID({
      randomUUID: () => {
        throw new Error('insecure context');
      },
      getRandomValues: (a: Uint8Array): Uint8Array => a,
    });
    expect(id).toMatch(UUID_PATTERN);
  });

  it('falls back to Math.random without crypto', () => {
    const ids = new Set(Array.from({ length: 50 }, () => generateUUID({})));
    expect(ids.size).toBe(50);
    for (const id of ids) expect(id).toMatch(UUID_PATTERN);
  });

  it('works with the real crypto object', () => {
    expect(generateUUID()).toMatch(UUID_PATTERN);
  });
});
