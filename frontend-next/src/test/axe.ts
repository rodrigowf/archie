/**
 * axe-core helper (spec 13 §3.5: an axe check in every component test).
 * `color-contrast` is disabled: jsdom does no layout or style resolution, and token contrast is
 * already audited by design/tokens (196/196 pairs).
 */
import axe, { type AxeResults, type Result } from 'axe-core';

export async function axeViolations(container: Element): Promise<Result[]> {
  const results: AxeResults = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false } },
  });
  return results.violations;
}

export function formatViolations(violations: Result[]): string {
  return violations
    .map((v) => `${v.id} (${v.impact ?? 'n/a'}): ${v.help}\n  ${v.nodes.map((n) => n.target.join(' ')).join('\n  ')}`)
    .join('\n');
}

/** Throws with a readable report when axe finds violations. */
export async function expectNoAxeViolations(container: Element): Promise<void> {
  const violations = await axeViolations(container);
  if (violations.length > 0) throw new Error(`axe violations:\n${formatViolations(violations)}`);
}
