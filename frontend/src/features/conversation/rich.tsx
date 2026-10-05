/**
 * The lazily loaded "rich" chunk of the conversation view (spec 13 §5.3 code splitting, §5.4
 * initial-JS budget): everything that needs the markdown pipeline (remark / micromark) or the tool
 * registry and renderers. The shell, the message list, user bubbles, notices and the stall / error
 * / termination cards stay in the initial bundle.
 *
 * `preloadRich()` starts the download when the conversation module is evaluated, in parallel with
 * the history request, so a conversation normally renders in one pass. While it is still loading,
 * the list shows its "Loading conversation…" state (no partial layout that would then jump).
 */
import { AssistantMessage, type AssistantMessageProps } from './entries/AssistantMessage';
import { Prefs } from './richPrefs';

export function RichAssistantMessage(props: AssistantMessageProps) {
  return (
    <Prefs>
      <AssistantMessage {...props} />
    </Prefs>
  );
}
