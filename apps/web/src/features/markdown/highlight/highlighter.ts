/**
 * The highlighter chunk (spec 13 §2.4, §5.3): highlight.js core through lowlight, with the
 * per-build language registry (`@/features/markdown/highlight/languages` is aliased to
 * languages.main.ts or languages.compat.ts). Loaded lazily by `loader.ts` on the first code
 * block, so neither build pays for it at startup.
 */
import type { ElementContent, Root } from 'hast';
import { createLowlight } from 'lowlight';
import { createElement, Fragment, type ReactNode } from 'react';
import { languageRegistry } from '@/features/markdown/highlight/languages';

const lowlight = createLowlight(languageRegistry.core);
const extraNames = new Set(languageRegistry.extraNames);
/** One load per extra language; a failed load (offline) is forgotten so it can be retried. */
const extraLoads = new Map<string, Promise<boolean>>();

export const target = languageRegistry.target;

/** Normalizes a fence info word: `Python` → `python`, `{.js}` → `js`. */
export function normalizeLanguage(lang: string): string {
  return lang.trim().toLowerCase().replace(/^\{?\.?/, '').replace(/\}$/, '');
}

/** True when the language (name or alias) can be highlighted right now. */
export function isRegistered(lang: string): boolean {
  return lowlight.registered(normalizeLanguage(lang));
}

/** True when the language lives in a lazy extra chunk that is not loaded yet (main only). */
export function needsExtra(lang: string): boolean {
  const name = normalizeLanguage(lang);
  return !lowlight.registered(name) && extraNames.has(name);
}

/** Makes `lang` available if it can be: resolves true when it is (or became) registered. */
export function ensureLanguage(lang: string): Promise<boolean> {
  const name = normalizeLanguage(lang);
  if (lowlight.registered(name)) return Promise.resolve(true);
  if (!extraNames.has(name) || !languageRegistry.loadExtra) return Promise.resolve(false);
  let pending = extraLoads.get(name);
  if (!pending) {
    const load = languageRegistry.loadExtra(name);
    if (!load) return Promise.resolve(false);
    pending = load.then(
      (grammars) => {
        lowlight.register(grammars);
        return lowlight.registered(name);
      },
      () => {
        extraLoads.delete(name);
        return false;
      },
    );
    extraLoads.set(name, pending);
  }
  return pending;
}

/** Names of every registered language (not aliases). */
export function listLanguages(): string[] {
  return lowlight.listLanguages();
}

function renderNodes(nodes: ElementContent[], keyPrefix: string): ReactNode[] {
  return nodes.map((node, i) => {
    if (node.type === 'text') return node.value;
    if (node.type !== 'element') return null;
    const cls = node.properties.className;
    const className = Array.isArray(cls) ? cls.join(' ') : typeof cls === 'string' ? cls : undefined;
    const key = `${keyPrefix}${i}`;
    return createElement(node.tagName, { key, className }, ...renderNodes(node.children, `${key}.`));
  });
}

/** hast from lowlight → React nodes (only `span` elements with classes, and text). */
export function hastToReact(tree: Root): ReactNode {
  return createElement(Fragment, null, ...renderNodes(tree.children as ElementContent[], ''));
}

const CACHE_LIMIT = 64;
const cache = new Map<string, ReactNode>();

/**
 * Highlights `code` as `lang`. Returns `null` when the language is not registered (call
 * `ensureLanguage` first for main's extra languages). Results are cached (LRU, 64 entries), so a
 * remount (the full parse after streaming, a tab coming back) costs nothing.
 */
export function highlightToReact(code: string, lang: string): ReactNode | null {
  const name = normalizeLanguage(lang);
  if (!lowlight.registered(name)) return null;
  const key = `${name}\u0000${code}`;
  const hit = cache.get(key);
  if (hit !== undefined) {
    cache.delete(key);
    cache.set(key, hit);
    return hit;
  }
  const node = hastToReact(lowlight.highlight(name, code));
  cache.set(key, node);
  if (cache.size > CACHE_LIMIT) {
    const oldest = cache.keys().next();
    if (!oldest.done) cache.delete(oldest.value);
  }
  return node;
}
