/**
 * Vitest projects (spec 13 §6.1):
 *   protocol  node   src/protocol/**            fixture conformance (W-05); FIXTURES_DIR env
 *   dom       jsdom  src/** (not protocol)      services, stores, components, platform
 *   compat    jsdom  src/**\/*.compat.test.*    compat aliases + the RegExp guard
 *   scripts   node   scripts/__tests__/**       build gates (scanner, budgets, remote console)
 */
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { FIXTURES_DIR, postcssPlugins, targetAliases, targetDefine } from './vite.shared.ts';

const TEST_GLOB = '**/*.test.{ts,tsx}';
const COMPAT_GLOB = '**/*.compat.test.{ts,tsx}';
const GFM_INLINE = ['remark-gfm', 'mdast-util-gfm', 'micromark-extension-gfm'];

export default defineConfig({
  test: {
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.gallery.tsx', 'src/env.d.ts'],
      thresholds: {
        'src/protocol/**': { lines: 95 },
        'src/voice/core/**': { lines: 90 },
        'src/services/**': { lines: 80 },
      },
    },
    projects: [
      {
        test: {
          name: 'protocol',
          environment: 'node',
          include: [`src/protocol/${TEST_GLOB}`],
          env: { FIXTURES_DIR },
        },
        resolve: { alias: targetAliases('main') },
        define: targetDefine('main'),
      },
      {
        plugins: [react()],
        test: {
          name: 'dom',
          environment: 'jsdom',
          include: [`src/${TEST_GLOB}`],
          exclude: [`src/protocol/**`, `src/${COMPAT_GLOB}`],
          setupFiles: ['src/test/setup.ts'],
          // Inline the GFM packages so the autolink-safe alias applies inside them too (W-08).
          server: { deps: { inline: GFM_INLINE } },
        },
        resolve: { alias: targetAliases('main') },
        define: targetDefine('main'),
        css: { postcss: { plugins: postcssPlugins('main') } },
      },
      {
        plugins: [react()],
        test: {
          name: 'compat',
          environment: 'jsdom',
          include: [`src/${COMPAT_GLOB}`],
          setupFiles: ['src/test/setup.compat.ts', 'src/test/setup.ts'],
          server: { deps: { inline: GFM_INLINE } },
        },
        resolve: { alias: targetAliases('compat') },
        define: targetDefine('compat'),
        css: { postcss: { plugins: postcssPlugins('compat') } },
      },
      {
        test: {
          name: 'scripts',
          environment: 'node',
          include: ['scripts/__tests__/**/*.test.{mjs,ts}'],
        },
      },
    ],
  },
});
