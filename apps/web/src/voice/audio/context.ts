/**
 * The shared AudioContext (spec 13 §2.5): `AudioContext ‖ webkitAudioContext`, created and
 * resumed inside the user's tap (iOS autoplay rule: a context made outside a gesture stays
 * suspended). Playback, meters and P-2 cues all use this one context, so a cue can sound during
 * a network drop without a new gesture.
 *
 * Also keeps one hidden `<audio playsinline autoplay>` element, "unlocked" in the same tap, for
 * the WebRTC remote stream.
 */

/** The subset of the Web Audio API the engine uses (also implemented by the test fakes). */
export interface AudioParamLike {
  value: number;
  setValueAtTime?(v: number, t: number): unknown;
  linearRampToValueAtTime?(v: number, t: number): unknown;
}
export interface AudioNodeLike {
  connect(dest: unknown): unknown;
  disconnect(): void;
}
export interface GainNodeLike extends AudioNodeLike {
  readonly gain: AudioParamLike;
}
export interface AnalyserNodeLike extends AudioNodeLike {
  fftSize: number;
  readonly frequencyBinCount: number;
  getByteTimeDomainData(arr: Uint8Array): void;
}
export interface AudioBufferLike {
  readonly duration: number;
  getChannelData(ch: number): Float32Array;
}
export interface BufferSourceLike extends AudioNodeLike {
  buffer: AudioBufferLike | null;
  onended: (() => void) | null;
  start(when?: number): void;
  stop(when?: number): void;
}
export interface OscillatorLike extends AudioNodeLike {
  type: string;
  readonly frequency: AudioParamLike;
  start(when?: number): void;
  stop(when?: number): void;
}
export interface AudioContextLike {
  readonly currentTime: number;
  readonly sampleRate: number;
  readonly destination: unknown;
  state?: string;
  resume?(): Promise<void>;
  close?(): Promise<void>;
  createGain(): GainNodeLike;
  createAnalyser(): AnalyserNodeLike;
  createBuffer(channels: number, length: number, sampleRate: number): AudioBufferLike;
  createBufferSource(): BufferSourceLike;
  createOscillator(): OscillatorLike;
  createMediaStreamSource(stream: MediaStream): AudioNodeLike;
  audioWorklet?: { addModule(url: string): Promise<void> };
}

type Ctor = new () => AudioContextLike;

export function audioContextCtor(win: unknown = typeof window !== 'undefined' ? window : undefined): Ctor | null {
  const w = win as { AudioContext?: Ctor; webkitAudioContext?: Ctor } | undefined;
  return w?.AudioContext ?? w?.webkitAudioContext ?? null;
}

let shared: AudioContextLike | null = null;
let element: HTMLAudioElement | null = null;

/** The shared context, created on first use (null where Web Audio is missing). */
export function sharedAudioContext(): AudioContextLike | null {
  if (shared && shared.state !== 'closed') return shared;
  const C = audioContextCtor();
  if (!C) return null;
  try {
    shared = new C();
  } catch {
    shared = null;
  }
  return shared;
}

/** For tests. */
export function setSharedAudioContext(ctx: AudioContextLike | null): void {
  shared = ctx;
}

/** The hidden element that plays the WebRTC remote stream. */
export function sharedAudioElement(): HTMLAudioElement | null {
  if (typeof document === 'undefined') return null;
  if (!element) {
    element = document.createElement('audio');
    element.autoplay = true;
    element.setAttribute('playsinline', '');
    element.setAttribute('aria-hidden', 'true');
    element.style.display = 'none';
    document.body.appendChild(element);
  }
  return element;
}

/**
 * Call synchronously from the tap that starts voice (before any await): creates and resumes the
 * shared context, plays one silent sample (the iOS 12 unlock), and primes the audio element.
 */
export function unlockAudio(): void {
  const ctx = sharedAudioContext();
  if (ctx) {
    try {
      void ctx.resume?.().catch(() => undefined);
      const buf = ctx.createBuffer(1, 1, 22050);
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(ctx.destination);
      src.start(0);
    } catch {
      // best effort
    }
  }
  const el = sharedAudioElement();
  if (el) {
    try {
      const p = el.play() as Promise<void> | undefined;
      if (p && typeof p.catch === 'function') p.catch(() => undefined);
    } catch {
      // nothing to play yet; the gesture still counts on iOS
    }
  }
}
