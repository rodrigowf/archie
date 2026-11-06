/**
 * Lazy chunk with the permission and agent-approval cards (they need markdown and the tool
 * registry, which they share with rich.tsx through a common chunk).
 */
import { AgentApprovalCards, PermissionCard } from './cards/permission';
import { AgentQuestionCards, QuestionCard } from './cards/question';
import { Prefs } from './richPrefs';

export function RichPermissionCards({ localId }: { localId: string }) {
  return (
    <Prefs>
      <PermissionCard localId={localId} />
      <QuestionCard localId={localId} />
      <AgentApprovalCards localId={localId} />
      <AgentQuestionCards localId={localId} />
    </Prefs>
  );
}
