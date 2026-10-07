/**
 * Entry point of `@/voice` (W-12, spec 13 §3.4, §3.9): the realtime voice engine, no React.
 *
 *   getVoiceController(localId)     the controller of a live Archie conversation (start/stop/mute, store)
 *   installVoiceEngine()            attach controllers to Archie runtimes as they open
 *   startVoiceFromGesture(localId)  the Voice button's tap (unlocks audio, gates capabilities)
 *   startVoiceWithoutGesture(id)    §6.11a SW-2 auto-start after a switch (needs audio already unlocked)
 *   voiceUnsupportedReason(caps)    capability helper (spec 13 §2.5, P-8)
 */
export { VoiceController, CONNECTION_INFO_TIMEOUT_MS, ENDING_TIMEOUT_MS, LINK_RETRY_BUDGET_MS, RESTORED_DISPLAY_MS, TRANSPORT_RETRY_MS } from './core/VoiceController';
export type { VoiceControllerDeps } from './core/VoiceController';
export {
  OFF_SNAPSHOT,
  dockVisible,
  isOwnerLive,
  systemClock,
  type Clock,
  type ConnectionInfo,
  type ConnectionType,
  type CuePlayer,
  type LinkSource,
  type LinkState,
  type NetworkSource,
  type TransportEvents,
  type TransportFactory,
  type TransportOptions,
  type VadInfo,
  type VoiceBanner,
  type VoiceErrorInfo,
  type VoicePhase,
  type VoicePort,
  type VoiceSnapshot,
  type VoiceStatus,
  type VoiceTransport,
} from './core/types';
export {
  parseConnectionInfo,
  transportForProvider,
  voiceUnsupportedReason,
  VOICE_NEEDS_HTTPS,
  VOICE_RELAY_UNSUPPORTED,
  VOICE_UNSUPPORTED,
  VOICE_WEBRTC_UNSUPPORTED,
} from './core/support';
export {
  getVoiceController,
  installVoiceEngine,
  startVoiceFromGesture,
  startVoiceWithoutGesture,
  syncVoiceControllers,
  uninstallVoiceEngine,
  VOICE_NEEDS_TAP,
} from './registry';
export { visualLevel } from './audio/meters';
export { PCM_CAPTURE_PROCESSOR } from './audio/capture/worklet';
