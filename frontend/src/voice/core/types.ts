/**
 * Voice engine types (spec 13 §3.4 `src/voice/`, spec 12 §7). No React, no DOM: the controller
 * gets its I/O (orchestrator socket, transports, clock, cues, network) injected.
 */
import type { ClientMessage, ServerFrame } from '@/protocol';

/** The device's OWN voice state (spec 12 §7; inv02 F-26 `VoiceStatus`). */
export type VoiceStatus = 'off' | 'connecting' | 'active' | 'speaking' | 'thinking' | 'tool_use' | 'ending' | 'error';

/** Sub-phase of `connecting` (spec 12 §7.3: `voice_status: summarizing | preparing`). */
export type VoicePhase = 'starting' | 'summarizing' | 'preparing' | null;

/**
 * P-2 link state (plan/20 P-2): `lost` = Reconnecting… (elapsed timer, repeating cue),
 * `restored` = the short "Reconnected" confirmation, `failed` = "Couldn't reconnect" after the
 * 30 s retry budget (manual Reconnect).
 */
export type LinkState = 'ok' | 'lost' | 'restored' | 'failed';

/** What dropped: the orchestrator socket, the provider transport, the browser network, or the server's provider relay (§7.7). */
export type LinkSource = 'socket' | 'transport' | 'network' | 'provider';

export type ConnectionType = 'webrtc' | 'websocket';

export interface VoiceErrorInfo {
  readonly message: string;
  /** `voice_error.recovery_hint`, or our own hint. */
  readonly hint: string | null;
  readonly category: string | null;
  readonly docUrl: string | null;
}

/** `voice_vad_state` (spec 12 §7.7): the "Listening Ns" counter. */
export interface VadInfo {
  readonly state: string;
  readonly durationMs: number;
  /** Clock time the event arrived (the UI adds the time since). */
  readonly at: number;
}

export type VoiceBanner =
  | { readonly kind: 'reconnect_warning'; readonly timeLeftS: number | null }
  | { readonly kind: 'warning'; readonly message: string }
  | null;

export interface VoiceSnapshot {
  readonly status: VoiceStatus;
  readonly phase: VoicePhase;
  /** Another device owns voice (§7.5). The device's own `status` stays `off` (fixes W-1). */
  readonly remoteActive: boolean;
  /** The owner's provider as told by `session_started{voice_initiator:false}` (VT-2 copy). */
  readonly remoteProvider: string | null;
  readonly link: LinkState;
  /** Clock time the link was lost (drives the elapsed timer). */
  readonly linkLostAt: number | null;
  readonly linkSource: LinkSource | null;
  /** How long the outage lasted, for "Back after 0:09". */
  readonly linkRecoveredMs: number | null;
  readonly error: VoiceErrorInfo | null;
  readonly banner: VoiceBanner;
  readonly micMuted: boolean;
  readonly speakerMuted: boolean;
  readonly vad: VadInfo | null;
  readonly connectionType: ConnectionType | null;
  readonly provider: string | null;
  /** WebRTC session recording running (§7.8). */
  readonly recording: boolean;
}

export const OFF_SNAPSHOT: VoiceSnapshot = {
  status: 'off',
  phase: null,
  remoteActive: false,
  remoteProvider: null,
  link: 'ok',
  linkLostAt: null,
  linkSource: null,
  linkRecoveredMs: null,
  error: null,
  banner: null,
  micMuted: false,
  speakerMuted: false,
  vad: null,
  connectionType: null,
  provider: null,
  recording: false,
};

/** True while this device runs (or is bringing up) a call. */
export function isOwnerLive(s: VoiceSnapshot): boolean {
  return s.status === 'connecting' || s.status === 'active' || s.status === 'speaking' || s.status === 'thinking' || s.status === 'tool_use';
}

/** The dock replaces the composer (IA §6) whenever this device has a voice state other than off. */
export function dockVisible(s: VoiceSnapshot): boolean {
  return s.status !== 'off';
}

// ───────────────────────── injected I/O ─────────────────────────

/** What the controller needs from the Archie conversation (ArchieRuntime's `VoiceBridge`). */
export interface VoicePort {
  readonly localId: string;
  /** The orchestrator JSONL id, for `voice_start.resume_sdk_id`. */
  sdkId(): string | null;
  /**
   * Run the start handshake now with `startMessage()`: on an open socket the runtime re-sends the
   * start (a `voice_start` while armed); on a closed one it reconnects at once.
   */
  requestStart(): void;
  /** Raw send on the shared orchestrator socket. False when the socket is not open. */
  send(msg: ClientMessage): boolean;
  /** OpenAI data-channel inbound event → the conversation reducer (§4.7). */
  feedDataChannelEvent(event: Readonly<Record<string, unknown>>): void;
  /** Every local end path delivers `voice_local_end` to the reducer (§7.6, VT-3). */
  voiceLocalEnd(): void;
  /** The conversation already believes voice is live (hooks attached after `session_started`). */
  conversationVoiceActive(): boolean;
}

export interface Clock {
  now(): number;
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

export const systemClock: Clock = {
  now: () => Date.now(),
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (h) => {
    clearTimeout(h as ReturnType<typeof setTimeout>);
  },
};

/** Browser online/offline (a faster signal than a TCP timeout on the socket). */
export interface NetworkSource {
  isOnline(): boolean;
  subscribe(fn: (online: boolean) => void): () => void;
}

/** P-2 audio cues. */
export type OneShotCue = 'reconnected' | 'failed';
export interface CuePlayer {
  /** The soft repeating "reconnecting" pattern, from the moment the link is lost. */
  startLoop(): void;
  stopLoop(): void;
  play(cue: OneShotCue): void;
}

/** Parsed `session_started.voice_connection_info` (inv01 §7; providers' `get_connection_info`). */
export interface ConnectionInfo {
  readonly connectionType: ConnectionType;
  readonly endpoint: string | null;
  readonly token: string | null;
  readonly inRate: number;
  readonly outRate: number;
}

export interface TransportEvents {
  /** WebRTC: data channel open. WS relay: mic capture and playback are running. */
  ready(): void;
  /** WebRTC data-channel inbound event (already JSON-parsed). */
  providerEvent(event: Record<string, unknown>): void;
  /** WebRTC data-channel outbound event (mirrored to the backend, §7.3). */
  outbound(event: Record<string, unknown>): void;
  /** WS relay: one 100 ms PCM16 chunk, base64. */
  audioChunk(b64: string): void;
  /** WebRTC: ICE disconnected (may recover by itself). */
  linkDown(): void;
  /** WebRTC: ICE connected again. */
  linkUp(): void;
  /** The transport died or could not start. */
  failed(message: string): void;
  /** WebRTC recording (§7.8). */
  recordingChunk(channel: 'user' | 'assistant', b64: string): void;
  recordingEnd(): void;
}

export interface TransportOptions {
  readonly info: ConnectionInfo;
  readonly events: TransportEvents;
  /** WebRTC session recording requested by `session_started.voice_recording_enabled`. */
  readonly record: boolean;
}

export interface VoiceTransport {
  readonly kind: ConnectionType;
  /** Rejects with a human message when setup fails (mic denied, SDP refused, …). */
  start(): Promise<void>;
  /** WebRTC: send on the data channel; false when it is not open. WS relay: false (the controller relays). */
  send(event: Record<string, unknown>): boolean;
  /** WS relay: schedule `voice_audio_out` PCM. */
  playAudio(b64: string): void;
  /** Barge-in: drop everything queued locally (V-9, V-10). */
  flushPlayback(): void;
  setMicMuted(muted: boolean): void;
  setSpeakerMuted(muted: boolean): void;
  /** RMS 0..1 of mic and speaker, polled by the level orb. */
  levels(): { mic: number; speaker: number };
  /** Data channel open / capture running and not failed. */
  healthy(): boolean;
  close(): void;
}

export type TransportFactory = (kind: ConnectionType, opts: TransportOptions) => VoiceTransport;

export type { ServerFrame };
