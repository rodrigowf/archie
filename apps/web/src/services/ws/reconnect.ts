/**
 * Reconnect scheduling and visibility (spec 12 T-13, T-14, §3.5; spec 13 §3.4).
 *
 * - Default policy = spec 12 T-13 (normative): `min(15 s, 1 s × 2^attempt) ± 20 %` jitter,
 *   unlimited while the view is open and the page visible; `attempt` resets on a successful
 *   `session_started` (`markHealthy`).
 * - `legacyPolicy` = the current web app's 2000 ms × 10 attempts (inv02 F-01), kept for
 *   comparison and as an option.
 * - **Paused while `document.hidden`** (T-14): a pending timer is cancelled when the page hides;
 *   on visible: an OPEN socket gets `start` re-sent (T-9), otherwise attempts reset and it
 *   connects at once.
 *
 * LOAD-BEARING inv02 F-01 / §7 #2 (frontend/src/hooks/useWebSocket.ts:6-8, 60-78, 112-125):
 * no reconnect while hidden; on visible, re-send `start` if OPEN else reset + connect now.
 */
import { getEnv } from '../env';

export interface ReconnectPolicy {
  /** Delay before attempt number `attempt` (0-based), or `null` to give up. */
  delayMs(attempt: number, random: () => number): number | null;
}

export const backoffPolicy: ReconnectPolicy = {
  delayMs(attempt, random) {
    const base = Math.min(15_000, 1000 * Math.pow(2, attempt));
    return Math.round(base * (0.8 + 0.4 * random()));
  },
};

export const legacyPolicy: ReconnectPolicy = {
  delayMs: (attempt) => (attempt < 10 ? 2000 : null),
};

let defaultPolicy: ReconnectPolicy = backoffPolicy;

export function setDefaultReconnectPolicy(p: ReconnectPolicy | null): void {
  defaultPolicy = p ?? backoffPolicy;
}

export interface ReconnectTarget {
  /** Is the transport open right now? */
  isOpen(): boolean;
  /** Is a connection attempt in flight? */
  isConnecting(): boolean;
  /** Open a new connection. */
  connect(): void;
  /** Visible again with an open socket: re-send `start` (T-9). */
  resync(): void;
  /** Called when the policy gives up. */
  gaveUp?(): void;
}

export class Reconnector {
  attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private unsubscribe: (() => void) | null = null;
  private stopped = false;

  constructor(
    private readonly target: ReconnectTarget,
    private readonly policy: ReconnectPolicy = defaultPolicy,
  ) {
    this.unsubscribe = getEnv().visibility.subscribe((hidden) => this.onVisibility(hidden));
  }

  get pending(): boolean {
    return this.timer !== null;
  }

  /** The socket closed: schedule the next attempt (not while hidden). */
  scheduleReconnect(): void {
    if (this.stopped || this.timer) return;
    if (getEnv().visibility.isHidden()) return; // T-14: the visible handler reconnects
    const delay = this.policy.delayMs(this.attempt, getEnv().random);
    if (delay === null) {
      this.target.gaveUp?.();
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      if (this.stopped) return;
      if (getEnv().visibility.isHidden()) return;
      this.attempt += 1;
      this.target.connect();
    }, delay);
  }

  /** Retry now (connection banner "Retry", visibility, network back). Resets the backoff. */
  reconnectNow(): void {
    if (this.stopped) return;
    this.cancelTimer();
    this.attempt = 0;
    if (!this.target.isOpen() && !this.target.isConnecting()) this.target.connect();
  }

  /** A successful subscribe (`session_started`, T-13) or an open watcher socket. */
  markHealthy(): void {
    this.attempt = 0;
  }

  private onVisibility(hidden: boolean): void {
    if (this.stopped) return;
    if (hidden) {
      this.cancelTimer(); // paused while hidden (T-14)
      return;
    }
    if (this.target.isOpen()) this.target.resync();
    else if (!this.target.isConnecting()) {
      this.cancelTimer();
      this.attempt = 0;
      this.target.connect();
    }
  }

  private cancelTimer(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  stop(): void {
    this.stopped = true;
    this.cancelTimer();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }
}
