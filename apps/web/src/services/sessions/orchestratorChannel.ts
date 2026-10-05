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
 */
import type { AgentSessionClosedFrame, AgentSessionOpenedFrame, ServerFrame } from '@/protocol';
import { Reconnector, type ReconnectPolicy } from '../ws/reconnect';
import { ArchieSocket, ORCHESTRATOR_WS_PATH } from '../ws/socket';

/** The attached conversation (ArchieRuntime). */
export interface ChannelClient {
  onSocketOpen(): void;
  onSocketFrame(frame: ServerFrame): void;
  onSocketClosed(): void;
  /** Visible again with the socket open: re-send `start` / `voice_start` (T-9). */
  onResync(): void;
}

export type WatcherFrame = AgentSessionOpenedFrame | AgentSessionClosedFrame;

export class OrchestratorChannel {
  private readonly socket: ArchieSocket;
  private readonly reconnector: Reconnector;
  private client: ChannelClient | null = null;
  private started = false;

  constructor(
    private readonly onWatcher: (frame: WatcherFrame) => void,
    policy?: ReconnectPolicy,
  ) {
    this.socket = new ArchieSocket(ORCHESTRATOR_WS_PATH, {
      onOpen: () => {
        if (this.client) this.client.onSocketOpen();
        else this.reconnector.markHealthy();
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
        resync: () => this.client?.onResync(),
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

  private onFrame(f: ServerFrame): void {
    if (f.type === 'ping') return; // T-4: server pings are ignored
    if (this.client) {
      this.client.onSocketFrame(f);
      return;
    }
    if (f.type === 'agent_session_opened' || f.type === 'agent_session_closed') this.onWatcher(f);
  }

  /** Teardown: closes the socket, sends nothing (P-1). */
  stop(): void {
    this.started = false;
    this.client = null;
    this.reconnector.stop();
    this.socket.close();
  }

  get isStarted(): boolean {
    return this.started;
  }
}
