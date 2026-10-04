/**
 * Voice gallery section (W-12, dev only): the dock in every state of the approved mockups —
 * phone (d) Listening / Speaking / Thinking / Using tools, (k) Reconnecting… → Reconnected /
 * Couldn't reconnect, the components board's "Voice dock" (own device · elsewhere), plus
 * Connecting, Ending and an error with its recovery hint.
 *
 * Screen mode for screenshots: `#/dev/gallery/voice?screen=<state>&theme=dark` renders a phone /
 * desktop conversation (W-09's view, seeded with voice transcripts) with the dock in the
 * composer slot.
 */
import { useMemo, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { ConversationPanel } from '@/features/conversation';
import { initialConversation, type AssistantEntry, type Block, type Conversation, type Entry, type ToolBlock, type UserEntry } from '@/protocol';
import { createSessionStore, getSessionEntry, registerSession } from '@/stores';
import { IconButton } from '@/ui/controls';
import { TopAppBar } from '@/ui/navigation';
import { Icon } from '@/ui/primitives';
import { OFF_SNAPSHOT, type VoiceSnapshot } from '@/voice';
import { statusWord } from './copy';
import { ActiveElsewhereView, VoiceDockView, type VoiceDockActions } from './VoiceDock';
import styles from './Voice.gallery.module.css';

const NOW = 1_000_000;

const noop = (): void => undefined;
const ACTIONS: VoiceDockActions = {
  onEnd: noop,
  onToggleMic: noop,
  onToggleSpeaker: noop,
  onInterrupt: noop,
  onRetry: noop,
  onReconnect: noop,
  onDismiss: noop,
};

const s = (over: Partial<VoiceSnapshot>): VoiceSnapshot => ({ ...OFF_SNAPSHOT, status: 'active', connectionType: 'webrtc', provider: 'openai', ...over });

interface State {
  readonly title: string;
  readonly snap: VoiceSnapshot | null;
  /** The live transcript tail is streaming (mockups (d)). */
  readonly live?: boolean;
}

const STATES: Record<string, State> = {
  listening: { title: 'Listening', snap: s({}), live: true },
  'listening-vad': { title: 'Listening · VAD counter (W-4)', snap: s({ vad: { state: 'listening', durationMs: 6_000, at: NOW } }), live: true },
  speaking: { title: 'Speaking (speaker muted)', snap: s({ status: 'speaking', speakerMuted: true }) },
  thinking: { title: 'Thinking', snap: s({ status: 'thinking' }) },
  tools: { title: 'Using tools', snap: s({ status: 'tool_use' }) },
  connecting: { title: 'Connecting · summarising', snap: s({ status: 'connecting', phase: 'summarizing' }) },
  ending: { title: 'Ending', snap: s({ status: 'ending' }) },
  reconnecting: { title: 'Reconnecting… (P-2)', snap: s({ link: 'lost', linkLostAt: NOW - 7_000, linkSource: 'socket' }) },
  reconnected: { title: 'Reconnected (P-2)', snap: s({ link: 'restored', linkRecoveredMs: 9_000 }) },
  failed: {
    title: 'Couldn’t reconnect (P-2)',
    snap: s({ status: 'error', link: 'failed', error: { message: 'Couldn’t reconnect', hint: null, category: 'network', docUrl: null } }),
  },
  error: {
    title: 'Error with recovery hint',
    snap: s({
      status: 'error',
      error: { message: 'Voice quota exceeded', hint: 'Top up the DashScope account, or switch the voice provider.', category: 'billing', docUrl: 'https://help.aliyun.com' },
    }),
  },
  elsewhere: { title: 'Voice active on another device', snap: null },
};

// ───────────────────────── a seeded voice conversation ─────────────────────────

let n = 0;
const id = (p: string): string => `${p}${(n += 1)}`;
const user = (text: string, over: Partial<UserEntry> = {}): UserEntry => ({ id: id('u'), kind: 'user', text, origin: 'voice', state: 'sent', ...over });
const text = (t: string, streaming = false): Block => ({ id: id('t'), type: 'text', text: t, streaming, scope: 'voice', origin: 'live' });
const tool = (name: string, input: Record<string, unknown>, output: string): ToolBlock => ({
  id: id('k'),
  type: 'tool',
  tool_use_id: id('toolu_'),
  tool_name: name,
  tool_input: input,
  status: 'done',
  output,
  scope: 'voice',
  origin: 'live',
});
const run = (...blocks: Block[]): AssistantEntry => ({ id: id('a'), kind: 'assistant', blocks });

function conversation(localId: string, live: boolean, cut: boolean): Conversation {
  const entries: Entry[] = [
    user('Approve the plan.'),
    run(
      tool('respond_to_agent_permission', { session_id: 'agent-tv', decision: 'allow' }, 'Approved ExitPlanMode'),
      tool('run_script', { script: 'tv_remote.py', args: ['queue'] }, '3 titles queued'),
      tool('run_script', { script: 'soundbar.py', args: ['mode=movie'] }, 'OK'),
      text('Kodi and Plex are both installed. I queued the three films and set the soundbar to movie mode.'),
    ),
    user('Dim the living-room lights to thirty percent.'),
    run(tool('run_script', { script: 'home_lights.py', args: ['--room', 'living', '--level', '30'] }, 'living-room: 30% (3 lamps)'), text('Done, the lights are at 30 percent.')),
  ];
  if (cut) entries.push(user('And put the jazz playlist on the TV.'), run(text('Starting the jazz playlist on the', true)));
  else entries.push(user('And put the jazz playlist on the TV', { streaming: live }));
  const c = initialConversation({ localId, kind: 'orchestrator', sdkId: `sdk-${localId}`, subscribed: true, voiceActive: true });
  return { ...c, status: 'idle', entries, history: { loaded: true, startIndex: 0, totalCount: entries.length, hasMore: false } };
}

function seed(c: Conversation): void {
  if (getSessionEntry(c.ref.localId)) return;
  registerSession(c.ref.localId, {
    handle: createSessionStore({ localId: c.ref.localId, conv: c }),
    runtime: { localId: c.ref.localId, dispose: noop },
  });
}

function ComposerStub() {
  return (
    <div className={styles.composer} aria-hidden="true">
      <span>Message Archie…</span>
      <span className={styles.composerNote}>composer · W-11</span>
    </div>
  );
}

function Dock({ state }: { state: State }) {
  const level = useMemo(() => () => 0.06, []);
  if (!state.snap) {
    return (
      <>
        <ActiveElsewhereView provider="qwen" onTakeOver={noop} />
        <ComposerStub />
      </>
    );
  }
  return <VoiceDockView snapshot={state.snap} now={NOW} level={level} {...ACTIONS} />;
}

function Screen({ name }: { name: string }) {
  const state = STATES[name] ?? (STATES.listening as State);
  const [localId] = useState(() => {
    const lid = `voice-${name}`;
    seed(conversation(lid, state.live === true, name === 'reconnecting' || name === 'failed' || name === 'reconnected'));
    return lid;
  });
  const word = state.snap ? statusWord(state.snap) : 'Active elsewhere';
  const recon = state.snap?.link === 'lost';
  return (
    <div className={styles.screenInner}>
      <TopAppBar
        className={styles.appbar}
        leading={<IconButton icon="menu" aria-label="Open navigation" />}
        title="Living-room TV"
        subtitle={
          <span className={styles.sub}>
            <Icon name={recon ? 'sync' : state.snap ? 'graphic_eq' : 'devices'} size={16} className={recon ? styles.warn : styles.primary} />
            Voice · {word}
          </span>
        }
        actions={
          <>
            {state.snap && state.snap.status !== 'error' ? (
              <IconButton icon="volume_up" selectedIcon="volume_off" selected={state.snap.speakerMuted} aria-label="Speaker on" />
            ) : null}
            <IconButton icon="more_vert" aria-label="Session menu" />
          </>
        }
      />
      <div className={styles.body}>
        <ConversationPanel localId={localId} hidden={false} composer={<Dock state={state} />} />
      </div>
    </div>
  );
}

function hashParams(): Record<string, string> {
  const h = window.location.hash;
  const q = h.indexOf('?');
  const out: Record<string, string> = {};
  if (q < 0) return out;
  for (const pair of h.slice(q + 1).split('&')) {
    const [k, v = ''] = pair.split('=');
    if (k) out[decodeURIComponent(k)] = decodeURIComponent(v);
  }
  return out;
}

function Board({ children }: { children: ReactNode }) {
  return <div className={styles.board}>{children}</div>;
}

export function VoiceGallery() {
  const params = hashParams();
  if (params.screen) {
    return createPortal(
      <div className={styles.screen}>
        <Screen key={params.screen} name={params.screen} />
      </div>,
      document.body,
    );
  }
  return (
    <div className={styles.page}>
      <p className={styles.lead}>
        Full-viewport scenes: <code>?screen=&lt;state&gt;</code> ({Object.keys(STATES).join(', ')}).
      </p>
      <Board>
        {Object.keys(STATES).map((k) => {
          const st = STATES[k] as State;
          return (
            <section key={k} className={styles.cell}>
              <h3 className={styles.cellTitle}>
                {st.title}
                <small>{k}</small>
              </h3>
              <Dock state={st} />
            </section>
          );
        })}
      </Board>
    </div>
  );
}
