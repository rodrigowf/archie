/**
 * Gallery route (spec 13 §3.1): `#/dev/gallery[/<section>][?theme=light|dark|system&full=1]`.
 * `full=1` lets the page grow to its content height (for full-page screenshots).
 */
import { isThemeMode, type ThemeMode } from '@/styles';

export const GALLERY_ROUTE = '#/dev/gallery';

export interface GalleryRoute {
  section: string | null;
  theme: ThemeMode | null;
  full: boolean;
}

export function isGalleryRoute(hash: string = window.location.hash): boolean {
  return hash === GALLERY_ROUTE || hash.startsWith(`${GALLERY_ROUTE}/`) || hash.startsWith(`${GALLERY_ROUTE}?`);
}

export function parseGalleryRoute(hash: string): GalleryRoute {
  const rest = hash.slice(GALLERY_ROUTE.length);
  const q = rest.indexOf('?');
  const path = (q >= 0 ? rest.slice(0, q) : rest).replace(/^\/+|\/+$/g, '');
  const params: Record<string, string> = {};
  if (q >= 0) {
    for (const pair of rest.slice(q + 1).split('&')) {
      const [k, v = ''] = pair.split('=');
      if (k) params[decodeURIComponent(k)] = decodeURIComponent(v);
    }
  }
  const theme = params.theme ?? null;
  return {
    section: path || null,
    theme: isThemeMode(theme) ? theme : null,
    full: params.full === '1',
  };
}

export function galleryHref(section: string | null, theme?: ThemeMode | null): string {
  const base = section ? `${GALLERY_ROUTE}/${section}` : GALLERY_ROUTE;
  return theme ? `${base}?theme=${theme}` : base;
}
