/**
 * Link handling for rendered markdown (spec 13 §2.4, inv02 F-03, F-37).
 *
 * - External links open in a new tab with `rel="noopener noreferrer"` and carry the global
 *   `md-link` class (LOAD-BEARING inv02 F-03, frontend/src/components/Markdown.tsx:16-28,
 *   commit e6f2f53): its CSS holds the iPad tap fix (`cursor: pointer; touch-action:
 *   manipulation; position: relative`), needed for taps on bare inline elements inside a
 *   momentum-scroll container on iOS 12.
 * - A `LinkResolver` lets a host open some links in the app instead. `createMemoryLinkResolver`
 *   resolves relative `.md` links against the current memory file (fixes inv02 §6.2: links used
 *   to resolve against the app root) and fetches files with each path segment URL-encoded.
 */

/** Global class on every markdown link (kept un-hashed on purpose, see above). */
export const MD_LINK_CLASS = 'md-link';

export const EXTERNAL_LINK_PROPS = { target: '_blank', rel: 'noopener noreferrer' } as const;

/** What a resolver returns for a link it handles in-app. */
export interface ResolvedLink {
  /** The real URL, used for the `href` (middle-click, copy link address, "Open raw"). */
  href: string;
  /** Called on a plain click; the default navigation is prevented. */
  onActivate: () => void;
  /** Optional tooltip (e.g. the resolved memory path). */
  title?: string;
}

/** Returns `null` for links it does not handle; those render as external links. */
export type LinkResolver = (href: string) => ResolvedLink | null;

const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
const MEMORY_PREFIX = '/memory/';

/** URL of a memory file (`GET /memory/<path>`), with every path segment URL-encoded. */
export function memoryFileUrl(path: string): string {
  return MEMORY_PREFIX + path.split('/').filter(Boolean).map(encodeURIComponent).join('/');
}

function safeDecode(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/**
 * Resolves a markdown `href` found in the memory file `currentPath` (relative to
 * context/memory/, POSIX) to another memory file path, or `null` when it is not a memory link:
 * absolute URLs, scheme links (`https:`, `mailto:` …), in-page anchors, non-`.md` targets, other
 * site paths, and relative paths that climb out of the memory root.
 *
 *   resolveMemoryHref('../infra/ssh.md#setup', 'assistant/architecture/voice.md')
 *     → 'assistant/infra/ssh.md'
 */
export function resolveMemoryHref(href: string, currentPath: string): string | null {
  const trimmed = href.trim();
  if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('//') || SCHEME.test(trimmed)) return null;

  const pathPart = trimmed.split('#')[0]?.split('?')[0] ?? '';
  if (!/\.md$/i.test(safeDecode(pathPart))) return null;

  let segments: string[];
  if (pathPart.startsWith(MEMORY_PREFIX)) {
    segments = pathPart.slice(MEMORY_PREFIX.length).split('/');
  } else if (pathPart.startsWith('/')) {
    return null;
  } else {
    const base = currentPath.split('/').filter(Boolean);
    base.pop(); // the current file name
    segments = [...base, ...pathPart.split('/')];
  }

  const out: string[] = [];
  for (const raw of segments) {
    const segment = safeDecode(raw);
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (out.length === 0) return null; // escapes context/memory/
      out.pop();
      continue;
    }
    out.push(segment);
  }
  return out.length > 0 ? out.join('/') : null;
}

/**
 * Resolver for a memory document: relative `.md` links open the target file in the app through
 * `open(path)`; their `href` is the raw file URL, so "open in new tab" still works.
 */
export function createMemoryLinkResolver(currentPath: string, open: (path: string) => void): LinkResolver {
  return (href) => {
    const path = resolveMemoryHref(href, currentPath);
    if (path === null) return null;
    return { href: memoryFileUrl(path), onActivate: () => open(path), title: path };
  };
}
