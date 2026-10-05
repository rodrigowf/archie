/**
 * Entry point of `@/features/composer` (W-11, spec 13 §3.6, §3.9).
 *
 *   <Composer localId />                          the message composer of one conversation
 *   setStartVoiceHandler(fn)                      W-12 registers realtime voice start here
 *   startVoice(localId)                           what the Voice button (and the empty state's big button) call
 */
export { Composer, COMPOSER_FIELD_ATTR, type ComposerProps } from './Composer';
export { setStartVoiceHandler, startVoice, useVoiceHandlerRegistered, type StartVoiceHandler } from './voiceHook';
export { primaryMode, ringTone, placeholderFor, isSubmitKey, formatClock, withSlashCommand, PRIMARY_LABEL, type PrimaryMode, type PrimaryInput } from './logic';
export { ContextRing, PrimaryButton, QueueTray, ReadOnlyBar, RecordingStrip, type TrayItem } from './parts';
export {
  blobToBase64,
  formatForMime,
  MAX_RECORDING_MS,
  micErrorMessage,
  pickMime,
  startRecording,
  type AudioFormat,
  type AudioMessage,
  type Recording,
  type RecorderEnv,
} from './audio/recorder';
export { encodeWav, resample, WAV_RATE } from './audio/wav';
