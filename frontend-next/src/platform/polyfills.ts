/**
 * Main build: nothing to polyfill (targets Chrome 109 / Safari 15.4 / Firefox 115).
 * The compat build aliases `@/platform/polyfills` to polyfills.compat.ts (vite.shared.ts).
 * main.tsx imports this module FIRST so polyfills run before any other module.
 */
export {};
