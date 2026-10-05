/**
 * Unit tests for the compat bundle scanner (spec 13 §2.3). The fixture list required by the spec:
 *   1. a real lookbehind (mdast-util-gfm-autolink-literal)        → fails
 *   2. highlight.js's escaped \(\?< pattern                         → passes
 *   3. marked's new RegExp("(?<=1)(?<!1)")                          → fails (as a string)
 *   4. a comment containing "(?<="                                  → passes
 *   5. a named group                                                → fails
 *   6. inset: 0 in CSS                                              → fails
 * plus syntax, flags, constructor forms, CSS rules, inline CSS in legacy chunks, HTML, allowlist,
 * sourcemap mapping and the CLI exit code.
 * Unsafe patterns are assembled from pieces (LB = "(?" + "<=") so this file stays scanner-clean.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, describe, expect, it } from 'vitest';
import { applyAllowlist, loadAllowlist, scanCss, scanDist, scanHtml, scanJs } from '../scan-compat-bundle.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FN = path.resolve(HERE, '..', '..');
const SCANNER = path.join(FN, 'scripts', 'scan-compat-bundle.mjs');
const LB = '(?' + '<=';
const NLB = '(?' + '<!';
const NG = '(?' + '<';

const errors = (findings) => findings.filter((f) => f.severity === 'error');
const rules = (findings) => errors(findings).map((f) => f.rule);
const js = (code) => scanJs(code, { file: 'fixture.js' });
const css = (code) => scanCss(code, { file: 'fixture.css' });

const tmpDirs = [];
function tmpDist(files, parent = os.tmpdir()) {
  const dir = fs.mkdtempSync(path.join(parent, '.scan-test-'));
  tmpDirs.push(dir);
  for (const [name, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), content);
  }
  return dir;
}
afterAll(() => {
  for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });
});

describe('spec 13 §2.3 fixture list', () => {
  it('1. the real mdast-util-gfm-autolink-literal lookbehind fails', () => {
    const src = fs.readFileSync(path.join(FN, 'node_modules/mdast-util-gfm-autolink-literal/lib/index.js'), 'utf8');
    const line = src.split('\n').find((l) => l.includes(`${LB}^|\\s|\\p{P}|\\p{S})`));
    expect(line).toBeTruthy();
    const literal = /\/\(\?<=.*\/gu/.exec(line)?.[0];
    expect(literal).toBeTruthy();
    const findings = js(`var findEmail = function () {}; var pairs = [[${literal}, findEmail]];`);
    expect(rules(findings)).toEqual(['regex-lookbehind']);
  });

  it('2 + 4. highlight.js core (escaped \\(\\?< literal next to a "(?<=" comment) passes', () => {
    const file = path.join(FN, 'node_modules/highlight.js/lib/core.js');
    const src = fs.readFileSync(file, 'utf8');
    expect(src).toContain('/\\(\\?<(?![=!])[^>]+>/, // a named capture group');
    expect(src).toContain(`not a lookbehind \`${LB}\``);
    expect(errors(scanJs(src, { file: 'core.js' }))).toEqual([]);
  });

  it('2. the escaped pattern alone passes', () => {
    expect(errors(js('var re = /\\(\\?<(?![=!])[^>]+>/;'))).toEqual([]);
  });

  it("3. marked's new RegExp(\"(?<=1)(?<!1)\") fails as a string", () => {
    const findings = js(`try { new RegExp("${LB}1)${NLB}1)"); } catch (e) {}`);
    expect(rules(findings)).toEqual(['regexp-lookbehind', 'regexp-lookbehind']);
  });

  it('4. a comment containing "(?<=" passes', () => {
    expect(errors(js(`// matches ${LB}x) in docs\n/* ${NG}name> too */ var a = 1;`))).toEqual([]);
  });

  it('5. a named group fails (literal and RegExp string)', () => {
    expect(rules(js(`var re = /${NG}year>\\d{4})/;`))).toEqual(['regex-named-group']);
    expect(rules(js(`var re = new RegExp("${NG}year>\\\\d{4})");`))).toEqual(['regexp-named-group']);
  });

  it('6. inset: 0 in CSS fails', () => {
    expect(rules(css('.scrim { position: fixed; inset: 0; }'))).toEqual(['css-inset']);
  });
});

describe('JS checks', () => {
  it('highlight.js 11.12 grammars whose "(?<" hits are only in comments pass (gcode, r, scala, haskell)', () => {
    // Spec 13 §2.3 lists gcode as a real lookbehind; in highlight.js 11.12.0 it is commented out
    // (gcode.js:66). The scanner, not grep, is the authority.
    for (const lang of ['gcode', 'r', 'scala', 'haskell']) {
      const file = path.join(FN, `node_modules/highlight.js/lib/languages/${lang}.js`);
      expect(fs.readFileSync(file, 'utf8')).toMatch(/\/\/.*\(\?</);
      expect(errors(scanJs(fs.readFileSync(file, 'utf8'), { file: `${lang}.js` }))).toEqual([]);
    }
  });

  it('fails ES2020+ syntax escaping Babel, allows ES2019 optional catch binding', () => {
    expect(rules(js('var a = b?.c;'))).toEqual(['syntax']);
    expect(rules(js('var a = 1_000;'))).toEqual(['syntax']);
    expect(rules(js('var a = 10n;'))).toEqual(['syntax']);
    expect(rules(js('class A { x = 1 }'))).toEqual(['syntax']);
    expect(rules(js('var a = b ?? c;'))).toEqual(['syntax']);
    expect(errors(js('try { f() } catch { g() }'))).toEqual([]);
  });

  it('fails negative lookbehind, named backreferences, modifiers', () => {
    expect(rules(js(`var re = /${NLB}a)b/;`))).toEqual(['regex-lookbehind']);
    expect(rules(js(`var re = /${NG}q>a)\\k<q>/;`))).toEqual(['regex-named-group', 'regex-named-backreference']);
  });

  it('fails the d and v flags (ES2022/2024 regex flags are also not ES2019 syntax)', () => {
    expect(rules(js('var re = new RegExp("a", "d");'))).toEqual(['regexp-flag-d']);
    expect(rules(js('var re = RegExp("[a]", "v");'))).toEqual(['regexp-flag-v']);
    expect(rules(js('var re = /a/d;'))).toEqual(['syntax']);
  });

  it('checks every RegExp constructor form', () => {
    for (const callee of ['RegExp', 'new RegExp', 'new window.RegExp', 'globalThis.RegExp', 'new self.RegExp']) {
      expect(rules(js(`var re = ${callee}("${LB}a)b");`))).toEqual(['regexp-lookbehind']);
    }
    expect(rules(js(`var re = new RegExp(\`${LB}a)b\`);`))).toEqual(['regexp-lookbehind']);
  });

  it('warns (not fails) on dynamic RegExp patterns', () => {
    const findings = js('var re = new RegExp("^" + x + "$");');
    expect(errors(findings)).toEqual([]);
    expect(findings.map((f) => f.rule)).toEqual(['regexp-dynamic']);
  });

  it('fails regex syntax assembled from strings and templates', () => {
    expect(rules(js(`var src = "a" + "${LB}" + "b";`))).toEqual(['string-regex-syntax']);
    expect(rules(js(`var src = \`${NG}name>\${x})\`;`))).toEqual(['string-regex-syntax']);
    expect(rules(js(`var src = s.replace("x", "${NLB}");`))).toEqual(['string-regex-syntax']);
  });

  it('does not flag safe look-alikes', () => {
    expect(errors(js('var a = "(?:x)(?=y)(?!z)"; var b = /(?=a)(?!b)(?:c)/g; var c = "(?<"; var d = "a <b>";'))).toEqual([]);
  });
});

describe('CSS checks', () => {
  it('fails flex gap, allows grid-gap', () => {
    expect(rules(css('.a { display: flex; gap: 8px; } .b { row-gap: 1px; column-gap: 2px }'))).toEqual(['css-gap', 'css-gap', 'css-gap']);
    expect(errors(css('.g { display: grid; grid-gap: 8px; }'))).toEqual([]);
  });

  it('fails inset-*, aspect-ratio and logical properties', () => {
    expect(rules(css('.a { inset-inline: 0; aspect-ratio: 1; margin-inline-start: 4px; padding-block: 2px; inline-size: 10px }'))).toEqual([
      'css-inset',
      'css-aspect-ratio',
      'css-logical',
      'css-logical',
      'css-logical',
    ]);
    expect(errors(css('.a { display: inline-block; vertical-align: text-bottom }'))).toEqual([]);
  });

  it('requires the -webkit- twin for backdrop-filter and mask-image', () => {
    expect(rules(css('.a { backdrop-filter: blur(4px) } .b { mask-image: none }'))).toEqual(['css-unprefixed', 'css-unprefixed']);
    expect(errors(css('.a { -webkit-backdrop-filter: blur(4px); backdrop-filter: blur(4px) }'))).toEqual([]);
  });

  it('fails :is/:where/:has and raw :focus-visible, allows .focus-visible', () => {
    expect(rules(css(':is(a, b) {} a:where(.x) {} .p:has(img) {} .b:focus-visible {}'))).toEqual([
      'css-selector',
      'css-selector',
      'css-selector',
      'css-selector',
    ]);
    expect(errors(css('.b.focus-visible { outline: 2px solid } .js-focus-visible :focus:not(.focus-visible) { outline: none }'))).toEqual([]);
  });

  it('fails dvh/svh/lvh, color-mix(), clamp(), @layer, @container', () => {
    expect(rules(css('.a { height: 100dvh; min-height: 50svh; max-height: 9lvh }'))).toEqual(['css-viewport-unit', 'css-viewport-unit', 'css-viewport-unit']);
    expect(rules(css('.a { color: color-mix(in srgb, red, blue); width: clamp(1px, 2vw, 3px) }'))).toEqual(['css-function', 'css-function']);
    expect(rules(css('@layer base { a {} } @container (min-width: 1px) { a {} }'))).toEqual(['css-at-rule', 'css-at-rule']);
    expect(errors(css('.a { height: 100vh; width: calc(100% - 8px); min-width: min(10px, 2vw) }'))).toEqual([]);
  });

  it('checks CSS that legacy chunks inline into JS (plugin-legacy emits no .css)', () => {
    const chunk = `System.register([], function () { return { execute: function () {
      var s = document.createElement("style");
      s.textContent = ".row{display:flex;gap:8px}.x{color:red}" + "/*$vite$:1*/";
      document.head.appendChild(s);
    } }; });`;
    const findings = js(chunk);
    expect(rules(findings)).toEqual(['css-gap']);
    expect(findings[0].file).toBe('fixture.js <inline css #1>');
  });
});

describe('HTML, allowlist, sourcemaps, CLI', () => {
  it('scans inline <script> and <style>, skips external scripts and JSON', () => {
    const html = `<html><head><script>var a = /${NG}n>x)/;</script>
<script src="/x.js"></script><script type="application/json">{"a": "${LB}"}</script>
<style>.a{gap:4px}</style></head></html>`;
    const findings = scanHtml(html, { file: 'index.html' });
    expect(rules(findings)).toEqual(['regex-named-group', 'css-gap']);
    expect(findings[0].file).toBe('index.html <script>');
  });

  it('allowlist entries suppress matching findings; unmatched entries fail', () => {
    const findings = js(`var probe = $RegExp("${NG}a>b)", "g");`);
    expect(rules(findings)).toEqual(['string-regex-syntax']);
    const entry = { module: 'fixture.js', snippet: `${NG}a>b)`, reason: 'feature probe in try/catch', addedBy: 'test' };
    const stale = { module: 'nowhere.js', snippet: 'nothing', reason: 'old', addedBy: 'test' };
    const result = applyAllowlist(findings, [entry, stale]);
    expect(result.filter((f) => f.allowlisted)).toHaveLength(1);
    expect(rules(result.filter((f) => !f.allowlisted))).toEqual(['allowlist-stale']);
  });

  it('the shipped allowlist file is valid', () => {
    const entries = loadAllowlist();
    for (const e of entries) expect(e.addedBy).toBeTruthy();
  });

  it('maps findings to the original module through the hidden sourcemap', () => {
    const code = `var a=/${LB}x)y/;`;
    // One mapping: generated 1:0 → source 0 ("../../src/feature.ts") line 7 col 0.
    const map = JSON.stringify({ version: 3, sources: ['../../src/feature.ts'], names: [], mappings: 'AAMA' });
    // The dist sits directly in frontend/, so ../../src resolves to frontend/src.
    const dir = tmpDist({ 'assets/index-legacy.js': code, 'assets/index-legacy.js.map': map }, FN);
    const result = scanDist(dir);
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0]).toMatchObject({ module: 'src/feature.ts', moduleLine: 7, file: 'assets/index-legacy.js' });
  });

  it('the CLI exits 1 on violations, 0 when clean, and writes .scan-report.json', () => {
    const bad = tmpDist({ 'index.html': '<script>var x = 1;</script>', 'assets/a.js': `var re = /${LB}a)b/;` });
    let status = 0;
    try {
      execFileSync(process.execPath, [SCANNER, bad], { stdio: 'pipe' });
    } catch (err) {
      status = err.status;
    }
    expect(status).toBe(1);
    const report = JSON.parse(fs.readFileSync(path.join(bad, '.scan-report.json'), 'utf8'));
    expect(report.errors[0].rule).toBe('regex-lookbehind');

    const good = tmpDist({ 'index.html': '<script>var x = 1;</script>', 'assets/a.js': 'var re = /a(?=b)/;', 'assets/a.css': '.a{margin:0}' });
    const empty = path.join(good, 'allow.json');
    fs.writeFileSync(empty, '[]');
    const out = execFileSync(process.execPath, [SCANNER, good, '--allowlist', empty], { encoding: 'utf8' });
    expect(out).toContain('PASS');
  });
});
