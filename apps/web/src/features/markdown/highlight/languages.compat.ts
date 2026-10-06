/**
 * highlight.js language registry, COMPAT build (spec 13 §2.4), selected by the alias
 * `@/features/markdown/highlight/languages` (vite.shared.ts). The core set only: there is no
 * extra-language chunk on compat (several extra grammars use lookbehind), so other languages
 * render as plain code.
 */
import { coreLanguages } from './languages.core';
import type { LanguageRegistry } from './types';

export const languageRegistry: LanguageRegistry = {
  target: 'compat',
  core: coreLanguages,
  extraNames: [],
  loadExtra: null,
};
