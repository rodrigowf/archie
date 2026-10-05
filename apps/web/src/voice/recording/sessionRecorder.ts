/**
 * WebRTC session recording (spec 12 §7.8; spec 13 §2.5: main only, needs AudioWorklet).
 *
 * When `session_started.voice_recording_enabled` and the transport is WebRTC (audio bypasses the
 * backend), the owner captures both channels with the `pcm-capture` worklet at the input rate,
 * buffers the PCM, and every 5 s sends one `voice_recording_chunk` per channel; on stop it sends
 * the remainder and `voice_recording_end` (inv02 F-30; the backend writes `context/recordings/`).
 *
 * Fixes W-2: the old recorder asked for `'pcm-capture-processor'`, the worklet registers
 * `'pcm-capture'`, so it never recorded; its nodes were also never connected to a destination.
 */
import { bytesToBase64, concatBytes } from '../audio/base64';
import type { AudioContextLike } from '../audio/context';
import { startPcmCapture, type PcmCapture, type WorkletNodeCtor } from '../audio/capture/worklet';

export const RECORDING_FLUSH_MS = 5_000;

export type RecordingChannel = 'user' | 'assistant';

export interface RecorderSink {
  chunk(channel: RecordingChannel, b64: string): void;
  end(): void;
}

export interface SessionRecorderOptions {
  ctx: AudioContextLike;
  sampleRate: number;
  sink: RecorderSink;
  WorkletNode?: WorkletNodeCtor | null;
  url?: string;
  timers?: { setInterval(fn: () => void, ms: number): unknown; clearInterval(h: unknown): void };
  log?: (...args: unknown[]) => void;
}

export class SessionRecorder {
  private readonly buffers: Record<RecordingChannel, Uint8Array[]> = { user: [], assistant: [] };
  private readonly captures: PcmCapture[] = [];
  private readonly attached = new Set<RecordingChannel>();
  private timer: unknown = null;
  private stopped = false;
  private readonly timers: NonNullable<SessionRecorderOptions['timers']>;

  constructor(private readonly opts: SessionRecorderOptions) {
    this.timers = opts.timers ?? {
      setInterval: (fn, ms) => setInterval(fn, ms),
      clearInterval: (h) => {
        clearInterval(h as ReturnType<typeof setInterval>);
      },
    };
    this.timer = this.timers.setInterval(() => this.flush(), RECORDING_FLUSH_MS);
  }

  /** Start capturing one channel's stream (the remote stream arrives later, on `ontrack`). */
  async attach(channel: RecordingChannel, stream: MediaStream): Promise<void> {
    if (this.stopped || this.attached.has(channel)) return;
    this.attached.add(channel);
    try {
      const source = this.opts.ctx.createMediaStreamSource(stream);
      const cap = await startPcmCapture({
        ctx: this.opts.ctx,
        source,
        targetSampleRate: this.opts.sampleRate,
        onChunk: (bytes) => {
          if (!this.stopped) this.buffers[channel].push(bytes);
        },
        WorkletNode: this.opts.WorkletNode,
        url: this.opts.url,
      });
      if (this.stopped) cap.stop();
      else this.captures.push(cap);
    } catch (err) {
      this.attached.delete(channel);
      this.opts.log?.('recording attach failed', channel, err);
    }
  }

  /** Send what is buffered (one chunk per channel). */
  flush(): void {
    (['user', 'assistant'] as const).forEach((ch) => {
      const parts = this.buffers[ch];
      if (parts.length === 0) return;
      this.buffers[ch] = [];
      this.opts.sink.chunk(ch, bytesToBase64(concatBytes(parts)));
    });
  }

  stop(): void {
    if (this.stopped) return;
    this.timers.clearInterval(this.timer);
    this.captures.forEach((c) => c.stop());
    this.flush();
    this.stopped = true;
    this.opts.sink.end();
  }
}
