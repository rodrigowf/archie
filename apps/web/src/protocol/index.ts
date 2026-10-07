/**
 * `@/protocol` (W-05): the framework-free TypeScript implementation of
 * docs/specs/12-client-protocol.md. Pure and deterministic: no DOM, no timers,
 * no clock, no I/O. Proven against every fixture in apps/protocol-fixtures/.
 *
 * Typical runtime loop (W-06):
 *   let conv = initialConversation({ localId, kind, sdkId, provider, liveStatus });
 *   step({ type: 'begin_reload' })                     // cold open: hold frames (§5.2)
 *   socket.onopen    → step({ type: 'socket_open' })   // effect: send start
 *   socket.onmessage → decodeFrame(ev.data) → step({ type: 'frame', frame })
 *   GET …/messages   → step({ type: 'history_page', mode: 'replace', response })
 *   where step(input) = { const r = stepConversation(conv, input); conv = r.state; run(r.effects) }
 */
export * from './types';
export type { ClientMessage, StartMessage, VoiceConfigFields, VoiceStartMessage } from './wire/client';
export { encodeClientMessage } from './wire/client';
export type * from './wire/server';
export { coerceFrame, decodeFrame, utf8Decode, type DecodeResult, type Utf8Decoder } from './wire/decode';
export type { ConversationInput, Effect, HistoryMode, StepResult } from './reducer/io';
export {
  initialConversation,
  reduceConversation,
  stepConversation,
  type InitialConversationOptions,
} from './reducer/machine';
export { classifyUserLine, normalizeOutput, type ClassifiedLine } from './history/classify';
export {
  computeDropLastN,
  countPromptLines,
  isPromptLine,
  isVisibleLine,
  lineMatchesEntry,
  mergeLines,
  pageAbuts,
  promptsNeeded,
  toIndexedLines,
  type DropLastNResult,
  type IndexedLine,
} from './history/dropLastN';
export {
  advanceCheckpoint,
  canResume,
  checkpointFromResumeState,
  checkpointStorageKey,
  createMemoryCheckpointStore,
  isDuplicateSeq,
  mergeResumeState,
  type CheckpointStore,
} from './resume/checkpoint';
export {
  ACTIVE_SESSION_PLACEHOLDER,
  contextUsage,
  deriveTitle,
  groupToolSteps,
  hasSdkId,
  isBusy,
  isRunLive,
  pendingPermission,
  unmatchedResults,
  type ContextUsage,
  type GroupToolStepsOptions,
  type SessionListItem,
  type StepItem,
  type UnmatchedResults,
} from './selectors';
