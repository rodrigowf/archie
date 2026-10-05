/**
 * Compat build for Safari 12 / iOS 12 (iPad mini 2): base '/compat/', dist-compat/, legacy
 * (SystemJS) chunks only, usage-based core-js polyfills (spec 13 §1.4, §2).
 */
import legacy from '@vitejs/plugin-legacy';
import { defineConfig, mergeConfig } from 'vite';
import { sharedConfig } from './vite.shared.ts';

const base = process.env.FN_BASE ?? '/compat/';

export default defineConfig(
  mergeConfig(sharedConfig('compat', base), {
    publicDir: 'public-compat',
    plugins: [
      legacy({
        targets: ['safari >= 12', 'ios_saf >= 12'],
        renderModernChunks: false,
        modernPolyfills: false,
        polyfills: true,
      }),
    ],
    build: {
      outDir: process.env.FN_OUT ?? 'dist-compat',
      emptyOutDir: true,
      // The CSS minifier must not re-introduce syntax Safari 12 lacks (e.g. folding
      // top/right/bottom/left into `inset`). The bundle scanner re-checks the output.
      cssTarget: ['safari12', 'ios12'],
      minify: 'terser',
      manifest: true, // read by scripts/check-budgets.mjs
      sourcemap: 'hidden', // the scanner maps findings back to source modules
    },
  }),
);
