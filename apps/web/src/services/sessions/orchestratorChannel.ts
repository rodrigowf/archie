/**
 * The app's single orchestrator WebSocket (spec 12 T-6, T-7; spec 13 §3.4 `poolWatcher`).
 *
 * - Exactly **one** orchestrator socket per app instance: text `start`, `voice_start`, voice
 *   relay frames and watcher events all share it (T-6; fixes W-10's stop-then-voice_start).
 * - It stays open while the app is visible even with no Archie tab: every orchestrator socket
 *   is a pool watcher from connect (`api/routes/orchestrator.py:127-128`), so this is how
 *   `agent_session_opened/closed` reach the app with no Archie tab (G-39). Unsubscribed it
 *   **never** sends `start`. Side-effect check (K13, code reading): an unsubscribed socket only
 *   joins `pool._watchers`; its `finally` block unwatches and has nothing else to tear down
 *   (`session is None`), so it is passive.
 * - When an Archie runtime is attached, every frame goes through its conversation (watcher
 *   frames come back as `watcher` effects, and WATCH-1 applies to the conversation itself).
 * - `orchestrator_switch` (§6.11a SW-1) never goes to the attached conversation: it arrives after
 *   WATCH-1 already stopped that view, so the channel hands it to `onSwitch` attached or not.
 * - `agent_turn_started/finished` (§3.7, device notifications) are app-level too: `onTurn`,
 *   attached or not.
 * - `visualization_changed` / `memory_changed` (§9.3) are app-level too: `onContent`, attached or
 *   not. `onReopen` fires on every open after the first, so the app can catch up on what the
 *   dropped socket missed (VZ-6).
 * - **POOL-2 (the server is the source of truth).** Before the attached conversation re-sends
 *   `start` on a reopen or a visible-again resync, the channel re-reads `pool/live` when that
 *   conversation had been live here (subscribed, or closed live). Missing from the pool of the
 *   **same server process** (`X-Archie-Server-Id`, SRV-1), or already closed by a live
 *   `agent_session_closed`, it was closed elsewhere: the conversation gets `onClosedWhileAway`
 *   (WATCH-1 effects) and no `start`, which would re-open it on the server for every device.
 *   Missing after a backend restart (a new server id, or none) it is resumed as before.
 */
import type { PoolSnapshot } from '../http/endpoints/sessions';
import type {
  AgentSessionClosedFrame,
  AgentSessionOpenedFrame,
  AgentTurnFinishedFrame,
  AgentTurnStartedFrame,
  OrchestratorSwitchFrame,
  ServerFrame,
} from '@/protocol';
import type { ContentFrame } from '../contentChanges';
import { Reconnector, type ReconnectPolicy } from '../ws/reconnect';
import { ArchieSocket, ORCHESTRATOR_WS_PATH } from '../ws/socket';

/** The attached conversation (ArchieRuntime). */
export interface ChannelClient {
  /** The conversation's pool key. */
  readonly localId: string;
  onSocketOpen(): void;
  onSocketFrame(frame: ServerFrame): void;
  onSocketClosed(): void;
  /** Visible again with the socket open: re-send `start` / `voice_start` (T-9). */
  onResync(): void;
  /** POOL-2: the socket is open, but this conversation was closed elsewhere: no `start` (WATCH-1 effects). */
  onClosedWhileAway(): void;
}

export type WatcherFrame = AgentSessionOpenedFrame | AgentSessionClosedFrame;
export type AgentTurnFrame = AgentTurnStartedFrame | AgentTurnFinishedFrame;

/** App-level hooks besides the watcher events. */
export interface ChannelHooks {
  /** §9.3 content changes. */
  onContent?: (frame: ContentFrame) => void;
  /** The socket opened again after a drop (not on the first open). */
  onReopen?: () => void;
  /** Agent turn started/finished (§3.7, device notifications). */
  onTurn?: (frame: AgentTurnFrame) => void;
  /** POOL-2: a fresh `GET /api/sessions/pool/live` with its server id. Without it the channel never reconciles. */
  probePool?: () => Promise<PoolSnapshot>;
}

/** The orchestrator conversation last subscribed on this channel, and where (POOL-2, SRV-1). */
interface LiveRecord {
  localId: string;
  gen: number;
  serverId: string | null;
}

export class OrchestratorChannel {
  private readonly socket: ArchieSocket;
  private readonly reconnector: Reconnector;
  private client: ChannelClient | null = null;
  private started = false;
  private everOpened = false;
  /** Socket generation: +1 on every open. */
  private gen = 0;
  /** SRV-1: the server process id read on the current socket (`null` = unknown). */
  private socketServerId: string | null = null;
  private live: LiveRecord | null = null;
  /** The conversation a live `agent_session_closed` ended (never auto-restarted, POOL-2). */
  private endedLocalId: string | null = null;

  constructor(
    private readonly onWatcher: (frame: WatcherFrame) => void,
    policy?: ReconnectPolicy,
    private readonly onSwitch: (frame: OrchestratorSwitchFrame) => void = () => undefined,
    private readonly hooks: ChannelHooks = {},
  ) {
    this.socket = new ArchieSocket(ORCHESTRATOR_WS_PATH, {
      onOpen: () => {
        this.gen += 1;
        this.socketServerId = null;
        const client = this.client;
        if (!client || !this.wasLiveHere(client.localId)) this.learnServerId(this.gen); // else whenInPool reads it
        if (client) this.whenInPool('open');
        else this.reconnector.markHealthy();
        if (this.everOpened) this.hooks.onReopen?.();
        this.everOpened = true;
      },
      onFrame: (f) => this.onFrame(f),
      onClose: () => {
        this.client?.onSocketClosed();
        this.reconnector.scheduleReconnect();
      },
    });
    this.reconnector = new Reconnector(
      {
        isOpen: () => this.socket.isOpen,
        isConnecting: () => this.socket.isConnecting,
        connect: () => this.socket.connect(),
        resync: () => {
          if (this.client) this.whenInPool('resync');
        },
      },
      policy,
    );
  }

  /** Open the watcher socket (T-7). Idempotent. */
  start(): void {
    this.started = true;
    if (!this.socket.isOpen && !this.socket.isConnecting) this.socket.connect();
  }

  get isOpen(): boolean {
    return this.socket.isOpen;
  }

  get attached(): ChannelClient | null {
    return this.client;
  }

  /** Attach the Archie conversation. If the socket is already open, it subscribes now. */
  attach(client: ChannelClient): void {
    this.client = client;
    if (this.endedLocalId !== client.localId) this.endedLocalId = null;
    if (this.socket.isOpen) client.onSocketOpen();
    else this.start();
  }

  /** Detach (the Archie tab closed). The socket stays as a passive watcher. */
  detach(client: ChannelClient): void {
    if (this.client === client) this.client = null;
  }

  send: ArchieSocket['send'] = (msg) => this.socket.send(msg);

  /** Banner "Retry". */
  retry(): void {
    if (this.socket.isOpen) this.client?.onResync();
    else this.reconnector.reconnectNow();
  }

  markHealthy(): void {
    this.reconnector.markHealthy();
  }

  /** The user resumes [localId] by typing into its ended view: its next `start` is not gated (POOL-2). */
  resumeExplicitly(localId: string): void {
    if (this.endedLocalId === localId) this.endedLocalId = null;
  }

  /** The attached conversation got `session_started` (POOL-2: remember where it is live). */
  markSubscribed(localId: string): void {
    this.reconnector.markHealthy();
    this.live = { localId, gen: this.gen, serverId: this.socketServerId };
    if (this.endedLocalId === localId) this.endedLocalId = null;
  }

  // ───────────────────────── POOL-2 ─────────────────────────

  /** SRV-1: read the server id of this socket in the background (binds a subscription made before it). */
  private learnServerId(gen: number): void {
    const probe = this.hooks.probePool;
    if (!probe) return;
    probe()
      .then((snap) => this.noteServerId(gen, snap.serverId))
      .catch(() => undefined);
  }

  private noteServerId(gen: number, serverId: string | null): void {
    if (gen !== this.gen || !serverId) return;
    this.socketServerId = serverId;
    if (this.live && this.live.gen === gen && !this.live.serverId) this.live.serverId = serverId;
  }

  /**
   * Re-send `start` (open / resync) only when the server still has the attached conversation, or
   * when it cannot be known (a backend restart, a failed read). A conversation that was never live
   * here (a new or just-attached view) is started at once, with no read.
   */
  private whenInPool(kind: 'open' | 'resync'): void {
    const client = this.client;
    if (!client) return;
    const go = (): void => (kind === 'open' ? client.onSocketOpen() : client.onResync());
    const id = client.localId;
    const probe = this.hooks.probePool;
    if (!probe || !this.wasLiveHere(id)) {
      go();
      return;
    }
    const gen = this.gen;
    probe()
      .then(
        (snap) => snap,
        () => null,
      )
      .then((snap) => {
        if (gen !== this.gen || this.client !== client || !this.socket.isOpen) return; // a newer open decides
        if (snap) this.noteServerId(gen, snap.serverId);
        const inPool = !!snap && snap.rows.some((r) => r.is_orchestrator === true && r.local_id === id);
        if (!inPool && (this.endedLocalId === id || (snap !== null && this.closedWhileAway(id)))) {
          this.live = null;
          this.endedLocalId = id;
          client.onClosedWhileAway();
          return;
        }
        go();
      });
  }

  /** `id` subscribed on this channel, or was closed live here: only then is a pool read needed. */
  private wasLiveHere(id: string): boolean {
    return this.live?.localId === id || this.endedLocalId === id;
  }

  /** SRV-1: `id` was live here on this very server process, which no longer has it. */
  private closedWhileAway(id: string): boolean {
    const sid = this.socketServerId;
    return !!sid && !!this.live && this.live.localId === id && this.live.serverId === sid;
  }

  private onFrame(f: ServerFrame): void {
    if (f.type === 'ping') return; // T-4: server pings are ignored
    if (f.type === 'orchestrator_switch') {
      this.onSwitch(f); // SW-1: socket level, attached or not
      return;
    }
    if (f.type === 'agent_turn_started' || f.type === 'agent_turn_finished') {
      this.hooks.onTurn?.(f); // device notifications: app level, attached or not
      return;
    }
    if (f.type === 'visualization_changed' || f.type === 'memory_changed') {
      this.hooks.onContent?.(f); // §9.3: app level, attached or not
      return;
    }
    if (f.type === 'agent_session_closed' && f.is_orchestrator === true) {
      if (this.live?.localId === f.session_id) this.live = null;
      if (this.client?.localId === f.session_id) this.endedLocalId = f.session_id; // WATCH-1: never auto-restart it
    }
    if (this.client) {
      const client = this.client;
      client.onSocketFrame(f);
      // The same conversation opened again (e.g. a voice rebuild on another device): re-subscribe.
      if (f.type === 'agent_session_opened' && f.is_orchestrator === true && this.endedLocalId === f.session_id && client.localId === f.session_id) {
        this.endedLocalId = null;
        client.onResync();
      }
      return;
    }
    if (f.type === 'agent_session_opened' || f.type === 'agent_session_closed') this.onWatcher(f);
  }

  /** Teardown: closes the socket, sends nothing (P-1). */
  stop(): void {
    this.started = false;
    this.everOpened = false;
    this.live = null;
    this.endedLocalId = null;
    this.socketServerId = null;
    this.client = null;
    this.reconnector.stop();
    this.socket.close();
  }

  get isStarted(): boolean {
    return this.started;
  }
}
