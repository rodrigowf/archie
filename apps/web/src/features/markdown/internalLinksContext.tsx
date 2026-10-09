/**
 * Host wiring for internal links (spec 12 §9.4). The app root provides how to open a
 * visualization / memory file (`openDocument`) and the backend origin; every `Markdown` below it
 * (chat, plans, tool output, memory documents) then opens internal links in the app and
 * auto-links printed paths (LNK-5). Without a provider, links behave as before.
 *
 * A plain click opens in the app; middle / ctrl / cmd-click keep the browser default on the real
 * URL (`hrefOf`), so "open in new tab" and "copy link address" still work (LNK-6).
 */
import { createContext, useContext, type ReactNode } from 'react';
import { resolveInternalLink, type InternalLinkContext, type InternalTarget } from './internalLinks';
import type { LinkResolver } from './links';

export interface InternalLinks {
  readonly context: InternalLinkContext;
  readonly open: (target: InternalTarget) => void;
  /** The absolute URL of a target (the anchor's `href`). */
  readonly hrefOf: (target: InternalTarget) => string;
}

const InternalLinksContext = createContext<InternalLinks | null>(null);

/** `value` must be stable (memoize it): every Markdown below re-renders when it changes. */
export function InternalLinksProvider({ value, children }: { value: InternalLinks; children: ReactNode }) {
  return <InternalLinksContext.Provider value={value}>{children}</InternalLinksContext.Provider>;
}

export function useInternalLinks(): InternalLinks | null {
  return useContext(InternalLinksContext);
}

export function createInternalLinkResolver(links: InternalLinks): LinkResolver {
  return (href) => {
    const target = resolveInternalLink(href, links.context);
    if (!target) return null;
    return { href: links.hrefOf(target), onActivate: () => links.open(target), title: target.path };
  };
}

/** `first`, then `second` for the links `first` does not handle. */
export function chainResolvers(first: LinkResolver | undefined, second: LinkResolver | undefined): LinkResolver | undefined {
  if (!first || !second) return first ?? second;
  return (href) => first(href) ?? second(href);
}
