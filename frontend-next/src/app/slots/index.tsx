/**
 * SLOT REGISTRY (W-07). Every destination and workspace part that a later work package builds is
 * imported by the shell **only from this file**, under the entry-point names of spec 13 §3.9.
 * Each export below is a clearly-marked PLACEHOLDER: when the owning package lands, replace the
 * placeholder with a re-export from its feature entry, e.g.
 *
 *   export { ConversationPanel } from '@/features/conversation';   // W-09
 *
 * and delete the placeholder. The props are the §3.9 contracts, so the shell does not change.
 *
 * | Slot                 | Owner | Used by                                       |
 * |----------------------|-------|-----------------------------------------------|
 * | ConversationPanel    | W-09  | PanelHost (chat tabs); composes the composer  | wired
 * | Composer             | W-11  | inside ConversationPanel                       | wired
 * | SessionMenu          | W-11  | workspace top bar / compact app bar (⋮)        | wired
 * | NewMenuItems         | W-11  | rail "＋ New" menu                             | wired
 * | HistoryPane          | W-14  | list pane (Chats), drawer body, History screen |
 * | MemoryPane           | W-14  | list pane (Memory), Memory screen              |
 * | MemoryDocument       | W-14  | PanelHost (memory tabs), compact doc screen    |
 * | VisualsPane          | W-14  | list pane (Visuals), Visuals screen            |
 * | VisualViewer         | W-14  | PanelHost (visual tabs), compact doc screen    |
 * | SettingsScreen       | W-13  | Settings screen                                |
 * | AuthGate             | W-13  | App root                                       |
 * | VoiceAction          | W-12  | compact app bar trailing voice/speaker state   |
 */
import { useMemo, type ReactNode } from 'react';
import { Composer, startVoice } from '@/features/composer';
import { ConversationPanel as ConversationView } from '@/features/conversation';
import { NewMenu, SessionActionsHost, SessionMenu as SessionMenuView } from '@/features/session-actions';
import { EmptyState } from '@/ui/controls';
import { navigate } from '../navigation/route';
import { ArchieMark } from '../shell/ArchieMark';
import { newAgent, requestCloseTab, requestRename } from '../shell/actions';
import { closeShellOverlays } from '../shell/shellState';
import { SlotNote } from './SlotNote';
import styles from './placeholders.module.css';

export { Composer } from '@/features/composer';
export { HistoryPane } from './HistoryPanePlaceholder';
export { SlotNote } from './SlotNote';

/* ------------------------------------------------------------- W-09 + W-11 */

/** W-09's conversation view with W-11's composer in its slot (W-12's VoiceDock takes it over while voice is active). */
export function ConversationPanel({ localId, hidden }: { localId: string; hidden: boolean }) {
  const composer = useMemo(() => <Composer localId={localId} />, [localId]);
  const onStartVoice = useMemo(() => () => startVoice(localId), [localId]);
  return <ConversationView localId={localId} hidden={hidden} composer={composer} onStartVoice={onStartVoice} />;
}

/** W-11 ⋮ session menu; Rename and Close run the shell's flows (W-07). Session settings: wire W-13's sheet here. */
export function SessionMenu({ localId }: { localId: string }) {
  return <SessionMenuView localId={localId} onRename={requestRename} onClose={requestCloseTab} />;
}

/** W-11 "＋ New" items: New Archie asks first when Archie is already running (§6.11, F-25). */
export function NewMenuItems() {
  return (
    <NewMenu
      archieMark={<ArchieMark size={20} />}
      prepare={() => {
        closeShellOverlays();
        navigate({ name: 'workspace' });
      }}
      onNewAgent={newAgent}
    />
  );
}

/* ------------------------------------------------------------- W-14 */

/** PLACEHOLDER (W-14 `MemoryPane`). */
export function MemoryPane() {
  return (
    <div className={styles.pane}>
      <EmptyState icon="book_2" title="Memory" description="The memory tree with search." />
      <SlotNote owner="W-14">Memory browser</SlotNote>
    </div>
  );
}

/** PLACEHOLDER (W-14 `VisualsPane`). */
export function VisualsPane() {
  return (
    <div className={styles.pane}>
      <EmptyState icon="bar_chart" title="Visuals" description="Visualizations Archie and agents made." />
      <SlotNote owner="W-14">Visuals gallery</SlotNote>
    </div>
  );
}

/** PLACEHOLDER (W-14 `MemoryDocument`). */
export function MemoryDocument({ path, hidden }: { path: string; hidden: boolean }) {
  return (
    <div className={styles.doc} data-hidden={hidden ? '' : undefined}>
      <EmptyState icon="description" title={path} description="Frontmatter, rendered markdown and in-app links." />
      <SlotNote owner="W-14">Memory document</SlotNote>
    </div>
  );
}

/**
 * PLACEHOLDER (W-14 `VisualViewer`). Already keeps the **[LOAD-BEARING]** iframe contract
 * (inv02 F-36, frontend/src/components/VisualizationPanel.tsx): exact sandbox tokens, and the
 * iframe stays mounted while hidden.
 */
export function VisualViewer({ path, url, hidden }: { path: string; url: string | undefined; hidden: boolean }) {
  return (
    <div className={styles.viewer} data-hidden={hidden ? '' : undefined}>
      <iframe
        className={styles.frame}
        title={path}
        src={url ?? 'about:blank'}
        sandbox="allow-scripts allow-same-origin allow-popups allow-forms allow-modals"
      />
      <SlotNote owner="W-14">Visual viewer toolbar</SlotNote>
    </div>
  );
}

/* ------------------------------------------------------------- W-13 */

/** PLACEHOLDER (W-13 `SettingsScreen`). */
export function SettingsScreen({ page }: { page?: string | null }) {
  return (
    <div className={styles.pane}>
      <EmptyState
        icon="settings"
        title={page ? `Settings · ${page}` : 'Settings'}
        description="This device · Archie (server) · About"
      />
      <SlotNote owner="W-13">Settings</SlotNote>
    </div>
  );
}

/**
 * PLACEHOLDER (W-13 `AuthGate`): renders the app; W-13 adds the sign-in screen.
 * Keep W-11's `SessionActionsHost` (session dialogs, busy overlay, F2) mounted beside the app.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <SessionActionsHost onRename={requestRename} />
    </>
  );
}

/* ------------------------------------------------------------- W-12 */

/** PLACEHOLDER (W-12): the compact app bar's voice/speaker state action. Nothing until W-12. */
export function VoiceAction(_props: { localId: string }) {
  return null;
}
