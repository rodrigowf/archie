/**
 * PCM capture through the AudioWorklet (spec 13 §2.5 `PcmCaptureSource`; inv02 F-26
 * `frontend/src/voice/transports/websocket.ts:37-73`). The worklet (`public-main/
 * pcm-capture-worklet.js`) resamples linearly to the target rate and posts PCM16 LE chunks of
 * `chunkMs`; base64 happens here (the worklet scope has no `btoa`).
 *
 * Fixes W-2 / inv02 F-30: one constant for the processor name the worklet registers
 * (`'pcm-capture'`, not `'pcm-capture-processor'`), and every capture node is connected through
 * a gain-0 sink to the destination so the worklet actually runs.
 *
 * There is no ScriptProcessor fallback: P-8 makes the WS relay unavailable where AudioWorklet is
 * missing (iOS 12).
 */
import { bytesToBase64 } from '../base64';
import type { AudioContextLike, AudioNodeLike } from '../context';

/** The name `registerProcessor` uses in `pcm-capture-worklet.js` (W-2). */
export const PCM_CAPTURE_PROCESSOR = 'pcm-capture';
export const PCM_CHUNK_MS = 100;

/** Served from the main build's public dir, under the app's base path. */
export function workletUrl(base: string = import.meta.env.BASE_URL): string {
  return `${base.replace(/\/$/, '')}/pcm-capture-worklet.js`;
}

export interface WorkletNodeLike extends AudioNodeLike {
  readonly port: { onmessage: ((e: { data: unknown }) => void) | null; close?(): void };
}
export type WorkletNodeCtor = new (
  ctx: AudioContextLike,
  name: string,
  opts: { processorOptions: { targetSampleRate: number; chunkMs: number } },
) => WorkletNodeLike;

export function workletNodeCtor(win: unknown = typeof window !== 'undefined' ? window : undefined): WorkletNodeCtor | null {
  return (win as { AudioWorkletNode?: WorkletNodeCtor } | undefined)?.AudioWorkletNode ?? null;
}

const loaded = new WeakMap<AudioContextLike, Promise<void>>();

/** `addModule` once per context (re-registering the processor would throw in the worklet). */
export function ensureWorklet(ctx: AudioContextLike, url: string = workletUrl()): Promise<void> {
  let p = loaded.get(ctx);
  if (!p) {
    const wl = ctx.audioWorklet;
    if (!wl) return Promise.reject(new Error('AudioWorklet is not available'));
    p = wl.addModule(url);
    loaded.set(ctx, p);
    p.catch(() => loaded.delete(ctx));
  }
  return p;
}

export interface PcmCapture {
  stop(): void;
}

/**
 * Capture `source` (a MediaStream source node) at `targetSampleRate`, calling `onChunk` with raw
 * PCM16 bytes every `chunkMs`.
 */
export async function startPcmCapture(opts: {
  ctx: AudioContextLike;
  source: AudioNodeLike;
  targetSampleRate: number;
  onChunk: (bytes: Uint8Array) => void;
  chunkMs?: number;
  WorkletNode?: WorkletNodeCtor | null;
  url?: string;
}): Promise<PcmCapture> {
  const Node = opts.WorkletNode ?? workletNodeCtor();
  if (!Node) throw new Error('AudioWorklet is not available');
  await ensureWorklet(opts.ctx, opts.url);
  const node = new Node(opts.ctx, PCM_CAPTURE_PROCESSOR, {
    processorOptions: { targetSampleRate: opts.targetSampleRate, chunkMs: opts.chunkMs ?? PCM_CHUNK_MS },
  });
  // attach before wiring so the first chunks are not lost (inv02 F-26)
  node.port.onmessage = (e) => {
    const m = e.data as { type?: string; buffer?: unknown } | null;
    if (m && m.type === 'pcm' && m.buffer instanceof ArrayBuffer) opts.onChunk(new Uint8Array(m.buffer));
  };
  const sink = opts.ctx.createGain();
  sink.gain.value = 0; // runs on the graph without feeding the mic back to the speakers
  opts.source.connect(node);
  node.connect(sink);
  sink.connect(opts.ctx.destination);
  return {
    stop() {
      node.port.onmessage = null;
      try {
        opts.source.disconnect();
      } catch {
        // shared source already gone
      }
      try {
        node.disconnect();
        sink.disconnect();
      } catch {
        // fine
      }
    },
  };
}

/** Base64 variant for `voice_audio_in` / `voice_recording_chunk`. */
export function toBase64Chunk(bytes: Uint8Array): string {
  return bytesToBase64(bytes);
}
