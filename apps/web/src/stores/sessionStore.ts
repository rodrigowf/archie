/**
 * Per-session store factory (spec 13 §3.3). One store per open conversation (`local_id`).
 *
 * - `conv` is the protocol state. The runtime keeps the live value; the store publishes it to
 *   React coalesced (one notify per frame) and **frozen while hidden** (§4.4): a hidden panel's
 *   subscribers keep the last published snapshot, and one catch-up publish happens on show.
 * - The resume checkpoint lives inside `conv` and therefore **in memory only** (spec 12 T-10):
 *   nothing here writes it to storage, so a page reload never sends a stale `resume_from`.
 * - The draft persists in sessionStorage (`draft:<local_id>`), written debounced, never per
 *   streamed event (spec 12 §8.2, A-8.14).
 */
import { createStore, type StoreApi } from 'zustand/vanilla';
import { sessionStore as sessionStorageSafe } from '@/platform';
import type { Conversation, ModelInfo, OrchestratorModelInfo } from '@/protocol';
import { getFrameScheduler, type FrameScheduler } from './scheduler';

export interface SessionState {
  readonly localId: string;
  readonly conv: Conversation;
  readonly draft: string;
  /** Opaque scroll anchor owned by the conversation view (W-09). */
  readonly scrollAnchor: unknown;
  readonly loadingOlder: boolean;
  /** A history fetch failed: the verbatim server detail (or a transport message). */
  readonly historyError: string | null;
  /** H-3: REST only, no WebSocket. */
  readonly readOnly: boolean;
  /** Orchestrator: the model info of the running session (session_started / model_info / model_changed). */
  readonly modelInfo: OrchestratorModelInfo | null;
  /** Orchestrator: `models_list` answer. */
  readonly models: readonly ModelInfo[] | null;
  /** The panel is hidden (frozen snapshot). */
  readonly hidden: boolean;
}

export type SessionStore = StoreApi<SessionState>;

export const DRAFT_KEY_PREFIX = 'draft:';
const DRAFT_WRITE_MS = 250;

export function draftKey(localId: string): string {
  return DRAFT_KEY_PREFIX + localId;
}

export interface SessionStoreHandle {
  readonly store: SessionStore;
  /** Queue a new protocol state; published at the next frame unless hidden. */
  setConv(conv: Conversation): void;
  /** Immediate update of the non-protocol fields. */
  patch(partial: Partial<Omit<SessionState, 'conv' | 'localId' | 'hidden'>>): void;
  setDraft(text: string): void;
  setHidden(hidden: boolean): void;
  /** Publish a pending `conv` now (tests, catch-up). */
  flush(): void;
  /** ID-1: the server adopted another `local_id`; the draft key moves with it. */
  rekey(localId: string): void;
  /** Stop timers; `forget` also deletes the persisted draft (explicit close). */
  dispose(forget?: boolean): void;
}

export interface CreateSessionStoreOptions {
  localId: string;
  conv: Conversation;
  readOnly?: boolean;
  hidden?: boolean;
  scheduler?: FrameScheduler;
}

export function createSessionStore(o: CreateSessionStoreOptions): SessionStoreHandle {
  const scheduler = o.scheduler ?? getFrameScheduler();
  const store = createStore<SessionState>(() => ({
    localId: o.localId,
    conv: o.conv,
    draft: sessionStorageSafe.get(draftKey(o.localId)) ?? '',
    scrollAnchor: null,
    loadingOlder: false,
    historyError: null,
    readOnly: o.readOnly === true,
    modelInfo: null,
    models: null,
    hidden: o.hidden === true,
  }));
  let pending: Conversation | null = null;
  let cancel: (() => void) | null = null;
  let draftTimer: ReturnType<typeof setTimeout> | null = null;
  let disposed = false;

  const flush = (): void => {
    cancel = null;
    if (pending === null) return;
    const conv = pending;
    pending = null;
    if (store.getState().conv !== conv) store.setState({ conv });
  };

  const writeDraft = (): void => {
    draftTimer = null;
    const s = store.getState();
    if (s.draft) sessionStorageSafe.set(draftKey(s.localId), s.draft);
    else sessionStorageSafe.remove(draftKey(s.localId));
  };

  return {
    store,
    setConv(conv) {
      if (disposed) return;
      pending = conv;
      if (store.getState().hidden || cancel) return; // frozen while hidden; one notify per frame
      cancel = scheduler.schedule(flush);
    },
    patch(partial) {
      if (!disposed) store.setState(partial);
    },
    setDraft(text) {
      if (disposed || store.getState().draft === text) return;
      store.setState({ draft: text });
      if (draftTimer) clearTimeout(draftTimer);
      draftTimer = setTimeout(writeDraft, DRAFT_WRITE_MS);
    },
    setHidden(hidden) {
      if (disposed || store.getState().hidden === hidden) return;
      if (hidden) {
        cancel?.();
        cancel = null;
        store.setState({ hidden: true });
        return;
      }
      // catch-up: one render with the latest state (spec 13 §4.4)
      const conv = pending ?? store.getState().conv;
      pending = null;
      cancel?.();
      cancel = null;
      store.setState({ hidden: false, conv });
    },
    flush() {
      cancel?.();
      flush();
    },
    rekey(localId) {
      const old = store.getState().localId;
      if (old === localId) return;
      const draft = store.getState().draft;
      sessionStorageSafe.remove(draftKey(old));
      if (draft) sessionStorageSafe.set(draftKey(localId), draft);
      store.setState({ localId });
    },
    dispose(forget = false) {
      if (disposed) return;
      cancel?.();
      cancel = null;
      if (draftTimer) {
        clearTimeout(draftTimer);
        draftTimer = null;
        if (!forget) writeDraft();
      }
      if (forget) sessionStorageSafe.remove(draftKey(store.getState().localId));
      disposed = true;
    },
  };
}
