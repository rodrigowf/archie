/**
 * Entry point of `@/features/subagents` (spec 12 §4.8, PLAN docs/projects/subagent-view §4.4): the
 * session's subagents as an index and as tabs.
 *
 *   <AgentsButton localId />            composer row: shown while the session has agents; opens the sheet (lazy)
 *   <SubagentTabs localId />            secondary tabs above the message list: Main + one per agent (lazy)
 *   <SubagentBody localId store toolUseId hidden /> / <SubagentBar … />   an agent tab (lazy): breadcrumb,
 *                                        transcript in the conversation's MessageList, read-only bar
 *   <SubagentHostContext.Provider value={localId}>                lets inline agent cards resolve their agent
 *   useSubagentInfo(toolUseId)           a card's live summary (status, activity, usage, report)
 *
 * `@/features/subagents/card` is the agent card's entry (labels, Open, status glyph, clock).
 *
 * The views are one lazy chunk (view.tsx); the protocol keeps transcripts lazily too (SA-5).
 */
export { AgentsButton, preloadAgentsSheet } from './AgentsButton';
export { preloadSubagentViews, SubagentBar, SubagentBody, SubagentTabs } from './lazy';
export { MAIN_TAB, showsAgentTabs, subagentPanelId } from './panelId';
export { SubagentHostContext, useSubagentHost, useSubagentInfo, type SubagentCardInfo } from './host';
// Labels, view actions and the clock are used only by lazy code: the agent card imports them from
// `@/features/subagents/card`, the views relatively (keeps them out of the initial chunks).
