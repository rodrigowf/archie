/**
 * The listening composer and its engine, as chunks fetched on the first press of the mic
 * (spec 13 §5.4, like `features/settings/lazy.tsx`).
 *
 * The composer is in the initial bundle, so none of this may be imported statically — see
 * `docs/clients/web.md`. Which engine is fetched depends on the browser, and that split matters:
 * a Safari 12 client takes the fallback and so never downloads `@/voice` at all.
 */
import { lazy } from 'react';
import type { DictationOptions, DictationSession } from '@/voice/dictation';

const loadPanel = () => import('./DictationPanel');

export const LazyDictationPanel = lazy(loadPanel);

export type StartDictation = (opts: DictationOptions) => Promise<DictationSession>;

/**
 * The engine for this browser: the streaming socket, or the record-then-transcribe fallback.
 * Fetched together with the panel, so `startDict` can show the listening composer in one go.
 */
export async function loadDictation(streaming: boolean): Promise<StartDictation> {
  const [engine] = await Promise.all([
    streaming ? import('@/voice/dictation').then((m) => m.startDictation) : import('./audio/dictationFallback').then((m) => m.startDictationFallback),
    loadPanel(),
  ]);
  return engine;
}
