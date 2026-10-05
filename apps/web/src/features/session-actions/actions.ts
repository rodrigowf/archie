/**
 * Session-level user actions (W-11, spec 12 §6, spec 13 §3.6 `session-actions`). Every function
 * here is a direct user action, so it may take focus. The protocol work is `@/services` (W-06);
 * this layer adds the confirmations, the busy overlay (inv02 F-12), the ID-3 "available after the
 * first reply" answers instead of silent no-ops (inv02 §6.2), and the §6.11 Archie flows.
 *
 * Closing (P-1): an explicit Close or Delete closes the session for every device. Nothing here
 * runs from unload, teardown or lifecycle paths.
 */
import { busy as isBusyStatus } from '@/protocol';
import {
  api,
  ArchieRuntime,
  closeSession,
  deleteSession,
  errorMessage,
  fetchPool,
  forkSession,
  getArchieRuntime,
  getOrchestratorRef,
  getSessionRuntime,
  openArchie,
  openSession,
  refreshSessionList,
  replaceRunningArchie,
  rewindSession,
  syncPool,
  type AnyRuntime,
} from '@/services';
import {
  activateTab,
  catalogStore,
  findTab,
  liveStatusStore,
  removeSession,
  removeTab,
  showSnackbar,
  tabsStore,
  tabTitle,
  type Tab,
} from '@/stores';
import { patchSessionActions, sessionActionsStore, withBusy, type ArchieConflict, type RunningArchie } from './actionsStore';

/** ID-3 copy (spec 12 §2.1): shown instead of silently ignoring the action. */
export const NEEDS_FIRST_REPLY = 'Available after the first reply';
export const STOP_FIRST = 'Stop the current reply first';

const GENERIC_ARCHIE = /^\s*(orchestrator|archie)?\s*$/i;

/** The title as the user sees it (IA §1: an untitled Archie conversation is "New conversation"). */
export function sessionTitle(localId: string): string {
  const tab = findTab(localId);
  if (!tab) return '';
  const t = tabTitle(tab).trim();
  if (tab.kind === 'archie') return GENERIC_ARCHIE.test(t) ? 'New conversation' : t;
  return t || 'Untitled';
}

/**
 * The sdk id an action goes to: the conversation's own, else the tab's, else the session-list
 * row matched by `local_id` (the same match the derived title uses, inv02 §7 #9).
 */
export function sdkIdOf(localId: string): string | null {
  const rt = getSessionRuntime(localId);
  if (rt?.conv.ref.sdkId) return rt.conv.ref.sdkId;
  const tab = findTab(localId);
  if (tab?.sdkId) return tab.sdkId;
  const row = catalogStore.getState().sessions.items.find((s) => s.local_id === localId);
  return row ? row.session_id : null;
}

function runtimeBusy(rt: AnyRuntime | undefined): boolean {
  return !!rt && (rt.conv.inTurn || isBusyStatus(rt.conv.status));
}

// ───────────────────────── compact (§6.4) ─────────────────────────

/** §6.4: disabled while busy (the button says so); Android parity (03 §5). */
export function compactSession(localId: string): boolean {
  const rt = getSessionRuntime(localId);
  if (!rt || rt.readOnly) return false;
  if (runtimeBusy(rt)) {
    showSnackbar(STOP_FIRST);
    return false;
  }
  rt.compact();
  return true;
}

// ───────────────────────── delete (§6.8) ─────────────────────────

export function requestDelete(localId: string): void {
  if (!sdkIdOf(localId)) {
    showSnackbar(`Delete is ${NEEDS_FIRST_REPLY.toLowerCase()}`);
    return;
  }
  patchSessionActions({ confirm: { kind: 'delete', localId } });
}

/**
 * §6.8: every open view of the session and its pool entry close **before** `DELETE`, so the
 * tab goes away (keyed by `local_id`, fixes inv02 §6.3 #8) and the CLI stops writing the file.
 */
export async function deleteNow(localId: string): Promise<boolean> {
  const sdk = sdkIdOf(localId);
  const title = sessionTitle(localId);
  if (!sdk) return false;
  try {
    await withBusy('Deleting…', () => deleteSession(sdk));
    // a view that never learned its sdk id is matched by local_id only
    if (findTab(localId)) await closeSession(localId);
    showSnackbar(`Deleted “${title}”`);
    return true;
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return false;
  }
}

// ───────────────────────── fork / rewind (§6.5) ─────────────────────────

export function requestFork(localId: string): void {
  if (!sdkIdOf(localId)) {
    showSnackbar(`Fork is ${NEEDS_FIRST_REPLY.toLowerCase()}`);
    return;
  }
  patchSessionActions({ confirm: { kind: 'fork', localId } });
}

/**
 * The ⋮ menu's Fork: a copy of the whole conversation (`drop_last_n: 0`, spec 12 §6.5 "n == 0 is
 * allowed: a plain copy"), opened focused (user-initiated). An Archie copy opens read-only (H-3):
 * resuming it would stop the running Archie, which its Resume bar asks about.
 */
export async function forkWhole(localId: string): Promise<AnyRuntime | null> {
  const rt = getSessionRuntime(localId);
  const sdk = sdkIdOf(localId);
  if (!sdk) {
    showSnackbar(`Fork is ${NEEDS_FIRST_REPLY.toLowerCase()}`);
    return null;
  }
  const archie = rt ? rt.kind === 'orchestrator' : findTab(localId)?.kind === 'archie';
  try {
    const r = await withBusy('Forking…', () => api.sessions.fork(sdk, 0));
    void refreshSessionList();
    if (archie) return openSession({ kind: 'archie', sdkId: r.session_id, focus: true, readOnly: true });
    return openSession({ kind: 'agent', sdkId: r.session_id, provider: rt?.conv.ref.provider ?? null, focus: true });
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return null;
  }
}

/** Message-level rewind (after the UI confirmed): close → truncate → reopen (**[LOAD-BEARING]** inv02 F-09). */
export async function rewindTo(localId: string, targetId: string): Promise<AnyRuntime | null> {
  try {
    return await withBusy('Rewinding…', () => rewindSession(localId, targetId));
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return null;
  }
}

/** Message-level fork (after the UI confirmed). */
export async function forkFrom(localId: string, targetId: string): Promise<AnyRuntime | null> {
  try {
    return await withBusy('Forking…', () => forkSession(localId, targetId));
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return null;
  }
}

// ───────────────────────── Archie: new / resume / conflict (§6.11) ─────────────────────────

/** The orchestrator that is running now: the open live view, else the pool's row. */
async function runningArchie(): Promise<RunningArchie | null> {
  const open = getArchieRuntime();
  if (open) return { localId: open.localId, sdkId: open.conv.ref.sdkId };
  try {
    await syncPool();
  } catch {
    // offline: the last known ref
  }
  const live = getArchieRuntime(); // pool sync may have opened it as a background tab
  if (live) return { localId: live.localId, sdkId: live.conv.ref.sdkId };
  return getOrchestratorRef();
}

const START_WATCH_MS = 15_000;

/**
 * A start can still lose the race (another device started Archie after our pool check):
 * `error{orchestrator_active}` → drop our failed view (it never existed server-side, so no
 * close) and show the conflict dialog for the one that won.
 */
function watchStart(rt: ArchieRuntime, conflict: Omit<ArchieConflict, 'running'>): void {
  const id = rt.localId;
  let done = false;
  const finish = (): void => {
    done = true;
    unsub();
    clearTimeout(timer);
  };
  const check = (): void => {
    if (done) return;
    const s = liveStatusStore.getState().byId[id];
    if (!s) return;
    if (s.conn === 'subscribed') {
      finish();
      return;
    }
    if (s.error === 'orchestrator_active') {
      finish();
      removeSession(id, true);
      removeTab(id);
      void fetchPool()
        .then((rows) => rows.find((r) => r.is_orchestrator))
        .catch(() => undefined)
        .then((row) => {
          const running = row ? { localId: row.local_id, sdkId: row.sdk_session_id } : getOrchestratorRef();
          if (running) patchSessionActions({ archieConflict: { ...conflict, running } });
          else showSnackbar('Archie is already running on another device. Try again.');
        });
    }
  };
  const unsub = liveStatusStore.subscribe(check);
  const timer = setTimeout(finish, START_WATCH_MS);
  check();
}

export type RequestArchie = { mode: 'new' } | { mode: 'resume'; sdkId: string; readOnlyId?: string };

/**
 * One entry for every "Archie" request (spec 12 §6.11, inv02 F-25, fixes inv02 §6.3 #11):
 * - `new`: with nothing running, a fresh `start{local_id}`; with Archie running anywhere, the
 *   three-action dialog (Open the running one / Stop it and start new / Cancel).
 * - `resume`: the running one already is that conversation → focus it; another one is running →
 *   the same dialog; nothing running → `start{local_id: uuid(), resume_sdk_id}`.
 * Resolves to the open runtime, or null when a dialog is showing (or it failed).
 */
export async function requestOpenArchie(req: RequestArchie): Promise<ArchieRuntime | null> {
  try {
    const running = await runningArchie();
    if (req.mode === 'new') {
      if (running) {
        patchSessionActions({ archieConflict: { mode: 'new', resumeSdkId: null, running } });
        return null;
      }
      const r = await openArchie({ focus: true });
      if (r.conflict) {
        patchSessionActions({ archieConflict: { mode: 'new', resumeSdkId: null, running: r.running } });
        return null;
      }
      watchStart(r.runtime, { mode: 'new', resumeSdkId: null });
      return r.runtime;
    }
    if (running && running.sdkId === req.sdkId) {
      if (req.readOnlyId) await closeSession(req.readOnlyId); // read-only: just removes the view
      return openSession({ kind: 'archie', localId: running.localId, sdkId: running.sdkId, focus: true }) as ArchieRuntime;
    }
    if (running) {
      patchSessionActions({
        archieConflict: { mode: 'resume', resumeSdkId: req.sdkId, running, ...(req.readOnlyId ? { readOnlyId: req.readOnlyId } : {}) },
      });
      return null;
    }
    // A read-only view of the same conversation would be found by sdk id and returned as is
    // (the "doesn't reopen" symptom): it goes first.
    await closeReadOnlyViewsOf(req.sdkId, req.readOnlyId);
    const r = await openArchie({ focus: true, resumeSdkId: req.sdkId });
    if (r.conflict) {
      patchSessionActions({ archieConflict: { mode: 'resume', resumeSdkId: req.sdkId, running: r.running } });
      return null;
    }
    watchStart(r.runtime, { mode: 'resume', resumeSdkId: req.sdkId });
    return r.runtime;
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return null;
  }
}

async function closeReadOnlyViewsOf(sdkId: string, alsoId?: string): Promise<void> {
  const ids = new Set<string>();
  if (alsoId) ids.add(alsoId);
  for (const t of tabsOf('archie')) if (t.readOnly && t.sdkId === sdkId) ids.add(t.id);
  for (const id of Array.from(ids)) {
    const rt = getSessionRuntime(id);
    if (!rt || rt.readOnly) await closeSession(id); // never a live view: that would close it for everybody
  }
}

function tabsOf(kind: Tab['kind']): Tab[] {
  return tabsStore.getState().tabs.filter((t) => t.kind === kind);
}

export type ConflictChoice = 'open' | 'replace' | 'cancel';

/** The dialog's answer. `replace` is an explicit close of the running Archie (P-1: for everybody). */
export async function resolveArchieConflict(choice: ConflictChoice): Promise<ArchieRuntime | null> {
  const c = sessionActionsStore.getState().archieConflict;
  patchSessionActions({ archieConflict: null });
  if (!c || choice === 'cancel') return null;
  try {
    if (choice === 'open') {
      const rt = openSession({ kind: 'archie', localId: c.running.localId, sdkId: c.running.sdkId, focus: true }) as ArchieRuntime;
      activateTab(rt.localId);
      return rt;
    }
    return await withBusy(c.mode === 'new' ? 'Starting Archie…' : 'Resuming…', async () => {
      // The running one may live only on another device: `closeSession` closes open views, so
      // its pool entry is closed here explicitly, before the new start (else orchestrator_active).
      if (!getSessionRuntime(c.running.localId)) {
        try {
          await api.sessions.close(c.running.localId);
        } catch {
          // errors ignored, as for any close (§6.7)
        }
      }
      if (c.resumeSdkId) await closeReadOnlyViewsOf(c.resumeSdkId, c.readOnlyId);
      const rt = await replaceRunningArchie(c.resumeSdkId);
      watchStart(rt, { mode: c.mode, resumeSdkId: c.resumeSdkId });
      return rt;
    });
  } catch (err) {
    showSnackbar(errorMessage(err), { tone: 'error' });
    return null;
  }
}

/**
 * The Resume bar of a read-only view (H-3): an Archie conversation goes through §6.11 (dialog
 * when another one runs); an agent conversation reopens live in the same place.
 */
export async function resumeReadOnly(localId: string): Promise<AnyRuntime | null> {
  const tab = findTab(localId);
  const sdk = sdkIdOf(localId);
  if (!tab || !sdk) return null;
  if (tab.kind === 'archie') return requestOpenArchie({ mode: 'resume', sdkId: sdk, readOnlyId: localId });
  await closeSession(localId); // read-only: no server call
  return openSession({ kind: 'agent', sdkId: sdk, provider: tab.provider ?? null, focus: true });
}
