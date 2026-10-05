#!/usr/bin/env node
/**
 * Token-var gate (spec 13 §1.5 gate:tokens, second half). Every `var(--md-…)` / `var(--app-…)`
 * used in src/**\/*.css must be declared in design/tokens/dist/tokens.css, or declared by the
 * app's own CSS (e.g. `--app-height`, which the shell sets at runtime with a CSS fallback).
 * Catches typos such as `--md-sys-color-surface-containr` that would silently resolve to nothing.
 *
 *   node scripts/check-token-vars.mjs [--src <dir>] [--tokens <tokens.css>]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FN_ROOT = path.resolve(HERE, '..');
export const DEFAULT_TOKENS = path.resolve(FN_ROOT, '..', 'design', 'tokens', 'dist', 'tokens.css');
export const DEFAULT_SRC = path.join(FN_ROOT, 'src');

const DECL = /(--(?:md|app)-[\w-]+)\s*:/g;
const USE = /var\(\s*(--(?:md|app)-[\w-]+)/g;

function cssFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  /** @type {string[]} */
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...cssFiles(full));
    else if (entry.name.endsWith('.css')) out.push(full);
  }
  return out.sort();
}

/** Strip comments so commented-out code is not checked. */
function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, (c) => c.replace(/[^\n]/g, ' '));
}

export function declaredVars(css) {
  const out = new Set();
  for (const m of stripComments(css).matchAll(DECL)) out.add(m[1]);
  return out;
}

/** @returns {{ file: string, line: number, name: string }[]} */
export function unknownVarUses({ srcDir = DEFAULT_SRC, tokensFile = DEFAULT_TOKENS } = {}) {
  const known = declaredVars(fs.readFileSync(tokensFile, 'utf8'));
  const files = cssFiles(srcDir).map((f) => ({ f, css: stripComments(fs.readFileSync(f, 'utf8')) }));
  for (const { css } of files) for (const v of declaredVars(css)) known.add(v);
  const problems = [];
  for (const { f, css } of files) {
    for (const m of css.matchAll(USE)) {
      if (!known.has(m[1])) {
        const line = css.slice(0, m.index).split('\n').length;
        problems.push({ file: path.relative(FN_ROOT, f), line, name: m[1] });
      }
    }
  }
  return problems;
}

function main(argv) {
  const args = argv.slice(2);
  const get = (flag) => {
    const i = args.indexOf(flag);
    return i >= 0 ? path.resolve(args[i + 1] ?? '') : undefined;
  };
  const srcDir = get('--src') ?? DEFAULT_SRC;
  const tokensFile = get('--tokens') ?? DEFAULT_TOKENS;
  const problems = unknownVarUses({ srcDir, tokensFile });
  if (problems.length) {
    console.error(`token-var gate: ${problems.length} unknown custom propert${problems.length === 1 ? 'y' : 'ies'}:`);
    for (const p of problems) console.error(`  ${p.file}:${p.line}  var(${p.name})`);
    return 1;
  }
  console.log(`token-var gate: PASS (${cssFiles(srcDir).length} CSS files checked against ${path.relative(FN_ROOT, tokensFile)})`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv);
}
