/**
 * Navigation rail (medium + expanded; IA §3, mockups `.rail`): ＋ New (FAB → menu), Chats,
 * Memory, Visuals, Settings pinned at the bottom. Medium adds the ☰ button that opens the
 * list-pane overlay. The rail destination drives the list pane's content.
 */
import { useRef, useState } from 'react';
import { usePrefs } from '@/stores';
import { Fab, IconButton } from '@/ui/controls';
import { Menu } from '@/ui/overlays';
import { NavigationRail, type RailDestination } from '@/ui/navigation';
import { useRoute } from '../navigation/route';
import { NewMenuItems } from '../slots';
import type { WindowClass } from '../useWindowClass';
import { selectDestination, type RailId } from './destinations';
import { setShell, useShell } from './shellState';

const DESTINATIONS: RailDestination[] = [
  { id: 'chats', label: 'Chats', icon: 'forum' },
  { id: 'memory', label: 'Memory', icon: 'book_2' },
  { id: 'visuals', label: 'Visuals', icon: 'bar_chart' },
];
const END: RailDestination[] = [{ id: 'settings', label: 'Settings', icon: 'settings' }];

export function useRailValue(): RailId {
  const route = useRoute();
  const last = usePrefs((p) => p.lastRailDestination);
  if (route.name === 'settings') return 'settings';
  if (route.name === 'memory') return 'memory';
  if (route.name === 'visuals') return 'visuals';
  if (route.name === 'history') return 'chats';
  return last === 'settings' ? 'chats' : last;
}

export function AppRail({ wc }: { wc: WindowClass }) {
  const value = useRailValue();
  const listOpen = useShell((s) => s.listOverlayOpen);
  const [menu, setMenu] = useState(false);
  const fabRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <NavigationRail
        aria-label="Main"
        destinations={DESTINATIONS}
        endDestinations={END}
        value={value}
        onChange={(id) => {
          selectDestination(id as RailId, wc);
        }}
        menuButton={
          wc === 'medium' ? (
            <IconButton
              icon="menu"
              aria-label="Open list"
              aria-expanded={listOpen}
              onClick={() => {
                setShell({ listOverlayOpen: !listOpen });
              }}
            />
          ) : undefined
        }
        fab={
          <Fab
            ref={fabRef}
            lowered
            icon="add"
            aria-label="New: Archie conversation or agent session"
            aria-haspopup="menu"
            aria-expanded={menu}
            onClick={() => {
              setMenu((o) => !o);
            }}
          />
        }
      />
      <Menu
        open={menu}
        onClose={() => {
          setMenu(false);
        }}
        anchor={fabRef}
        placement="right-start"
        aria-label="New"
      >
        <NewMenuItems />
      </Menu>
    </>
  );
}
