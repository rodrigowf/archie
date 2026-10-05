/**
 * RMS level meters (spec 13 §2.5: `AnalyserNode`, ~15 fps, 10 fps on low-end; inv02 F-26 polled
 * mic and remote RMS every 66 ms). The orb polls `level()`; nothing here runs on a timer.
 */
import type { AnalyserNodeLike, AudioContextLike, AudioNodeLike } from './context';

const buffers = new WeakMap<AnalyserNodeLike, Uint8Array>();

/** RMS of the analyser's current time-domain window, 0..1. */
export function analyserLevel(a: AnalyserNodeLike): number {
  let data = buffers.get(a);
  if (!data || data.length !== a.frequencyBinCount) {
    data = new Uint8Array(a.frequencyBinCount);
    buffers.set(a, data);
  }
  a.getByteTimeDomainData(data);
  let sum = 0;
  for (let i = 0; i < data.length; i++) {
    const v = ((data[i] as number) - 128) / 128;
    sum += v * v;
  }
  return data.length ? Math.sqrt(sum / data.length) : 0;
}

export interface LevelMeter {
  level(): number;
  close(): void;
}

export const SILENT_METER: LevelMeter = { level: () => 0, close: () => undefined };

/** A meter on a MediaStream (mic or remote WebRTC stream). Not connected to the speakers. */
export function streamMeter(ctx: AudioContextLike | null, stream: MediaStream | null): LevelMeter {
  if (!ctx || !stream) return SILENT_METER;
  let source: AudioNodeLike;
  let analyser: AnalyserNodeLike;
  try {
    source = ctx.createMediaStreamSource(stream);
    analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);
  } catch {
    return SILENT_METER;
  }
  return {
    level: () => analyserLevel(analyser),
    close: () => {
      try {
        source.disconnect();
        analyser.disconnect();
      } catch {
        // fine
      }
    },
  };
}

/** Map RMS (speech sits around 0.02–0.3) to a 0..1 visual level. */
export function visualLevel(rms: number): number {
  if (!(rms > 0)) return 0;
  return Math.min(1, Math.sqrt(rms * 4));
}
