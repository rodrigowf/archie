/**
 * One WebSocket (spec 12 §3.1, spec 13 §3.4).
 *
 * - `binaryType = 'arraybuffer'`; server frames are binary UTF-8 JSON (T-1, G-2), decoded with
 *   `decodeFrame` (TextDecoder, with the pure fallback). Text frames are accepted too.
 * - The client **always sends text frames** (T-2: a binary frame makes the server drop the socket).
 * - Undecodable frames are logged and dropped (T-3); they never reach the reducer.
 * - Close and error become typed events, never raw strings in the UI (W-6.2 "websocket_error").
 * - An upgrade with no answer after {@link WS_HANDSHAKE_TIMEOUT_MS} is abandoned and reported as an
 *   abnormal close, so the reconnect backoff runs (T-16). Browsers wait a long time on their own, and
 *   nginx on the Jetson proxies the API WebSockets with 24 h timeouts.
 *
 * LOAD-BEARING inv02 F-01 (frontend/src/api/websocket.ts:12-52): URL from the page scheme,
 * arraybuffer frames, malformed frames ignored, sends only while OPEN.
 */
import { decodeFrame, encodeClientMessage, type ClientMessage, type ServerFrame } from '@/protocol';
import { getEnv, wsUrl, type WebSocketLike } from '../env';

export const WS_OPEN = 1;

/** spec 12 T-16. */
export const WS_HANDSHAKE_TIMEOUT_MS = 10_000;

export interface SocketHandlers {
  onOpen(): void;
  onFrame(frame: ServerFrame): void;
  /** After `close()` by us, no `onClose` is delivered. */
  onClose(info: { code: number; reason: string; clean: boolean }): void;
}

export const CHAT_WS_PATH = '/api/sessions/chat';
export const ORCHESTRATOR_WS_PATH = '/api/orchestrator/chat';

/** jsdom / other realms hand out ArrayBuffers that fail `instanceof`; normalise to a view. */
function normaliseData(data: unknown): unknown {
  if (typeof data === 'string' || data === null || data === undefined) return data;
  if (Object.prototype.toString.call(data) === '[object ArrayBuffer]') return new Uint8Array(data as ArrayBuffer);
  return data;
}

export class ArchieSocket {
  private ws: WebSocketLike | null = null;
  private closedByUs = false;
  private handshakeTimer: ReturnType<typeof setTimeout> | null = null;
  readonly url: string;

  constructor(
    path: string,
    private readonly handlers: SocketHandlers,
  ) {
    this.url = wsUrl(path);
  }

  /** Open a new connection. Throws only if the constructor throws (bad URL). */
  connect(): void {
    this.detach();
    this.closedByUs = false;
    const env = getEnv();
    const ws = new env.WebSocket(this.url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.handshakeTimer = setTimeout(() => {
      this.handshakeTimer = null;
      if (this.ws !== ws || ws.readyState !== 0) return;
      getEnv().log('warn', `handshake timed out after ${WS_HANDSHAKE_TIMEOUT_MS} ms`);
      this.detach();
      this.handlers.onClose({ code: 1006, reason: 'handshake timeout', clean: false });
    }, WS_HANDSHAKE_TIMEOUT_MS);
    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.clearHandshakeTimer();
      this.handlers.onOpen();
    };
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return;
      const d = decodeFrame(normaliseData(ev.data));
      if (!d.ok) {
        env.log('warn', 'dropped frame:', d.reason); // T-3
        return;
      }
      this.handlers.onFrame(d.frame);
    };
    ws.onerror = () => {
      // an error is always followed by close; the close carries the state change
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      this.clearHandshakeTimer();
      this.ws = null;
      if (this.closedByUs) return;
      this.handlers.onClose({ code: ev.code ?? 1006, reason: ev.reason ?? '', clean: ev.wasClean === true });
    };
  }

  get isOpen(): boolean {
    return this.ws !== null && this.ws.readyState === WS_OPEN;
  }

  get isConnecting(): boolean {
    return this.ws !== null && this.ws.readyState === 0;
  }

  /** Send a text frame (T-2). Returns false when the socket is not open (nothing is sent). */
  send(msg: ClientMessage): boolean {
    if (!this.ws || this.ws.readyState !== WS_OPEN) return false;
    try {
      this.ws.send(encodeClientMessage(msg));
      return true;
    } catch (err) {
      getEnv().log('warn', 'send failed', err);
      return false;
    }
  }

  /**
   * Close the transport locally. Sends **no** protocol frame (no `stop`, no `close`): closing a
   * socket never closes a session (P-1).
   */
  close(): void {
    this.closedByUs = true;
    this.detach();
  }

  private clearHandshakeTimer(): void {
    if (this.handshakeTimer !== null) clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
  }

  private detach(): void {
    this.clearHandshakeTimer();
    const ws = this.ws;
    this.ws = null;
    if (!ws) return;
    ws.onopen = null;
    ws.onmessage = null;
    ws.onclose = null;
    ws.onerror = () => undefined; // a late error of a socket we gave up on is not news
    try {
      ws.close(1000, 'client');
    } catch {
      // already closed
    }
  }
}
