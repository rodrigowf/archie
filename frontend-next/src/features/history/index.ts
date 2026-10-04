/**
 * Entry point of `@/features/history` (W-14, spec 13 §3.6, §3.9).
 *
 *   <HistoryPane variant openNow? activeId? onFocusOpen? onOpenSession? archieMark? />
 *   groupSessions / matchesQuery / conversationTitle        the pure list model
 *   parseServerTime / historyGroupOf / historyStamp / shortAge   offset-aware times (A-8.4)
 *   <ActionRow/> <RenameDialog/>                            shared with the Visuals list
 */
export { HistoryPane, type HistoryPaneProps, type HistoryVariant, type OpenNowItem } from './HistoryPane';
export { ActionRow, type ActionRowProps, type RowAction } from './ActionRow';
export { RenameDialog, type RenameDialogProps } from './RenameDialog';
export {
  conversationTitle,
  groupSessions,
  lastActivityMs,
  matchesQuery,
  NEW_CONVERSATION,
  sessionTitleOf,
  type GroupOptions,
  type HistorySection,
} from './grouping';
export { useMinuteClock } from './useMinuteClock';
export { HISTORY_GROUPS, historyGroupOf, historyStamp, parseServerTime, shortAge, type HistoryGroup } from './time';
