/**
 * Gapless PCM16 playback with flush (spec 13 §3.4 `audio/pcmPlayer.ts`).
 *
 * LOAD-BEARING inv02 F-26 (frontend/src/voice/audio/pcmPlayer.ts:60-87, 99-108): every chunk is
 * scheduled tail-to-tail on a running `nextStartTime` cursor (no gaps on network jitter); a
 * chunk behind the clock starts now; `flush()` stops every scheduled source, including the one
 * playing, so barge-in silences Archie at once.
 *
 * Unlike the old player this one does not own its AudioContext (the shared, gesture-unlocked
 * context is passed in), so destroying it never closes the context the P-2 cues need.
 */
import { base64ToBytes, pcm16ToFloat32 } from './base64';
import type { AnalyserNodeLike, AudioContextLike, BufferSourceLike, GainNodeLike } from './context';
import { analyserLevel } from './meters';

export class PcmPlayer {
  private readonly gain: GainNodeLike;
  private readonly analyser: AnalyserNodeLike;
  private nextStartTime = 0;
  private readonly active = new Set<BufferSourceLike>();
  private muted = false;
  private destroyed = false;

  constructor(
    private readonly ctx: AudioContextLike,
    private readonly sampleRate: number,
  ) {
    this.gain = ctx.createGain();
    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 256;
    this.gain.connect(this.analyser);
    this.analyser.connect(ctx.destination);
  }

  /** Schedule one base64 PCM16 chunk at the cursor. */
  push(b64: string): void {
    if (this.destroyed || !b64) return;
    const samples = pcm16ToFloat32(base64ToBytes(b64));
    if (samples.length === 0) return;
    // the destination may run at another rate; the buffer source resamples on the fly
    const buf = this.ctx.createBuffer(1, samples.length, this.sampleRate);
    buf.getChannelData(0).set(samples);
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.gain);
    const startAt = Math.max(this.ctx.currentTime, this.nextStartTime);
    src.start(startAt);
    this.nextStartTime = startAt + buf.duration;
    this.active.add(src);
    src.onended = () => {
      this.active.delete(src);
    };
  }

  /** Barge-in: silence now and drop everything scheduled. */
  flush(): void {
    this.active.forEach((src) => {
      try {
        src.stop();
      } catch {
        // already stopped
      }
      try {
        src.disconnect();
      } catch {
        // fine
      }
    });
    this.active.clear();
    this.nextStartTime = this.ctx.currentTime;
  }

  get pending(): number {
    return this.active.size;
  }

  setMuted(muted: boolean): void {
    this.muted = muted;
    this.gain.gain.value = muted ? 0 : 1;
  }

  isMuted(): boolean {
    return this.muted;
  }

  /** Speaker RMS 0..1. */
  level(): number {
    return this.destroyed ? 0 : analyserLevel(this.analyser);
  }

  destroy(): void {
    if (this.destroyed) return;
    this.flush();
    this.destroyed = true;
    try {
      this.gain.disconnect();
      this.analyser.disconnect();
    } catch {
      // fine
    }
  }
}
