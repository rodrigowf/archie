/**
 * Audio messages (spec 12 §6.16, spec 13 §2.5): MediaRecorder path, WAV fallback (Safari 12 /
 * `?caps=compat`), 60 s cap, cancel releases the microphone.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { blobToBase64, formatForMime, micErrorMessage, pickMime, startRecording, type RecorderEnv } from '../audio/recorder';
import { encodeWav, resample, WAV_RATE } from '../audio/wav';

afterEach(() => {
  vi.useRealTimers();
});

function fakeStream() {
  const track = { stop: vi.fn() };
  return { stream: { getTracks: () => [track] } as unknown as MediaStream, track };
}

class FakeRecorder {
  static supported = ['audio/ogg;codecs=opus', 'audio/mp4'];
  static last: FakeRecorder | null = null;
  static isTypeSupported(m: string): boolean {
    return FakeRecorder.supported.indexOf(m) >= 0;
  }
  state = 'inactive';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  constructor(
    readonly stream: MediaStream,
    readonly opts?: { mimeType?: string },
  ) {
    FakeRecorder.last = this;
  }
  get mimeType(): string {
    return this.opts?.mimeType ?? '';
  }
  start(): void {
    this.state = 'recording';
  }
  stop(): void {
    this.state = 'inactive';
    this.ondataavailable?.({ data: new Blob(['abc'], { type: this.mimeType }) });
    this.onstop?.();
  }
}

class FakeCtx {
  static last: FakeCtx | null = null;
  sampleRate = 48_000;
  destination = {};
  proc: { onaudioprocess: ((e: unknown) => void) | null; connect: () => void; disconnect: () => void } | null = null;
  closed = false;
  resumed = false;
  constructor() {
    FakeCtx.last = this;
  }
  resume(): Promise<void> {
    this.resumed = true;
    return Promise.resolve();
  }
  close(): Promise<void> {
    this.closed = true;
    return Promise.resolve();
  }
  createMediaStreamSource() {
    return { connect: vi.fn(), disconnect: vi.fn() };
  }
  createScriptProcessor() {
    this.proc = { onaudioprocess: null, connect: vi.fn(), disconnect: vi.fn() };
    return this.proc;
  }
}

function env(over: Partial<RecorderEnv> = {}, stream = fakeStream()): RecorderEnv & { gum: ReturnType<typeof vi.fn> } {
  const gum = vi.fn(() => Promise.resolve(stream.stream));
  return { getUserMedia: gum, MediaRecorder: FakeRecorder as never, AudioContext: FakeCtx as never, now: () => 1000, gum, ...over };
}

describe('MediaRecorder path', () => {
  it('picks the first supported MIME type and maps it to a format', () => {
    expect(pickMime(FakeRecorder as never)).toBe('audio/ogg;codecs=opus');
    expect(formatForMime('audio/webm;codecs=opus')).toBe('webm');
    expect(formatForMime('audio/ogg;codecs=opus')).toBe('ogg');
    expect(formatForMime('audio/mp4')).toBe('mp4');
    expect(formatForMime('audio/wav')).toBe('wav');
  });

  it('records with echo cancellation + noise suppression and returns base64 + format', async () => {
    const s = fakeStream();
    const e = env({}, s);
    const rec = await startRecording({ preferMediaRecorder: true, env: e });
    expect(rec.path).toBe('media-recorder');
    expect(e.gum).toHaveBeenCalledWith({ audio: { echoCancellation: true, noiseSuppression: true } });
    const msg = await rec.stop();
    expect(msg.format).toBe('ogg');
    expect(atob(msg.base64)).toBe('abc');
    expect(s.track.stop).toHaveBeenCalled(); // mic released
  });

  it('cancel discards and releases the microphone', async () => {
    const s = fakeStream();
    const rec = await startRecording({ preferMediaRecorder: true, env: env({}, s) });
    rec.cancel();
    expect(s.track.stop).toHaveBeenCalled();
    expect(FakeRecorder.last?.state).toBe('inactive');
  });

  it('calls onLimit at the 60 s cap', async () => {
    vi.useFakeTimers();
    const onLimit = vi.fn();
    await startRecording({ preferMediaRecorder: true, env: env(), onLimit });
    vi.advanceTimersByTime(59_999);
    expect(onLimit).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onLimit).toHaveBeenCalledTimes(1);
  });

  it('turns permission errors into human messages', () => {
    expect(micErrorMessage({ name: 'NotAllowedError' })).toBe('Microphone access was denied');
    expect(micErrorMessage({ name: 'NotFoundError' })).toBe('No microphone found');
    expect(micErrorMessage(new Error('boom'))).toBe('Could not record: boom');
  });
});

describe('WAV fallback (Safari 12 / ?caps=compat)', () => {
  it('uses ScriptProcessor PCM, resamples to 16 kHz and sends format "wav"', async () => {
    const s = fakeStream();
    const rec = await startRecording({ preferMediaRecorder: false, env: env({}, s) });
    expect(rec.path).toBe('wav');
    const ctx = FakeCtx.last as FakeCtx;
    expect(ctx.resumed).toBe(true); // resumed inside the gesture (iOS)
    const chunk = new Float32Array(4800).fill(0.5); // 0.1 s at 48 kHz
    ctx.proc?.onaudioprocess?.({ inputBuffer: { getChannelData: () => chunk } });
    const msg = await rec.stop();
    expect(msg.format).toBe('wav');
    const bytes = Uint8Array.from(atob(msg.base64), (c) => c.charCodeAt(0));
    expect(String.fromCharCode(...bytes.slice(0, 4))).toBe('RIFF');
    expect(new DataView(bytes.buffer).getUint32(24, true)).toBe(WAV_RATE);
    expect(bytes.length).toBe(44 + 1600 * 2); // 0.1 s at 16 kHz, PCM16
    expect(ctx.closed).toBe(true);
    expect(s.track.stop).toHaveBeenCalled();
  });

  it('falls back to WAV when MediaRecorder is missing even if preferred', async () => {
    const rec = await startRecording({ preferMediaRecorder: true, env: env({ MediaRecorder: null }) });
    expect(rec.path).toBe('wav');
    rec.cancel();
  });

  it('rejects when neither path exists', async () => {
    await expect(startRecording({ preferMediaRecorder: true, env: env({ MediaRecorder: null, AudioContext: null }) })).rejects.toThrow(/not supported/);
  });
});

describe('wav encoder', () => {
  it('resamples linearly and writes a PCM16 mono header', () => {
    const out = resample([new Float32Array([0, 1]), new Float32Array([0, 1])], 32_000, 16_000);
    expect(Array.from(out)).toEqual([0, 0]);
    const wav = new DataView(encodeWav(new Float32Array([1, -1, 0])));
    expect(wav.getUint16(20, true)).toBe(1);
    expect(wav.getUint16(22, true)).toBe(1);
    expect(wav.getUint16(34, true)).toBe(16);
    expect(wav.getInt16(44, true)).toBe(0x7fff);
    expect(wav.getInt16(46, true)).toBe(-0x8000);
  });
  it('blobToBase64 strips the data-URL prefix', async () => {
    expect(await blobToBase64(new Blob(['hi']))).toBe(btoa('hi'));
  });
});
