/**
 * Rendering preferences for markdown. The "Syntax highlighting" device pref (Appearance, default
 * on, spec 13 §2.4) is owned by the settings store; the host (W-09 / W-13) passes it in through
 * `MarkdownPrefsProvider`. Without a provider the defaults apply.
 */
import { createContext, useContext, type ReactNode } from 'react';

export interface MarkdownPrefs {
  /** Highlight closed code fences. */
  syntaxHighlight: boolean;
}

export const DEFAULT_MARKDOWN_PREFS: MarkdownPrefs = { syntaxHighlight: true };

const MarkdownPrefsContext = createContext<MarkdownPrefs>(DEFAULT_MARKDOWN_PREFS);

export function MarkdownPrefsProvider({ value, children }: { value: MarkdownPrefs; children: ReactNode }) {
  return <MarkdownPrefsContext.Provider value={value}>{children}</MarkdownPrefsContext.Provider>;
}

export function useMarkdownPrefs(): MarkdownPrefs {
  return useContext(MarkdownPrefsContext);
}
