import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import manifest from './icons.manifest.json';
import { filledIconPaths, hasFilledVariant, iconNames, iconPaths, isIconName } from './index';

describe('icons', () => {
  it('bundles exactly the manifest icons', () => {
    expect([...iconNames].sort()).toEqual([...manifest.icons].sort());
    expect(Object.keys(filledIconPaths).sort()).toEqual([...manifest.filled].sort());
  });

  it('has path data for every icon', () => {
    for (const name of iconNames) expect(iconPaths[name]).toMatch(/^[Mm][-\d]/);
  });

  it('narrows names', () => {
    expect(isIconName('terminal')).toBe(true);
    expect(isIconName('not_an_icon')).toBe(false);
    expect(isIconName('toString')).toBe(false);
    expect(hasFilledVariant('forum')).toBe(true);
    expect(hasFilledVariant('terminal')).toBe(false);
  });

  it('generated.ts is up to date with the manifest', () => {
    const root = path.resolve(__dirname, '../../..');
    const script = path.join(root, 'scripts', 'gen-icons.mjs');
    expect(fs.existsSync(script)).toBe(true);
    // --check exits non-zero (execFileSync throws) when generated.ts is stale.
    expect(() => execFileSync(process.execPath, [script, '--check'], { cwd: root, stdio: 'pipe' })).not.toThrow();
  });
});
