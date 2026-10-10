/**
 * Shared machinery of the agent and orchestrator runtimes (spec 12 §1 layer 2, §3, §5; spec 13
 * §3.4). A runtime owns one conversation: it feeds every input through the pure
 * `stepConversation` (one ordered queue, L-2), keeps the live state for logic, publishes it to
 * the session store (coalesced, frozen while hidden), and executes the machine's effects.
 *
 * The resume checkpoint is part of the conversation state, so it is **in memory only**
 * (spec 12 T-10): nothing here persists it, and a page reload rebuilds from REST without
 * `resume_from` (fixes the duplicate-on-reload bug W-7).
 */
import {
  pageAbuts,
  stepConversation,
  type AgentSessionClosedFrame,
  type AgentSessionOpenedFrame,
  type ClientMessage,
  type Conversation,
  type ConversationInput,
  type Effect,
  type MessagesPage,
  type ServerFrame,
  type SessionKind,
} from '@/protocol';
import { markTabUnseen, publishLiveStatus, showSnackbar, type RuntimeHandle, type SessionStoreHandle } from '@/stores';
import { getEnv } from '../env';
import { api } from '../http/endpoints';
import { errorMessage, isApiError } from '../http/errors';

export const EMPTY_PAGE: MessagesPage = { messages: [], total_count: 0, has_more: false, start_index: 0 };
export const HISTORY_PAGE_SIZE = 50;
export const RECONCILE_DEBOUNCE_MS = 500;
const OUTBOX_CAP = 20;

/** What a runtime asks of the session directory (implemented by `manager.ts`). */
export interface RuntimeHooks {
  /** ID-1: the server adopted another `local_id`. */
  onRekey(runtime: ConversationRuntime, oldId: string, newId: string): void;
  /** ID-2/ID-4: the conversation learned its sdk id. */
  onSdkId(runtime: ConversationRuntime, sdkId: string): void;
  /** A turn ended (MC-2 refreshList, visualizations, approvals cleanup, unseen badge). */
  onTurnEnded(runtime: ConversationRuntime): void;
  /** Pool watcher event delivered through a conversation (§3.7). */
  onWatcher(frame: AgentSessionOpenedFrame | AgentSessionClosedFrame): void;
  /** `nested_session_event`: feed `event` into the open agent view `localId` (§4.3). */
  onNested(localId: string, event: ServerFrame): void;
  /** ID-2 / SEQ-6: look the sdk id up in `GET /api/sessions/pool/live`. */
  lookupSdkId(localId: string): Promise<string | null>;
  /** The conversation (re)subscribed after a gap: apply `pool/live` status (ST-2). */
  onResubscribed(runtime: ConversationRuntime): void;
}

export abstract class ConversationRuntime implements RuntimeHandle {
  conv: Conversation;
  protected disposed = false;
  private reloadInFlight = false;
  private reloadAgain = false;
  private reconcileTimer: ReturnType<typeof setTimeout> | null = null;
  private reconcileInFlight = false;
  private reconcileAgain = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private outbox: ClientMessage[] = [];
  /** Set by socket close / visibility resume: apply the pool status at the next subscribe (ST-2). */
  needsPoolStatus = false;

  protected constructor(
    initial: Conversation,
    readonly handle: SessionStoreHandle,
    protected readonly hooks: RuntimeHooks,
  ) {
    this.conv = initial;
    publishLiveStatus(initial.ref.localId, initial);
  }

  get localId(): string {
    return this.conv.ref.localId;
  }

  get kind(): SessionKind {
    return this.conv.ref.kind;
  }

  get isDisposed(): boolean {
    return this.disposed;
  }

  /** Send on this conversation's socket. False when not open. */
  protected abstract transportSend(msg: ClientMessage): boolean;
  /** Called when the conversation subscribed (`session_started` applied). */
  protected abstract onSubscribed(): void;

  // ───────────────────────── the single input queue (L-2) ─────────────────────────

  step(input: ConversationInput): void {
    if (this.disposed) return;
    const before = this.conv;
    const r = stepConversation(before, input);
    const after = r.state;
    this.conv = after;
    if (before.ref.localId !== after.ref.localId) this.hooks.onRekey(this, before.ref.localId, after.ref.localId);
    if (after !== before) {
      this.handle.setConv(after);
      publishLiveStatus(after.ref.localId, after); // never frozen: the tab strip reads it while hidden
      // unseen = new activity while the tab is in the background (not a history load)
      if (input.type === 'frame' && after.entries.length > before.entries.length) markTabUnseen(after.ref.localId);
    }
    if (after.ref.sdkId && before.ref.sdkId !== after.ref.sdkId) this.hooks.onSdkId(this, after.ref.sdkId);
    // a (re)subscribe: first subscribe, or the answer to a re-sent start on a subscribed socket
    const answered = before.awaitingSessionStarted && !after.awaitingSessionStarted;
    if (after.conn === 'subscribed' && (before.conn !== 'subscribed' || answered)) this.subscribed();
    for (const e of r.effects) this.runEffect(e);
  }

  private subscribed(): void {
    this.onSubscribed();
    const box = this.outbox;
    this.outbox = [];
    for (const m of box) this.transportSend(m);
    if (this.needsPoolStatus) {
      this.needsPoolStatus = false;
      this.hooks.onResubscribed(this);
    }
  }

  /** Send a user-level message now if subscribed, else after the next `session_started`. */
  protected sendWhenSubscribed(msg: ClientMessage): void {
    if (this.conv.conn === 'subscribed' && this.transportSend(msg)) return;
    this.outbox.push(msg);
    if (this.outbox.length > OUTBOX_CAP) this.outbox.shift();
  }

  /** Pending user messages (tests, UI "sending…" hints). */
  get outboxSize(): number {
    return this.outbox.length;
  }

  protected runEffect(e: Effect): void {
    switch (e.type) {
      case 'send':
        if (!this.transportSend(e.message)) getEnv().log('debug', 'send skipped (socket not open)', e.message.type);
        return;
      case 'reload':
        void this.runReload();
        return;
      case 'reconcile':
        this.scheduleReconcile();
        return;
      case 'learn_sdk_id':
        void this.learnSdkId();
        return;
      case 'turn_ended':
        this.hooks.onTurnEnded(this);
        return;
      case 'nested_event':
        this.hooks.onNested(e.localId, e.event);
        return;
      case 'watcher':
        this.hooks.onWatcher(e.frame);
        return;
      case 'protocol_error':
        getEnv().log('warn', 'protocol error', e.code, e.detail);
        if (e.code !== 'not_started') showSnackbar(e.detail || `The server rejected a request (${e.code}).`, { durationMs: 3000 });
        return;
      case 'retry_start':
        if (this.retryTimer) clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null;
          this.resendStart();
        }, e.delayMs);
        return;
    }
  }

  /** Re-send `start` (or the voice owner's `voice_start`) on an open socket (T-9, T-11). */
  abstract resendStart(): void;

  /**
   * The user's Retry on an open socket: after a start error the conversation is `failed` and a
   * plain re-send is refused (no automatic retry loop, T-12), so the handshake restarts as on
   * a fresh open.
   */
  protected retryHandshake(start?: ClientMessage & { type: 'voice_start' }): void {
    if (this.conv.conn === 'failed') this.step(start ? { type: 'socket_open', start } : { type: 'socket_open' });
    else this.step(start ? { type: 'resend_start', start } : { type: 'resend_start' });
  }

  // ───────────────────────── history (§5) ─────────────────────────

  /** `GET …/messages?limit=50`; 404 = a new session whose JSONL does not exist yet (§5.2). */
  protected async fetchLatestPage(sdkId: string): Promise<MessagesPage> {
    try {
      return await api.sessions.messages(sdkId, { limit: HISTORY_PAGE_SIZE });
    } catch (err) {
      if (isApiError(err, 404)) return EMPTY_PAGE;
      throw err;
    }
  }

  /**
   * Complete a canonical reload (§5.6): the machine is `reloading` and holds frames until the
   * `history_page{replace}`. Single-flight; a reload requested meanwhile runs once more after.
   */
  protected async runReload(): Promise<void> {
    if (this.reloadInFlight) {
      this.reloadAgain = true;
      return;
    }
    this.reloadInFlight = true;
    try {
      let sdk = this.conv.ref.sdkId;
      if (!sdk) {
        const found = await this.hooks.lookupSdkId(this.localId); // SEQ-6
        if (this.disposed) return;
        if (found) {
          this.step({ type: 'sdk_id', sdkId: found });
          sdk = found;
        }
      }
      if (!sdk) {
        this.step({ type: 'reload_failed' });
        return;
      }
      const page = await this.fetchLatestPage(sdk);
      if (this.disposed || !this.conv.reloading) return;
      this.handle.patch({ historyError: null });
      this.step({ type: 'history_page', mode: 'replace', response: page });
    } catch (err) {
      if (this.disposed) return;
      this.handle.patch({ historyError: errorMessage(err) });
      if (this.conv.reloading) this.step({ type: 'reload_failed' });
    } finally {
      this.reloadInFlight = false;
      if (this.reloadAgain && !this.disposed) {
        this.reloadAgain = false;
        if (this.conv.reloading) void this.runReload();
      }
    }
  }

  /** The user's Reload, termination recovery, a non-abutting page (§5.6). */
  reload(): Promise<void> {
    if (this.disposed) return Promise.resolve();
    if (!this.conv.reloading) this.step({ type: 'begin_reload' });
    return this.runReload();
  }

  /** §5.3: older page on scroll-up; a page that does not abut the loaded range → canonical reload. */
  async loadOlder(): Promise<void> {
    const conv = this.conv;
    const sdk = conv.ref.sdkId;
    if (!sdk || !conv.history.hasMore || conv.reloading || this.handle.store.getState().loadingOlder) return;
    const before = conv.history.startIndex;
    this.handle.patch({ loadingOlder: true });
    try {
      const page = await api.sessions.messages(sdk, { limit: HISTORY_PAGE_SIZE, before });
      if (this.disposed) return;
      this.handle.patch({ loadingOlder: false, historyError: null });
      if (this.conv.history.startIndex !== before || this.conv.reloading) return; // superseded
      if (!pageAbuts(page, before)) {
        void this.reload(); // the file was truncated meanwhile (A-4.4.6)
        return;
      }
      this.step({ type: 'history_page', mode: 'prepend', response: page });
    } catch (err) {
      if (!this.disposed) this.handle.patch({ loadingOlder: false, historyError: errorMessage(err) });
    }
  }

  /** R-7: refetch the latest page to fill tool results (debounce 500 ms, one at a time). */
  private scheduleReconcile(): void {
    if (this.reconcileTimer || this.disposed) return;
    this.reconcileTimer = setTimeout(() => {
      this.reconcileTimer = null;
      void this.doReconcile();
    }, RECONCILE_DEBOUNCE_MS);
  }

  private async doReconcile(): Promise<void> {
    if (this.reconcileInFlight) {
      this.reconcileAgain = true;
      return;
    }
    const sdk = this.conv.ref.sdkId;
    if (!sdk || this.disposed) return;
    this.reconcileInFlight = true;
    try {
      const page = await api.sessions.messages(sdk, { limit: HISTORY_PAGE_SIZE });
      if (!this.disposed) this.step({ type: 'history_page', mode: 'reconcile', response: page });
    } catch (err) {
      getEnv().log('warn', 'reconcile failed', errorMessage(err));
    } finally {
      this.reconcileInFlight = false;
      if (this.reconcileAgain) {
        this.reconcileAgain = false;
        this.scheduleReconcile();
      }
    }
  }

  private async learnSdkId(): Promise<void> {
    const id = await this.hooks.lookupSdkId(this.localId);
    if (id && !this.disposed && !this.conv.ref.sdkId) this.step({ type: 'sdk_id', sdkId: id });
  }

  // ───────────────────────── misc ─────────────────────────

  dismissBanner(): void {
    this.step({ type: 'dismiss_banner' });
  }

  setHidden(hidden: boolean): void {
    this.handle.setHidden(hidden);
  }

  setDraft(text: string): void {
    this.handle.setDraft(text);
  }

  /** Close the transport and stop timers. Sends nothing (P-1). */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    if (this.reconcileTimer) clearTimeout(this.reconcileTimer);
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.reconcileTimer = null;
    this.retryTimer = null;
    this.outbox = [];
    this.closeTransport();
  }

  protected abstract closeTransport(): void;
}
