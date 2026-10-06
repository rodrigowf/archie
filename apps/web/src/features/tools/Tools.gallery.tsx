/**
 * Tool cards gallery section (W-10): every F-05 tool in running / done / error, the "N steps"
 * groups (live, collapsed, a desktop-like conversation), the special states from the mockups
 * (Edit diff, Bash exit code, TodoWrite, Task, send_to_agent_session live feed, WebFetch waiting)
 * and a replayable live run that shows the group collapsing once text follows. Dev-only.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { groupToolSteps, type Block, type TextBlock } from '@/protocol';
import { Markdown } from '@/features/markdown';
import { SAMPLES, tool, withResult } from './__fixtures__/blocks';
import { StepGroup } from './StepGroup';
import { ToolCard } from './ToolCard';
import styles from './Tools.gallery.module.css';

const text = (id: string, t: string): TextBlock => ({ id, type: 'text', text: t, streaming: false, scope: 'turn', origin: 'live' });

function Section({ id, title, note, children }: { id: string; title: string; note?: string; children: ReactNode }) {
  return (
    <section className={styles.section} id={id} aria-labelledby={`${id}-title`}>
      <h3 className={styles.title} id={`${id}-title`}>
        {title}
        {note ? <small>{note}</small> : null}
      </h3>
      <div className={styles.frame}>{children}</div>
    </section>
  );
}

/** Renders a run the way the conversation (W-09) will: groupToolSteps → StepGroup / ToolCard / text. */
function Run({ blocks, live, feeds }: { blocks: readonly Block[]; live: boolean; feeds?: Record<string, readonly string[]> }) {
  const items = groupToolSteps(blocks, { live });
  const last = blocks[blocks.length - 1];
  return (
    <div className={styles.run}>
      {items.map((item) => {
        if (item.kind === 'steps') return <StepGroup key={item.key} blocks={item.blocks} live={item.live} sessionKind="orchestrator" {...(feeds ? { feeds } : {})} />;
        const b = item.block;
        if (b.type === 'tool')
          return <ToolCard key={b.id} block={b} sessionKind="orchestrator" autoOpen={live && b === last} {...(feeds?.[b.tool_use_id] ? { feed: feeds[b.tool_use_id] } : {})} />;
        if (b.type === 'text')
          return (
            <div key={b.id} className={styles.prose}>
              <Markdown source={b.text} />
            </div>
          );
        return null;
      })}
    </div>
  );
}

function User({ children }: { children: string }) {
  return <div className={styles.user}>{children}</div>;
}

/* ---------- fixed scenes (mockups) ---------- */

const DESK_G1 = [
  tool('Grep', { pattern: '_voice', path: 'orchestrator/' }, { output: 'orchestrator/session.py: 14 matches\norchestrator/providers/openai_voice.py: 6 matches' }),
  tool('Read', { file_path: 'orchestrator/session.py' }, { output: '642 lines' }),
  tool('Read', { file_path: 'orchestrator/providers/openai_voice.py' }, { output: '388 lines' }),
];
const EDIT = SAMPLES.find((s) => s.name === 'Edit');
const DESK_G2 = [
  tool('Read', { file_path: 'orchestrator/voice/state.py', offset: 0, limit: 2 }, { output: '  1  class VoiceState(Enum):\n  2      IDLE = "idle"; LISTENING = "listening"; SPEAKING = "speaking"' }),
  tool('Grep', { pattern: 'self._voice =', path: 'orchestrator/' }, { output: 'session.py:212    self._voice = True\nsession.py:388    self._voice = False' }),
  tool('Edit', EDIT ? EDIT.input : {}, { output: 'The file orchestrator/session.py has been updated.' }),
  tool('Bash', { command: 'pytest tests/test_voice_parity.py -q' }, { output: '..............................................  [100%]\n46 passed in 3.82s' }),
];

const PHONE_A = [
  tool('run_script', { script: 'context/scripts/connect_tv.py' }, { output: '{"exit_code": 0, "stdout": "Connected to Fire TV · 10.0.0.42\\n", "stderr": ""}' }),
  tool('send_to_agent_session', { session_id: '4f2a91c0-7d1e-4b8b', message: 'Draft the living-room TV setup plan for Friday movie night.' }, { status: 'running', tool_use_id: 'send-1' }),
];
const PHONE_A_FEED = { 'send-1': ['Read assistant/devices/fire_tv.md', 'Listed 23 installed apps', 'Drafting plan, asking to exit plan mode'] };

/* ---------- live replay ---------- */

interface Frame {
  readonly blocks: Block[];
  readonly live: boolean;
}

function replayFrames(): Frame[] {
  const g = tool('Grep', { pattern: 'duck', path: 'android/' }, { status: 'running', id: 'rp-1' });
  const r = tool('Read', { file_path: 'android/app/src/main/java/AudioDucker.kt' }, { status: 'running', id: 'rp-2' });
  const b = tool('Bash', { command: './gradlew :app:testDebugUnitTest --tests "*Duck*"', description: 'Run the ducking tests' }, { status: 'running', id: 'rp-3' });
  const intro = text('rp-t0', 'Looking at how ducking works today.');
  const outro = text('rp-t1', 'Ducking lives in `AudioDucker.kt`; its tests pass. I will switch it to audio focus next.');
  const gD = withResult(g, 'AudioDucker.kt: 9 matches');
  const rD = withResult(r, '184 lines');
  const bD = withResult(b, '> Task :app:testDebugUnitTest\nBUILD SUCCESSFUL in 41s');
  return [
    { blocks: [intro], live: true },
    { blocks: [intro, g], live: true },
    { blocks: [intro, gD, r], live: true },
    { blocks: [intro, gD, rD, b], live: true },
    { blocks: [intro, gD, rD, bD], live: true },
    { blocks: [intro, gD, rD, bD, outro], live: true },
    { blocks: [intro, gD, rD, bD, outro], live: false },
  ];
}

function LiveReplay() {
  const frames = useMemo(() => replayFrames(), []);
  const [i, setI] = useState(frames.length - 1);
  const [playing, setPlaying] = useState(false);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const stop = (): void => {
    if (timer.current !== null) clearInterval(timer.current);
    timer.current = null;
    setPlaying(false);
  };
  useEffect(() => () => {
    if (timer.current !== null) clearInterval(timer.current);
  }, []);
  const play = (): void => {
    stop();
    setI(0);
    setPlaying(true);
    timer.current = setInterval(() => {
      setI((n) => {
        const next = Math.min(n + 1, frames.length - 1);
        if (next >= frames.length - 1) {
          if (timer.current !== null) clearInterval(timer.current);
          timer.current = null;
          setPlaying(false);
        }
        return next;
      });
    }, 1200);
  };
  const f = frames[i] as Frame;
  return (
    <>
      <div className={styles.controls}>
        <button type="button" className={styles.button} onClick={play}>
          {playing ? 'Playing…' : 'Replay live run'}
        </button>
        <span className={styles.muted}>{`step ${i + 1} / ${frames.length}`}</span>
      </div>
      <div className={styles.column}>
        <User>Make echo ducking use audio focus instead of lowering the stream volume.</User>
        <Run blocks={f.blocks} live={f.live} />
      </div>
    </>
  );
}

/* ---------- every tool in every state ---------- */

function StatesGrid() {
  return (
    <div className={styles.states}>
      <div className={styles.stateHead} aria-hidden="true">
        <span>Running</span>
        <span>Done</span>
        <span>Error</span>
      </div>
      {SAMPLES.map((s, i) => {
        const kind = s.kind ?? 'agent';
        const base = { id: `st-${i}` };
        return (
          <div key={s.title} className={styles.stateRow}>
            <ToolCard block={tool(s.name, s.input, { ...base, id: `st-${i}-r`, status: 'running', origin: 'history' })} sessionKind={kind} autoOpen={false} />
            <ToolCard block={tool(s.name, s.input, { ...base, id: `st-${i}-d`, output: s.output })} sessionKind={kind} autoOpen={false} />
            <ToolCard block={tool(s.name, s.input, { ...base, id: `st-${i}-e`, status: 'error', output: 'Error: permission denied' })} sessionKind={kind} autoOpen={false} />
          </div>
        );
      })}
    </div>
  );
}

const LONG_OUT = Array.from({ length: 260 }, (_, i) => `[${String(i + 1).padStart(3, ' ')}] building module ${i + 1}`).join('\n');

export function ToolsGallery() {
  const board = useMemo(
    () => [
      tool('Read', { file_path: 'orchestrator/session.py', offset: 211, limit: 3 }, { output: '642 lines · showing 212–214' }),
      tool('Edit', { file_path: 'api/routes/voice.py', old_string: '    ttl = 60\n', new_string: '    ttl = settings.voice_token_ttl\n' }, { output: 'Updated.' }),
      tool('Bash', { command: 'npm run build' }, { status: 'running', progress: { elapsed_seconds: 12, message: '' }, output: null }),
      tool('run_script', { script: 'context/scripts/browser_cmd.py', args: ['look'] }, { output: 'Snapshot: 38 refs · screenshot 1280×800' }),
      tool('WebFetch', { url: 'https://developer.android.com/media/optimize/audio-focus' }, { status: 'running', tool_use_id: 'stalled-fetch', progress: { elapsed_seconds: 134, message: '' } }),
      tool('mcp__chrome-devtools__take_screenshot', { filePath: '/tmp/living-room-tv.png' }, { output: '1920×1080 · 412 KB' }),
      tool('mcp__chrome-devtools__click', { uid: '14' }, { output: 'Clicked · page navigated' }),
      tool('TodoWrite', (SAMPLES.find((s) => s.name === 'TodoWrite') ?? { input: {} }).input, { output: 'Todos have been modified successfully.' }),
      tool('Task', { description: 'where is voice state set?', subagent_type: 'Explore', prompt: 'Find where _voice is assigned.' }, { status: 'running', progress: { elapsed_seconds: 31, message: '' } }),
      tool('update_assistant_config', { harness_model: 'claude-opus' }, { output: '{"harness_model": "claude-opus"}' }),
      tool('send_to_agent_session', { session_id: '7c1d22aa-91', message: 'Energy dashboard: add the weekly view' }, { status: 'error', output: 'Session is not open (404). Reopen it from history.' }),
      tool('Grep', { pattern: '_voice =', path: 'orchestrator/' }, { output: 'session.py:212   self._voice = True\nsession.py:388   self._voice = False' }),
    ],
    [],
  );

  return (
    <div className={styles.page}>
      <Section id="tools-board" title="Tool cards, all 12 categories" note="click a header to fold its output">
        <div className={styles.grid}>
          {board.map((b, i) => (
            <ToolCard key={b.id} block={b} sessionKind="orchestrator" autoOpen={i !== board.length - 1} stalled={b.tool_use_id === 'stalled-fetch'} />
          ))}
        </div>
      </Section>

      <Section id="tools-desktop" title="Conversation: N steps groups" note="earlier group collapsed once text followed; the latest one clicked open">
        <div className={styles.column}>
          <Run blocks={[...DESK_G1, text('d-t1', 'Voice state is split between `OrchestratorSession` and `OpenAIVoiceProvider`, and both flip `_voice` directly. I will move every transition into one `VoiceStateMachine`.')]} live={false} />
          <User>Go ahead. Keep the parity tests green.</User>
          <Run blocks={[text('d-t2', 'Extracting the state machine now.'), ...DESK_G2, text('d-t3', 'Done. All **46 parity tests** pass.')]} live={false} />
        </div>
      </Section>

      <Section id="tools-live" title="Live group (phone a)" note="send_to_agent_session streams the agent's activity into its card">
        <div className={styles.column}>
          <User>Plan the living-room TV setup for movie night on Friday.</User>
          <Run blocks={[text('a-t1', "On it. I'll check what the Fire TV can play, then ask an agent to draft the setup."), ...PHONE_A]} live feeds={PHONE_A_FEED} />
        </div>
      </Section>

      <Section id="tools-replay" title="Live run replay" note="expanded while live, collapses to one line once text follows">
        <LiveReplay />
      </Section>

      <Section id="tools-special" title="Special states">
        <div className={styles.grid}>
          <ToolCard block={tool('Bash', { command: 'npm test', description: 'Run the unit tests' }, { status: 'running' })} autoOpen />
          <ToolCard block={tool('Bash', { command: 'seq 260' }, { output: LONG_OUT })} autoOpen />
          <ToolCard block={tool('Bash', { command: 'false' }, { status: 'error', output: 'Error: Exit code 1\nmake: *** [all] Error 1' })} autoOpen />
          <ToolCard block={tool('Bash', { command: 'tail -f log' }, { status: 'no_result' })} autoOpen />
          <ToolCard block={tool('Read', { file_path: '/home/rodrigo/notes.md' }, { status: 'no_result', origin: 'history' })} autoOpen />
          <ToolCard block={tool('Glob', { pattern: '**/*.ts' }, { output: 'src/a.ts\nsrc/b.ts', inferred: true })} autoOpen />
          <ToolCard block={tool('read_file', { path: 'context/memory/MEMORY.md', start_line: 1, end_line: 3 }, { output: '# Memory Index\n\n## Feedback' })} sessionKind="orchestrator" autoOpen />
          <ToolCard block={tool('todo_write', { todos: [{ id: 'p0', content: 'Phase 0: migrate Qwen config', status: 'completed' }, { id: 'p1', content: 'Phase 1: symlink', status: 'in_progress' }] }, { output: 'ok' })} autoOpen />
        </div>
      </Section>

      <Section id="tools-states" title="Every tool: running · done · error" note="collapsed; click any header">
        <StatesGrid />
      </Section>
    </div>
  );
}
