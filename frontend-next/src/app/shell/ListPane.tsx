/**
 * List pane (IA §3–§4; mockups `.lp`): Chats → HistoryPane, Memory → MemoryPane, Visuals →
 * VisualsPane. Expanded: a standard 320 dp pane, collapsible from its header (remembered in the
 * `listPaneCollapsed` device pref). Medium: the same content in a modal side sheet next to the
 * rail (scrim, Escape, Back), closed by default.
 */
import { setPref } from '@/stores';
import { IconButton } from '@/ui/controls';
import { SideSheet } from '@/ui/overlays';
import { ScrollArea } from '@/ui/primitives';
import { HistoryPane, MemoryPane, VisualsPane } from '../slots';
import { useRailValue } from './AppRail';
import { setShell, useShell } from './shellState';
import styles from './shell.module.css';

const TITLES = { chats: 'Chats', memory: 'Memory', visuals: 'Visuals' } as const;

function PaneBody({ dest }: { dest: 'chats' | 'memory' | 'visuals' }) {
  if (dest === 'memory') return <MemoryPane />;
  if (dest === 'visuals') return <VisualsPane />;
  return <HistoryPane variant="pane" />;
}

function useListDest(): 'chats' | 'memory' | 'visuals' {
  const v = useRailValue();
  return v === 'settings' ? 'chats' : v;
}

export function ListPane() {
  const dest = useListDest();
  return (
    <aside className={styles.listPane} aria-label={TITLES[dest]}>
      <div className={styles.lpHead}>
        <h2 className={styles.lpTitle}>{TITLES[dest]}</h2>
        <IconButton
          icon="left_panel_close"
          aria-label="Collapse list"
          onClick={() => {
            setPref('listPaneCollapsed', true);
          }}
        />
      </div>
      <ScrollArea className={styles.lpBody}>
        <PaneBody dest={dest} />
      </ScrollArea>
    </aside>
  );
}

export function ListPaneOverlay() {
  const dest = useListDest();
  const open = useShell((s) => s.listOverlayOpen);
  return (
    <SideSheet
      open={open}
      onClose={() => {
        setShell({ listOverlayOpen: false });
      }}
      side="left"
      offset={80}
      width={320}
      title={TITLES[dest]}
      closeButton
    >
      <PaneBody dest={dest} />
    </SideSheet>
  );
}
