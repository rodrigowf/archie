/**
 * Transcription REST: the catalogue Settings renders, the batch fallback for browsers that
 * cannot stream, and the voice notes the canvas lists. Live dictation is not here — it is a
 * WebSocket (`features/composer/audio/dictation.ts`).
 */
import { http } from '../client';
import type { TranscriberCatalog, VoiceNote } from '../types';

export const transcribe = {
  /** Which transcribers exist, which have a key on this server, what each costs. */
  providers: () => http.get<TranscriberCatalog>('/api/transcribe/providers', { probe: true }),

  /** One finished WAV → text. The compat build's dictation, and the TV's. */
  wav: (wav: Blob, language?: string) => {
    const form = new FormData();
    form.append('audio', wav, 'dictation.wav');
    if (language) form.append('language', language);
    return http.post<{ text: string }>('/api/transcribe', { body: form, timeoutMs: 60_000 });
  },

  notes: (limit = 100) => http.get<{ notes: VoiceNote[] }>('/api/transcribe/notes', { query: { limit } }),
  note: (id: string) => http.get<VoiceNote>(`/api/transcribe/notes/${encodeURIComponent(id)}`),
  rename: (id: string, title: string) =>
    http.patch<VoiceNote>(`/api/transcribe/notes/${encodeURIComponent(id)}`, { json: { title } }),
  remove: (id: string) => http.del<{ deleted: boolean }>(`/api/transcribe/notes/${encodeURIComponent(id)}`),
};
