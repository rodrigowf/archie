/** DOM ids and the visibility rule shared by the agent tab row (lazy) and the conversation panel. */
export const MAIN_TAB = '__main__';

/**
 * Whether the agent tab row shows: some agent's tab is open (not closed with "Close finished", or
 * the one selected). With only Main left the row hides; the agents list reopens a tab.
 */
export function showsAgentTabs(subagents: readonly { tool_use_id: string }[], closed: readonly string[], selected: string | null): boolean {
  return subagents.some((s) => closed.indexOf(s.tool_use_id) < 0 || s.tool_use_id === selected);
}

/** DOM id of the panel region that a sub-tab controls. */
export function subagentPanelId(localId: string, tab: string | null): string {
  return `sa-${localId.replace(/[^a-zA-Z0-9_-]/g, '_')}-${(tab ?? MAIN_TAB).replace(/[^a-zA-Z0-9_-]/g, '_')}`;
}
