/** Markdown prefs for the lazy chunks: the "Syntax highlighting" device pref (spec 13 §2.4). */
import { useMemo, type ReactNode } from 'react';
import { MarkdownPrefsProvider } from '@/features/markdown';
import { usePrefs } from '@/stores';

/** The "Syntax highlighting" device pref for every markdown body in the view. */
export function Prefs({ children }: { children: ReactNode }) {
  const syntaxHighlight = usePrefs((p) => p.syntaxHighlighting);
  const value = useMemo(() => ({ syntaxHighlight }), [syntaxHighlight]);
  return <MarkdownPrefsProvider value={value}>{children}</MarkdownPrefsProvider>;
}
