/** check-budgets.mjs and check-token-vars.mjs on synthetic inputs. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { afterAll, describe, expect, it } from 'vitest';
import { checkBuild, measureBuild } from '../check-budgets.mjs';
import { declaredVars, unknownVarUses } from '../check-token-vars.mjs';

const dirs: string[] = [];
function tmp(files: Record<string, string | Buffer>): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fn-gates-'));
  dirs.push(dir);
  for (const [name, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), content);
  }
  return dir;
}
afterAll(() => {
  for (const d of dirs) fs.rmSync(d, { recursive: true, force: true });
});

// Incompressible bytes so gzip size ≈ raw size.
const noise = (n: number): string => {
  let s = '';
  let x = 12345;
  for (let i = 0; i < n; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff;
    s += String.fromCharCode(33 + (x % 90));
  }
  return `var a="${s.replace(/["\\]/g, 'x')}";`;
};
const gz = (s: string): number => zlib.gzipSync(s, { level: 9 }).length;

describe('budget gate', () => {
  const entry = noise(3000);
  const shared = noise(1000);
  const lazy = noise(2000);
  const mainDist = tmp({
    '.vite/manifest.json': JSON.stringify({
      'index.html': { file: 'assets/index.js', isEntry: true, imports: ['_shared.js'], css: ['assets/index.css'], dynamicImports: ['src/lazy.ts'] },
      '_shared.js': { file: 'assets/shared.js' },
      'src/lazy.ts': { file: 'assets/lazy.js', isDynamicEntry: true },
    }),
    'assets/index.js': entry,
    'assets/shared.js': shared,
    'assets/lazy.js': lazy,
    'assets/index.css': '.a{margin:0}',
    'assets/roboto-flex-latin-wght-normal-x.woff2': Buffer.alloc(34_000),
    'index.html': '<link rel="preload" as="font" type="font/woff2" crossorigin href="/assets/roboto-flex-latin-wght-normal-x.woff2">',
  });

  it('measures initial (entry + static imports), lazy, total, CSS and preloaded fonts', () => {
    const m = measureBuild(mainDist, 'main');
    expect(m.initialJs.files.sort()).toEqual(['assets/index.js', 'assets/shared.js']);
    expect(m.initialJs.bytes).toBe(gz(entry) + gz(shared));
    expect(m.largestLazyJs).toEqual({ bytes: gz(lazy), files: ['assets/lazy.js'] });
    expect(m.totalJs.bytes).toBe(gz(entry) + gz(shared) + gz(lazy));
    expect(m.initialCss.files).toEqual(['assets/index.css']);
    expect(m.fonts.bytes).toBe(34_000);
  });

  it('fails a metric over budget and passes under it', () => {
    const budgets = { main: { initialJs: 1, largestLazyJs: 60, fonts: 80 } };
    const res = checkBuild(mainDist, 'main', budgets);
    expect(res.find((r) => r.metric === 'initialJs')?.ok).toBe(false);
    expect(res.find((r) => r.metric === 'largestLazyJs')?.ok).toBe(true);
    expect(() => checkBuild(mainDist, 'main', { main: { bogus: 1 } })).toThrow(/unknown metric/);
  });

  it('compat: polyfills measured separately; inline CSS counted as initial CSS', () => {
    const css = '.row{display:flex;margin:0}';
    const dist = tmp({
      '.vite/manifest.json': JSON.stringify({
        'index-legacy.html': { file: 'assets/index-legacy.js', isEntry: true },
        'vite/legacy-polyfills-legacy': { file: 'assets/polyfills-legacy.js', name: 'polyfills', isEntry: true },
      }),
      'assets/index-legacy.js': `var s=document.createElement("style");s.textContent=${JSON.stringify(css)};`,
      'assets/polyfills-legacy.js': noise(500),
      'index.html': '<html></html>',
    });
    const m = measureBuild(dist, 'compat');
    expect(m.initialJs.files).toEqual(['assets/index-legacy.js']);
    expect(m.polyfills?.files).toEqual(['assets/polyfills-legacy.js']);
    expect(m.initialCss.bytes).toBe(gz(css));
  });
});

describe('token-var gate', () => {
  const tokens = ':root{--md-sys-color-surface:#000;--app-space-4:16px;}';
  it('accepts known tokens and app-declared vars, rejects typos', () => {
    const dir = tmp({
      'tokens.css': tokens,
      'src/a.css': '.a{--app-height:100%;height:var(--app-height);color:var(--md-sys-color-surface)}',
      'src/b/c.module.css': '.b{margin:var(--app-space-4) var(--app-space-44);background:var(--md-sys-color-surfce, red)}\n/* var(--md-commented-out) */',
    });
    const problems = unknownVarUses({ srcDir: path.join(dir, 'src'), tokensFile: path.join(dir, 'tokens.css') });
    expect(problems.map((p: { name: string }) => p.name)).toEqual(['--app-space-44', '--md-sys-color-surfce']);
    expect(problems[0]).toMatchObject({ line: 1 });
    expect([...declaredVars(tokens)]).toEqual(['--md-sys-color-surface', '--app-space-4']);
  });
});
