/**
 * Entry point of `@/features/voice` (W-12, spec 13 §3.6, §3.9).
 *
 *   <VoiceSlot localId composer />   composer slot: VoiceDock while voice is on, Active elsewhere above the composer
 *   <VoiceDock localId />            the own-device dock
 *   <VoiceAction localId />          compact app bar speaker toggle
 *   useVoiceUi(localId)              voice snapshot + controller
 *   installVoice()                   engine + the composer's Voice handler (W-11 setStartVoiceHandler)
 *                                    + the voice auto-start after an orchestrator switch (§6.11a SW-2)
 */
import { setStartVoiceHandler } from '@/features/composer';
import { setSwitchVoiceHandler } from '@/services';
import { showSnackbar } from '@/stores';
import { installVoiceEngine, startVoiceFromGesture, startVoiceWithoutGesture, VOICE_NEEDS_TAP } from '@/voice';

export { VoiceDock, VoiceDockView, ActiveElsewhereView, type VoiceDockViewProps, type VoiceDockActions, type ActiveElsewhereViewProps } from './VoiceDock';
export { VoiceSlot, VoiceAction } from './VoiceSlot';
export { LevelOrb, type OrbTone, type LevelOrbProps } from './LevelOrb';
export { useVoiceUi, useTicker, type VoiceUi } from './useVoiceUi';
export { dockText, statusWord, formatElapsed, vadSeconds, bannerText, VAD_COUNTER_AFTER_MS } from './copy';

let installed = false;

function startFromTap(localId: string): void {
  const reason = startVoiceFromGesture(localId);
  if (reason) showSnackbar(reason, { tone: 'error' });
}

/**
 * §6.11a: Archie switched to a past conversation while voice was live here. Voice continues on
 * the resumed view with no tap when the browser allows it; else the snackbar's Start voice (a
 * tap) and the view's normal voice button remain.
 */
export function continueVoiceAfterSwitch(localId: string, title: string): void {
  const reason = startVoiceWithoutGesture(localId);
  if (!reason) showSnackbar(`Switched to ${title}`);
  else if (reason === VOICE_NEEDS_TAP)
    showSnackbar(`Switched to ${title}`, { action: { label: 'Start voice', run: () => startFromTap(localId) } });
  else showSnackbar(`Switched to ${title}. ${reason}`, { tone: 'error' });
}

/** Idempotent: start the voice engine and register the composer's Voice action and the switch handler. */
export function installVoice(): void {
  if (installed) return;
  installed = true;
  installVoiceEngine();
  setStartVoiceHandler(startFromTap);
  setSwitchVoiceHandler(continueVoiceAfterSwitch);
}
