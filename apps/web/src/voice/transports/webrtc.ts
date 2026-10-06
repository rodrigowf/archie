/**
 * OpenAI realtime over WebRTC (spec 12 §7.3 `connection_type == "webrtc"`; spec 13 §3.4).
 *
 * Ported from inv02 F-26 (frontend/src/voice/transports/webrtc.ts, frontend/src/api/voice.ts:46-65):
 * peer connection + mic track + data channel `"oai-events"`; SDP offer POSTed to
 * `connection_info.endpoint` with `Authorization: Bearer <ephemeral_token>` and
 * `Content-Type: application/sdp`; remote audio on a hidden `<audio playsinline autoplay>`.
 *
 * New: ICE `disconnected` is reported as a transient link loss (P-2) and `connected` again as
 * recovery; `failed` / an unexpected data-channel close is a dead transport. Safari 12 has no
 * `connectionState`, so `iceConnectionState` is watched too.
 */
import type { AudioContextLike } from '../audio/context';
import type { WorkletNodeCtor } from '../audio/capture/worklet';
import { SILENT_METER, streamMeter, type LevelMeter } from '../audio/meters';
import type { ConnectionInfo, TransportEvents, VoiceTransport } from '../core/types';
import { SessionRecorder } from '../recording/sessionRecorder';
import { micConstraints, micErrorText, setStreamEnabled, stopStream, type MediaDevicesLike } from './shared';

export const OAI_EVENTS_CHANNEL = 'oai-events';
export const DEFAULT_CALLS_URL = 'https://api.openai.com/v1/realtime/calls?model=gpt-realtime';

export interface DataChannelLike {
  readyState: string;
  onopen: (() => void) | null;
  onmessage: ((e: { data: unknown }) => void) | null;
  onclose: (() => void) | null;
  onerror: ((e: unknown) => void) | null;
  send(data: string): void;
  close(): void;
}

export interface PeerConnectionLike {
  connectionState?: string;
  iceConnectionState?: string;
  ontrack: ((e: { streams: readonly MediaStream[] }) => void) | null;
  onconnectionstatechange: (() => void) | null;
  oniceconnectionstatechange: (() => void) | null;
  addTrack(track: MediaStreamTrack, stream: MediaStream): unknown;
  createDataChannel(label: string): DataChannelLike;
  createOffer(): Promise<{ type: string; sdp?: string }>;
  setLocalDescription(d: { type: string; sdp?: string }): Promise<void>;
  setRemoteDescription(d: { type: 'answer'; sdp: string }): Promise<void>;
  close(): void;
}

export interface AudioElementLike {
  muted: boolean;
  srcObject: MediaProvider | null;
  play(): Promise<void> | void;
}

export interface WebRtcEnv {
  RTCPeerConnection: new () => PeerConnectionLike;
  media: MediaDevicesLike;
  fetch: (url: string, init: { method: string; headers: Record<string, string>; body: string }) => Promise<{
    ok: boolean;
    status: number;
    text(): Promise<string>;
  }>;
  audioElement(): AudioElementLike | null;
  audioContext(): AudioContextLike | null;
  /** WebRTC recording needs AudioWorklet (spec 13 §2.5): null hides it. */
  WorkletNode?: WorkletNodeCtor | null;
  workletUrl?: string;
}

export class WebRtcTransport implements VoiceTransport {
  readonly kind = 'webrtc' as const;
  private pc: PeerConnectionLike | null = null;
  private dc: DataChannelLike | null = null;
  private mic: MediaStream | null = null;
  private remote: MediaStream | null = null;
  private micMeter: LevelMeter = SILENT_METER;
  private remoteMeter: LevelMeter = SILENT_METER;
  private recorder: SessionRecorder | null = null;
  private closed = false;
  private failedOnce = false;
  private down = false;
  private micMuted = false;
  private speakerMuted = false;

  constructor(
    private readonly info: ConnectionInfo,
    private readonly events: TransportEvents,
    private readonly env: WebRtcEnv,
    private readonly record: boolean,
  ) {}

  async start(): Promise<void> {
    const token = this.info.token;
    if (!token) throw new Error('Voice did not start (no session token from the server)');
    const pc = new this.env.RTCPeerConnection();
    this.pc = pc;
    pc.ontrack = (e) => this.onTrack(e.streams[0] ?? null);
    pc.onconnectionstatechange = () => this.onState(pc.connectionState);
    pc.oniceconnectionstatechange = () => {
      if (pc.connectionState === undefined) this.onState(pc.iceConnectionState);
    };
    let mic: MediaStream;
    try {
      mic = await this.env.media.getUserMedia(micConstraints(this.info.inRate));
    } catch (err) {
      throw new Error(micErrorText(err));
    }
    if (this.closed) {
      stopStream(mic);
      return;
    }
    this.mic = mic;
    setStreamEnabled(mic, !this.micMuted);
    mic.getTracks().forEach((t) => pc.addTrack(t, mic));
    this.micMeter = streamMeter(this.env.audioContext(), mic);

    const dc = pc.createDataChannel(OAI_EVENTS_CHANNEL);
    this.dc = dc;
    dc.onopen = () => {
      if (!this.closed) this.events.ready();
    };
    dc.onmessage = (e) => {
      if (this.closed || typeof e.data !== 'string') return;
      let ev: unknown;
      try {
        ev = JSON.parse(e.data);
      } catch {
        return; // unparseable payloads ignored (inv02 F-26)
      }
      if (ev && typeof ev === 'object' && !Array.isArray(ev)) this.events.providerEvent(ev as Record<string, unknown>);
    };
    dc.onclose = () => this.fail('The voice connection closed');
    dc.onerror = () => undefined; // a close follows

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    if (this.closed) return;
    let res: Awaited<ReturnType<WebRtcEnv['fetch']>>;
    try {
      res = await this.env.fetch(this.info.endpoint ?? DEFAULT_CALLS_URL, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/sdp' },
        body: offer.sdp ?? '',
      });
    } catch {
      throw new Error('Could not reach the voice service');
    }
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(`The voice service refused the call (${res.status})${detail ? `: ${detail.slice(0, 200)}` : ''}`);
    }
    const answer = await res.text();
    if (this.closed) return;
    await pc.setRemoteDescription({ type: 'answer', sdp: answer });
    if (this.record) this.startRecorder();
  }

  private onTrack(stream: MediaStream | null): void {
    if (!stream || this.closed) return;
    this.remote = stream;
    const el = this.env.audioElement();
    if (el) {
      el.srcObject = stream;
      el.muted = this.speakerMuted;
      try {
        const p = el.play();
        if (p && typeof p.catch === 'function') p.catch(() => undefined);
      } catch {
        // autoplay rules: the element was unlocked in the start tap
      }
    }
    this.remoteMeter.close();
    this.remoteMeter = streamMeter(this.env.audioContext(), stream);
    void this.recorder?.attach('assistant', stream);
  }

  private onState(state: string | undefined): void {
    if (this.closed || !state) return;
    if (state === 'disconnected') {
      if (!this.down) {
        this.down = true;
        this.events.linkDown();
      }
    } else if (state === 'connected' || state === 'completed') {
      if (this.down) {
        this.down = false;
        this.events.linkUp();
      }
    } else if (state === 'failed' || state === 'closed') {
      this.fail('The voice connection was lost');
    }
  }

  private fail(message: string): void {
    if (this.closed || this.failedOnce) return;
    this.failedOnce = true;
    this.events.failed(message);
  }

  private startRecorder(): void {
    const ctx = this.env.audioContext();
    if (!ctx || !this.env.WorkletNode || !this.mic) return;
    this.recorder = new SessionRecorder({
      ctx,
      sampleRate: this.info.inRate,
      sink: { chunk: (ch, b64) => this.events.recordingChunk(ch, b64), end: () => this.events.recordingEnd() },
      WorkletNode: this.env.WorkletNode,
      url: this.env.workletUrl,
    });
    void this.recorder.attach('user', this.mic);
    if (this.remote) void this.recorder.attach('assistant', this.remote);
  }

  send(event: Record<string, unknown>): boolean {
    const dc = this.dc;
    if (this.closed || !dc || dc.readyState !== 'open') return false;
    dc.send(JSON.stringify(event));
    this.events.outbound(event);
    return true;
  }

  playAudio(): void {
    // audio arrives as a media track
  }

  flushPlayback(): void {
    // the provider handles interruption over WebRTC (V-11)
  }

  setMicMuted(muted: boolean): void {
    this.micMuted = muted;
    setStreamEnabled(this.mic, !muted);
  }

  setSpeakerMuted(muted: boolean): void {
    this.speakerMuted = muted;
    const el = this.env.audioElement();
    if (el && this.remote) el.muted = muted;
  }

  levels(): { mic: number; speaker: number } {
    return { mic: this.micMeter.level(), speaker: this.remoteMeter.level() };
  }

  healthy(): boolean {
    if (this.closed || this.failedOnce || !this.dc || this.dc.readyState !== 'open') return false;
    const st = this.pc?.connectionState ?? this.pc?.iceConnectionState;
    return st !== 'failed' && st !== 'closed';
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.recorder?.stop();
    this.recorder = null;
    this.micMeter.close();
    this.remoteMeter.close();
    stopStream(this.mic);
    this.mic = null;
    try {
      if (this.dc) {
        this.dc.onclose = null;
        this.dc.close();
      }
      this.pc?.close();
    } catch {
      // fine
    }
    const el = this.env.audioElement();
    if (el && el.srcObject === this.remote) el.srcObject = null;
    this.remote = null;
  }
}
