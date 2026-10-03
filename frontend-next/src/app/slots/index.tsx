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
 * | ConversationPanel    | W-09  | PanelHost (chat tabs); composes the composer  |
 * | Composer             | W-11  | inside ConversationPanel                       |
 * | SessionMenu          | W-11  | workspace top bar / compact app bar (⋮)        |
 * | NewMenuItems         | W-11  | rail "＋ New" menu                             |
 * | HistoryPane          | W-14  | list pane (Chats), drawer body, History screen |
 * | MemoryPane           | W-14  | list pane (Memory), Memory screen              |
 * | MemoryDocument       | W-14  | PanelHost (memory tabs), compact doc screen    |
 * | VisualsPane          | W-14  | list pane (Visuals), Visuals screen            |
 * | VisualViewer         | W-14  | PanelHost (visual tabs), compact doc screen    |
 * | SettingsScreen       | W-13  | Settings screen                                |
 * | AuthGate             | W-13  | App root                                       |
 * | VoiceAction          | W-12  | compact app bar trailing voice/speaker state   |
 */
import type { ReactNode } from 'react';
import { EmptyState } from '@/ui/controls';
import { MenuItem } from '@/ui/overlays';
import { ArchieMark } from '../shell/ArchieMark';
import { newAgent, newArchie } from '../shell/actions';
import { SlotNote } from './SlotNote';
import styles from './placeholders.module.css';

export { ConversationPanel, Composer } from './ConversationPlaceholder';
export { SessionMenu } from './SessionMenuPlaceholder';
export { HistoryPane } from './HistoryPanePlaceholder';
export { SlotNote } from './SlotNote';


/* ------------------------------------------------------------- W-11 NewMenu */

/** PLACEHOLDER (W-11 `NewMenu`): the two "new" actions; W-11 adds the "Archie already active" dialog (F-25). */
export function NewMenuItems() {
  return (
    <>
      <MenuItem leading={<ArchieMark size={20} />} shortcut="Ctrl+Alt+N" onSelect={newArchie}>
        New Archie conversation
      </MenuItem>
      <MenuItem icon="terminal" shortcut="Ctrl+Alt+Shift+N" onSelect={newAgent}>
        New agent session
      </MenuItem>
    </>
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

/** PLACEHOLDER (W-13 `AuthGate`): renders the app; W-13 adds the sign-in screen. */
export function AuthGate({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

/* ------------------------------------------------------------- W-12 */

/** PLACEHOLDER (W-12): the compact app bar's voice/speaker state action. Nothing until W-12. */
export function VoiceAction(_props: { localId: string }) {
  return null;
}
