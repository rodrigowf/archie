/**
 * Lazy chunk with the permission and agent-approval cards (they need markdown and the tool
 * registry, which they share with rich.tsx through a common chunk).
 */
import { AgentApprovalCards, PermissionCard } from './cards/permission';
import { Prefs } from './richPrefs';

export function RichPermissionCards({ localId }: { localId: string }) {
  return (
    <Prefs>
      <PermissionCard localId={localId} />
      <AgentApprovalCards localId={localId} />
    </Prefs>
  );
}
