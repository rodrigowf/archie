/**
 * The lazy chunk of the agent views (spec 13 §5.4: only a session that has agents needs them):
 * the tab row (+ live-region announcer), an agent tab's transcript, and its read-only bar.
 * Loaded through `lazy.tsx`.
 */
import { AgentTabRow } from './AgentTabRow';
import { AgentTranscript, type AgentTranscriptProps } from './AgentTranscript';
import { AgentViewBar } from './AgentViewParts';
import { SubagentAnnouncer } from './SubagentAnnouncer';

export function SubagentTabsView({ localId }: { localId: string }) {
  return (
    <>
      <AgentTabRow localId={localId} />
      <SubagentAnnouncer localId={localId} />
    </>
  );
}

export function SubagentBodyView(props: AgentTranscriptProps) {
  return <AgentTranscript {...props} />;
}

export function SubagentBarView({ localId, toolUseId }: { localId: string; toolUseId: string }) {
  return <AgentViewBar localId={localId} toolUseId={toolUseId} />;
}
