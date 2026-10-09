/**
 * LNK-5 (spec 12 §9.4): turn paths agents print without a link into links, so they open in the
 * app like any internal link. Only active under an `InternalLinksProvider` (Markdown.tsx), since
 * the resulting `href`s are raw paths that only the internal resolver understands.
 *
 * - Inline code that is exactly an internal path or URL (`linkableCode`) is wrapped in a link,
 *   keeping its code styling.
 * - Bare `context/public/….html` / `context/memory/….md` paths in plain text become links
 *   (`findBarePaths`). URLs are already GFM autolinks; `docs/` is only linked from inline code.
 * - Nothing inside an existing link, and never fenced code (only `inlineCode` / `text` nodes).
 */
import type { Link, Parent, PhrasingContent, Root, RootContent } from 'mdast';
import { findBarePaths, linkableCode, type InternalLinkContext } from '@/features/links';

function link(url: string, child: PhrasingContent): Link {
  return { type: 'link', url, title: null, children: [child] };
}

function rewrite(parent: Parent, ctx: InternalLinkContext): void {
  const out: RootContent[] = [];
  let changed = false;
  for (const node of parent.children) {
    if (node.type === 'link' || node.type === 'linkReference' || node.type === 'code' || node.type === 'html') {
      out.push(node);
      continue;
    }
    if (node.type === 'inlineCode' && linkableCode(node.value, ctx)) {
      out.push(link(node.value.trim(), node));
      changed = true;
      continue;
    }
    if (node.type === 'text') {
      const found = findBarePaths(node.value);
      if (!found.length) {
        out.push(node);
        continue;
      }
      let at = 0;
      for (const f of found) {
        if (f.start > at) out.push({ type: 'text', value: node.value.slice(at, f.start) });
        out.push(link(f.path, { type: 'text', value: f.path }));
        at = f.end;
      }
      if (at < node.value.length) out.push({ type: 'text', value: node.value.slice(at) });
      changed = true;
      continue;
    }
    if ('children' in node) rewrite(node, ctx);
    out.push(node);
  }
  if (changed) parent.children = out as typeof parent.children;
}

/** unified plugin: `[remarkInternalPaths, ctx]`. */
export function remarkInternalPaths(ctx: InternalLinkContext = {}) {
  return (tree: Root): void => {
    rewrite(tree, ctx);
  };
}
