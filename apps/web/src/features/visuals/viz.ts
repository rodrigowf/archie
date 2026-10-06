/**
 * Visualization helpers (spec 12 §9.1, inv02 F-36).
 *
 * - VZ-1: the backend's `url` is not percent-encoded (G-38), so every path segment is encoded
 *   before it goes into an iframe `src` or a link.
 * - Dev: visualizations are arbitrary root paths served by the backend catch-all, which the Vite
 *   dev server would answer with the app shell; `VITE_VIZ_ORIGIN` points frames at the backend
 *   (spec 13 §1.4). Production: same origin.
 * - VZ-5: meta = relative `modified` (offset-aware, A-8.4) and the parent folder or "public".
 * - Show on TV (BX-2, IA §9.4): `POST /api/visualizations/cast {path}` → `{ok, message}`; the
 *   failure snackbar shows the server's message verbatim; success says "Showing “title” on TV".
 */
import { api, castVisualization, errorMessage, httpUrl, type VisualizationInfo } from '@/services';
import { showSnackbar } from '@/stores';

export function vizOrigin(): string {
  const configured: unknown = import.meta.env.VITE_VIZ_ORIGIN;
  const dev = typeof configured === 'string' ? configured.trim() : '';
  return (dev || httpUrl('')).replace(/\/+$/, '');
}

/** The backend URL of a visualization (unencoded, as the list sends it). */
export function visualUrl(path: string, url?: string | null): string {
  return url && url.length ? url : `/${path}`;
}

/** Absolute, segment-encoded URL for the iframe / "Open in browser" / "Copy link". */
export function vizHref(path: string, url?: string | null): string {
  return vizOrigin() + api.visuals.frameUrl(visualUrl(path, url));
}

/** Parent folder name, or "public" for files at the root of context/public/ (VZ-5). */
export function vizFolder(path: string): string {
  const parts = path.split('/').filter(Boolean);
  if (parts.length < 2) return 'public';
  const last = parts[parts.length - 1] ?? '';
  // `charts/index.html` belongs to "charts"; `a/b/c.html` to "b".
  return parts[parts.length - 2] ?? (last || 'public');
}

export function findVisual(items: readonly VisualizationInfo[], path: string): VisualizationInfo | undefined {
  return items.find((v) => v.path === path);
}

/** Show on TV. Resolves `true` when the server says it worked. */
export async function showOnTv(path: string, title?: string): Promise<boolean> {
  try {
    const r = await castVisualization(path);
    if (r && r.ok) {
      // The server's success text carries the full URL ("Showing on TV: https://…"); the title reads better.
      showSnackbar(`Showing “${title ?? path}” on TV`);
      return true;
    }
    showSnackbar(r && r.message ? r.message : 'Could not show it on the TV', { tone: 'error' });
    return false;
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return false;
  }
}
