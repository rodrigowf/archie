/**
 * Type of the per-build alias `@/features/markdown/highlight/languages`. Vite and Vitest resolve
 * it to `languages.main.ts` or `languages.compat.ts` (vite.shared.ts `targetAliases`); this file
 * only gives TypeScript a single module to check imports against.
 */
import type { LanguageRegistry } from './types';

export declare const languageRegistry: LanguageRegistry;
