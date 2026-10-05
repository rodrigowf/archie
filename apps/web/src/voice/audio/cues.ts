/**
 * P-2 call-app cues (plan/20 P-2, mockups (k)): a soft repeating "reconnecting" pattern from
 * the moment the link drops, a distinct rising "reconnected" tone, a falling "failed" tone.
 * Synthesised with oscillators on the shared, gesture-unlocked context: no audio files, nothing
 * to download while the network is down.
 */
import type { CuePlayer, OneShotCue } from '../core/types';
import type { AudioContextLike } from './context';

export interface Tone {
  /** Hz. */
  readonly freq: number;
  /** Offset from the pattern start, seconds. */
  readonly at: number;
  /** Seconds. */
  readonly dur: number;
}

/** Two short soft beeps (like a call app's "reconnecting" pips). */
export const RECONNECTING_PATTERN: readonly Tone[] = [
  { freq: 440, at: 0, dur: 0.12 },
  { freq: 440, at: 0.22, dur: 0.12 },
];
/** The pattern repeats this often while the link is down. */
export const RECONNECTING_PERIOD_MS = 2_000;
export const RECONNECTED_PATTERN: readonly Tone[] = [
  { freq: 660, at: 0, dur: 0.12 },
  { freq: 990, at: 0.14, dur: 0.2 },
];
export const FAILED_PATTERN: readonly Tone[] = [
  { freq: 660, at: 0, dur: 0.16 },
  { freq: 495, at: 0.18, dur: 0.16 },
  { freq: 330, at: 0.36, dur: 0.3 },
];
/** Quiet: a cue, not an alarm. */
export const CUE_GAIN = 0.12;

export interface CueTimers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(h: unknown): void;
}

const systemTimers: CueTimers = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (h) => {
    clearTimeout(h as ReturnType<typeof setTimeout>);
  },
};

/** Schedule one pattern on `ctx` now. */
export function playPattern(ctx: AudioContextLike, pattern: readonly Tone[], gain = CUE_GAIN): void {
  if (ctx.state === 'suspended') void ctx.resume?.().catch(() => undefined);
  const t0 = ctx.currentTime + 0.02;
  for (const tone of pattern) {
    const osc = ctx.createOscillator();
    const g = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = tone.freq;
    const start = t0 + tone.at;
    const end = start + tone.dur;
    // short attack/release so the pips do not click
    if (g.gain.setValueAtTime && g.gain.linearRampToValueAtTime) {
      g.gain.setValueAtTime(0, start);
      g.gain.linearRampToValueAtTime(gain, start + 0.015);
      g.gain.setValueAtTime(gain, end - 0.03);
      g.gain.linearRampToValueAtTime(0, end);
    } else {
      g.gain.value = gain;
    }
    osc.connect(g);
    g.connect(ctx.destination);
    osc.start(start);
    osc.stop(end + 0.01);
  }
}

export class WebAudioCuePlayer implements CuePlayer {
  private loop: unknown = null;
  private looping = false;

  constructor(
    private readonly context: () => AudioContextLike | null,
    private readonly timers: CueTimers = systemTimers,
  ) {}

  get isLooping(): boolean {
    return this.looping;
  }

  startLoop(): void {
    if (this.looping) return;
    this.looping = true;
    const tick = (): void => {
      if (!this.looping) return;
      this.emit(RECONNECTING_PATTERN);
      this.loop = this.timers.setTimeout(tick, RECONNECTING_PERIOD_MS);
    };
    tick();
  }

  stopLoop(): void {
    this.looping = false;
    if (this.loop !== null) this.timers.clearTimeout(this.loop);
    this.loop = null;
  }

  play(cue: OneShotCue): void {
    this.emit(cue === 'reconnected' ? RECONNECTED_PATTERN : FAILED_PATTERN);
  }

  private emit(pattern: readonly Tone[]): void {
    const ctx = this.context();
    if (!ctx) return;
    try {
      playPattern(ctx, pattern);
    } catch {
      // a cue must never break the call
    }
  }
}
