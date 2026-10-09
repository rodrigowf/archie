/**
 * Entry point of `@/features/markdown` (W-08, spec 13 §3.6, §3.9).
 *
 *   <Markdown source linkResolver? />            static, memoized by props
 *   <StreamingMarkdown source streaming />       incremental while streaming, full parse at the end
 *   <CodeBlock code lang />                      header + Copy + lazy highlight
 *   createMemoryLinkResolver(path, open)         relative .md links → in-app (F-37)
 *   <InternalLinksProvider value={{context, open, hrefOf}}>  viz / memory links → in-app (§9.4)
 *   resolveInternalLink(href, ctx)               the shared link rules (LNK-1…LNK-5)
 *   splitFrontmatter(raw) / summarizeFrontmatter memory-file frontmatter (F-37)
 *   <MarkdownPrefsProvider value={{ syntaxHighlight }}>  the Appearance pref
 */
export { Markdown, type MarkdownProps } from './Markdown';
export { StreamingMarkdown, type StreamingMarkdownProps } from './StreamingMarkdown';
export { CodeBlock, type CodeBlockProps } from './CodeBlock';
export {
  createMemoryLinkResolver,
  EXTERNAL_LINK_PROPS,
  MD_LINK_CLASS,
  memoryFileUrl,
  resolveMemoryHref,
  type LinkResolver,
  type ResolvedLink,
} from './links';
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
export { chainResolvers, createInternalLinkResolver, InternalLinksProvider, useInternalLinks, type InternalLinks } from './internalLinksContext';
export { splitFrontmatter, summarizeFrontmatter, type FrontmatterSplit, type FrontmatterSummary } from './frontmatter';
export { splitBlocks, type MarkdownChunk, type SplitResult } from './splitBlocks';
export { DEFAULT_MARKDOWN_PREFS, MarkdownPrefsProvider, useMarkdownPrefs, type MarkdownPrefs } from './prefs';
