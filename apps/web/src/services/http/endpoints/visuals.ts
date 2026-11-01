/** Visualizations (inventory 01 §3.3; spec 12 §9.1) and BX-2 "Show on TV". */
import { encodePath, http } from '../client';
import type { CastProbe, CastResult, VisualizationInfo } from '../types';

/** `favorite` is absent on older backends (spec 12 §9.1): false. */
export function decodeVisualizations(list: unknown): VisualizationInfo[] {
  if (!Array.isArray(list)) return [];
  return (list as VisualizationInfo[]).map((v) => ({ ...v, favorite: v.favorite === true }));
}

export const visuals = {
  list: () => http.get<unknown>('/api/visualizations').then(decodeVisualizations),
  rename: (path: string, title: string) => http.patch<undefined>('/api/visualizations/rename', { json: { path, title } }),
  /** VZ-8 star / unstar: 204, idempotent. */
  setFavorite: (path: string, favorite: boolean) => http.put<undefined>('/api/visualizations/favorite', { json: { path, favorite } }),
  /** The iframe URL: `url` is not percent-encoded by the backend (VZ-1, G-38). */
  frameUrl: (url: string) => encodePath(url.charAt(0) === '/' ? url : `/${url}`),
  /** BX-2 capability probe. A `text/html` 200 (SPA fallback on an older backend) → ApiError 404. */
  castProbe: () => http.get<CastProbe>('/api/visualizations/cast', { probe: true, timeoutMs: 8000 }),
  cast: (path: string) => http.post<CastResult>('/api/visualizations/cast', { json: { path }, timeoutMs: 20_000 }),
};
