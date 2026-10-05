/**
 * The composer's Voice action (W-11 → W-12 hand-off). The primary button's Voice state and the
 * empty Archie conversation's big voice button call `startVoice(localId)`; the voice engine
 * (W-12) registers the real implementation with `setStartVoiceHandler` at start-up. Until then
 * the button tells the user voice is not available instead of doing nothing.
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import { showSnackbar } from '@/stores';

export type StartVoiceHandler = (localId: string) => void;

const store = createStore<{ handler: StartVoiceHandler | null }>(() => ({ handler: null }));

/** W-12: register the realtime voice start. Returns the unregister function. */
export function setStartVoiceHandler(fn: StartVoiceHandler | null): () => void {
  store.setState({ handler: fn });
  return () => {
    if (store.getState().handler === fn) store.setState({ handler: null });
  };
}

export function startVoice(localId: string): void {
  const h = store.getState().handler;
  if (h) h(localId);
  else showSnackbar('Voice is not available yet');
}

export function useVoiceHandlerRegistered(): boolean {
  return useStore(store, (s) => s.handler !== null);
}
