/**
 * Shared Vite configuration for the two builds (spec 13 §1.4).
 * `sharedConfig(target, base)` is merged by vite.config.main.ts and vite.config.compat.ts;
 * `targetAliases` and `targetDefine` are reused by vitest.config.ts.
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import postcss from 'postcss';
import postcssPresetEnv from 'postcss-preset-env';
import type { Alias, UserConfig } from 'vite';
import { browserslistEnv } from './scripts/browserslist-env.mjs';
import { htmlTarget, type BuildTarget } from './scripts/vite-plugin-html-target.ts';

export type { BuildTarget };

export const FN_ROOT = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(FN_ROOT, '..');
export const SRC_DIR = path.join(FN_ROOT, 'src');
export const TOKENS_DIST = path.join(REPO_ROOT, 'design', 'tokens', 'dist');
export const FIXTURES_DIR = path.join(REPO_ROOT, 'shared', 'protocol-fixtures');
const CERTS_DIR = path.join(REPO_ROOT, 'context', 'certs');

export const PORTS = { main: 5450, compat: 5451, mock: 8799 } as const;

/** Aliases. Specific entries come before the generic `@/` prefix (first match wins). */
export function targetAliases(target: BuildTarget): Alias[] {
  return [
    // Compat-only polyfills; main resolves `@/platform/polyfills` to the no-op polyfills.ts.
    ...(target === 'compat'
      ? [{ find: /^@\/platform\/polyfills$/, replacement: path.join(SRC_DIR, 'platform', 'polyfills.compat.ts') }]
      : []),
    // Per-build highlight.js language registry (W-08, spec 13 §2.4).
    {
      find: /^@\/features\/markdown\/highlight\/languages$/,
      replacement: path.join(SRC_DIR, 'features', 'markdown', 'highlight', `languages.${target}.ts`),
    },
    // Lookbehind-free fork of the GFM autolink extension, on BOTH builds (spec 13 §2.4).
    {
      find: /^mdast-util-gfm-autolink-literal$/,
      replacement: path.join(SRC_DIR, 'features', 'markdown', 'gfm', 'autolinkLiteralSafe.ts'),
    },
    { find: /^@tokens\//, replacement: `${TOKENS_DIST}/` },
    { find: /^@\//, replacement: `${SRC_DIR}/` },
  ];
}

function gitSha(): string {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: FN_ROOT, stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim();
  } catch {
    return 'nogit';
  }
}

export function targetDefine(target: BuildTarget): Record<string, string> {
  const pkg = JSON.parse(fs.readFileSync(path.join(FN_ROOT, 'package.json'), 'utf8')) as { version: string };
  return {
    __TARGET__: JSON.stringify(target),
    __APP_VERSION__: JSON.stringify(`${pkg.version}+${gitSha()}`),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  };
}

/**
 * postcss-preset-env removes `@custom-media` definitions per file, so a CSS Module would never
 * see the size classes declared once in src/styles/media.css (W-02). This plugin prepends that
 * file's `@custom-media` rules to every stylesheet before preset-env runs.
 */
export function customMediaGlobals(mediaFile: string = path.join(SRC_DIR, 'styles', 'media.css')): postcss.Plugin {
  return {
    postcssPlugin: 'archie-custom-media-globals',
    Once(root) {
      if (!fs.existsSync(mediaFile) || root.source?.input.file === mediaFile) return;
      const defs = postcss.parse(fs.readFileSync(mediaFile, 'utf8'), { from: mediaFile });
      const rules: postcss.AtRule[] = [];
      defs.walkAtRules('custom-media', (rule) => {
        rules.push(rule.clone());
      });
      root.prepend(...rules);
    },
  };
}

export function postcssPlugins(target: BuildTarget, mediaFile?: string): postcss.AcceptedPlugin[] {
  return [
    customMediaGlobals(mediaFile),
    postcssPresetEnv({
      // Explicit queries from the [main] / [compat] section of .browserslistrc. The `env` option
      // would look the file up relative to each stylesheet, so CSS outside frontend/ (e.g.
      // design/tokens/dist/tokens.css) would silently get browserslist defaults.
      browsers: browserslistEnv(target),
      stage: 2,
      features: {
        'nesting-rules': true,
        'custom-media-queries': true,
        'logical-properties-and-values': true,
        'cascade-layers': false,
        // compat: rewrite :focus-visible to .focus-visible (paired with the focus-visible
        // polyfill) and drop the original selector so Safari 12 never sees it.
        'focus-visible-pseudo-class': target === 'compat' ? { preserve: false } : false,
      },
    }),
  ];
}

function httpsOptions(): { key: Buffer; cert: Buffer } | undefined {
  if (process.env.FN_HTTPS === '0') return undefined; // plain HTTP, e.g. for a quick device check
  const key = path.join(CERTS_DIR, 'key.pem');
  const cert = path.join(CERTS_DIR, 'cert.pem');
  if (!fs.existsSync(key) || !fs.existsSync(cert)) return undefined;
  // Needed for mic + WebRTC on LAN devices (as in frontend/vite.config.ts:6-21).
  return { key: fs.readFileSync(key), cert: fs.readFileSync(cert) };
}

export function sharedConfig(target: BuildTarget, base: string): UserConfig {
  const backend = process.env.ARCHIE_BACKEND ?? 'http://localhost:8765';
  const proxyEntry = { target: backend, changeOrigin: true, secure: false };
  const proxy = {
    '/api': { ...proxyEntry, ws: true },
    '/memory': proxyEntry,
    '/uploads': proxyEntry,
    '/projects': proxyEntry,
  };
  const https = httpsOptions();
  const port = PORTS[target];
  return {
    root: FN_ROOT,
    base,
    plugins: [react(), htmlTarget(target)],
    resolve: { alias: targetAliases(target) },
    define: targetDefine(target),
    css: {
      postcss: { plugins: postcssPlugins(target) },
      modules: { generateScopedName: '[name]__[local]__[hash:base64:5]' },
    },
    server: {
      host: '0.0.0.0',
      port,
      strictPort: true,
      ...(https ? { https } : {}),
      proxy,
      fs: { allow: [REPO_ROOT, TOKENS_DIST, FIXTURES_DIR] },
    },
    preview: {
      host: '0.0.0.0',
      port,
      strictPort: true,
      ...(https ? { https } : {}),
      proxy,
    },
  };
}
