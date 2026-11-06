/**
 * Live dictation: speak, and punctuated text streams into the composer field.
 *
 * The mic goes to the **backend** (`WS /api/transcribe/stream`), not to a transcription vendor:
 * the server owns the API keys and picks the model from Settings, so this module never knows
 * which provider is doing the work. It feeds PCM16 up and renders `committed + partial` down —
 * see `backend/transcription/base.py` for why those two are separate (OpenAI appends final
 * word-deltas; ElevenLabs rewrites a volatile tail until a segment closes).
 *
 * Capture is the voice stack's, unchanged: `startPcmCapture` already resamples to a target rate
 * and emits base64-ready PCM16 every 100 ms, and `streamMeter` already gives the level. The
 * server names the rate it wants in `ready`, so the worklet is started only once that arrives.
 *
 * Not available where `AudioWorklet` is missing (Safari 12 / `?caps=compat`): there the composer
 * records a WAV and posts it to `/api/transcribe` instead, which is the same feature without the
 * live text.
 *
 * **Inside `@/voice`, and reached only as `@/voice/dictation`** (spec 13 §3.9, §5.4). It uses the
 * same microphone stack as a voice call, so this is where it belongs — and importing it through
 * the package barrel would drag that stack into the initial bundle, because the voice UI imports
 * the barrel eagerly. Its own entry keeps it in the chunk the composer fetches on the first press.
 */
import { wsUrl } from '@/services';
import { sharedAudioContext, type AudioContextLike } from './audio/context';
import { startPcmCapture, toBase64Chunk, type PcmCapture } from './audio/capture/worklet';
import { streamMeter, visualLevel, type LevelMeter } from './audio/meters';
import { defaultMediaDevices, micConstraints, stopStream, type MediaDevicesLike } from './transports/shared';

/** What the field should show: everything banked, plus the tail still being revised. */
export interface DictationText {
  readonly committed: string;
  readonly partial: string;
}

export interface DictationSession {
  readonly startedAt: number;
  /** 0–1, for the level meter. */
  level(): number;
  /** Finish and resolve with the final text (the mic is released immediately). */
  stop(): Promise<string>;
  /** Throw the dictation away. */
  cancel(): void;
}

export interface DictationOptions {
  /** "" lets the server fall back to the configured language. */
  readonly language?: string;
  /** Called on every update; `committed + partial` is what the field shows. */
  readonly onText: (text: DictationText) => void;
  /** Terminal: the dictation is over and `stop()` will not produce more. */
  readonly onError: (message: string) => void;
  readonly env?: DictationEnv;
}

/** Everything this module touches outside itself, so tests can supply fakes. */
export interface DictationEnv {
  readonly socket: (url: string) => WebSocketLike;
  readonly media: MediaDevicesLike | null;
  readonly audioContext: () => AudioContextLike | null;
  readonly capture: typeof startPcmCapture;
  readonly meter: typeof streamMeter;
  readonly now: () => number;
}

export interface WebSocketLike {
  readyState: number;
  onopen: (() => void) | null;
  onmessage: ((e: { data: unknown }) => void) | null;
  onerror: (() => void) | null;
  onclose: (() => void) | null;
  send(data: string): void;
  close(): void;
}

export const DICTATION_PATH = '/api/transcribe/stream';
/** The handshake and the final flush both have to finish inside this. */
const READY_TIMEOUT_MS = 15_000;
const FINAL_TIMEOUT_MS = 30_000;
const OPEN = 1;

export function browserDictationEnv(): DictationEnv {
  return {
    socket: (url) => new WebSocket(url) as unknown as WebSocketLike,
    media: defaultMediaDevices(),
    audioContext: sharedAudioContext,
    capture: startPcmCapture,
    meter: streamMeter,
    now: () => Date.now(),
  };
}

interface ServerFrame {
  type?: string;
  append?: string;
  partial?: string;
  text?: string;
  error?: string;
  detail?: string;
  sample_rate?: number;
}

/** Human copy for the error codes the socket can report. */
export function dictationErrorMessage(code: string, detail?: string): string {
  if (code === 'no_api_key') return detail || 'No transcription key is configured on the server';
  if (code === 'busy') return 'The server is already running the maximum number of transcriptions';
  if (code === 'too_long') return detail || 'Dictation reached its time limit';
  if (code === 'idle_timeout') return 'Dictation stopped: no audio was arriving';
  if (code === 'connect_failed') return 'Could not reach the transcription service';
  if (code === 'provider_closed' || code === 'read_failed') return 'The transcription service dropped the connection';
  return detail || 'Dictation failed';
}

export async function startDictation(opts: DictationOptions): Promise<DictationSession> {
  const env = opts.env ?? browserDictationEnv();
  const ws = env.socket(wsUrl(DICTATION_PATH));

  let committed = '';
  let partial = '';
  let capture: PcmCapture | null = null;
  let stream: MediaStream | null = null;
  let meter: LevelMeter | null = null;
  let done = false;
  let finalResolve: ((text: string) => void) | null = null;

  const text = (): string => (committed + partial).trim();

  const teardown = (): void => {
    capture?.stop();
    capture = null;
    meter?.close();
    meter = null;
    stopStream(stream);
    stream = null;
  };

  const finish = (value: string): void => {
    if (done) return;
    done = true;
    teardown();
    try {
      ws.close();
    } catch {
      // already closing
    }
    finalResolve?.(value);
    finalResolve = null;
  };

  const fail = (message: string): void => {
    const already = done;
    finish(text());
    if (!already) opts.onError(message);
  };

  // --- handshake: the server names the provider's sample rate -------------
  const ready = await new Promise<ServerFrame>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error('The transcription service did not answer'));
    }, READY_TIMEOUT_MS);
    const settle = (fn: () => void): void => {
      clearTimeout(timer);
      fn();
    };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'start', ...(opts.language ? { language: opts.language } : {}) }));
    };
    ws.onerror = () => {
      settle(() => {
        reject(new Error('Could not reach the transcription service'));
      });
    };
    ws.onclose = () => {
      settle(() => {
        reject(new Error('The transcription service closed the connection'));
      });
    };
    ws.onmessage = (e) => {
      const frame = parseFrame(e.data);
      if (!frame) return;
      if (frame.type === 'ready') settle(() => { resolve(frame); });
      else if (frame.type === 'error') settle(() => { reject(new Error(dictationErrorMessage(frame.error ?? '', frame.detail))); });
    };
  }).catch((err: unknown) => {
    try {
      ws.close();
    } catch {
      // nothing to close
    }
    throw err;
  });

  // --- the mic ------------------------------------------------------------
  const rate = typeof ready.sample_rate === 'number' ? ready.sample_rate : 16000;
  try {
    if (!env.media) throw new Error('No microphone access in this browser');
    stream = await env.media.getUserMedia(micConstraints(rate));
    const ctx = env.audioContext();
    if (!ctx) throw new Error('Web Audio is not available');
    meter = env.meter(ctx, stream);
    capture = await env.capture({
      ctx,
      source: ctx.createMediaStreamSource(stream),
      targetSampleRate: rate,
      onChunk: (bytes) => {
        if (ws.readyState === OPEN) ws.send(JSON.stringify({ type: 'audio', audio: toBase64Chunk(bytes) }));
      },
    });
  } catch (err) {
    teardown();
    try {
      ws.close();
    } catch {
      // nothing to close
    }
    throw err;
  }

  // Opening the microphone takes a moment, and the handshake's handlers are still the ones
  // attached: a socket that died in that window would leave us capturing into nothing.
  if (ws.readyState !== OPEN) {
    teardown();
    throw new Error('The transcription service closed the connection');
  }

  // --- running ------------------------------------------------------------
  ws.onerror = () => {
    fail('The transcription connection failed');
  };
  ws.onclose = () => {
    // A close after `final` is the normal end; before it, we keep the words.
    finish(text());
  };
  ws.onmessage = (e) => {
    const frame = parseFrame(e.data);
    if (!frame) return;
    if (frame.type === 'text') {
      committed += frame.append ?? '';
      partial = frame.partial ?? '';
      opts.onText({ committed, partial });
    } else if (frame.type === 'final') {
      committed = typeof frame.text === 'string' ? frame.text : text();
      partial = '';
      opts.onText({ committed, partial });
      finish(committed.trim());
    } else if (frame.type === 'error') {
      fail(dictationErrorMessage(frame.error ?? '', frame.detail));
    }
  };

  const startedAt = env.now();
  return {
    startedAt,
    level: () => (meter ? visualLevel(meter.level()) : 0),
    stop() {
      if (done) return Promise.resolve(text());
      // Release the mic at once — the recording is over even though the last
      // words are still coming back.
      capture?.stop();
      capture = null;
      meter?.close();
      meter = null;
      stopStream(stream);
      stream = null;
      if (ws.readyState === OPEN) ws.send(JSON.stringify({ type: 'stop' }));
      return new Promise<string>((resolve) => {
        finalResolve = resolve;
        setTimeout(() => {
          finish(text());
        }, FINAL_TIMEOUT_MS);
      });
    },
    cancel() {
      if (done) return;
      if (ws.readyState === OPEN) {
        try {
          ws.send(JSON.stringify({ type: 'cancel' }));
        } catch {
          // closing anyway
        }
      }
      // Forget the words too, so a later `stop()` cannot resurrect what was discarded.
      committed = '';
      partial = '';
      finish('');
    },
  };
}

function parseFrame(data: unknown): ServerFrame | null {
  if (typeof data !== 'string') return null;
  try {
    const parsed: unknown = JSON.parse(data);
    return parsed && typeof parsed === 'object' ? (parsed as ServerFrame) : null;
  } catch {
    return null;
  }
}
