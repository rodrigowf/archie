/**
 * Qwen / Gemini through the backend relay (spec 12 §7.3 `connection_type == "websocket"`,
 * `audio_relay: "backend"`; spec 13 §3.4).
 *
 * Ported from inv02 F-26 (frontend/src/voice/transports/websocket.ts): mic → AudioWorklet
 * `pcm-capture` → PCM16 LE mono at `audio_in_format.sample_rate`, 100 ms chunks, base64 →
 * `voice_audio_in` (the controller sends them only after `voice_status: ready`); `voice_audio_out`
 * → gapless `PcmPlayer` at `audio_out_format.sample_rate`; `flushPlayback()` for barge-in.
 *
 * Uses the shared, gesture-unlocked AudioContext (the old code made two private contexts).
 */
import type { AudioContextLike } from '../audio/context';
import { startPcmCapture, toBase64Chunk, type PcmCapture, type WorkletNodeCtor } from '../audio/capture/worklet';
import { SILENT_METER, streamMeter, type LevelMeter } from '../audio/meters';
import { PcmPlayer } from '../audio/pcmPlayer';
import type { ConnectionInfo, TransportEvents, VoiceTransport } from '../core/types';
import { micConstraints, micErrorText, setStreamEnabled, stopStream, type MediaDevicesLike } from './shared';

export interface WsRelayEnv {
  media: MediaDevicesLike;
  audioContext(): AudioContextLike | null;
  WorkletNode: WorkletNodeCtor | null;
  workletUrl?: string;
}

export class WsRelayTransport implements VoiceTransport {
  readonly kind = 'websocket' as const;
  private mic: MediaStream | null = null;
  private capture: PcmCapture | null = null;
  private player: PcmPlayer | null = null;
  private micMeter: LevelMeter = SILENT_METER;
  private closed = false;
  private running = false;
  private micMuted = false;
  private speakerMuted = false;

  constructor(
    private readonly info: ConnectionInfo,
    private readonly events: TransportEvents,
    private readonly env: WsRelayEnv,
  ) {}

  async start(): Promise<void> {
    const ctx = this.env.audioContext();
    if (!ctx) throw new Error('Web Audio is not available');
    if (ctx.state === 'suspended') void ctx.resume?.().catch(() => undefined);
    this.player = new PcmPlayer(ctx, this.info.outRate);
    this.player.setMuted(this.speakerMuted);
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
    this.micMeter = streamMeter(ctx, mic);
    const cap = await startPcmCapture({
      ctx,
      source: ctx.createMediaStreamSource(mic),
      targetSampleRate: this.info.inRate,
      onChunk: (bytes) => {
        if (!this.closed) this.events.audioChunk(toBase64Chunk(bytes));
      },
      WorkletNode: this.env.WorkletNode,
      url: this.env.workletUrl,
    });
    if (this.closed) {
      cap.stop();
      return;
    }
    this.capture = cap;
    this.running = true;
    this.events.ready();
  }

  send(): boolean {
    return false; // provider frames go over the orchestrator socket as voice_event (the controller)
  }

  playAudio(b64: string): void {
    if (!this.closed) this.player?.push(b64);
  }

  flushPlayback(): void {
    this.player?.flush();
  }

  setMicMuted(muted: boolean): void {
    this.micMuted = muted;
    setStreamEnabled(this.mic, !muted);
  }

  setSpeakerMuted(muted: boolean): void {
    this.speakerMuted = muted;
    this.player?.setMuted(muted);
  }

  levels(): { mic: number; speaker: number } {
    return { mic: this.micMeter.level(), speaker: this.player?.level() ?? 0 };
  }

  healthy(): boolean {
    return this.running && !this.closed;
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.running = false;
    this.capture?.stop();
    this.capture = null;
    this.micMeter.close();
    stopStream(this.mic);
    this.mic = null;
    this.player?.destroy();
    this.player = null;
  }
}
