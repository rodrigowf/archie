/**
 * Dictation without the live text, for browsers with no `AudioWorklet` (Safari 12 / the compat
 * build): record a WAV, then one `POST /api/transcribe` on stop. Same buttons, same result, the
 * text just all arrives at once — which is also what a batch model does on a modern browser, so
 * the composer renders exactly one "flushing" state for both.
 *
 * Here rather than in `@/voice/dictation` because it needs the composer's recorder and **not** the
 * voice capture stack: on the browsers that use this path, `@/voice` is never fetched at all.
 */
import { api } from '@/services';
import type { DictationOptions, DictationSession } from '@/voice/dictation';
import { startRecording } from './recorder';

/**
 * The recorder hands back base64; `POST /api/transcribe` wants the bytes. Decoded here rather
 * than with `@/voice`'s helper so this path never loads the voice package — the browsers that
 * take it are the ones that can least afford it.
 */
function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export async function startDictationFallback(opts: DictationOptions): Promise<DictationSession> {
  // Force the WAV path: `POST /api/transcribe` refuses anything else (415).
  const rec = await startRecording({ preferMediaRecorder: false, onLimit: () => undefined });
  let stopped = false;
  return {
    startedAt: rec.startedAt,
    level: () => 0,
    async stop() {
      if (stopped) return '';
      stopped = true;
      const message = await rec.stop();
      const bytes = base64ToBytes(message.base64);
      const { text } = await api.transcribe.wav(new Blob([bytes as unknown as BlobPart], { type: 'audio/wav' }), opts.language);
      return text.trim();
    },
    cancel() {
      if (stopped) return;
      stopped = true;
      rec.cancel();
    },
  };
}
