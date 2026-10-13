/**
 * `@/features/subagents/card`: what the inline agent card (features/tools AgentCard, in the lazy
 * rich chunk) needs. A second entry on purpose: going through the package barrel, which the
 * initial bundle imports (Agents button, host context), would make these modules initial too —
 * an ESM barrel chunk re-exports bindings from the chunks it names (spec 13 §5.4; eslint.config.js
 * allows exactly this deep import).
 */
export { SUBAGENT_STATUS_TEXT, activityLine, formatElapsed, subagentElapsedMs, subagentTitle } from './labels';
export { showSubagentTab } from './nav';
export { useNow } from './useNow';
export { useSubagentInfo, type SubagentCardInfo } from './host';
export { SubagentStatusGlyph } from './SubagentStatusGlyph';
