/**
 * Entry point of `@/features/session-actions` (W-11, spec 13 §3.6, §3.9).
 *
 *   <SessionMenu localId onRename? onClose? onSessionSettings? />   the ⋮ session menu
 *   <NewMenu onNewAgent prepare? archieMark? />                      "＋ New" menu items
 *   <SessionActionsHost onRename? />                                 dialogs + busy overlay + F2 (mount once)
 *   useSessionActions(localId)                                       availability with reasons (ID-3)
 *   requestOpenArchie({mode:'new'} | {mode:'resume', sdkId})         spec 12 §6.11 incl. the conflict dialog
 */
export { SessionMenu, type SessionMenuProps } from './SessionMenu';
export { NewMenu, requestNewArchie, type NewMenuProps } from './NewMenu';
export { SessionActionsHost, type SessionActionsHostProps } from './SessionActionsHost';
export { useSessionActions, type ActionState, type SessionActions } from './useSessionActions';
export {
  compactSession,
  deleteNow,
  forkFrom,
  forkWhole,
  NEEDS_FIRST_REPLY,
  requestDelete,
  requestFork,
  requestOpenArchie,
  resolveArchieConflict,
  resumeReadOnly,
  rewindTo,
  sdkIdOf,
  sessionTitle,
  STOP_FIRST,
  type ConflictChoice,
  type RequestArchie,
} from './actions';
export {
  patchSessionActions,
  resetSessionActions,
  sessionActionsStore,
  useSessionActionsState,
  type ArchieConflict,
  type RunningArchie,
  type SessionActionsState,
} from './actionsStore';
