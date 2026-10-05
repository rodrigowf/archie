/**
 * Push-to-record audio messages (spec 12 §6.16, spec 13 §2.5; inv02 F-17
 * `frontend/src/hooks/useAudioRecorder.ts:71-198`): microphone with echo cancellation and noise
 * suppression, at most 60 s, then base64 + format for `send_audio` on the orchestrator socket.
 *
 * Two paths behind one interface, chosen by runtime capability (not by build):
 * - `MediaRecorder` (main): the first supported of webm/opus → webm → ogg/opus → mp4.
 * - WAV fallback (Safari 12 / `?caps=compat`): PCM from a `ScriptProcessorNode` (4096 frames),
 *   resampled to 16 kHz and encoded to WAV in JS.
 *
 * iOS rule: the AudioContext is created and resumed synchronously inside the tap handler (before
 * any await), or it stays suspended.
 */
import { encodeWav, resample, WAV_RATE } from './wav';

export type AudioFormat = 'webm' | 'ogg' | 'mp4' | 'wav';

export interface AudioMessage {
  readonly base64: string;
  readonly format: AudioFormat;
  readonly durationMs: number;
}

export interface Recording {
  readonly path: 'media-recorder' | 'wav';
  readonly startedAt: number;
  /** Stop and encode. */
  stop(): Promise<AudioMessage>;
  /** Discard (mic released, nothing encoded). */
  cancel(): void;
}

export const MAX_RECORDING_MS = 60_000;
export const MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];

interface MediaRecorderLike {
  readonly mimeType?: string;
  state: string;
  ondataavailable: ((e: { data: Blob }) => void) | null;
  onstop: (() => void) | null;
  start(timeslice?: number): void;
  stop(): void;
}
interface MediaRecorderCtor {
  new (stream: MediaStream, opts?: { mimeType?: string }): MediaRecorderLike;
  isTypeSupported?(mime: string): boolean;
}
interface ProcessorLike {
  onaudioprocess: ((e: { inputBuffer: { getChannelData(ch: number): Float32Array } }) => void) | null;
  connect(dest: unknown): void;
  disconnect(): void;
}
interface AudioContextLike {
  readonly sampleRate: number;
  readonly destination: unknown;
  state?: string;
  resume?(): Promise<void>;
  close?(): Promise<void>;
  createMediaStreamSource(stream: MediaStream): { connect(dest: unknown): void; disconnect(): void };
  createScriptProcessor(size: number, inCh: number, outCh: number): ProcessorLike;
}

export interface RecorderEnv {
  getUserMedia(c: MediaStreamConstraints): Promise<MediaStream>;
  MediaRecorder: MediaRecorderCtor | null;
  AudioContext: (new () => AudioContextLike) | null;
  now(): number;
}

type AudioWindow = Window & typeof globalThis & { webkitAudioContext?: new () => AudioContextLike; MediaRecorder?: MediaRecorderCtor };

export function browserRecorderEnv(): RecorderEnv {
  const w = window as AudioWindow;
  const md = navigator.mediaDevices as MediaDevices | undefined;
  return {
    getUserMedia: (c) => (md ? md.getUserMedia(c) : Promise.reject(new Error('No microphone access in this browser'))),
    MediaRecorder: typeof w.MediaRecorder === 'function' ? w.MediaRecorder : null,
    AudioContext: (w.AudioContext as unknown as (new () => AudioContextLike) | undefined) ?? w.webkitAudioContext ?? null,
    now: () => Date.now(),
  };
}

export function formatForMime(mime: string): AudioFormat {
  const m = mime.toLowerCase();
  if (m.indexOf('ogg') >= 0) return 'ogg';
  if (m.indexOf('mp4') >= 0 || m.indexOf('aac') >= 0 || m.indexOf('m4a') >= 0) return 'mp4';
  if (m.indexOf('wav') >= 0) return 'wav';
  return 'webm';
}

export function pickMime(ctor: MediaRecorderCtor): string | undefined {
  if (typeof ctor.isTypeSupported !== 'function') return undefined;
  for (const m of MIME_CANDIDATES) if (ctor.isTypeSupported(m)) return m;
  return undefined;
}

/** Base64 of a Blob without the data-URL prefix (FileReader: Safari 12 and jsdom). */
export function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onerror = () => {
      reject(r.error ?? new Error('Could not read the recording'));
    };
    r.onload = () => {
      const s = typeof r.result === 'string' ? r.result : '';
      const i = s.indexOf(',');
      resolve(i >= 0 ? s.slice(i + 1) : s);
    };
    r.readAsDataURL(blob);
  });
}

/** A human message for a microphone failure (inv02 F-17 only logged these). */
export function micErrorMessage(err: unknown): string {
  const name = (err as { name?: string } | null)?.name ?? '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError') return 'Microphone access was denied';
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'No microphone found';
  if (name === 'NotReadableError') return 'The microphone is busy in another app';
  const msg = (err as { message?: string } | null)?.message;
  return msg ? `Could not record: ${msg}` : 'Could not record';
}

const CONSTRAINTS: MediaStreamConstraints = { audio: { echoCancellation: true, noiseSuppression: true } };

function releaseStream(stream: MediaStream): void {
  for (const t of stream.getTracks()) t.stop();
}

export interface StartOptions {
  /** Use MediaRecorder when present (false = force the WAV path, e.g. `?caps=compat`). */
  readonly preferMediaRecorder: boolean;
  readonly env?: RecorderEnv;
  readonly maxMs?: number;
  /** The 60 s cap was reached; the UI stops and sends. */
  readonly onLimit?: () => void;
}

/** Start recording. Call it from the tap handler itself (iOS AudioContext rule). */
export function startRecording(o: StartOptions): Promise<Recording> {
  const env = o.env ?? browserRecorderEnv();
  const useMr = o.preferMediaRecorder && !!env.MediaRecorder;
  // created synchronously, inside the gesture
  let ctx: AudioContextLike | null = null;
  if (!useMr) {
    if (!env.AudioContext) return Promise.reject(new Error('Recording is not supported in this browser'));
    ctx = new env.AudioContext();
    void ctx.resume?.().catch(() => undefined);
  }
  const limitMs = o.maxMs ?? MAX_RECORDING_MS;
  return env.getUserMedia(CONSTRAINTS).then(
    (stream) => {
      const startedAt = env.now();
      const limit = setTimeout(() => o.onLimit?.(), limitMs);
      const done = (): void => {
        clearTimeout(limit);
        releaseStream(stream);
      };
      if (useMr && env.MediaRecorder) return mediaRecorderPath(env.MediaRecorder, stream, startedAt, env, done);
      return wavPath(ctx as AudioContextLike, stream, startedAt, env, done);
    },
    (err: unknown) => {
      void ctx?.close?.().catch(() => undefined);
      throw err;
    },
  );
}

function mediaRecorderPath(Ctor: MediaRecorderCtor, stream: MediaStream, startedAt: number, env: RecorderEnv, done: () => void): Recording {
  const mime = pickMime(Ctor);
  const rec = mime ? new Ctor(stream, { mimeType: mime }) : new Ctor(stream);
  const chunks: Blob[] = [];
  rec.ondataavailable = (e) => {
    if (e.data && e.data.size > 0) chunks.push(e.data);
  };
  rec.start(250);
  let cancelled = false;
  return {
    path: 'media-recorder',
    startedAt,
    stop: () =>
      new Promise<AudioMessage>((resolve, reject) => {
        const finish = (): void => {
          done();
          const type = rec.mimeType || mime || 'audio/webm';
          const blob = new Blob(chunks, { type });
          blobToBase64(blob).then((base64) => {
            resolve({ base64, format: formatForMime(type), durationMs: env.now() - startedAt });
          }, reject);
        };
        if (rec.state === 'inactive') {
          finish();
          return;
        }
        rec.onstop = finish;
        rec.stop();
      }),
    cancel: () => {
      if (cancelled) return;
      cancelled = true;
      rec.onstop = null;
      if (rec.state !== 'inactive') rec.stop();
      done();
    },
  };
}

function wavPath(ctx: AudioContextLike, stream: MediaStream, startedAt: number, env: RecorderEnv, done: () => void): Recording {
  const source = ctx.createMediaStreamSource(stream);
  const proc = ctx.createScriptProcessor(4096, 1, 1);
  const chunks: Float32Array[] = [];
  proc.onaudioprocess = (e) => {
    chunks.push(new Float32Array(e.inputBuffer.getChannelData(0))); // copy: the buffer is reused
  };
  source.connect(proc);
  proc.connect(ctx.destination); // Safari only runs a connected processor; its output is silence
  const teardown = (): void => {
    proc.onaudioprocess = null;
    try {
      source.disconnect();
      proc.disconnect();
    } catch {
      // already disconnected
    }
    void ctx.close?.().catch(() => undefined);
    done();
  };
  return {
    path: 'wav',
    startedAt,
    stop: () => {
      const rate = ctx.sampleRate;
      teardown();
      const wav = encodeWav(resample(chunks, rate, WAV_RATE), WAV_RATE);
      return blobToBase64(new Blob([wav], { type: 'audio/wav' })).then((base64) => ({ base64, format: 'wav' as const, durationMs: env.now() - startedAt }));
    },
    cancel: teardown,
  };
}
