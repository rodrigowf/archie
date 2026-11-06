/**
 * Live dictation (`WS /api/transcribe/stream`): the handshake, the two provider shapes rendered
 * as one stream, and — the part that matters most — never losing words the user already said.
 */
import { describe, expect, it, vi } from 'vitest';
import { dictationErrorMessage, startDictation, type DictationEnv, type WebSocketLike } from '../dictation';

class FakeSocket implements WebSocketLike {
  static last: FakeSocket | null = null;
  readyState = 1;
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  sent: Record<string, unknown>[] = [];
  closed = false;

  constructor(readonly url: string) {
    FakeSocket.last = this;
  }
  /** The socket the code under test opened; fails loudly if it never opened one. */
  static opened(): FakeSocket {
    if (!FakeSocket.last) throw new Error('startDictation opened no socket');
    return FakeSocket.last;
  }
  send(data: string): void {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }
  close(): void {
    this.closed = true;
    this.readyState = 3;
  }
  /** Deliver a server frame. */
  emit(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
  types(): string[] {
    return this.sent.map((m) => String(m.type));
  }
}

function env(overrides: Partial<DictationEnv> = {}): { env: DictationEnv; track: { stop: ReturnType<typeof vi.fn> }; chunk: () => void; captureStopped: () => boolean } {
  const track = { stop: vi.fn() };
  let onChunk: ((b: Uint8Array) => void) | null = null;
  let stopped = false;
  return {
    track,
    chunk: () => onChunk?.(new Uint8Array([1, 2, 3, 4])),
    captureStopped: () => stopped,
    env: {
      socket: (url) => new FakeSocket(url),
      media: { getUserMedia: () => Promise.resolve({ getTracks: () => [track] } as unknown as MediaStream) },
      audioContext: () => ({ createMediaStreamSource: () => ({ connect() {}, disconnect() {} }) }) as never,
      capture: (opts: { onChunk: (b: Uint8Array) => void }) => {
        onChunk = opts.onChunk;
        return Promise.resolve({
          stop() {
            stopped = true;
          },
        });
      },
      meter: () => ({ level: () => 0.5, close: () => undefined }),
      now: () => 1000,
      ...overrides,
    } as DictationEnv,
  };
}

/** Open a session, answering the handshake with `ready`. */
async function open(e: DictationEnv, opts: Partial<Parameters<typeof startDictation>[0]> = {}) {
  const onText = vi.fn();
  const onError = vi.fn();
  const promise = startDictation({ onText, onError, env: e, ...opts });
  await Promise.resolve();
  const ws = FakeSocket.opened();
  ws.onopen?.();
  ws.emit({ type: 'ready', provider: 'openai', model: 'gpt-live-transcribe', sample_rate: 24000 });
  const session = await promise;
  return { session, ws, onText, onError };
}

describe('startDictation', () => {
  it('sends start, then streams PCM once the server names the rate', async () => {
    const e = env();
    const { ws, session } = await open(e.env);
    expect(ws.url).toContain('/api/transcribe/stream');
    expect(ws.sent[0]).toEqual({ type: 'start' });
    expect(session.startedAt).toBe(1000);

    e.chunk();
    const audio = ws.sent[1];
    expect(audio?.type).toBe('audio');
    expect(typeof audio?.audio).toBe('string');
  });

  it('passes a language through when one is configured', async () => {
    const e = env();
    const { ws } = await open(e.env, { language: 'pt' });
    expect(ws.sent[0]).toEqual({ type: 'start', language: 'pt' });
  });

  it('renders append + partial as one growing text (both provider shapes)', async () => {
    const e = env();
    const { ws, onText } = await open(e.env);

    // OpenAI's shape: append-only, no partial.
    ws.emit({ type: 'text', append: ' Hey', partial: '' });
    ws.emit({ type: 'text', append: ' buddy', partial: '' });
    expect(onText).toHaveBeenLastCalledWith({ committed: ' Hey buddy', partial: '' });

    // ElevenLabs' shape: a volatile tail that is replaced, then committed.
    ws.emit({ type: 'text', append: '', partial: 'we are' });
    expect(onText).toHaveBeenLastCalledWith({ committed: ' Hey buddy', partial: 'we are' });
    ws.emit({ type: 'text', append: '', partial: "we're back" });
    expect(onText).toHaveBeenLastCalledWith({ committed: ' Hey buddy', partial: "we're back" });
    ws.emit({ type: 'text', append: " we're back.", partial: '' });
    expect(onText).toHaveBeenLastCalledWith({ committed: " Hey buddy we're back.", partial: '' });
  });

  it('stop releases the microphone at once and resolves with the final text', async () => {
    const e = env();
    const { ws, session } = await open(e.env);
    ws.emit({ type: 'text', append: 'Add a fourth mode', partial: '' });

    const done = session.stop();
    // The recording is over even though the last words are still coming back.
    expect(e.captureStopped()).toBe(true);
    expect(e.track.stop).toHaveBeenCalled();
    expect(ws.types()).toContain('stop');

    ws.emit({ type: 'final', text: 'Add a fourth mode to that function.' });
    await expect(done).resolves.toBe('Add a fourth mode to that function.');
    expect(ws.closed).toBe(true);
  });

  it('keeps what was already transcribed when the provider fails', async () => {
    const e = env();
    const { ws, session, onError } = await open(e.env);
    ws.emit({ type: 'text', append: 'the half I said', partial: '' });
    ws.emit({ type: 'error', error: 'provider_closed', detail: 'upstream went away' });

    expect(onError).toHaveBeenCalledWith('The transcription service dropped the connection');
    await expect(session.stop()).resolves.toBe('the half I said');
    expect(e.track.stop).toHaveBeenCalled();
  });

  it('keeps the words when the socket closes without a final', async () => {
    const e = env();
    const { ws, session } = await open(e.env);
    ws.emit({ type: 'text', append: 'half a sentence', partial: ' and a tail' });
    ws.onclose?.();
    await expect(session.stop()).resolves.toBe('half a sentence and a tail');
  });

  it('cancel discards the text and tells the server', async () => {
    const e = env();
    const { ws, session } = await open(e.env);
    ws.emit({ type: 'text', append: 'forget this', partial: '' });
    session.cancel();
    expect(ws.types()).toContain('cancel');
    expect(ws.closed).toBe(true);
    expect(e.track.stop).toHaveBeenCalled();
    await expect(session.stop()).resolves.toBe('');
  });

  it('reports an error frame during the handshake instead of opening the mic', async () => {
    const e = env();
    const getUserMedia = vi.fn();
    const promise = startDictation({ onText: vi.fn(), onError: vi.fn(), env: { ...e.env, media: { getUserMedia } } });
    await Promise.resolve();
    const ws = FakeSocket.opened();
    ws.onopen?.();
    ws.emit({ type: 'error', error: 'no_api_key', detail: 'OPENAI_API_KEY is not configured on the backend' });
    await expect(promise).rejects.toThrow('OPENAI_API_KEY is not configured on the backend');
    expect(getUserMedia).not.toHaveBeenCalled();
    expect(ws.closed).toBe(true);
  });

  it('fails loudly if the socket dies while the microphone is opening', async () => {
    const e = env();
    const promise = startDictation({ onText: vi.fn(), onError: vi.fn(), env: e.env });
    await Promise.resolve();
    const ws = FakeSocket.opened();
    ws.onopen?.();
    ws.emit({ type: 'ready', sample_rate: 16000 });
    // The handshake has resolved; the mic is still opening when the socket goes.
    ws.readyState = 3;
    await expect(promise).rejects.toThrow('closed the connection');
    expect(e.track.stop).toHaveBeenCalled();
  });

  it('releases the microphone if the socket dies right after it opened', async () => {
    const e = env();
    const { ws } = await open(e.env);
    ws.onerror?.();
    expect(e.track.stop).toHaveBeenCalled();
    expect(e.captureStopped()).toBe(true);
  });

  it('closes the socket when the microphone is refused', async () => {
    const e = env({ media: { getUserMedia: () => Promise.reject(new DOMException('no', 'NotAllowedError')) } });
    const promise = startDictation({ onText: vi.fn(), onError: vi.fn(), env: e.env });
    await Promise.resolve();
    const ws = FakeSocket.opened();
    ws.onopen?.();
    ws.emit({ type: 'ready', sample_rate: 16000 });
    await expect(promise).rejects.toThrow();
    expect(ws.closed).toBe(true);
  });
});

describe('dictationErrorMessage', () => {
  it('turns codes into something a person can act on', () => {
    expect(dictationErrorMessage('busy')).toContain('maximum number');
    expect(dictationErrorMessage('idle_timeout')).toContain('no audio');
    expect(dictationErrorMessage('no_api_key', 'OPENAI_API_KEY is not configured')).toBe('OPENAI_API_KEY is not configured');
    expect(dictationErrorMessage('something_new', 'verbatim detail')).toBe('verbatim detail');
    expect(dictationErrorMessage('something_new')).toBe('Dictation failed');
  });
});
