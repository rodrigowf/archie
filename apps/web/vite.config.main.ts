/** Main build: base '/', dist/, modern targets, PWA files (spec 13 §1.4). */
import { defineConfig, mergeConfig } from 'vite';
import { sharedConfig } from './vite.shared.ts';

const base = process.env.FN_BASE ?? '/';

export default defineConfig(
  mergeConfig(sharedConfig('main', base), {
    publicDir: 'public-main',
    build: {
      outDir: process.env.FN_OUT ?? 'dist',
      emptyOutDir: true,
      target: ['es2020', 'chrome109', 'safari15.4', 'firefox115'],
      manifest: true, // read by scripts/check-budgets.mjs
      sourcemap: 'hidden',
    },
  }),
);
