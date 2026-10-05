/**
 * RegExp guard (spec 13 §2.3, runtime complement). Wraps the global RegExp constructor so that
 * `new RegExp(src)` / `RegExp(src)` throws on what Safari 12 cannot parse: lookbehind, named
 * groups, named backreferences, inline modifiers, and the d / v flags. Grammars that build their
 * regexes at registration time (highlight.js) are then caught by the `compat` test project even
 * though no test runs on Safari 12. Regex LITERALS do not pass through the constructor; ESLint
 * (es-x) and the bundle scanner cover those.
 */
import { findUnsafeRegexFeatures } from '../../scripts/regex-rules.mjs';

const NativeRegExp: RegExpConstructor = globalThis.RegExp;
let installed: RegExpConstructor | null = null;
let validating = false; // re-entrancy guard, in case the validator itself constructs a RegExp

export class UnsafeRegExpError extends SyntaxError {
  constructor(pattern: string, flags: string, detail: string) {
    super(`RegExp guard: /${pattern}/${flags} uses ${detail}, which Safari 12 cannot parse`);
    this.name = 'UnsafeRegExpError';
  }
}

export function assertSafeRegExp(pattern: string, flags = ''): void {
  if (validating) return;
  validating = true;
  let issues;
  try {
    issues = findUnsafeRegexFeatures(pattern, flags).filter((i) => i.feature !== 'invalid');
  } finally {
    validating = false;
  }
  if (issues.length > 0) {
    throw new UnsafeRegExpError(pattern, flags, issues.map((i) => i.message).join(', '));
  }
}

export function installRegExpGuard(): void {
  if (installed) return;
  const Guarded = function RegExp(this: unknown, pattern?: string | RegExp, flags?: string): RegExp {
    const source = pattern instanceof NativeRegExp ? pattern.source : pattern === undefined ? '(?:)' : String(pattern);
    const effectiveFlags = flags ?? (pattern instanceof NativeRegExp ? pattern.flags : '');
    assertSafeRegExp(source, effectiveFlags);
    return new.target
      ? (Reflect.construct(NativeRegExp, [pattern, flags], new.target) as RegExp)
      : NativeRegExp(pattern as string, flags);
  } as unknown as RegExpConstructor;
  Object.setPrototypeOf(Guarded, NativeRegExp);
  Object.defineProperty(Guarded, 'prototype', { value: NativeRegExp.prototype });
  globalThis.RegExp = Guarded;
  installed = Guarded;
}

export function uninstallRegExpGuard(): void {
  if (!installed) return;
  globalThis.RegExp = NativeRegExp;
  installed = null;
}

export function isRegExpGuardInstalled(): boolean {
  return installed !== null && globalThis.RegExp === installed;
}
