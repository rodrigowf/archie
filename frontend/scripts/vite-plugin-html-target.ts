/**
 * Injects the per-target <head>/<body> content into the shared index.html (spec 13 §1.4).
 *
 * | Item                                  | main                         | compat                     |
 * |---------------------------------------|------------------------------|----------------------------|
 * | Remote-console inline script (F-38)   | yes, off by default          | yes, on by default, prefix |
 * | manifest, apple-touch-icon, SW        | yes (SW only when base '/')  | no                         |
 * | <link rel=preload> latin Roboto Flex  | yes, once the font is bundled| same                       |
 *
 * index.html carries two markers: <!--archie:head--> and <!--archie:body-->.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Plugin, ResolvedConfig } from 'vite';

export type BuildTarget = 'main' | 'compat';

const here = path.dirname(fileURLToPath(import.meta.url));
export const REMOTE_CONSOLE_SOURCE = path.join(here, 'remote-console.js');

/** Asset name pattern of the font file to preload (owned by W-02's fonts.css). */
export const PRELOAD_FONT_PATTERN = /roboto-flex-latin-wght-normal[^/]*\.woff2$/;

export interface HtmlTargetOptions {
  target: BuildTarget;
  base: string;
  /** true for `vite build`, false for the dev server. */
  isBuild: boolean;
  /** Emitted file names of the bundle (build only), used for the font preload. */
  bundleFiles?: string[];
}

export function remoteConsoleScript(target: BuildTarget): string {
  const source = fs.readFileSync(REMOTE_CONSOLE_SOURCE, 'utf8');
  const cfg = {
    endpoint: '/api/debug/log',
    prefix: target === 'compat' ? '[compat] ' : '',
    defaultOn: target === 'compat',
  };
  // Strip the leading doc comment; keep the code as written (ES5, not transpiled).
  const code = source.replace(/^\/\*[\s\S]*?\*\/\s*/, '');
  return code.replace('__REMOTE_CONSOLE_CONFIG__', () => JSON.stringify(cfg));
}

function withBase(base: string, file: string): string {
  return (base.endsWith('/') ? base : `${base}/`) + file.replace(/^\//, '');
}

export function headHtml(opts: HtmlTargetOptions): string {
  const { target, base } = opts;
  const lines: string[] = [];
  lines.push(`<script>${remoteConsoleScript(target)}</script>`);
  lines.push(`<link rel="icon" type="image/svg+xml" href="${withBase(base, 'icon.svg')}" />`);
  if (target === 'main') {
    lines.push(`<link rel="manifest" href="${withBase(base, 'manifest.json')}" />`);
    lines.push(`<meta name="apple-mobile-web-app-title" content="Archie" />`);
    lines.push(`<link rel="apple-touch-icon" href="${withBase(base, 'icon-192.png')}" />`);
  }
  const font = (opts.bundleFiles ?? []).find((f) => PRELOAD_FONT_PATTERN.test(f));
  if (font) {
    lines.push(`<link rel="preload" as="font" type="font/woff2" crossorigin href="${withBase(base, font)}" />`);
  }
  return lines.join('\n    ');
}

export function bodyHtml(opts: HtmlTargetOptions): string {
  // The service worker is registered only for the production main build at '/'. Preview builds
  // (/next/) must not register a second SW scope (spec 13 §1.6); the dev server never does.
  if (opts.target !== 'main' || !opts.isBuild || opts.base !== '/') return '';
  return [
    '<script>',
    "if ('serviceWorker' in navigator) { navigator.serviceWorker.register('/sw.js'); }",
    '</script>',
  ].join('');
}

export function htmlTarget(target: BuildTarget): Plugin {
  let config: ResolvedConfig | undefined;
  return {
    name: 'archie-html-target',
    configResolved(resolved) {
      config = resolved;
    },
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const opts: HtmlTargetOptions = {
          target,
          base: config?.base ?? '/',
          isBuild: config?.command === 'build',
          bundleFiles: ctx.bundle ? Object.keys(ctx.bundle) : [],
        };
        if (!html.includes('<!--archie:head-->') || !html.includes('<!--archie:body-->')) {
          throw new Error('index.html is missing the <!--archie:head--> / <!--archie:body--> markers');
        }
        return html
          .replace('<!--archie:head-->', () => headHtml(opts))
          .replace('<!--archie:body-->', () => bodyHtml(opts));
      },
    },
  };
}
