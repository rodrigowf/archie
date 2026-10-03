/**
 * Display titles (IA §1 vocabulary). The title itself is derived from the session list
 * (**[LOAD-BEARING]** inv02 §7 #9, `tabTitle` in `@/stores`); this layer only replaces the
 * generic names the backend gives untitled Archie conversations ("Orchestrator", inv02 F-23) and
 * the store's kind placeholder ("Archie") with "New conversation". The UI never says
 * "Orchestrator": the Archie mark and the "Archie" subtitle convey the kind.
 */
import { catalogStore, tabTitle, type CatalogState, type Tab } from '@/stores';

export const NEW_CONVERSATION = 'New conversation';

const GENERIC_ARCHIE = /^\s*(orchestrator|archie)?\s*$/i;

/** A conversation title as shown; `isArchie` applies the Archie vocabulary rule. */
export function conversationTitle(raw: string | null | undefined, isArchie: boolean): string {
  const t = (raw ?? '').trim();
  if (isArchie) return GENERIC_ARCHIE.test(t) ? NEW_CONVERSATION : t;
  return t || 'Untitled';
}

export function displayTabTitle(tab: Tab, cat: CatalogState = catalogStore.getState()): string {
  const derived = tabTitle(tab, cat);
  return tab.kind === 'archie' ? conversationTitle(derived, true) : derived;
}
