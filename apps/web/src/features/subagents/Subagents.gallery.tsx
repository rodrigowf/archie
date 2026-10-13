/**
 * Subagent gallery board (dev only; spec 12 §4.8, PLAN §4.4): the inline agent card in every
 * state, the conversation panel with the auto-created agent tabs (Main selected, then an agent's
 * tab with breadcrumb and read-only bar), and the agents sheet (side and bottom) inline. The
 * session is built by feeding wire frames through the real reducer.
 */
import { useState } from 'react';
import { coerceFrame, initialConversation, stepConversation, type Conversation, type ConversationInput, type MessagesPage } from '@/protocol';
import { ConversationPanel } from '@/features/conversation';
import { createSessionStore, getSessionEntry, registerSession } from '@/stores';
import AgentsSheet from './AgentsSheet';
import { AgentsButton } from './AgentsButton';
import styles from './Subagents.gallery.module.css';

const t0 = Date.now() - 95_000;
const iso = (dt: number): string => new Date(t0 + dt).toISOString();

function frames(): Record<string, unknown>[] {
  const spawn = (id: string, type: string, description: string, extra: Record<string, unknown> = {}) => ({
    type: 'tool_use',
    tool_use_id: id,
    tool_name: 'Agent',
    tool_input: { subagent_type: type, description, prompt: `${description}.`, ...extra },
  });
  const started = (id: string, type: string, description: string, at: number, parent: string | null = null, background = false) => ({
    type: 'subagent_started',
    tool_use_id: id,
    parent_tool_use_id: parent,
    agent_type: type,
    description,
    background,
    started_at: iso(at),
  });
  const ev = (id: string, frame: Record<string, unknown>) => ({ type: 'subagent_event', tool_use_id: id, frame });
  const prog = (id: string, activity: string, tokens: number, tools: number) => ({
    type: 'subagent_progress',
    tool_use_id: id,
    activity,
    usage: { tokens, tool_uses: tools, duration_ms: null },
  });
  const fin = (id: string, status: string, summary: string, ms: number, tokens: number, tools: number) => ({
    type: 'subagent_finished',
    tool_use_id: id,
    status,
    summary,
    usage: { tokens, tool_uses: tools, duration_ms: ms },
    ended_at: iso(ms),
  });
  return [
    { type: 'status', status: 'processing' },
    { type: 'text_complete', text: 'I will split this across four agents.' },
    spawn('g-a', 'Explore', 'Find the scroll logic'),
    started('g-a', 'Explore', 'Find the scroll logic', 0),
    spawn('g-b', 'general-purpose', 'Review the tests'),
    started('g-b', 'general-purpose', 'Review the tests', 1000),
    spawn('g-c', 'general-purpose', 'Digest the logs', { run_in_background: true }),
    started('g-c', 'general-purpose', 'Digest the logs', 2000, null, true),
    { type: 'tool_result', tool_use_id: 'g-c', output: 'Async agent launched successfully. agentId: a1b2', is_error: false },
    spawn('g-d', 'Plan', 'Plan the migration'),
    started('g-d', 'Plan', 'Plan the migration', 3000),
    ev('g-a', { type: 'tool_use', tool_use_id: 'a1', tool_name: 'Grep', tool_input: { pattern: 'pinned', path: 'src/' } }),
    ev('g-a', { type: 'tool_result', tool_use_id: 'a1', output: 'useMessageWindow.ts:41', is_error: false }),
    ev('g-a', spawn('g-n', 'Explore', 'Count the callers')),
    started('g-n', 'Explore', 'Count the callers', 9000, 'g-a'),
    prog('g-a', 'Agent', 4200, 2),
    ev('g-b', { type: 'tool_use', tool_use_id: 'b1', tool_name: 'Bash', tool_input: { command: 'npx vitest run scroll', description: 'Run the scroll tests' } }),
    ev('g-b', { type: 'tool_result', tool_use_id: 'b1', output: '12 passed', is_error: false }),
    fin('g-b', 'done', '`scroll.test.tsx` covers pinning, the freeze buffer and prepends; **12 tests pass**.', 42_000, 12_400, 3),
    { type: 'tool_result', tool_use_id: 'g-b', output: 'scroll.test.tsx covers pinning; 12 tests pass.', is_error: false },
    prog('g-c', 'Read', 2100, 2),
    fin('g-d', 'failed', 'Planner crashed: context window exceeded.', 15_000, 180_000, 9),
    { type: 'tool_result', tool_use_id: 'g-d', output: 'Planner crashed: context window exceeded.', is_error: true },
  ];
}

function build(localId: string): Conversation {
  let c = initialConversation({ localId, kind: 'agent', sdkId: `sdk-${localId}`, provider: 'claude', subscribed: true });
  c = { ...c, history: { loaded: true, startIndex: 0, totalCount: 0, hasMore: false } };
  const step = (input: ConversationInput): void => {
    c = stepConversation(c, input).state;
  };
  step({ type: 'local_send', text: 'Where does the chat decide to follow new messages, and is it tested?' });
  for (const raw of frames()) {
    const d = coerceFrame(raw);
    if (d.ok) step({ type: 'frame', frame: d.frame });
  }
  // the first agent's transcript, materialised as if its tab had been opened
  const page: MessagesPage = {
    messages: [
      { role: 'user', text: 'Find where the chat list decides to follow new messages.', blocks: [{ type: 'text', text: 'Find where the chat list decides to follow new messages.' }] },
      { role: 'assistant', text: '', blocks: [{ type: 'tool_use', tool_use_id: 'a1', tool_name: 'Grep', tool_input: { pattern: 'pinned', path: 'src/' } }] },
      { role: 'user', text: '', blocks: [{ type: 'tool_result', tool_use_id: 'a1', output: 'useMessageWindow.ts:41', is_error: false }] },
      { role: 'assistant', text: 'It is `useMessageWindow`; checking who calls it.', blocks: [{ type: 'text', text: 'It is `useMessageWindow`; checking who calls it.' }, { type: 'tool_use', tool_use_id: 'g-n', tool_name: 'Agent', tool_input: { subagent_type: 'Explore', description: 'Count the callers' } }] },
    ],
    total_count: 4,
    has_more: false,
    start_index: 0,
  };
  step({ type: 'open_subagent', toolUseId: 'g-a' });
  step({ type: 'subagent_page', toolUseId: 'g-a', mode: 'replace', response: page });
  return c;
}

const inert = (localId: string) => ({ localId, dispose: () => undefined });

function seed(localId: string, tab: string | null): void {
  if (getSessionEntry(localId)) return;
  const handle = createSessionStore({ localId, conv: build(localId) });
  registerSession(localId, { handle, runtime: inert(localId) });
  if (tab) handle.patch({ subagentTab: tab });
}

function Frame({ title, note, children }: { title: string; note?: string; children: React.ReactNode }) {
  return (
    <section className={styles.section}>
      <h3 className={styles.title}>
        {title}
        {note ? <small>{note}</small> : null}
      </h3>
      {children}
    </section>
  );
}

export function SubagentsGallery() {
  seed('g-sa-main', null);
  seed('g-sa-tab', 'g-a');
  seed('g-sa-sheet', null);
  const [sheet, setSheet] = useState<'side' | 'bottom'>('side');
  return (
    <div className={styles.page}>
      <Frame title="Conversation with agents" note="cards · auto-created tabs · Main selected">
        <div className={styles.panel}>
          <ConversationPanel localId="g-sa-main" hidden={false} composer={<AgentsButton localId="g-sa-main" />} />
        </div>
      </Frame>
      <Frame title="An agent's tab" note="breadcrumb · transcript · read-only bar">
        <div className={styles.panel}>
          <ConversationPanel localId="g-sa-tab" hidden={false} />
        </div>
      </Frame>
      <Frame title="Agents sheet" note="grouped Running · Done · Failed or stopped; nested indented">
        <div className={styles.switch}>
          <button type="button" onClick={() => setSheet('side')} aria-pressed={sheet === 'side'}>
            Side sheet
          </button>
          <button type="button" onClick={() => setSheet('bottom')} aria-pressed={sheet === 'bottom'}>
            Bottom sheet
          </button>
        </div>
        <div className={styles.sheetHost}>
          <AgentsSheet localId="g-sa-sheet" open inline kind={sheet} onClose={() => undefined} />
        </div>
      </Frame>
    </div>
  );
}
