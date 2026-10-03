/**
 * Conformance suite (spec 12 §11, spec 13 §3.2): every fixture in FIXTURES_DIR, no skips.
 * Each fixture is replayed through the binary-frame decoder and the state machine, normalised
 * and compared with `expected`: entries, orphan_results, unattributed_results exactly; queue when
 * present; state keys when present.
 */
import fs from 'node:fs';
import { describe, expect, it } from 'vitest';
import { fixturesDir, loadFixtures } from '../../../mock-server/scenarios.mjs';
import { deepFreeze, fixtureInputs, fixtureStart, normalise, runFixture, runInputs } from './harness';

const dir = fixturesDir();
const files = loadFixtures(dir);

describe('protocol fixtures', () => {
  it('loads every *.json fixture in the directory', () => {
    const onDisk = fs.readdirSync(dir).filter((f: string) => f.endsWith('.json'));
    expect(files.length).toBe(onDisk.length);
    expect(files.length).toBeGreaterThanOrEqual(33);
    for (const { file, fixture } of files) expect(`${fixture.name}.json`).toBe(file);
  });

  it.each(files.map((f) => [f.fixture.name, f.fixture] as const))('%s', (_name, fx) => {
    const { conv } = runFixture(fx);
    const exp = fx.expected;
    const actual = normalise(conv, Object.keys(exp.state ?? {}));
    expect(actual.entries).toStrictEqual(exp.entries);
    expect(actual.orphan_results).toStrictEqual(exp.orphan_results ?? []);
    expect(actual.unattributed_results).toStrictEqual(exp.unattributed_results ?? []);
    if (exp.queue !== undefined) expect(actual.queue).toStrictEqual(exp.queue);
    expect(actual.state).toStrictEqual(exp.state ?? {});
  });

  it.each(files.map((f) => [f.fixture.name, f.fixture] as const))('%s: never writes to a previous state', (_name, fx) => {
    const start = fixtureStart(fx).conv;
    deepFreeze(start);
    const inputs = fixtureInputs(fx);
    let conv = start;
    for (const input of inputs) {
      conv = deepFreeze(runInputs(conv, [input]).conv); // throws (strict mode) if the step mutates its input
    }
    expect(normalise(conv).entries).toStrictEqual(normalise(runFixture(fx).conv).entries);
  });
});
