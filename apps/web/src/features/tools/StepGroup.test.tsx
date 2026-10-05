import { within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { groupToolSteps, type Block, type TextBlock, type ToolBlock } from '@/protocol';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { tool, withResult } from './__fixtures__/blocks';
import { StepGroup, groupStatus, groupSummary } from './StepGroup';
import { ToolCard } from './ToolCard';

const text = (t: string): TextBlock => ({ id: `t-${t}`, type: 'text', text: t, streaming: false, scope: 'turn', origin: 'live' });

function groupHeader(container: HTMLElement): HTMLButtonElement {
  return container.querySelector('button[aria-expanded]') as HTMLButtonElement;
}
function groupBody(container: HTMLElement): HTMLElement {
  return document.getElementById(groupHeader(container).getAttribute('aria-controls') ?? '') as HTMLElement;
}
function cardHeaders(container: HTMLElement): HTMLButtonElement[] {
  return Array.from(groupBody(container).querySelectorAll('button[aria-expanded]')).filter((b) => b.closest('[data-tool]')) as HTMLButtonElement[];
}

/** A tiny run renderer, the way W-09 will use the pieces. */
function Run({ blocks, live }: { blocks: readonly Block[]; live: boolean }) {
  return (
    <div>
      {groupToolSteps(blocks, { live }).map((item) =>
        item.kind === 'steps' ? (
          <StepGroup key={item.key} blocks={item.blocks} live={item.live} />
        ) : item.block.type === 'tool' ? (
          <ToolCard key={item.block.id} block={item.block} autoOpen={live && item.block === blocks[blocks.length - 1]} />
        ) : (
          <p key={item.block.id}>{item.block.type === 'text' ? item.block.text : ''}</p>
        ),
      )}
    </div>
  );
}

describe('groupSummary / groupStatus', () => {
  it('labels in first-seen order with repeat counts', () => {
    expect(groupSummary(['Grep', 'Read', 'Read'])).toBe('Grep, Read ×2');
    expect(groupSummary(['Read', 'Grep', 'Edit', 'Bash'])).toBe('Read, Grep, Edit, Bash');
  });
  it('running > error > no output > done', () => {
    const d = tool('Read', {});
    expect(groupStatus([d, tool('Bash', {}, { status: 'running' }), tool('Bash', {}, { status: 'error' })])).toBe('running');
    expect(groupStatus([d, tool('Bash', {}, { status: 'error' }), tool('Bash', {}, { status: 'no_result' })])).toBe('error');
    expect(groupStatus([d, tool('Bash', {}, { status: 'no_result' })])).toBe('no_result');
    expect(groupStatus([d, d])).toBe('done');
  });
});

describe('StepGroup (IA §9.3)', () => {
  const steps = (): ToolBlock[] => [
    tool('Grep', { pattern: '_voice', path: 'orchestrator/' }, { output: 'session.py: 14 matches' }),
    tool('Read', { file_path: 'orchestrator/session.py' }, { output: '642 lines' }),
    tool('Read', { file_path: 'orchestrator/providers/openai_voice.py' }, { status: 'running' }),
  ];

  it('live: expanded, "running" summary, cards open and streaming into their output', async () => {
    const view = renderUi(<StepGroup blocks={steps()} live />);
    const h = groupHeader(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('true');
    expect(h.textContent).toContain('3 steps');
    expect(h.textContent).toContain('running');
    expect(groupBody(view.container).hidden).toBe(false);
    const cards = cardHeaders(view.container);
    expect(cards).toHaveLength(3);
    for (const c of cards) expect(c.getAttribute('aria-expanded')).toBe('true');
    expect(within(groupBody(view.container)).getByText('session.py: 14 matches')).toBeTruthy();
    expect(within(groupBody(view.container)).getByText('Running…')).toBeTruthy();
    await expectNoAxeViolations(view.container);
  });

  it('collapses to a one-line summary once text follows; cards fold too', () => {
    const blocks = steps();
    const view = renderUi(<StepGroup blocks={blocks} live />);
    const done = blocks.map((b) => (b.status === 'running' ? withResult(b, '388 lines') : b));
    view.rerender(<StepGroup blocks={done} live={false} />);
    const h = groupHeader(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    expect(groupBody(view.container).hidden).toBe(true);
    expect(h.textContent).toContain('Grep, Read ×2');
    expect(h.querySelector('[data-icon="check_circle"]')).toBeTruthy();
    for (const c of cardHeaders(view.container)) expect(c.getAttribute('aria-expanded')).toBe('false');
  });

  it('the user can open a collapsed group; its cards show their output (every card still expandable)', async () => {
    const view = renderUi(<StepGroup blocks={steps().map((b) => withResult(b, `out ${b.id}`))} live={false} />);
    const h = groupHeader(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    await view.user.click(h);
    expect(h.getAttribute('aria-expanded')).toBe('true');
    const cards = cardHeaders(view.container);
    for (const c of cards) expect(c.getAttribute('aria-expanded')).toBe('true');
    const first = cards[0] as HTMLButtonElement;
    await view.user.click(first);
    expect(first.getAttribute('aria-expanded')).toBe('false');
    expect(cards[1]?.getAttribute('aria-expanded')).toBe('true');
    // keyboard on the group header
    h.focus();
    await view.user.keyboard('{Enter}');
    expect(h.getAttribute('aria-expanded')).toBe('false');
  });

  it('a live group the user collapsed still auto-collapses / stays folded when text follows', async () => {
    const blocks = steps();
    const view = renderUi(<StepGroup blocks={blocks} live />);
    await view.user.click(groupHeader(view.container));
    expect(groupHeader(view.container).getAttribute('aria-expanded')).toBe('false');
    view.rerender(<StepGroup blocks={blocks} live={false} />);
    expect(groupHeader(view.container).getAttribute('aria-expanded')).toBe('false');
  });

  it('R7: a result that arrives while the group is collapsed is shown when opened', async () => {
    const blocks = steps();
    const view = renderUi(<StepGroup blocks={blocks} live={false} />);
    const late = blocks.map((b) => (b.status === 'running' ? withResult(b, 'late result') : b));
    view.rerender(<StepGroup blocks={late} live={false} />);
    await view.user.click(groupHeader(view.container));
    expect(within(groupBody(view.container)).getByText('late result')).toBeTruthy();
  });

  it('error count and status for screen readers; stalled card shows the hourglass', () => {
    const blocks = [tool('Bash', { command: 'a' }, { status: 'error', output: 'x' }), tool('WebFetch', { url: 'https://x.dev' }, { status: 'running', tool_use_id: 'stall-1' })];
    const view = renderUi(<StepGroup blocks={blocks} live={false} stalledToolUseId="stall-1" />);
    expect(groupHeader(view.container).textContent).toContain('running');
    expect(groupBody(view.container).querySelector('[data-icon="hourglass_top"]')).toBeTruthy();
    const errs = renderUi(<StepGroup blocks={[blocks[0] as ToolBlock, tool('Read', {})]} live={false} />);
    expect(groupHeader(errs.container).textContent).toContain('1 failed');
  });

  it('header is valid HTML (phrasing only) with aria-controls pointing outside it', () => {
    const view = renderUi(<StepGroup blocks={steps()} live />);
    const h = groupHeader(view.container);
    expect(h.querySelector('div, p, ul')).toBeNull();
    expect(h.contains(groupBody(view.container))).toBe(false);
  });

  it('shows at most 4 category tiles, then +N', () => {
    const many = Array.from({ length: 6 }, (_, i) => tool('Read', { file_path: `/f${i}` }));
    const view = renderUi(<StepGroup blocks={many} live={false} />);
    expect(groupHeader(view.container).textContent).toContain('+2');
    expect(groupHeader(view.container).textContent).toContain('6 steps');
  });
});

describe('grouping with groupToolSteps in a run (live behaviour)', () => {
  it('stream order kept; tail group expanded while live, collapses when text arrives', () => {
    const g1 = [tool('Grep', { pattern: 'x' }), tool('Read', { file_path: '/a' })];
    const g2 = [tool('Read', { file_path: '/b' }), tool('Edit', { file_path: '/b', old_string: 'a', new_string: 'b' }, { status: 'running' })];
    const blocks: Block[] = [...g1, text('Found it.'), ...g2];
    const view = renderUi(<Run blocks={blocks} live />);
    const groups = view.container.querySelectorAll('[data-live]');
    expect(groups).toHaveLength(2);
    expect(groups[0]?.getAttribute('data-live')).toBe('false');
    expect(groups[1]?.getAttribute('data-live')).toBe('true');
    const headers = Array.from(groups).map((g) => g.querySelector('button') as HTMLButtonElement);
    expect(headers.map((h) => h.getAttribute('aria-expanded'))).toEqual(['false', 'true']);
    // order: group, text, group
    expect(view.container.firstElementChild?.children[1]?.textContent).toBe('Found it.');

    const g2done = [g2[0] as ToolBlock, withResult(g2[1] as ToolBlock, 'updated')];
    view.rerender(<Run blocks={[...g1, text('Found it.'), ...g2done, text('Done.')]} live />);
    const after = Array.from(view.container.querySelectorAll('[data-live]')).map((g) => (g.querySelector('button') as HTMLButtonElement).getAttribute('aria-expanded'));
    expect(after).toEqual(['false', 'false']);
  });

  it('a single tool call is a solo card, not a group', () => {
    const view = renderUi(<Run blocks={[text('Checking.'), tool('Bash', { command: 'ls' }, { status: 'running' })]} live />);
    expect(view.container.querySelector('[data-live]')).toBeNull();
    const card = view.container.querySelector('[data-tool="Bash"] button') as HTMLButtonElement;
    expect(card.getAttribute('aria-expanded')).toBe('true');
  });
});
