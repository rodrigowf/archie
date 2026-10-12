/** DOM ids shared by the agent tab row (lazy) and the conversation panel's tab panels. */
export const MAIN_TAB = '__main__';

/** DOM id of the panel region that a sub-tab controls. */
export function subagentPanelId(localId: string, tab: string | null): string {
  return `sa-${localId.replace(/[^a-zA-Z0-9_-]/g, '_')}-${(tab ?? MAIN_TAB).replace(/[^a-zA-Z0-9_-]/g, '_')}`;
}
