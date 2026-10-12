/**
 * Sessions REST (inventory 01 §3.2). History calls take the **sdk id** (G-12, ID-3); only
 * `close` takes the `local_id`.
 */
import type { MessagesPage, SubagentsListResponse } from '@/protocol';
import { http } from '../client';
import type { PoolSession, SessionConfig, SessionInfo } from '../types';

const id = (s: string): string => encodeURIComponent(s);

export const sessions = {
  list: () => http.get<SessionInfo[]>('/api/sessions'),
  poolLive: () => http.get<PoolSession[]>('/api/sessions/pool/live'),
  /** `limit` 1–200 (default 50); `before` = the oldest `start_index` held (§5.3). */
  messages: (sdkId: string, opts: { limit?: number; before?: number } = {}) =>
    http.get<MessagesPage>(`/api/sessions/${id(sdkId)}/messages`, { query: { limit: opts.limit ?? 50, before: opts.before } }),
  /** spec 12 SA-8: every subagent of the session (live registry + the harness's files). */
  subagents: (sdkId: string) => http.get<SubagentsListResponse>(`/api/sessions/${id(sdkId)}/subagents`),
  /** spec 12 SA-5/SA-8: one subagent's transcript, paged like `messages`. 404 = unknown. */
  subagentMessages: (sdkId: string, toolUseId: string, opts: { limit?: number; before?: number } = {}) =>
    http.get<MessagesPage>(`/api/sessions/${id(sdkId)}/subagents/${id(toolUseId)}/messages`, {
      query: { limit: opts.limit ?? 50, before: opts.before },
    }),
  getConfig: (sdkId: string) => http.get<SessionConfig>(`/api/sessions/${id(sdkId)}/config`),
  /** Only changed keys; `null` = inherit global (§6.14). */
  putConfig: (sdkId: string, patch: Partial<SessionConfig>) => http.put<SessionConfig>(`/api/sessions/${id(sdkId)}/config`, { json: patch }),
  rename: (sdkId: string, title: string) => http.patch<undefined>(`/api/sessions/${id(sdkId)}/rename`, { json: { title } }),
  remove: (sdkId: string) => http.del<undefined>(`/api/sessions/${id(sdkId)}`),
  duplicate: (sdkId: string) => http.post<{ session_id: string }>(`/api/sessions/${id(sdkId)}/duplicate`),
  truncate: (sdkId: string, dropLastN: number) =>
    http.post<{ session_id: string }>(`/api/sessions/${id(sdkId)}/truncate`, { json: { drop_last_n: dropLastN } }),
  fork: (sdkId: string, dropLastN: number) =>
    http.post<{ session_id: string }>(`/api/sessions/${id(sdkId)}/fork`, { json: { drop_last_n: dropLastN } }),
  /**
   * §6.9 answer an agent's pending permission without its socket. 404 = not in the pool (or, with
   * detail "Not Found", a server without this route); 409 = already answered or unknown request.
   */
  permission: (localId: string, body: { request_id: string; decision: 'allow' | 'deny'; message?: string }) =>
    http.post<{ ok: boolean }>(`/api/sessions/${id(localId)}/permission`, { json: body }),
  /** Explicit close only (P-1): never called from unload, teardown or lifecycle paths. */
  close: (localId: string) => http.post<undefined>(`/api/sessions/${id(localId)}/close`, { as: 'none' }),
};
