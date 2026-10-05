/**
 * `useSessionActions(localId)` (spec 13 §3.9): what the ⋮ menu (and any other surface) may do
 * with a session right now, each with the reason when it may not (ID-3: never a silent no-op).
 */
import { contextUsage, isBusy } from '@/protocol';
import { useCatalog, useSession, useTabs } from '@/stores';
import { compactSession, forkFrom, NEEDS_FIRST_REPLY, requestDelete, requestFork, resumeReadOnly, rewindTo, STOP_FIRST } from './actions';

export interface ActionState {
  readonly enabled: boolean;
  /** Why it is disabled (shown as the menu item's supporting text). */
  readonly reason: string | null;
}

export interface SessionActions {
  readonly kind: 'archie' | 'agent' | null;
  readonly readOnly: boolean;
  readonly sdkId: string | null;
  readonly busy: boolean;
  /** Context used, 0–100, or null when unknown (spec 12 §6.4). */
  readonly contextPercent: number | null;
  readonly rename: ActionState;
  readonly settings: ActionState;
  readonly compact: ActionState;
  readonly fork: ActionState;
  readonly del: ActionState;
  compactNow(): boolean;
  requestFork(): void;
  requestDelete(): void;
  resume(): void;
  rewindTo(targetId: string): ReturnType<typeof rewindTo>;
  forkFrom(targetId: string): ReturnType<typeof forkFrom>;
}

const OK: ActionState = { enabled: true, reason: null };
const no = (reason: string): ActionState => ({ enabled: false, reason });

export function useSessionActions(localId: string): SessionActions {
  const tab = useTabs((s) => s.tabs.find((t) => t.id === localId));
  const convSdk = useSession(localId, (s) => s.conv.ref.sdkId);
  const busy = useSession(localId, (s) => isBusy(s.conv) || s.conv.inTurn);
  const readOnly = useSession(localId, (s) => s.readOnly) || tab?.readOnly === true;
  const subscribed = useSession(localId, (s) => s.conv.conn === 'subscribed');
  const percent = useSession(localId, (s) => contextUsage(s.conv).percent);
  const rowSdk = useCatalog((c) => c.sessions.items.find((s) => s.local_id === localId)?.session_id ?? null);
  const sdkId = convSdk ?? tab?.sdkId ?? rowSdk;
  const kind = tab?.kind === 'archie' || tab?.kind === 'agent' ? tab.kind : null;

  const needsSdk = sdkId ? OK : no(NEEDS_FIRST_REPLY);
  const rename = readOnly ? no('Read-only view') : needsSdk;
  // Session settings are per agent session (inv02 F-18); Archie's live in Settings → Archie.
  const settings = kind !== 'agent' ? no('Not for Archie') : readOnly ? no('Read-only view') : needsSdk;
  const compact = readOnly ? no('Read-only view') : busy ? no(STOP_FIRST) : !subscribed ? no('Not connected') : OK;
  const fork = needsSdk;
  const del = needsSdk;

  return {
    kind,
    readOnly,
    sdkId,
    busy,
    contextPercent: percent,
    rename,
    settings,
    compact,
    fork,
    del,
    compactNow: () => compactSession(localId),
    requestFork: () => requestFork(localId),
    requestDelete: () => requestDelete(localId),
    resume: () => void resumeReadOnly(localId),
    rewindTo: (targetId) => rewindTo(localId, targetId),
    forkFrom: (targetId) => forkFrom(localId, targetId),
  };
}
