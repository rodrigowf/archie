import { describe, expect, it } from 'vitest';
import { assertSafeRegExp, isRegExpGuardInstalled, uninstallRegExpGuard, installRegExpGuard } from './regexpGuard';

// Patterns are built from pieces so this file itself contains no unsafe literal.
const LB = '(?' + '<=';
const NLB = '(?' + '<!';
const NG = '(?' + '<year>';

describe('RegExp guard (compat project)', () => {
  it('is installed by setup.compat.ts', () => {
    expect(isRegExpGuardInstalled()).toBe(true);
  });

  it('throws on lookbehind, named groups, named backrefs and the d/v flags', () => {
    expect(() => new RegExp(`${LB}a)b`)).toThrow(/lookbehind/);
    expect(() => new RegExp(`${NLB}a)b`)).toThrow(/negative lookbehind/);
    expect(() => new RegExp(`${NG}\\d{4})`)).toThrow(/named capture group/);
    expect(() => RegExp(`${NG}x)\\k<year>`)).toThrow(/named backreference/);
    expect(() => new RegExp('a', 'd')).toThrow(/flag "d"/);
    expect(() => new RegExp('[a]', 'v')).toThrow(/flag "v"/);
  });

  it('allows safe patterns, including ones that match the text "(?<"', () => {
    const re = new RegExp('\\(\\?<(?![=!])[^>]+>');
    expect(re.test('(?<name>')).toBe(true);
    expect(new RegExp('(a)(?=b)', 'gu').flags).toBe('gu');
    expect(RegExp('x') instanceof RegExp).toBe(true);
    expect(/abc/ instanceof RegExp).toBe(true);
  });

  it('copies flags from a RegExp argument', () => {
    expect(new RegExp(/a/gi).flags).toBe('gi');
    expect(() => {
      assertSafeRegExp('ok', 'g');
    }).not.toThrow();
  });

  it('can be uninstalled and reinstalled', () => {
    uninstallRegExpGuard();
    expect(isRegExpGuardInstalled()).toBe(false);
    expect(new RegExp(`${LB}a)b`).test('ab')).toBe(true);
    installRegExpGuard();
    expect(isRegExpGuardInstalled()).toBe(true);
  });
});
