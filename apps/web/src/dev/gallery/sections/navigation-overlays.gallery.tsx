/**
 * W-04 gallery sections: navigation (rail, tab strip with the all-tabs menu, top app bar, drawer,
 * session switcher sheet, tablet list-pane overlay, M3 tabs, tree) and overlays (menu, dialogs,
 * sheets, snackbars, tooltip, busy overlay). Frames mirror docs/frontend-refactor/mockups.
 * Static specimens use each overlay's `inline` mode; the "Try it" buttons open the real modal
 * versions (focus trap, Escape, Back, scroll lock).
 *
 * Buttons, chips, status dots and search fields are the W-03 controls (`@/ui/controls`).
 */
import { useRef, useState, type ReactNode } from 'react';
import {
  BottomSheet,
  BusyOverlay,
  ConfirmDialog,
  Dialog,
  Menu,
  MenuItem,
  MenuList,
  MenuSeparator,
  SideSheet,
  Snackbar,
  SnackbarHost,
  Tooltip,
  useSnackbarQueue,
} from '@/ui/overlays';
import {
  NavigationDrawer,
  NavigationDrawerHeadline,
  NavigationDrawerItem,
  NavigationRail,
  Tabs,
  TopAppBar,
  Tree,
  type TabItem,
  type TreeNode,
} from '@/ui/navigation';
import { Button, Fab, IconButton, SearchField, StatusDot, Tag } from '@/ui/controls';
import { Icon, Spinner } from '@/ui/primitives';
import { ArchieMark } from '../ArchieMark';
import type { GallerySection } from '../types';
import s from './navigation-overlays.gallery.module.css';

/* ---------------------------------------------------------------- stand-ins */

function Board({ title, note, wide, children }: { title: string; note?: string; wide?: boolean; children: ReactNode }) {
  return (
    <div className={wide ? `${s.board} ${s.wide}` : s.board}>
      <h3 className={s.boardTitle}>
        {title}
        {note ? <small>{note}</small> : null}
      </h3>
      {children}
    </div>
  );
}

const Prov = Tag;
const Dot = ({ label = 'Idle' }: { label?: string }) => <StatusDot status="idle" label={label} />;
const Hand = () => <StatusDot status="warning" label="Waiting for approval" />;
const Warn = () => <StatusDot status="disconnected" />;

/* ---------------------------------------------------------------- data */

const DESK_TABS: TabItem[] = [
  { id: 'tv', label: 'Living-room TV', leading: <ArchieMark size={20} />, status: <Hand />, statusLabel: 'Waiting for approval', closable: true, pinned: true },
  { id: 'voice', label: 'Refactor voice module', icon: 'terminal', meta: <Prov>Claude</Prov>, status: <Dot />, closable: true },
  { id: 'energy', label: 'Energy dashboard', icon: 'terminal', meta: <Prov>Qwen</Prov>, status: <Spinner label="Working" />, statusLabel: 'Using Bash', closable: true, unseen: true },
  { id: 'doc', label: 'voice_subsystem.md', icon: 'description', closable: true },
  { id: 'viz', label: 'Weekly energy usage', icon: 'bar_chart', closable: true },
  { id: 'gem', label: 'Gemini review', icon: 'terminal', meta: <Prov>Gemini</Prov>, status: <Warn />, statusLabel: 'Disconnected', closable: true },
];

const MEMORY: TreeNode[] = [
  {
    id: 'assistant',
    label: 'assistant',
    children: [
      { id: 'arch', label: 'architecture', children: [{ id: 'voice', label: 'voice_subsystem.md' }, { id: 'wake', label: 'wakeword_subsystem.md' }, { id: 'av', label: 'android_viewmodel.md' }] },
      { id: 'devices', label: 'devices', children: [{ id: 'tv', label: 'fire_tv.md' }, { id: 'ipad', label: 'frontend-compat.md' }] },
      { id: 'infra', label: 'infrastructure', children: [] },
    ],
  },
  { id: 'projects', label: 'projects', children: [{ id: 'pp', label: 'personal_projects_index.md' }] },
  { id: 'mem', label: 'MEMORY.md', meta: 'root' },
];

/* ---------------------------------------------------------------- session menu */

function SessionMenuItems() {
  return (
    <>
      <MenuItem icon="edit" shortcut="F2">
        Rename
      </MenuItem>
      <MenuItem icon="tune">Session settings</MenuItem>
      <MenuItem icon="compress">Compact context</MenuItem>
      <MenuItem icon="call_split">Fork</MenuItem>
      <MenuSeparator />
      <MenuItem icon="close" shortcut="Ctrl+Alt+W">
        Close
      </MenuItem>
      <MenuItem icon="delete" destructive>
        Delete
      </MenuItem>
    </>
  );
}

function SessionMenuButton() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  return (
    <>
      <IconButton icon="more_vert" aria-label="Session menu" size="small" ref={ref} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} />
      <Menu open={open} onClose={() => setOpen(false)} anchor={ref} placement="bottom-end" aria-label="Session menu">
        <SessionMenuItems />
      </Menu>
    </>
  );
}

/* ---------------------------------------------------------------- desktop */

function NewFab() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  return (
    <>
      <Fab lowered icon="add" aria-label="New: Archie conversation or agent session" ref={ref} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} />
      <Menu open={open} onClose={() => setOpen(false)} anchor={ref} placement="right-start" aria-label="New">
        <MenuItem leading={<ArchieMark size={20} />}>New Archie conversation</MenuItem>
        <MenuItem icon="terminal" shortcut="Ctrl+Alt+Shift+N">
          New agent session
        </MenuItem>
      </Menu>
    </>
  );
}

const RAIL = [
  { id: 'chats', label: 'Chats', icon: 'forum' as const },
  { id: 'memory', label: 'Memory', icon: 'book_2' as const },
  { id: 'visuals', label: 'Visuals', icon: 'bar_chart' as const },
];
const RAIL_END = [{ id: 'settings', label: 'Settings', icon: 'settings' as const }];

function TabStripDemo({ items: initial, value: v0 }: { items: TabItem[]; value: string }) {
  const [items, setItems] = useState(initial);
  const [value, setValue] = useState<string | null>(v0);
  const close = (id: string): void => {
    setItems((list) => {
      const i = list.findIndex((t) => t.id === id);
      const next = list.filter((t) => t.id !== id);
      if (id === value) setValue(next[Math.max(0, i - 1)]?.id ?? null);
      return next;
    });
  };
  return (
    <Tabs
      variant="strip"
      aria-label="Open sessions"
      items={items}
      value={value}
      onChange={setValue}
      onClose={close}
      onRename={() => undefined}
      onReorder={(id, to) => {
        setItems((list) => {
          const from = list.findIndex((t) => t.id === id);
          const next = list.slice();
          const [m] = next.splice(from, 1);
          if (m) next.splice(to, 0, m);
          return next;
        });
      }}
    />
  );
}

function ListPaneSample() {
  return (
    <>
      <NavigationDrawerHeadline trailing="3">Open now</NavigationDrawerHeadline>
      <NavigationDrawerItem leading={<ArchieMark size={24} />} label="Living-room TV" supporting="Archie · waiting for approval" trailing={<Hand />} />
      <NavigationDrawerItem icon="terminal" label="Refactor voice module" supporting={<><Prov>Claude</Prov><span className={s.sp}>Ready</span></>} trailing={<Dot />} active />
      <NavigationDrawerItem icon="terminal" label="Energy dashboard" supporting={<><Prov>Qwen</Prov><span className={s.sp}>Using Bash</span></>} trailing={<Spinner />} />
      <NavigationDrawerHeadline>Today</NavigationDrawerHeadline>
      <NavigationDrawerItem leading={<ArchieMark size={20} />} label="Weekly energy report" trailing="14:20" />
      <NavigationDrawerItem icon="terminal" label="Fix context-sync delete race" trailing="11:05" />
      <NavigationDrawerHeadline>Yesterday</NavigationDrawerHeadline>
      <NavigationDrawerItem leading={<ArchieMark size={20} />} label="Morning briefing" trailing="08:12" />
      <NavigationDrawerItem icon="terminal" label="Wake-word tuning review" trailing="Thu" />
    </>
  );
}

function SearchStub() {
  return <SearchField label="Search conversations" className={s.search} />;
}

function DesktopShell() {
  const [dest, setDest] = useState('chats');
  return (
    <div className={`${s.frame} ${s.desk}`}>
      <NavigationRail value={dest} onChange={setDest} destinations={RAIL} endDestinations={RAIL_END} fab={<NewFab />} />
      <aside className={s.listPane} aria-label="Chats">
        <div className={s.lpHead}>
          <span>Chats</span>
          <IconButton icon="left_panel_close" aria-label="Collapse list" />
        </div>
        <SearchStub />
        <ListPaneSample />
      </aside>
      <section className={s.ws} aria-label="Workspace">
        <div className={s.wtop}>
          <TabStripDemo items={DESK_TABS} value="voice" />
          <Tooltip label="14 turns · $0.82 · context 42%">
            <button type="button" className={s.wstat}>
              <Dot label="Ready" />
              <span>Ready · 14 turns</span>
            </button>
          </Tooltip>
          <SessionMenuButton />
        </div>
        <div className={s.conv}>
          <p className={s.msgA}>Voice state is split between OrchestratorSession and OpenAIVoiceProvider. I'll move every transition into one VoiceStateMachine.</p>
          <p className={s.msgU}>Go ahead. Keep the parity tests green.</p>
        </div>
      </section>
    </div>
  );
}

/* ---------------------------------------------------------------- tablet */

function TabletShell() {
  const [dest, setDest] = useState('chats');
  const [list, setList] = useState(true);
  return (
    <div className={`${s.frame} ${s.tablet}`}>
      <NavigationRail
        value={dest}
        onChange={setDest}
        destinations={RAIL}
        endDestinations={RAIL_END}
        menuButton={<IconButton icon="menu" aria-label="Open list" onClick={() => setList(true)} />}
        fab={<Fab lowered icon="add" aria-label="New" />}
      />
      <section className={s.ws} aria-label="Workspace (tablet)">
        <div className={s.wtop}>
          <TabStripDemo items={[DESK_TABS[0], DESK_TABS[1], DESK_TABS[2], DESK_TABS[3]].filter((t): t is TabItem => !!t)} value="tv" />
          <IconButton icon="more_vert" aria-label="Session menu" size="small" />
        </div>
        <div className={s.conv}>
          <p className={s.msgU}>Good morning. Anything I should know today?</p>
        </div>
      </section>
      <SideSheet inline open={list} onClose={() => setList(false)} side="left" offset={80} width={320} title="Chats" closeButton>
        <SearchStub />
        <ListPaneSample />
      </SideSheet>
    </div>
  );
}

/* ---------------------------------------------------------------- phone */

function PhoneBar({ expanded, onTitle, onMenu }: { expanded?: boolean; onTitle?: () => void; onMenu?: () => void }) {
  return (
    <TopAppBar
      leading={<IconButton icon="menu" aria-label="Open navigation" onClick={onMenu} />}
      titleIcon={<ArchieMark size={24} />}
      title="Living-room TV"
      subtitle={
        <>
          <Icon name="front_hand" size={15} className={s.warn} />
          Waiting for your approval
        </>
      }
      onTitleClick={onTitle ?? (() => undefined)}
      titleButtonLabel="Switch session"
      titleExpanded={expanded}
      onTitleSwipe={() => undefined}
      actions={
        <>
          <IconButton icon="volume_up" aria-label="Speaker on" />
          <IconButton icon="more_vert" aria-label="Session menu" />
        </>
      }
    />
  );
}

function PhoneBody() {
  return (
    <div className={s.conv}>
      <p className={s.msgU}>Plan the living-room TV setup for movie night on Friday.</p>
      <p className={s.msgA}>On it. I'll check what the Fire TV can play, then ask an agent to draft the setup.</p>
    </div>
  );
}

function SwitcherContent() {
  const rows: { title: string; sub: ReactNode; tile: ReactNode; active?: boolean }[] = [
    { title: 'Living-room TV', sub: <><Hand /><span>Archie · waiting for approval</span></>, tile: <ArchieMark size={40} />, active: true },
    { title: 'Refactor voice module', sub: <><Prov>Claude</Prov><Dot /><span>Ready · 14 turns</span></>, tile: <Icon name="terminal" /> },
    { title: 'Energy dashboard', sub: <><Prov>Qwen</Prov><Spinner /><span>Using Bash…</span></>, tile: <Icon name="terminal" /> },
    { title: 'voice_subsystem.md', sub: 'Memory · assistant/architecture', tile: <Icon name="description" /> },
  ];
  return (
    <>
      <NavigationDrawerHeadline>Open now</NavigationDrawerHeadline>
      {rows.map((r) => (
        <div key={r.title} className={`${s.swRow} ${r.active ? s.swActive : ''}`}>
          <span className={s.tile}>{r.tile}</span>
          <span className={s.swText}>
            <b>{r.title}</b>
            <span className={s.swSub}>{r.sub}</span>
          </span>
          <IconButton icon="close" aria-label={`Close ${r.title}`} />
        </div>
      ))}
    </>
  );
}

function SwitcherFooter() {
  return (
    <div className={s.sheetActs}>
      <Button variant="tonal" icon="add_comment">New Archie chat</Button>
      <Button variant="outlined" icon="terminal">
        New agent session
      </Button>
    </div>
  );
}

function DrawerContent() {
  return <ListPaneSample />;
}

function DrawerHeader() {
  return (
    <>
      <div className={s.drHead}>
        <ArchieMark size={40} />
        <span className={s.drText}>
          <b>Archie</b>
          <span className={s.drSub}>
            <Dot label="Connected" />
            <span>Connected to jetson</span>
          </span>
        </span>
      </div>
      <SearchStub />
    </>
  );
}

function DrawerFooter() {
  return (
    <>
      <NavigationDrawerItem icon="book_2" label="Memory" />
      <NavigationDrawerItem icon="bar_chart" label="Visuals" />
      <NavigationDrawerItem icon="settings" label="Settings" />
    </>
  );
}

function PhoneFrames() {
  const [drawer, setDrawer] = useState(false);
  const [sheet, setSheet] = useState(false);
  return (
    <div className={s.phones}>
      <figure className={s.fig}>
        <div className={`${s.frame} ${s.phone}`}>
          <PhoneBar onTitle={() => setSheet(true)} onMenu={() => setDrawer(true)} expanded={sheet} />
          <PhoneBody />
        </div>
        <figcaption>Top app bar · try ☰ and the title (live modal drawer and switcher)</figcaption>
      </figure>
      <figure className={s.fig}>
        <div className={`${s.frame} ${s.phone}`}>
          <PhoneBar />
          <PhoneBody />
          <NavigationDrawer inline open onClose={() => undefined} aria-label="Navigation (preview)" header={<DrawerHeader />} footer={<DrawerFooter />}>
            <DrawerContent />
          </NavigationDrawer>
        </div>
        <figcaption>Navigation drawer (modal)</figcaption>
      </figure>
      <figure className={s.fig}>
        <div className={`${s.frame} ${s.phone}`}>
          <PhoneBar expanded />
          <PhoneBody />
          <BottomSheet inline open onClose={() => undefined} title="Sessions" headerAction={<Button variant="text">History</Button>} footer={<SwitcherFooter />}>
            <SwitcherContent />
          </BottomSheet>
        </div>
        <figcaption>Session switcher (bottom sheet)</figcaption>
      </figure>
      <NavigationDrawer open={drawer} onClose={() => setDrawer(false)} aria-label="Navigation" header={<DrawerHeader />} footer={<DrawerFooter />}>
        <DrawerContent />
      </NavigationDrawer>
      <BottomSheet open={sheet} onClose={() => setSheet(false)} title="Sessions" headerAction={<Button variant="text">History</Button>} footer={<SwitcherFooter />}>
        <SwitcherContent />
      </BottomSheet>
    </div>
  );
}

/* ---------------------------------------------------------------- M3 tabs + tree */

function M3TabsDemo() {
  const [a, setA] = useState('device');
  const [b, setB] = useState('tree');
  return (
    <div className={s.stack}>
      <Tabs
        aria-label="Settings (primary)"
        items={[
          { id: 'device', label: 'This device', icon: 'mobile' },
          { id: 'server', label: 'Archie (server)', icon: 'dns' },
          { id: 'about', label: 'About', icon: 'info' },
        ]}
        value={a}
        onChange={setA}
      />
      <Tabs
        variant="secondary"
        aria-label="Memory view (secondary)"
        items={[
          { id: 'tree', label: 'Tree' },
          { id: 'recent', label: 'Recent' },
          { id: 'search', label: 'Search' },
        ]}
        value={b}
        onChange={setB}
      />
    </div>
  );
}

function TreeDemo() {
  const [sel, setSel] = useState<string | null>('voice');
  return (
    <Tree
      nodes={MEMORY}
      aria-label="Memory"
      defaultExpanded={['assistant', 'arch']}
      selectedId={sel}
      onSelect={(n) => {
        setSel(n.id);
      }}
    />
  );
}

function Navigation() {
  return (
    <div className={s.grid}>
      <Board title="Desktop (Expanded)" note="rail · list pane · tab strip in the top bar; try ⌄, ×, middle-click, drag" wide>
        <DesktopShell />
      </Board>
      <Board title="Tablet (Medium)" note="list pane as a modal side sheet next to the rail" wide>
        <TabletShell />
      </Board>
      <Board title="Phone (Compact)" note="no bottom bar: drawer + switcher" wide>
        <PhoneFrames />
      </Board>
      <Board title="Tabs" note="primary · secondary" wide>
        <M3TabsDemo />
      </Board>
      <Board title="Tree" note="Memory; arrows, type-ahead, *">
        <TreeDemo />
      </Board>
    </div>
  );
}

/* ---------------------------------------------------------------- overlays */

function DialogsDemo() {
  const [basic, setBasic] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [full, setFull] = useState(false);
  return (
    <>
      <div className={s.specimens}>
        <ConfirmDialog inline open title="Delete this session?" confirmLabel="Delete" destructive onConfirm={() => undefined} onCancel={() => undefined}>
          “Refactor voice module” and its 14 turns are removed from history on jetson. Memory files are kept.
        </ConfirmDialog>
        <Dialog inline open icon="restart_alt" title="Rewind to here?" onClose={() => undefined} maxWidth={360} actions={<><Button variant="text">Cancel</Button><Button variant="text">Rewind</Button></>}>
          The 3 later turns are removed and the session reopens from this message.
        </Dialog>
      </div>
      <div className={s.tryRow}>
        <Button variant="tonal" onClick={() => setBasic(true)}>Basic dialog</Button>
        <Button variant="tonal" onClick={() => setConfirm(true)}>Confirm (destructive)</Button>
        <Button variant="tonal" onClick={() => setFull(true)}>Full-screen on phones</Button>
      </div>
      <Dialog open={basic} onClose={() => setBasic(false)} title="Archie already active" actions={<><Button variant="text" onClick={() => setBasic(false)}>Cancel</Button><Button variant="text" onClick={() => setBasic(false)}>Switch to it</Button></>}>
        An Archie conversation is already open. Switch to it, or view this past conversation read-only.
      </Dialog>
      <ConfirmDialog open={confirm} title="Delete this session?" confirmLabel="Delete" destructive onConfirm={() => setConfirm(false)} onCancel={() => setConfirm(false)}>
        “Refactor voice module” and its 14 turns are removed from history on jetson.
      </ConfirmDialog>
      <Dialog open={full} fullScreen="compact" onClose={() => setFull(false)} title="Session settings" actions={<Button variant="text" onClick={() => setFull(false)}>Save</Button>}>
        <p>Working directory, model and MCP servers for this session. On phones this dialog fills the screen.</p>
      </Dialog>
    </>
  );
}

function SheetsDemo() {
  const [bottom, setBottom] = useState(false);
  const [side, setSide] = useState(false);
  const [drawer, setDrawer] = useState(false);
  return (
    <>
      <div className={s.tryRow}>
        <Button variant="tonal" onClick={() => setBottom(true)}>Bottom sheet</Button>
        <Button variant="tonal" onClick={() => setSide(true)}>Side sheet</Button>
        <Button variant="tonal" onClick={() => setDrawer(true)}>Drawer</Button>
      </div>
      <p className={s.note}>Swipe the bottom sheet down (touch or mouse drag) to dismiss. Escape and Back close the topmost layer only.</p>
      <BottomSheet open={bottom} onClose={() => setBottom(false)} title="Sessions" footer={<SwitcherFooter />}>
        <SwitcherContent />
      </BottomSheet>
      <SideSheet open={side} onClose={() => setSide(false)} title="Session settings" footer={<><Button variant="filled" onClick={() => setSide(false)}>Save and restart</Button><Button variant="outlined" onClick={() => setSide(false)}>Cancel</Button></>}>
        <p className={s.note}>Working directory, model, MCP servers. `null` inherits the server default.</p>
      </SideSheet>
      <NavigationDrawer open={drawer} onClose={() => setDrawer(false)} aria-label="Navigation" header={<DrawerHeader />} footer={<DrawerFooter />}>
        <DrawerContent />
      </NavigationDrawer>
    </>
  );
}

function SnackbarsDemo() {
  const q = useSnackbarQueue();
  return (
    <>
      <div className={s.snacks}>
        <Snackbar snackbar={{ id: 1, message: 'Saved' }} onDismiss={() => undefined} />
        <Snackbar snackbar={{ id: 2, message: 'Server said: "voice model gpt-realtime-mini not available"', action: { label: 'Retry', onAction: () => undefined } }} onDismiss={() => undefined} />
        <Snackbar snackbar={{ id: 3, message: 'Showing on Living-room TV', action: { label: 'Stop', onAction: () => undefined }, dismissible: true }} onDismiss={() => undefined} />
      </div>
      <div className={s.tryRow}>
        <Button variant="tonal" onClick={() => q.show({ message: 'Saved' })}>Show “Saved”</Button>
        <Button variant="tonal" onClick={() => q.show({ message: 'Server said: "Directory does not exist"', action: { label: 'Retry', onAction: () => undefined } })}>Show error + Retry</Button>
      </div>
      <SnackbarHost snackbar={q.current} onDismiss={q.dismiss} />
    </>
  );
}

function MenuDemo() {
  return (
    <div className={s.specimens}>
      <MenuList aria-label="Session menu (preview)" className={s.menuSpec}>
        <SessionMenuItems />
      </MenuList>
      <div className={s.stack}>
        <p className={s.note}>Live: arrows, Home/End, type-ahead, Escape/Tab/outside press close, focus returns to ⋮.</p>
        <div className={s.tryRow}>
          <SessionMenuButton />
        </div>
      </div>
    </div>
  );
}

function TooltipBusyDemo() {
  return (
    <div className={s.specimens}>
      <div className={s.tipBox}>
        <Tooltip label="14 turns · $0.82 · context 42%" open inline>
          <button type="button" className={s.wstat}>
            <Dot label="Ready" />
            <span>Ready · 14 turns</span>
          </button>
        </Tooltip>
      </div>
      <div className={s.busyBox}>
        <p className={s.msgA}>Rewinding removes the later turns and reopens the session.</p>
        <BusyOverlay label="Rewinding…" />
      </div>
    </div>
  );
}

function Overlays() {
  return (
    <div className={s.grid}>
      <Board title="Menu" note="session ⋮ menu" wide>
        <MenuDemo />
      </Board>
      <Board title="Dialogs" note="confirm · hero icon · full-screen on Compact" wide>
        <DialogsDemo />
      </Board>
      <Board title="Sheets and drawer" note="modal; Back closes">
        <SheetsDemo />
      </Board>
      <Board title="Snackbars" note="every save confirms">
        <SnackbarsDemo />
      </Board>
      <Board title="Tooltip and busy overlay" note="tooltip never the only label" wide>
        <TooltipBusyDemo />
      </Board>
    </div>
  );
}

export const sections: GallerySection[] = [
  {
    id: 'navigation',
    title: 'Navigation',
    description: 'W-04: navigation rail, the session tab strip with the all-tabs menu, top app bar, drawer, session switcher, the tablet list-pane overlay, M3 tabs and the Memory tree.',
    order: 70,
    Component: Navigation,
  },
  {
    id: 'overlays',
    title: 'Overlays',
    description: 'W-04: menus, dialogs, sheets, snackbars, tooltips and the busy overlay. Every modal traps focus, closes on Escape (topmost only) and Back, and returns focus to its trigger.',
    order: 71,
    Component: Overlays,
  },
];
