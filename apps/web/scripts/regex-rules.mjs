/**
 * Regex features Safari 12 cannot parse (spec 13 §2.3). Shared by the compat bundle scanner
 * (scan-compat-bundle.mjs) and the Vitest RegExp guard (src/test/regexpGuard.ts).
 *
 * Validation is semantic (regexpp), so a pattern that merely MATCHES the text "(?<" — such as
 * highlight.js's /\(\?<(?![=!])[^>]+>/ — is not reported.
 */
import { RegExpValidator } from '@eslint-community/regexpp';

/** Flags Safari 12 rejects: `d` (hasIndices, ES2022) and `v` (unicodeSets, ES2024). */
export const UNSAFE_FLAGS = ['d', 'v'];

/**
 * @param {string} pattern regex source (no slashes)
 * @param {string} [flags]
 * @returns {{ feature: string, message: string }[]} empty when the pattern is safe. A pattern
 *   that regexpp cannot parse yields a single `invalid` entry (not a Safari-12 feature).
 */
export function findUnsafeRegexFeatures(pattern, flags = '') {
  /** @type {{ feature: string, message: string }[]} */
  const issues = [];
  // regexpp re-parses a pattern once it has seen a group name, so callbacks can fire twice
  // for the same position: dedupe on feature + position.
  const seen = new Set();
  const report = (start, feature, message) => {
    const key = `${feature}@${start}`;
    if (seen.has(key)) return;
    seen.add(key);
    issues.push({ feature, message });
  };
  for (const flag of UNSAFE_FLAGS) {
    if (flags.includes(flag)) issues.push({ feature: `flag-${flag}`, message: `regex flag "${flag}"` });
  }
  const validator = new RegExpValidator({
    ecmaVersion: 2025,
    onLookaroundAssertionEnter(start, kind, negate) {
      if (kind === 'lookbehind') {
        report(start, 'lookbehind', negate ? 'negative lookbehind (?<!…)' : 'lookbehind (?<=…)');
      }
    },
    onCapturingGroupEnter(start, name) {
      if (name) report(start, 'named-group', `named capture group (?<${name}>…)`);
    },
    onBackreference(start, _end, ref) {
      if (typeof ref === 'string') report(start, 'named-backreference', `named backreference \\k<${ref}>`);
    },
    onModifiersEnter(start) {
      report(start, 'modifiers', 'inline modifiers (?i:…)');
    },
  });
  try {
    validator.validatePattern(pattern, 0, pattern.length, {
      unicode: flags.includes('u'),
      unicodeSets: flags.includes('v'),
    });
  } catch (err) {
    issues.push({ feature: 'invalid', message: `unparseable pattern: ${err instanceof Error ? err.message : String(err)}` });
  }
  return issues;
}

const SUSPICIOUS_TEXT = /\(\?<[=!]|\(\?<[A-Za-z_$][\w$]*>/;

/**
 * String heuristic (spec 13 §2.3 step 5): text that looks like a lookbehind or named group,
 * e.g. a regex source assembled from strings.
 * @param {string} text
 * @returns {string | null} the matched fragment, or null
 */
export function findSuspiciousRegexText(text) {
  const m = SUSPICIOUS_TEXT.exec(text);
  return m ? m[0] : null;
}
