/**
 * Internal links in every rendered markdown (spec 12 §9.4): a link to a visualization or a memory
 * file opens it the way the Visuals / Memory lists do (`openDocument`: a tab on medium/expanded,
 * a document screen on compact) instead of a browser tab. The anchor keeps the real URL, so
 * middle / ctrl-click still open a new tab.
 */
import type { ReactNode } from 'react';
import { InternalLinksProvider, memoryFileUrl, type InternalLinks, type InternalTarget } from '@/features/markdown';
import { vizHref, vizOrigin } from '@/features/visuals';
import { httpUrl } from '@/services';
import { catalogStore } from '@/stores';
import { openDocument } from './shell/actions';
import { currentWindowClass } from './useWindowClass';

function backendOrigin(): string {
  const o = vizOrigin();
  if (o) return o;
  return typeof location !== 'undefined' ? location.origin : '';
}

function listed(path: string) {
  return catalogStore.getState().visuals.items.find((v) => v.path === path);
}

function basename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] ?? path;
}

export function openInternalTarget(t: InternalTarget): void {
  const compact = currentWindowClass() === 'compact';
  if (t.kind === 'memory') {
    openDocument('memory', t.path, { compact, title: basename(t.path) });
    return;
  }
  const v = listed(t.path);
  openDocument('visual', t.path, { compact, ...(v ? { url: v.url, title: v.title } : {}) });
}

const LINKS: InternalLinks = {
  context: {
    get origin() {
      return backendOrigin();
    },
    isVisual: (path) => !!listed(path),
  },
  open: openInternalTarget,
  hrefOf: (t) => (t.kind === 'memory' ? httpUrl(memoryFileUrl(t.path)) + (t.fragment ? `#${t.fragment}` : '') : vizHref(t.path, listed(t.path)?.url)),
};

export function InternalLinksHost({ children }: { children: ReactNode }) {
  return <InternalLinksProvider value={LINKS}>{children}</InternalLinksProvider>;
}
