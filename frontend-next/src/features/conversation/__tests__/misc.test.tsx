/**
 * Orchestrator view parts and the remaining timeline pieces: agent approvals (spec 12 §6.9, PM-5),
 * the Archie empty state (greeting, voice button, suggestion → draft), the queue tray (I-12),
 * unmatched tool results (R-9), the inline visual card, and an axe pass over a busy conversation.
 */
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initialConversation, type Conversation, type Entry, type ToolBlock } from '@/protocol';
import { catalogStore, setCatalogItems, tabsStore } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { FakeWebSocket, setupServices, teardownServices } from '../../../services/__tests__/fakes';
import { ConversationPanel } from '../ConversationPanel';
import { visualFromTool } from '../entries/VisualCard';
import { fixtureRun, seedSession } from './helpers';

beforeEach(() => {
  setupServices();
});
afterEach(() => {
  teardownServices();
});

function orch(over: Partial<Conversation> = {}): Conversation {
  const c = initialConversation({ localId: 'O1', kind: 'orchestrator', sdkId: 'O1', subscribed: true });
  return { ...c, history: { loaded: true, startIndex: 0, totalCount: 0, hasMore: false }, ...over };
}

const tool = (id: string, name: string, input: Record<string, unknown>, status: ToolBlock['status'] = 'done'): ToolBlock => ({
  id,
  type: 'tool',
  tool_use_id: `tu-${id}`,
  tool_name: name,
  tool_input: input,
  status,
  output: status === 'running' ? null : 'ok',
  scope: 'turn',
  origin: 'live',
});

describe('orchestrator view', () => {
  it('agent approvals: "<agent> wants to start" with the plan; Approve answers on the agent socket', () => {
    setCatalogItems('sessions', [
      {
        session_id: 'sdk-a9',
        local_id: 'A9',
        title: 'TV setup plan',
        started_at: '',
        last_activity: '',
        message_count: 1,
        is_orchestrator: false,
        provider: 'claude',
      },
    ] as never);
    seedSession(
      orch({ agentApprovals: [{ localId: 'A9', request_id: 'r9', tool_name: 'ExitPlanMode', tool_input: { plan: '1. Check Kodi\n2. Queue films' } }] }),
    );
    render(<ConversationPanel localId="O1" hidden={false} />);
    const c = screen.getByRole('region', { name: 'Permission request from TV setup plan' });
    expect(c.textContent).toContain('TV setup plan wants to start');
    expect(within(c).getByText('Queue films')).toBeTruthy();
    fireEvent.click(within(c).getByRole('button', { name: 'Approve' }));
    const ws = FakeWebSocket.last('/api/sessions/chat');
    ws.open();
    expect(ws.messages()).toEqual([{ type: 'permission_response', session_id: 'A9', request_id: 'r9', decision: 'allow' }]);
    expect(within(c).getByRole('button', { name: 'Approve' }).getAttribute('aria-disabled')).toBe('true');
    catalogStore.setState({ sessions: { ...catalogStore.getState().sessions, items: [] } });
  });

  it('empty Archie conversation: greeting, big voice button, suggestions fill the draft', () => {
    const { handle } = seedSession(orch());
    const onStartVoice = vi.fn();
    render(<ConversationPanel localId="O1" hidden={false} onStartVoice={onStartVoice} />);
    expect(screen.getByText(/What are we doing\?/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Start voice conversation' }));
    expect(onStartVoice).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /Plan the living-room TV setup/ }));
    expect(handle.store.getState().draft).toBe('Plan the living-room TV setup');
  });

  it('the voice button is hidden without a voice handler', () => {
    seedSession(orch());
    render(<ConversationPanel localId="O1" hidden={false} />);
    expect(screen.queryByRole('button', { name: 'Start voice conversation' })).toBeNull();
  });
});

describe('timeline extras', () => {
  it('queued prompts show in the tray, not in the timeline (I-12)', () => {
    const c = initialConversation({ localId: 'Q1', kind: 'agent', sdkId: 's', provider: 'claude', subscribed: true });
    const user: Entry = { id: 'u1', kind: 'user', text: 'first', origin: 'local', state: 'sent' };
    seedSession({ ...c, entries: [user], inTurn: true, status: 'processing', queue: [{ text: 'second', owner: 'local' }] });
    render(<ConversationPanel localId="Q1" hidden={false} />);
    const tray = screen.getByLabelText('Queued messages');
    expect(tray.textContent).toContain('second');
    expect(tray.textContent).toContain('Queued, sends when the reply ends');
    expect(document.querySelectorAll('[data-entry-id]')).toHaveLength(1);
  });

  it('unmatched tool results are reachable from a chip (R-9)', () => {
    const c = initialConversation({ localId: 'U1', kind: 'agent', sdkId: 's', provider: 'claude', subscribed: true });
    seedSession({
      ...c,
      entries: [{ id: 'u1', kind: 'user', text: 'x', origin: 'local', state: 'sent' }],
      unattributed: [{ output: 'orphan output', is_error: false, origin: 'live' }],
    });
    render(<ConversationPanel localId="U1" hidden={false} />);
    fireEvent.click(screen.getByRole('button', { name: '1 tool result could not be matched' }));
    expect(screen.getByRole('dialog').textContent).toContain('orphan output');
  });

  it('a visual written under public/ gets an inline card with Open (and Show on TV only when cast is available)', () => {
    const w = tool('w1', 'Write', { file_path: '/home/rodrigo/assistant/context/public/visualizations/energy/index.html', content: '<html>' });
    expect(visualFromTool(w)).toEqual({ path: 'visualizations/energy/index.html', url: '/visualizations/energy/index.html' });
    expect(visualFromTool({ ...w, status: 'running' })).toBeNull();
    expect(visualFromTool(tool('r', 'Read', { file_path: '/x/public/a.html' }))).toBeNull();
    const c = orch({
      entries: [{ id: 'a1', kind: 'assistant', blocks: [w, { id: 't', type: 'text', text: 'On the TV.', streaming: false, scope: 'turn', origin: 'live' }] }],
    });
    seedSession(c);
    render(<ConversationPanel localId="O1" hidden={false} />);
    const v = document.querySelector('[data-block="visual"]') as HTMLElement;
    expect(v.textContent).toContain('energy');
    expect(within(v).queryByRole('button', { name: /Show on TV/ })).toBeNull();
    fireEvent.click(within(v).getByRole('button', { name: 'Open' }));
    expect(tabsStore.getState().activeId).toBe('viz:visualizations/energy/index.html');
  });
});

describe('accessibility', () => {
  it('a conversation with tools, a permission and cards has no axe violations', async () => {
    const { conv } = fixtureRun('permission_request_resolve');
    const mid = fixtureRun('stall_notice_repeated_seq').conv;
    seedSession({ ...conv, stall: mid.stall, status: 'tool_use', inTurn: true });
    const { container } = render(<ConversationPanel localId={conv.ref.localId} hidden={false} />);
    await act(async () => {
      await expectNoAxeViolations(container);
    });
  });
});
