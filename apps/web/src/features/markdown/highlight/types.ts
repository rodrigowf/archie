import type { LanguageFn } from 'highlight.js';

/** Shape of `languages.main.ts` / `languages.compat.ts` (spec 13 §2.4). */
export interface LanguageRegistry {
  target: 'main' | 'compat';
  /** Registered when the highlighter chunk loads. Same set on both builds. */
  core: Record<string, LanguageFn>;
  /** Names and aliases served by lazy per-language chunks (main only; empty on compat). */
  extraNames: readonly string[];
  /** Loads the chunk that defines `lang` (a name or alias); null when there is none. */
  loadExtra: ((lang: string) => Promise<Record<string, LanguageFn>> | null) | null;
}
