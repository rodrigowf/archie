/**
 * Subagent UI (spec 12 §4.8; PLAN docs/projects/subagent-view §4.4): wording helpers, the inline
 * agent card (status from the Subagent, activity, Open), the auto-created tab row, the agent tab
 * (breadcrumb, read-only bar, Back to Main), Close finished, the Agents button + sheet, and the
 * live-region announcements. Sessions are seeded from the shared `subagent_*` fixtures.
 */
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Conversation, Subagent } from '@/protocol';
import { getSessionEntry } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { setupServices, teardownServices } from '../../../services/__tests__/fakes';
import { ConversationPanel } from '../../conversation/ConversationPanel';
import { preloadRich } from '../../conversation/lazyRich';
import { fixtureRun, seedSession } from '../../conversation/__tests__/helpers';
import { groupAgents } from '../AgentsSheet';
import { AgentsButton } from '../AgentsButton';
import { activityLine, formatElapsed, formatTokens, subagentElapsedMs, treeOrder } from '../labels';

beforeAll(async () => {
  await preloadRich();
  await import('../view');
});

/** The lazy agent views mount on the next tick. */
async function settle(): Promise<void> {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}
beforeEach(() => {
  setupServices();
});
afterEach(() => {
  teardownServices();
});

const sub = (over: Partial<Subagent>): Subagent => ({
  tool_use_id: 'x',
  parent_tool_use_id: null,
  agent_type: 'Explore',
  description: 'd',
  background: false,
  status: 'running',
  activity: null,
  usage: null,
  summary: null,
  started_at: null,
  ended_at: null,
  conv: null,
  held: null,
  lifecycle: true,
  loadError: null,
  ...over,
});

function seeded(name: string): Conversation {
  const conv = fixtureRun(name).conv;
  seedSession(conv);
  return conv;
}

describe('labels', () => {
  it('formats elapsed time, tokens and the activity line', () => {
    expect(formatElapsed(42_000)).toBe('42s');
    expect(formatElapsed(102_000)).toBe('1:42');
    expect(formatElapsed(3_723_000)).toBe('1:02:03');
    expect(formatTokens(950)).toBe('950 tokens');
    expect(formatTokens(1200)).toBe('1.2k tokens');
    expect(formatTokens(12_400)).toBe('12k tokens');
    expect(formatTokens(1_250_000)).toBe('1.3M tokens');
    expect(activityLine(sub({ activity: 'Grep', usage: { tokens: 1200, tool_uses: 3, duration_ms: null } }))).toBe('Grep · 3 tools · 1.2k tokens');
    expect(activityLine(sub({ status: 'done', usage: { tokens: null, tool_uses: 1, duration_ms: 5 } }))).toBe('Done · 1 tool');
    expect(activityLine(sub({ background: true }))).toBe('Working in the background');
  });

  it('elapsed: live while running, the reported duration once finished', () => {
    const start = '2026-10-12T12:00:00Z';
    const t0 = Date.parse(start);
    expect(subagentElapsedMs(sub({ started_at: start }), t0 + 5000)).toBe(5000);
    expect(subagentElapsedMs(sub({ status: 'done', started_at: start, usage: { tokens: null, tool_uses: null, duration_ms: 3000 } }), t0 + 9e6)).toBe(3000);
    expect(subagentElapsedMs(sub({ status: 'done', started_at: start, ended_at: '2026-10-12T12:00:09Z' }), 0)).toBe(9000);
    expect(subagentElapsedMs(sub({}), 0)).toBeNull();
  });

  it('tree order puts nested agents under their parent; groups split by state with finished last', () => {
    const list = [sub({ tool_use_id: 'a' }), sub({ tool_use_id: 'b', status: 'done' }), sub({ tool_use_id: 'n', parent_tool_use_id: 'a' })];
    expect(treeOrder(list).map((r) => `${r.sub.tool_use_id}${r.depth}`)).toEqual(['a0', 'n1', 'b0']);
    expect(groupAgents(list).map((g) => `${g.key}:${g.rows.map((r) => r.sub.tool_use_id).join(',')}`)).toEqual(['running:a,n', 'done:b']);
  });
});

describe('the agent card, tab row and agent tab', () => {
  it('auto-creates a placeholder tab per agent and keeps Main selected (FOCUS-1)', async () => {
    seeded('subagent_nested_failed_stopped_history');
    render(<ConversationPanel localId="L1" hidden={false} />);
    await settle();
    const tabs = within(screen.getByRole('tablist', { name: 'Agents' })).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['Main', 'Earlier search', 'Plan migration', '↳ Search callers']);
    expect(tabs[0]?.getAttribute('aria-selected')).toBe('true');
  });

  it('the card shows the agent status (not the spawn result), its activity line and Open', () => {
    seeded('subagent_background_after_turn');
    const { container } = render(<ConversationPanel localId="L1" hidden={false} />);
    const card = container.querySelector('[data-tool="Task"]') as HTMLElement;
    expect(card.getAttribute('data-agent-status')).toBe('done');
    expect(within(card).getByText('Done · 1 tool · 1.2k tokens')).toBeTruthy();
    fireEvent.click(within(card).getByRole('button', { name: 'Open general-purpose: Digest logs' }));
    expect(getSessionEntry('L1')?.handle.store.getState().subagentTab).toBe('t1');
  });

  it('SA-7: the task notification is a "Background agent" divider with its summary, not a bubble', () => {
    seeded('subagent_background_after_turn');
    const { container } = render(<ConversationPanel localId="L1" hidden={false} />);
    const notice = container.querySelector('[data-notice="background"]') as HTMLElement;
    expect(notice.textContent).toContain('Background agent');
    expect(notice.textContent).toContain('Agent "Digest logs" finished');
    expect(container.querySelectorAll('[data-kind="user"]')).toHaveLength(1);
  });

  it('a running background agent keeps its card running after the spawn card completed', () => {
    const fx = fixtureRun('subagent_background_after_turn');
    const mid = fx.trace[6]?.next as Conversation; // after turn_complete, agent still running
    seedSession(mid);
    const { container } = render(<ConversationPanel localId="L1" hidden={false} />);
    const card = container.querySelector('[data-tool="Task"]') as HTMLElement;
    expect(card.getAttribute('data-agent-status')).toBe('running');
    expect(card.getAttribute('data-status')).toBe('running');
  });

  it('selecting a tab shows the transcript with breadcrumb and the read-only bar; Main goes back', async () => {
    seeded('subagent_open_mid_run');
    const { container } = render(<ConversationPanel localId="L1" hidden={false} composer={<div data-testid="composer" />} />);
    await settle();
    fireEvent.click(screen.getByRole('tab', { name: /Find scroll logic/ }));
    await settle();
    const view = container.querySelector('[data-subagent-view="t1"]') as HTMLElement;
    expect(view).not.toBeNull();
    expect(within(view).getByRole('navigation', { name: 'Agent path' }).textContent).toContain('Main');
    expect(view.querySelectorAll('[data-tool="Read"], [data-tool="Grep"]').length).toBeGreaterThan(0);
    expect(screen.getByRole('region', { name: 'Agent transcript, read only' }).textContent).toContain('Viewing Explore: Find scroll logic');
    expect(screen.getByTestId('composer').parentElement?.hasAttribute('hidden')).toBe(true);
    await expectNoAxeViolations(container);
    fireEvent.click(screen.getByRole('button', { name: 'Back to Main' }));
    expect(container.querySelector('[data-subagent-view]')).toBeNull();
    expect(screen.getByTestId('composer').parentElement?.hasAttribute('hidden')).toBe(false);
  });

  it('Close finished hides finished tabs; opening one from the card brings it back', async () => {
    seeded('subagent_nested_failed_stopped_history');
    const { container } = render(<ConversationPanel localId="L1" hidden={false} />);
    await settle();
    fireEvent.click(screen.getByRole('button', { name: 'Close finished agent tabs' }));
    const tabs = () => within(screen.getByRole('tablist', { name: 'Agents' })).getAllByRole('tab').map((t) => t.textContent);
    expect(tabs()).toEqual(['Main']);
    const card = container.querySelector('[data-tool="Task"]') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: /^Open/ }));
    expect(tabs()).toEqual(['Main', 'Earlier search']);
  });
});

describe('Agents button and sheet', () => {
  it('is hidden without agents and shows the running count', () => {
    seedSession(fixtureRun('plain_text_turn').conv);
    const { container, unmount } = render(<AgentsButton localId="L1" />);
    expect(container.firstChild).toBeNull();
    unmount();
    teardownServices();
    setupServices();
    const fx = fixtureRun('subagent_open_mid_run');
    seedSession(fx.trace[3]?.next as Conversation); // t1 running
    render(<AgentsButton localId="L1" />);
    expect(screen.getByRole('button', { name: 'Agents: 1 running, 1 in total' })).toBeTruthy();
  });

  it('opens the grouped list; a row opens its tab', async () => {
    seeded('subagent_nested_failed_stopped_history');
    render(<AgentsButton localId="L1" />);
    fireEvent.click(screen.getByRole('button', { name: /^Agents:/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: /Done/ })).toBeTruthy();
    expect(within(dialog).getByRole('heading', { name: /Failed or stopped/ })).toBeTruthy();
    fireEvent.click(within(dialog).getByText('Plan migration'));
    expect(getSessionEntry('L1')?.handle.store.getState().subagentTab).toBe('t1');
  });
});

describe('announcements', () => {
  it('announces a status change politely, not the agents already known', async () => {
    const fx = fixtureRun('subagent_foreground_unopened');
    const running = fx.trace[3]?.next as Conversation;
    const { handle } = seedSession(running);
    render(<ConversationPanel localId="L1" hidden={false} />);
    await settle();
    await act(async () => {
      handle.setConv(fx.conv);
      handle.flush();
      await new Promise((r) => setTimeout(r, 80));
    });
    expect(document.querySelector('[data-announcer="polite"]')?.textContent).toBe('Agent finished: Explore: Find scroll logic');
  });
});
