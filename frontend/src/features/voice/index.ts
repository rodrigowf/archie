/**
 * Entry point of `@/features/voice` (W-12, spec 13 §3.6, §3.9).
 *
 *   <VoiceSlot localId composer />   composer slot: VoiceDock while voice is on, Active elsewhere above the composer
 *   <VoiceDock localId />            the own-device dock
 *   <VoiceAction localId />          compact app bar speaker toggle
 *   useVoiceUi(localId)              voice snapshot + controller
 *   installVoice()                   engine + the composer's Voice handler (W-11 setStartVoiceHandler)
 */
import { setStartVoiceHandler } from '@/features/composer';
import { showSnackbar } from '@/stores';
import { installVoiceEngine, startVoiceFromGesture } from '@/voice';

export { VoiceDock, VoiceDockView, ActiveElsewhereView, type VoiceDockViewProps, type VoiceDockActions, type ActiveElsewhereViewProps } from './VoiceDock';
export { VoiceSlot, VoiceAction } from './VoiceSlot';
export { LevelOrb, type OrbTone, type LevelOrbProps } from './LevelOrb';
export { useVoiceUi, useTicker, type VoiceUi } from './useVoiceUi';
export { dockText, statusWord, formatElapsed, vadSeconds, bannerText, VAD_COUNTER_AFTER_MS } from './copy';

let installed = false;

/** Idempotent: start the voice engine and register the composer's Voice action. */
export function installVoice(): void {
  if (installed) return;
  installed = true;
  installVoiceEngine();
  setStartVoiceHandler((localId) => {
    const reason = startVoiceFromGesture(localId);
    if (reason) showSnackbar(reason, { tone: 'error' });
  });
}
