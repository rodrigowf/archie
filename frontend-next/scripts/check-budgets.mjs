#!/usr/bin/env node
/**
 * Bundle budget gate (spec 13 §5.4). Reads each build's Vite manifest, gzips every emitted file
 * (zlib level 9) and compares with scripts/budgets.json. Over budget fails the build.
 *
 *   node scripts/check-budgets.mjs [--main <dir>] [--compat <dir>] [--only main|compat]
 *
 * Measures per build:
 *   initialJs      entry chunks + their static imports (compat: excluding the polyfills chunk)
 *   polyfills      compat only: the plugin-legacy polyfills chunk
 *   initialCss     main: CSS files of the initial chunks; compat: the CSS that legacy chunks
 *                  inline as JS strings (plugin-legacy emits no .css file), measured gzipped
 *   fonts          woff2 files preloaded by index.html (uncompressed)
 *   largestLazyJs  the biggest chunk not in the initial set
 *   totalJs        every .js file
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import * as acorn from 'acorn';
import { extractInlineCss } from './scan-compat-bundle.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FN_ROOT = path.resolve(HERE, '..');
export const DEFAULT_BUDGETS = path.join(HERE, 'budgets.json');

const gz = (buf) => zlib.gzipSync(buf, { level: 9 }).length;
const kB = (bytes) => bytes / 1000;

function readManifest(dir) {
  const file = path.join(dir, '.vite', 'manifest.json');
  if (!fs.existsSync(file)) throw new Error(`no Vite manifest at ${file} (build with build.manifest: true)`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function isPolyfills(key, chunk) {
  return chunk.name === 'polyfills' || /legacy-polyfills/.test(key);
}

function inlineCssBytes(code) {
  try {
    const ast = acorn.parse(code, { ecmaVersion: 'latest', sourceType: 'script' });
    return extractInlineCss(ast).reduce((sum, { css }) => sum + gz(Buffer.from(css)), 0);
  } catch {
    return 0;
  }
}

function preloadedFonts(dir) {
  const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
  const files = [];
  for (const m of html.matchAll(/<link\b[^>]*>/gi)) {
    const tag = m[0];
    if (!/rel=["']?preload/i.test(tag) || !/as=["']?font/i.test(tag)) continue;
    const href = /href=["']([^"']+)["']/i.exec(tag)?.[1];
    if (href) files.push(path.basename(href));
  }
  return files;
}

/**
 * @typedef {{ bytes: number, files: string[] }} Measure
 * @typedef {{ initialJs: Measure, initialCss: Measure, fonts: Measure, largestLazyJs: Measure,
 *   totalJs: Measure, polyfills?: Measure }} BuildMeasures
 */

/**
 * @param {string} dir
 * @param {'main' | 'compat'} target
 * @returns {BuildMeasures}
 */
export function measureBuild(dir, target) {
  const manifest = readManifest(dir);
  const chunks = Object.entries(manifest);
  const byFile = new Map(chunks.map(([key, c]) => [c.file, { key, ...c }]));
  const initial = new Set();
  const visit = (key) => {
    const c = manifest[key];
    if (!c || initial.has(c.file)) return;
    initial.add(c.file);
    for (const imp of c.imports ?? []) visit(imp);
  };
  for (const [key, c] of chunks) if (c.isEntry && !isPolyfills(key, c)) visit(key);

  const size = (file) => fs.readFileSync(path.join(dir, file));
  const jsFiles = [];
  const walkDir = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      if (e.isDirectory() && e.name !== '.vite') walkDir(full);
      else if (e.name.endsWith('.js')) jsFiles.push(path.relative(dir, full).split(path.sep).join('/'));
    }
  };
  walkDir(dir);
  const assetJs = jsFiles.filter((f) => byFile.has(f));

  const initialJsFiles = [...initial].filter((f) => f.endsWith('.js'));
  const result = {
    initialJs: { bytes: initialJsFiles.reduce((s, f) => s + gz(size(f)), 0), files: initialJsFiles },
    initialCss: { bytes: 0, files: [] },
    fonts: { bytes: 0, files: [] },
    largestLazyJs: { bytes: 0, files: [] },
    totalJs: { bytes: assetJs.reduce((s, f) => s + gz(size(f)), 0), files: assetJs },
  };

  if (target === 'main') {
    const cssFiles = [...new Set([...initial].flatMap((f) => byFile.get(f)?.css ?? []))];
    result.initialCss = { bytes: cssFiles.reduce((s, f) => s + gz(size(f)), 0), files: cssFiles };
  } else {
    result.initialCss = {
      bytes: initialJsFiles.reduce((s, f) => s + inlineCssBytes(size(f).toString('utf8')), 0),
      files: initialJsFiles.map((f) => `${f} (inline css)`),
    };
    const poly = chunks.filter(([key, c]) => isPolyfills(key, c)).map(([, c]) => c.file);
    result.polyfills = { bytes: poly.reduce((s, f) => s + gz(size(f)), 0), files: poly };
  }

  for (const f of assetJs) {
    const c = byFile.get(f);
    if (initial.has(f) || (c && isPolyfills(c.key, c))) continue;
    const bytes = gz(size(f));
    if (bytes > result.largestLazyJs.bytes) result.largestLazyJs = { bytes, files: [f] };
  }

  const fonts = preloadedFonts(dir).map((name) => {
    const hit = fs.readdirSync(path.join(dir, 'assets')).find((f) => f === name);
    return hit ? `assets/${hit}` : null;
  }).filter(Boolean);
  result.fonts = { bytes: fonts.reduce((s, f) => s + size(f).length, 0), files: fonts };
  return result;
}

/** @returns {{ metric: string, actual: number, budget: number, ok: boolean, files: string[] }[]} */
export function checkBuild(dir, target, budgets) {
  const measured = measureBuild(dir, target);
  return Object.entries(budgets[target]).map(([metric, budget]) => {
    const m = measured[metric];
    if (!m) throw new Error(`budgets.json: unknown metric "${metric}" for ${target}`);
    const actual = kB(m.bytes);
    return { metric, actual, budget, ok: actual <= budget, files: m.files };
  });
}

function main(argv) {
  const args = argv.slice(2);
  const get = (flag, def) => {
    const i = args.indexOf(flag);
    return i >= 0 ? args[i + 1] : def;
  };
  const budgets = JSON.parse(fs.readFileSync(DEFAULT_BUDGETS, 'utf8'));
  const only = get('--only', null);
  const builds = [
    ['main', path.resolve(FN_ROOT, get('--main', 'dist'))],
    ['compat', path.resolve(FN_ROOT, get('--compat', 'dist-compat'))],
  ].filter(([t]) => !only || only === t);

  let failed = false;
  for (const [target, dir] of builds) {
    if (!fs.existsSync(dir)) {
      console.error(`budgets: ${target} build not found at ${dir}`);
      failed = true;
      continue;
    }
    console.log(`budgets: ${target} (${path.relative(FN_ROOT, dir)})`);
    for (const r of checkBuild(dir, target, budgets)) {
      const line = `  ${r.ok ? 'ok  ' : 'OVER'} ${r.metric.padEnd(14)} ${r.actual.toFixed(1).padStart(7)} kB / ${String(r.budget).padStart(4)} kB`;
      if (r.ok) console.log(line);
      else {
        failed = true;
        console.error(`${line}   ${r.files.join(', ')}`);
      }
    }
  }
  console.log(failed ? 'budgets: FAIL' : 'budgets: PASS');
  return failed ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv);
}
