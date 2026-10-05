#!/usr/bin/env node
/**
 * Compat bundle scanner gate (spec 13 §2.3). Fails the build when the Safari 12 / iOS 12 output
 * contains something that would throw at parse time (white screen) or silently break layout.
 *
 *   node scripts/scan-compat-bundle.mjs <distDir> [--allowlist <file>]
 *
 * JS (every *.js file and every inline <script> in *.html):
 *   - parse with acorn, ecmaVersion 2019, sourceType 'script' (ES2020+ syntax fails). Spec 13 §2.3
 *     says 2018, but Safari 11.1+ supports ES2019's only syntax addition (optional catch binding,
 *     `catch {}`), which Babel correctly leaves untranspiled for `safari >= 12`;
 *   - regex literals: lookbehind, named groups, named backreferences, modifiers, flags d / v;
 *   - RegExp(...) / new RegExp(...) with a static pattern: same rules; dynamic patterns warn;
 *   - string literals / template quasis that look like "(?<=", "(?<!" or "(?<name>" fail.
 * CSS (every *.css file and inline <style>): see CSS_RULES below.
 * Findings are mapped to the original module through the hidden sourcemap (`<file>.map`).
 * Allowlist (compat-scan-allowlist.json): [{ module, snippet, reason, addedBy }]. An entry that
 * matches nothing is itself a failure, so stale entries do not accumulate.
 * Output: a human summary on stdout + <distDir>/.scan-report.json. Exit 1 on any failure.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import * as acorn from 'acorn';
import * as walk from 'acorn-walk';
import postcss from 'postcss';
import sourceMapJs from 'source-map-js';
import { findSuspiciousRegexText, findUnsafeRegexFeatures } from './regex-rules.mjs';

const { SourceMapConsumer } = sourceMapJs;
const HERE = path.dirname(fileURLToPath(import.meta.url));
export const FN_ROOT = path.resolve(HERE, '..');
export const DEFAULT_ALLOWLIST = path.join(HERE, 'compat-scan-allowlist.json');
const REPORT_NAME = '.scan-report.json';
const SNIPPET_MAX = 120;

/**
 * @typedef {{
 *   severity: 'error' | 'warning',
 *   rule: string,
 *   message: string,
 *   file: string,
 *   line: number,
 *   column: number,
 *   module: string,
 *   moduleLine: number | null,
 *   snippet: string,
 *   context?: string,
 *   allowlisted?: { reason: string, addedBy: string },
 * }} Finding
 */

function clip(text, max = SNIPPET_MAX) {
  const oneLine = String(text).replace(/\s+/g, ' ');
  return oneLine.length > max ? `${oneLine.slice(0, max - 1)}…` : oneLine;
}

function excerpt(text, index, radius = 40) {
  const start = Math.max(0, index - radius);
  const end = Math.min(text.length, index + radius);
  return clip(`${start > 0 ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}`, SNIPPET_MAX + 4);
}

function toPosix(p) {
  return p.split(path.sep).join('/');
}

/** Maps generated positions to original modules using a hidden sourcemap, when present. */
class ModuleMapper {
  /**
   * @param {string | null} mapText
   * @param {string} mapDir absolute directory of the .map file
   */
  constructor(mapText, mapDir) {
    this.consumer = null;
    this.mapDir = mapDir;
    if (mapText) {
      try {
        const raw = JSON.parse(mapText);
        this.sourceRoot = raw.sourceRoot || '';
        this.consumer = new SourceMapConsumer(raw);
      } catch {
        this.consumer = null;
      }
    }
  }

  /** @returns {{ module: string | null, line: number | null }} */
  lookup(line, column) {
    if (!this.consumer) return { module: null, line: null };
    const pos = this.consumer.originalPositionFor({ line, column });
    if (!pos.source) return { module: null, line: null };
    return { module: normalizeModule(pos.source, this.mapDir, this.sourceRoot), line: pos.line };
  }
}

/** Original source path, relative to frontend/ (e.g. "src/main.tsx", "node_modules/x/y.js"). */
export function normalizeModule(source, mapDir, sourceRoot = '') {
  if (/^[\0a-z]+:/i.test(source) || source.startsWith('\0')) return source.replace(/^\0/, '');
  const abs = path.resolve(mapDir, sourceRoot, source);
  const rel = toPosix(path.relative(FN_ROOT, abs));
  if (!rel.startsWith('../')) return rel;
  // plugin-legacy's polyfill chunk is built from another directory, so its sources look like
  // "../../../../../core-js/internals/x.js". Re-anchor them under node_modules/ when possible.
  const stripped = toPosix(source).replace(/^(\.\.\/)+/, '');
  if (fs.existsSync(path.join(FN_ROOT, 'node_modules', stripped))) return `node_modules/${stripped}`;
  return toPosix(abs);
}

function isRegExpCallee(node) {
  if (node.type === 'Identifier') return node.name === 'RegExp';
  if (node.type === 'MemberExpression' && !node.computed && node.property.type === 'Identifier') {
    return (
      node.property.name === 'RegExp' &&
      node.object.type === 'Identifier' &&
      ['window', 'globalThis', 'self'].includes(node.object.name)
    );
  }
  return false;
}

function staticString(node) {
  if (!node) return null;
  if (node.type === 'Literal' && typeof node.value === 'string') return node.value;
  if (node.type === 'TemplateLiteral' && node.expressions.length === 0) return node.quasis[0]?.value.cooked ?? null;
  return null;
}

/**
 * Flatten a string expression: literals, expression-free templates and `+` concatenations.
 * Dynamic parts (e.g. runtime asset URLs) become `__dynamic__`. Returns null for anything else.
 */
function flattenString(node) {
  const direct = staticString(node);
  if (direct !== null) return direct;
  if (node?.type === 'BinaryExpression' && node.operator === '+') {
    const left = flattenString(node.left) ?? '__dynamic__';
    const right = flattenString(node.right) ?? '__dynamic__';
    return left + right;
  }
  return null;
}

/**
 * CSS that legacy (SystemJS) chunks inject at runtime. @vitejs/plugin-legacy emits no .css file
 * when renderModernChunks is false; Vite inlines each chunk's CSS as
 * `var s = document.createElement('style'); s.textContent = "<css>"; document.head.appendChild(s)`.
 * @returns {{ css: string, node: any }[]}
 */
export function extractInlineCss(ast) {
  const out = [];
  walk.simple(ast, {
    AssignmentExpression(node) {
      const left = node.left;
      if (left.type !== 'MemberExpression' || left.computed || left.property.name !== 'textContent') return;
      const css = flattenString(node.right);
      if (css !== null && css.includes('{') && css.includes(':')) out.push({ css, node });
    },
  });
  return out;
}

/**
 * Scan one JS source.
 * @param {string} code
 * @param {{ file: string, mapText?: string | null, mapDir?: string, lineOffset?: number }} opts
 * @returns {Finding[]}
 */
export function scanJs(code, opts) {
  const { file } = opts;
  const lineOffset = opts.lineOffset ?? 0;
  const mapper = new ModuleMapper(opts.mapText ?? null, opts.mapDir ?? FN_ROOT);
  /** @type {Finding[]} */
  const findings = [];

  const add = (severity, rule, message, node, snippet, context) => {
    const loc = node.loc?.start ?? { line: 1, column: 0 };
    const orig = mapper.lookup(loc.line, loc.column);
    findings.push({
      severity,
      rule,
      message,
      file,
      line: loc.line + lineOffset,
      column: loc.column,
      module: orig.module ?? file,
      moduleLine: orig.line,
      snippet: clip(snippet),
      ...(context ? { context: context.slice(0, 2000) } : {}),
    });
  };

  let ast;
  try {
    ast = acorn.parse(code, { ecmaVersion: 2019, sourceType: 'script', locations: true, allowHashBang: true });
  } catch (err) {
    const loc = err.loc ?? { line: 1, column: 0 };
    const lines = code.split('\n');
    const lineText = lines[loc.line - 1] ?? '';
    add(
      'error',
      'syntax',
      `not ES2019 script syntax: ${err.message}`,
      { loc: { start: loc } },
      excerpt(lineText, loc.column),
    );
    return findings;
  }

  /** String/template nodes already validated as RegExp(...) patterns. */
  const handled = new Set();

  const regexpCall = (node) => {
    if (!isRegExpCallee(node.callee)) return;
    const [patternArg, flagsArg] = node.arguments;
    if (!patternArg) return;
    if (patternArg.type === 'Literal' && patternArg.regex) return; // checked as a literal
    const pattern = staticString(patternArg);
    if (pattern === null) {
      add('warning', 'regexp-dynamic', 'RegExp() with a dynamic pattern (not statically checkable)', node, code.slice(node.start, node.end));
      return;
    }
    handled.add(patternArg);
    const flags = staticString(flagsArg) ?? '';
    for (const issue of findUnsafeRegexFeatures(pattern, flags)) {
      const severity = issue.feature === 'invalid' ? 'warning' : 'error';
      add(severity, `regexp-${issue.feature}`, `RegExp("…"): ${issue.message}`, node, code.slice(node.start, node.end), pattern);
    }
  };

  walk.simple(ast, { CallExpression: regexpCall, NewExpression: regexpCall });

  walk.simple(ast, {
    Literal(node) {
      if (node.regex) {
        for (const issue of findUnsafeRegexFeatures(node.regex.pattern, node.regex.flags)) {
          const severity = issue.feature === 'invalid' ? 'warning' : 'error';
          add(severity, `regex-${issue.feature}`, `regex literal: ${issue.message}`, node, node.raw ?? '', node.regex.pattern);
        }
        return;
      }
      if (typeof node.value !== 'string' || handled.has(node)) return;
      const hit = findSuspiciousRegexText(node.value);
      if (hit) {
        add('error', 'string-regex-syntax', `string contains "${hit}" (regex source assembled from strings?)`, node, excerpt(node.value, node.value.indexOf(hit)), node.value);
      }
    },
    TemplateLiteral(node) {
      if (handled.has(node)) return;
      for (const quasi of node.quasis) {
        const text = quasi.value.cooked ?? quasi.value.raw;
        const hit = findSuspiciousRegexText(text);
        if (hit) {
          add('error', 'string-regex-syntax', `template contains "${hit}" (regex source assembled from strings?)`, quasi, excerpt(text, text.indexOf(hit)), text);
        }
      }
    },
  });

  extractInlineCss(ast).forEach(({ css, node }, i) => {
    const loc = node.loc?.start ?? { line: 1, column: 0 };
    for (const f of scanCss(css, { file: `${file} <inline css #${i + 1}>` })) {
      findings.push({ ...f, line: loc.line + lineOffset, column: loc.column, module: f.module, moduleLine: f.line });
    }
  });

  return findings;
}

/** CSS that Safari 12 does not support and that PostCSS should have lowered. */
const LOGICAL_PROP = /(^|-)(inline|block)(-|$)/;
const VIEWPORT_UNITS = /(?:\d|\.)(?:d|s|l)v(?:h|w|i|b|min|max)\b/i;
const BAD_FUNCTIONS = /\b(color-mix|clamp)\(/i;
const BAD_SELECTORS = /:(is|where|has)\(|:focus-visible/;
const BAD_AT_RULES = new Set(['layer', 'container']);
const NEEDS_WEBKIT = ['backdrop-filter', 'mask-image'];

/**
 * @param {string} css
 * @param {{ file: string, lineOffset?: number }} opts
 * @returns {Finding[]}
 */
export function scanCss(css, opts) {
  const { file } = opts;
  const lineOffset = opts.lineOffset ?? 0;
  /** @type {Finding[]} */
  const findings = [];
  const add = (rule, message, node, snippet) => {
    findings.push({
      severity: 'error',
      rule,
      message,
      file,
      line: (node.source?.start?.line ?? 1) + lineOffset,
      column: node.source?.start?.column ?? 0,
      module: file,
      moduleLine: null,
      snippet: clip(snippet),
    });
  };

  let root;
  try {
    root = postcss.parse(css, { from: undefined });
  } catch (err) {
    findings.push({ severity: 'error', rule: 'css-syntax', message: `CSS parse error: ${err.message}`, file, line: (err.line ?? 1) + lineOffset, column: err.column ?? 0, module: file, moduleLine: null, snippet: '' });
    return findings;
  }

  root.walkDecls((decl) => {
    const prop = decl.prop.toLowerCase();
    const text = decl.toString();
    if (!prop.startsWith('--')) {
      if (prop === 'gap' || prop === 'row-gap' || prop === 'column-gap') {
        add('css-gap', `"${prop}" (flex gap needs Safari 14.1; use margins / layout primitives)`, decl, text);
      } else if (/^inset(-|$)/.test(prop)) {
        add('css-inset', `"${prop}" (Safari 14.1+; use top/right/bottom/left)`, decl, text);
      } else if (prop === 'aspect-ratio') {
        add('css-aspect-ratio', '"aspect-ratio" (Safari 15+)', decl, text);
      } else if (!prop.startsWith('-') && LOGICAL_PROP.test(prop)) {
        add('css-logical', `logical property "${prop}" (not lowered by PostCSS)`, decl, text);
      } else if (NEEDS_WEBKIT.includes(prop)) {
        const prefixed = `-webkit-${prop}`;
        const hasPrefixed = decl.parent?.some?.((n) => n.type === 'decl' && n.prop.toLowerCase() === prefixed);
        if (!hasPrefixed) add('css-unprefixed', `"${prop}" without "${prefixed}"`, decl, text);
      }
    }
    if (VIEWPORT_UNITS.test(decl.value)) add('css-viewport-unit', 'dynamic/small/large viewport unit (Safari 15.4+)', decl, text);
    const fn = BAD_FUNCTIONS.exec(decl.value);
    if (fn) add('css-function', `${fn[1]}() (Safari 12 lacks it)`, decl, text);
  });

  root.walkRules((rule) => {
    const m = BAD_SELECTORS.exec(rule.selector);
    if (m) add('css-selector', `selector uses ${m[0].replace('(', '()')} (Safari 12 drops the whole rule)`, rule, rule.selector);
  });

  root.walkAtRules((atRule) => {
    if (BAD_AT_RULES.has(atRule.name.toLowerCase())) {
      add('css-at-rule', `@${atRule.name} (Safari 12 lacks it)`, atRule, `@${atRule.name} ${atRule.params}`);
    }
  });

  return findings;
}

function lineOf(text, index) {
  let line = 0;
  for (let i = 0; i < index; i++) if (text.charCodeAt(i) === 10) line++;
  return line;
}

/**
 * Inline <script> and <style> blocks of an HTML file.
 * @returns {Finding[]}
 */
export function scanHtml(html, opts) {
  const { file } = opts;
  /** @type {Finding[]} */
  const findings = [];
  const scriptRe = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let m;
  while ((m = scriptRe.exec(html))) {
    const attrs = m[1] ?? '';
    const body = m[2] ?? '';
    if (/\bsrc\s*=/i.test(attrs) || !body.trim()) continue;
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs)?.[1]?.toLowerCase();
    if (type && !['text/javascript', 'application/javascript', 'module'].includes(type)) continue;
    const bodyStart = m.index + m[0].indexOf('>') + 1;
    findings.push(...scanJs(body, { file: `${file} <script>`, lineOffset: lineOf(html, bodyStart) }));
  }
  const styleRe = /<style\b[^>]*>([\s\S]*?)<\/style\s*>/gi;
  while ((m = styleRe.exec(html))) {
    const bodyStart = m.index + m[0].indexOf('>') + 1;
    findings.push(...scanCss(m[1] ?? '', { file: `${file} <style>`, lineOffset: lineOf(html, bodyStart) }));
  }
  return findings;
}

function listFiles(dir) {
  /** @type {string[]} */
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === REPORT_NAME || entry.name === '.vite') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listFiles(full));
    else out.push(full);
  }
  return out.sort();
}

/**
 * @param {Finding[]} findings
 * @param {{ module: string, snippet: string, reason: string, addedBy: string }[]} allowlist
 * @returns {Finding[]} findings plus one error per stale allowlist entry
 */
export function applyAllowlist(findings, allowlist) {
  const used = new Set();
  for (const f of findings) {
    allowlist.forEach((entry, i) => {
      if (f.allowlisted) return;
      const moduleOk = f.module === entry.module || f.module.endsWith(`/${entry.module}`);
      const haystack = `${f.snippet}\n${f.context ?? ''}`;
      if (moduleOk && haystack.includes(entry.snippet)) {
        f.allowlisted = { reason: entry.reason, addedBy: entry.addedBy };
        used.add(i);
      }
    });
  }
  const stale = allowlist
    .map((entry, i) => ({ entry, i }))
    .filter(({ i }) => !used.has(i))
    .map(({ entry }) => ({
      severity: /** @type {const} */ ('error'),
      rule: 'allowlist-stale',
      message: `allowlist entry matches nothing (remove it): ${entry.reason}`,
      file: 'compat-scan-allowlist.json',
      line: 0,
      column: 0,
      module: entry.module,
      moduleLine: null,
      snippet: entry.snippet,
    }));
  return [...findings, ...stale];
}

export function loadAllowlist(file = DEFAULT_ALLOWLIST) {
  if (!fs.existsSync(file)) return [];
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const entries = Array.isArray(data) ? data : data.entries;
  if (!Array.isArray(entries)) throw new Error(`${file}: expected an array or { entries: [] }`);
  for (const e of entries) {
    for (const key of ['module', 'snippet', 'reason', 'addedBy']) {
      if (typeof e[key] !== 'string' || !e[key]) throw new Error(`${file}: every entry needs a non-empty "${key}"`);
    }
  }
  return entries;
}

/**
 * Scan a dist directory.
 * @returns {{ files: number, errors: Finding[], warnings: Finding[], allowlisted: Finding[] }}
 */
export function scanDist(distDir, { allowlist = [] } = {}) {
  const abs = path.resolve(distDir);
  if (!fs.existsSync(abs)) throw new Error(`dist directory not found: ${abs}`);
  /** @type {Finding[]} */
  let findings = [];
  let files = 0;
  for (const full of listFiles(abs)) {
    const rel = toPosix(path.relative(abs, full));
    if (full.endsWith('.js') || full.endsWith('.mjs')) {
      files++;
      const mapFile = `${full}.map`;
      const mapText = fs.existsSync(mapFile) ? fs.readFileSync(mapFile, 'utf8') : null;
      findings.push(...scanJs(fs.readFileSync(full, 'utf8'), { file: rel, mapText, mapDir: path.dirname(full) }));
    } else if (full.endsWith('.css')) {
      files++;
      findings.push(...scanCss(fs.readFileSync(full, 'utf8'), { file: rel }));
    } else if (full.endsWith('.html')) {
      files++;
      findings.push(...scanHtml(fs.readFileSync(full, 'utf8'), { file: rel }));
    }
  }
  findings = applyAllowlist(findings, allowlist);
  return {
    files,
    errors: findings.filter((f) => f.severity === 'error' && !f.allowlisted),
    warnings: findings.filter((f) => f.severity === 'warning' && !f.allowlisted),
    allowlisted: findings.filter((f) => f.allowlisted),
  };
}

function formatFinding(f) {
  const where = f.module !== f.file ? `${f.module}${f.moduleLine ? `:${f.moduleLine}` : ''} (in ${f.file}:${f.line})` : `${f.file}:${f.line}`;
  return `  [${f.rule}] ${f.message}\n      at ${where}\n      ${f.snippet}`;
}

function main(argv) {
  const args = argv.slice(2);
  let allowlistFile = DEFAULT_ALLOWLIST;
  const ai = args.indexOf('--allowlist');
  if (ai >= 0) {
    allowlistFile = path.resolve(args[ai + 1] ?? '');
    args.splice(ai, 2);
  }
  const distDir = args[0];
  if (!distDir) {
    console.error('usage: scan-compat-bundle.mjs <distDir> [--allowlist <file>]');
    return 2;
  }
  const result = scanDist(distDir, { allowlist: loadAllowlist(allowlistFile) });
  const reportPath = path.join(path.resolve(distDir), REPORT_NAME);
  fs.writeFileSync(reportPath, `${JSON.stringify({ distDir, ...result }, null, 2)}\n`);

  console.log(`compat scanner: ${result.files} files in ${distDir}`);
  if (result.warnings.length) {
    console.log(`\n${result.warnings.length} warning(s):`);
    for (const w of result.warnings.slice(0, 20)) console.log(formatFinding(w));
    if (result.warnings.length > 20) console.log(`  … ${result.warnings.length - 20} more in ${REPORT_NAME}`);
  }
  if (result.allowlisted.length) console.log(`\n${result.allowlisted.length} allowlisted finding(s) (see ${REPORT_NAME})`);
  if (result.errors.length) {
    console.error(`\n${result.errors.length} error(s): Safari 12 / iOS 12 would fail on these:`);
    for (const e of result.errors) console.error(formatFinding(e));
    console.error(`\nFAIL (report: ${reportPath})`);
    return 1;
  }
  console.log(`\nPASS (report: ${reportPath})`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv);
}
