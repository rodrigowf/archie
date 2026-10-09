/**
 * Entry point of `@/features/links` (spec 12 §9.4): which links point at a visualization or a
 * memory file, and the provider through which the app opens them. Light on purpose (no markdown
 * renderer), so the app root can import it.
 *
 *   resolveInternalLink(href, ctx)               LNK-1…LNK-4 → {kind: 'visual' | 'memory', path}
 *   linkableCode(code) / findBarePaths(text)     LNK-5 auto-linking
 *   <InternalLinksProvider value={{context, open, hrefOf}}>
 */
export {
  findBarePaths,
  internalTargetUrl,
  isPrivateHost,
  linkableCode,
  resolveInternalLink,
  type BarePath,
  type InternalLinkContext,
  type InternalTarget,
} from './internalLinks';
export { InternalLinksProvider, useInternalLinks, type InternalLinks } from './context';
