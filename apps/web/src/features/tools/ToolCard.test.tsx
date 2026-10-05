import { act, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ToolBlock } from '@/protocol';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { SAMPLES, tool, withResult } from './__fixtures__/blocks';
import { resetTiming, setTimingClock } from './timing';
import { ToolCard } from './ToolCard';

function header(container: HTMLElement): HTMLButtonElement {
  return container.querySelector('button[aria-expanded]') as HTMLButtonElement;
}

function body(container: HTMLElement): HTMLElement {
  const h = header(container);
  return document.getElementById(h.getAttribute('aria-controls') ?? '') as HTMLElement;
}

function output(container: HTMLElement): HTMLElement | null {
  return body(container).querySelector('[data-kind]:not([data-kind="diff"])');
}

beforeEach(() => resetTiming());

describe('ToolCard: every F-05 renderer, collapsed and expanded, running / done / error', () => {
  it.each(SAMPLES.map((s) => [s.title, s] as const))('%s', async (_title, s) => {
    const kind = s.kind ?? 'agent';
    // running, collapsed (autoOpen=false)
    const running = tool(s.name, s.input, { status: 'running' });
    const view = renderUi(<ToolCard block={running} sessionKind={kind} autoOpen={false} />);
    const h = header(view.container);
    const openByDefault = s.name === 'TodoWrite'; // the only `defaultOpen: 'always'` tool
    expect(h.getAttribute('aria-expanded')).toBe(String(openByDefault));
    expect(body(view.container).hidden).toBe(!openByDefault);
    expect(h.textContent).toContain('running');
    // valid HTML: the header button holds phrasing content only (fixes button>div)
    expect(h.querySelector('div, p, pre, ul, dl')).toBeNull();
    expect(h.contains(body(view.container))).toBe(false);

    // expand while running: "Running…"/waiting text, never empty (fixes Bash §6.2)
    if (!openByDefault) await view.user.click(h);
    expect(h.getAttribute('aria-expanded')).toBe('true');
    expect(output(view.container)?.getAttribute('data-kind')).toBe('running');

    // done: the output shows in place (TC-1)
    view.rerender(<ToolCard block={withResult(running, s.output)} sessionKind={kind} autoOpen={false} />);
    expect(h.textContent).toContain('done');
    await waitFor(() => expect(output(view.container)?.getAttribute('data-kind')).toBe('output'));

    // error
    view.rerender(<ToolCard block={withResult(running, 'Error: boom', true)} sessionKind={kind} autoOpen={false} />);
    expect(h.textContent).toContain('failed');
    expect(within(body(view.container)).getByText(/Error: boom/)).toBeTruthy();

    // collapse again
    await view.user.click(h);
    expect(body(view.container).hidden).toBe(true);
    await expectNoAxeViolations(view.container);
  });
});

describe('ToolCard R7 (result delivery in the UI)', () => {
  it('a result arriving while expanded updates the open output region', async () => {
    const b = tool('Bash', { command: 'npm run build' }, { status: 'running' });
    const view = renderUi(<ToolCard block={b} autoOpen />);
    expect(header(view.container).getAttribute('aria-expanded')).toBe('true');
    expect(within(body(view.container)).getByText('Running…')).toBeTruthy();
    view.rerender(<ToolCard block={withResult(b, 'built in 12s')} autoOpen />);
    expect(within(body(view.container)).getByText('built in 12s')).toBeTruthy();
    expect(within(body(view.container)).queryByText('Running…')).toBeNull();
  });

  it('a result arriving while collapsed (never opened) shows on expand', async () => {
    const b = tool('Read', { file_path: '/a.ts' }, { status: 'running' });
    const view = renderUi(<ToolCard block={b} autoOpen={false} />);
    view.rerender(<ToolCard block={withResult(b, 'file body')} autoOpen={false} />);
    await view.user.click(header(view.container));
    expect(within(body(view.container)).getByText('file body')).toBeTruthy();
  });

  it('a result arriving after the card folded (was open, then collapsed) is there on re-open', async () => {
    const b = tool('Grep', { pattern: 'x' }, { status: 'running' });
    const view = renderUi(<ToolCard block={b} autoOpen />);
    view.rerender(<ToolCard block={b} autoOpen={false} />); // text followed: folds
    expect(body(view.container).hidden).toBe(true);
    view.rerender(<ToolCard block={withResult(b, 'a.ts:1:x')} autoOpen={false} />);
    await view.user.click(header(view.container));
    expect(within(body(view.container)).getByText('a.ts:1:x')).toBeTruthy();
  });

  it('no_result: "No output received" live, "No output recorded" from history; never "done"', () => {
    const live = renderUi(<ToolCard block={tool('Bash', { command: 'x' }, { status: 'no_result' })} autoOpen />);
    expect(within(body(live.container)).getByText('No output received')).toBeTruthy();
    expect(header(live.container).textContent).toContain('no output');
    expect(header(live.container).textContent).not.toContain('done');
    const hist = renderUi(<ToolCard block={tool('Bash', { command: 'x' }, { status: 'no_result', origin: 'history' })} autoOpen />);
    expect(within(body(hist.container)).getByText('No output recorded')).toBeTruthy();
  });

  it('no_result is upgraded by a later result (R-6)', () => {
    const b = tool('Bash', { command: 'x' }, { status: 'no_result' });
    const view = renderUi(<ToolCard block={b} autoOpen />);
    view.rerender(<ToolCard block={withResult(b, 'late output')} autoOpen />);
    expect(within(body(view.container)).getByText('late output')).toBeTruthy();
  });

  it('inferred results carry a "matched by position" hint (TC-3)', () => {
    const view = renderUi(<ToolCard block={tool('Bash', { command: 'x' }, { output: 'out', inferred: true })} autoOpen />);
    expect(within(body(view.container)).getByText('matched by position')).toBeTruthy();
  });

  it('a done tool with an empty output says so instead of an empty box', () => {
    const view = renderUi(<ToolCard block={tool('Bash', { command: 'true' }, { output: '' })} autoOpen />);
    expect(within(body(view.container)).getByText('No output')).toBeTruthy();
  });
});

describe('ToolCard per-tool details', () => {
  it('Bash: description, command, ANSI-stripped output and exit code', () => {
    const view = renderUi(
      <ToolCard block={tool('Bash', { command: 'pytest -q', description: 'Run tests' }, { output: '\u001b[32m46 passed\u001b[0m' })} autoOpen />,
    );
    const b = within(body(view.container));
    expect(b.getByText('Run tests')).toBeTruthy();
    expect(b.getByText('pytest -q')).toBeTruthy();
    expect(b.getByText('46 passed')).toBeTruthy();
    expect(b.getByText('exit 0')).toBeTruthy();
    const err = renderUi(<ToolCard block={tool('Bash', { command: 'false' }, { status: 'error', output: 'Exit code 1' })} autoOpen />);
    expect(within(body(err.container)).getByText('exit 1')).toBeTruthy();
  });

  it('long output: first 200 lines, then "Show all"', async () => {
    const long = Array.from({ length: 450 }, (_, i) => `row ${i + 1}`).join('\n');
    const view = renderUi(<ToolCard block={tool('Bash', { command: 'seq 450' }, { output: long })} autoOpen />);
    const b = within(body(view.container));
    const pre = body(view.container).querySelector('[data-kind="output"] pre') as HTMLElement;
    expect(pre.textContent?.split('\n')).toHaveLength(200);
    const more = b.getByRole('button', { name: 'Show all 450 lines' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    await view.user.click(more);
    expect(pre.textContent?.split('\n')).toHaveLength(450);
    expect(b.getByRole('button', { name: 'Show less' })).toBeTruthy();
  });

  it('Edit: lazy diff with +/− lines and a header stat', async () => {
    const s = SAMPLES.find((x) => x.name === 'Edit');
    if (!s) throw new Error('fixture');
    const view = renderUi(<ToolCard block={tool('Edit', s.input, { output: s.output })} autoOpen />);
    const diff = body(view.container).querySelector('[data-kind="diff"]') as HTMLElement;
    expect(diff).toBeTruthy();
    await waitFor(() => expect(diff.getAttribute('data-ready')).toBe('true'));
    expect(header(view.container).textContent).toMatch(/\+4\s−1/);
    expect(diff.textContent).toContain('ttl = 60');
    expect(diff.querySelectorAll('[class*="dDel"]')).toHaveLength(1);
    expect(diff.querySelectorAll('[class*="dAdd"]')).toHaveLength(4);
    expect(within(body(view.container)).getByText(/has been updated/)).toBeTruthy();
  });

  it('Edit: replace_all shows the mode line', () => {
    const view = renderUi(<ToolCard block={tool('Edit', { file_path: '/a', old_string: 'a', new_string: 'b', replace_all: true })} autoOpen />);
    expect(within(body(view.container)).getByText('Replace all occurrences')).toBeTruthy();
  });

  it('Read: file and range; orchestrator read_file: path and 1-based range (not JSON)', () => {
    const read = renderUi(<ToolCard block={tool('Read', { file_path: '/x/y.py', offset: 10, limit: 5 })} autoOpen />);
    expect(within(body(read.container)).getByText('lines 11–15')).toBeTruthy();
    const orch = renderUi(<ToolCard block={tool('read_file', { path: 'MEMORY.md', start_line: 3, end_line: 9 })} sessionKind="orchestrator" autoOpen />);
    expect(within(body(orch.container)).getByText('MEMORY.md')).toBeTruthy();
    expect(within(body(orch.container)).getByText('lines 3–9')).toBeTruthy();
    expect(body(orch.container).textContent).not.toContain('"path"');
  });

  it('Grep: pattern, path and options', () => {
    const view = renderUi(<ToolCard block={tool('Grep', { pattern: 'x', path: 'src', output_mode: 'content', '-n': true, '-C': 2 })} autoOpen />);
    expect(within(body(view.container)).getByText('content, line numbers, ±2 context')).toBeTruthy();
  });

  it('TodoWrite: checklist, progress in the header, collapsible (fixes §6.2)', async () => {
    const s = SAMPLES.find((x) => x.name === 'TodoWrite');
    if (!s) throw new Error('fixture');
    const view = renderUi(<ToolCard block={tool('TodoWrite', s.input, { output: s.output })} autoOpen={false} />);
    const h = header(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('true'); // open by default
    expect(h.textContent).toContain('2 of 5 done');
    const items = body(view.container).querySelectorAll('li');
    expect(items).toHaveLength(5);
    expect(items[2]?.textContent).toContain('Porting parity tests'); // activeForm while in progress
    expect(items[0]?.textContent).toContain('done: ');
    await view.user.click(h);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    expect(body(view.container).hidden).toBe(true);
  });

  it('Task: collapsible, prompt + Markdown report', async () => {
    const s = SAMPLES.find((x) => x.name === 'Task');
    if (!s) throw new Error('fixture');
    const view = renderUi(<ToolCard block={tool('Task', s.input, { output: s.output })} autoOpen={false} />);
    const h = header(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    await view.user.click(h);
    const b = within(body(view.container));
    expect(b.getByText('Explore')).toBeTruthy();
    expect(b.getByText(/Find every place/)).toBeTruthy();
    expect(b.getByRole('heading', { name: 'Findings' })).toBeTruthy();
  });

  it('send_to_agent_session: live feed while running, then the output', () => {
    const b = tool('send_to_agent_session', { session_id: '4f2a91c0-7d1e', message: 'Draft the plan' }, { status: 'running' });
    const view = renderUi(<ToolCard block={b} sessionKind="orchestrator" autoOpen feed={['Read fire_tv.md', 'Listed 23 apps']} />);
    const out = output(view.container) as HTMLElement;
    expect(out.getAttribute('data-kind')).toBe('feed');
    expect(out.textContent).toContain('Live · session 4f2a91c0');
    expect(out.textContent).toContain('Listed 23 apps');
    view.rerender(<ToolCard block={withResult(b, '{"turn_id":"t-1"}')} sessionKind="orchestrator" autoOpen feed={['x']} />);
    expect(within(body(view.container)).getByText('{"turn_id":"t-1"}')).toBeTruthy();
  });

  it('run_script: parses {exit_code, stdout, stderr}', () => {
    const view = renderUi(
      <ToolCard block={tool('run_script', { script: 's.py' }, { output: '{"exit_code": 3, "stdout": "hello\\n", "stderr": "warn"}' })} sessionKind="orchestrator" autoOpen />,
    );
    const b = within(body(view.container));
    expect(b.getByText('hello')).toBeTruthy();
    expect(b.getByText('warn')).toBeTruthy();
    expect(b.getByText('exit 3')).toBeTruthy();
  });

  it('WebFetch: "Waiting for response…" while running; hourglass when stalled', () => {
    const b = tool('WebFetch', { url: 'https://developer.android.com/x' }, { status: 'running' });
    const view = renderUi(<ToolCard block={b} autoOpen stalled />);
    expect(within(body(view.container)).getByText('Waiting for response…')).toBeTruthy();
    expect(header(view.container).querySelector('[data-icon="hourglass_top"]')).toBeTruthy();
    expect(header(view.container).textContent).toContain('waiting');
    const link = within(body(view.container)).getByRole('link', { name: 'https://developer.android.com/x' });
    expect(link.getAttribute('target')).toBe('_blank');
  });

  it('generic tools show the input as JSON', () => {
    const view = renderUi(<ToolCard block={tool('SomethingNew', { foo: 'bar' })} autoOpen />);
    expect(body(view.container).querySelector('pre')?.textContent).toContain('"foo": "bar"');
  });

  it('AskUserQuestion: questions and options', () => {
    const s = SAMPLES.find((x) => x.name === 'AskUserQuestion');
    if (!s) throw new Error('fixture');
    const view = renderUi(<ToolCard block={tool('AskUserQuestion', s.input, { output: s.output })} autoOpen />);
    expect(within(body(view.container)).getByText('voice-refactor')).toBeTruthy();
    expect(within(body(view.container)).getByText(/Commit on voice-refactor/)).toBeTruthy();
  });
});

describe('ToolCard expansion, keyboard and timing', () => {
  afterEach(() => resetTiming());

  it('autoOpen: open while live, folds when text follows, user toggle wins until then', async () => {
    const b = tool('Read', { file_path: '/a' });
    const view = renderUi(<ToolCard block={b} autoOpen />);
    const h = header(view.container);
    expect(h.getAttribute('aria-expanded')).toBe('true');
    await view.user.click(h);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    view.rerender(<ToolCard block={b} autoOpen={false} />);
    expect(h.getAttribute('aria-expanded')).toBe('false');
    await view.user.click(h);
    expect(h.getAttribute('aria-expanded')).toBe('true');
  });

  it('defaults to open while running when autoOpen is not given', () => {
    const view = renderUi(<ToolCard block={tool('Bash', { command: 'x' }, { status: 'running' })} />);
    expect(header(view.container).getAttribute('aria-expanded')).toBe('true');
  });

  it('Enter and Space toggle the header', async () => {
    const view = renderUi(<ToolCard block={tool('Glob', { pattern: '*' })} autoOpen={false} />);
    const h = header(view.container);
    h.focus();
    await view.user.keyboard('{Enter}');
    expect(h.getAttribute('aria-expanded')).toBe('true');
    await view.user.keyboard(' ');
    expect(h.getAttribute('aria-expanded')).toBe('false');
  });

  it('shows a running clock and a final duration for live calls; none for history', () => {
    let now = 1_000_000;
    const restore = setTimingClock(() => now);
    try {
      const b = tool('Bash', { command: 'sleep' }, { status: 'running' });
      const view = renderUi(<ToolCard block={b} autoOpen={false} />);
      now += 12_000;
      view.rerender(<ToolCard block={{ ...b }} autoOpen={false} />);
      expect(header(view.container).textContent).toContain('0:12');
      now += 800;
      view.rerender(<ToolCard block={withResult(b, 'ok')} autoOpen={false} />);
      expect(header(view.container).textContent).toContain('13s');
      const hist = renderUi(<ToolCard block={tool('Bash', { command: 'x' }, { origin: 'history' })} autoOpen={false} />);
      expect(header(hist.container).textContent).not.toMatch(/\d+(\.\d)?s/);
    } finally {
      restore();
    }
  });

  it('orchestrator progress elapsed_seconds drives the clock (TC-3)', () => {
    const b: ToolBlock = tool('run_script', { script: 's.py' }, { status: 'running', origin: 'history', progress: { elapsed_seconds: 75, message: 'Still executing run_script...' } });
    const view = renderUi(<ToolCard block={b} sessionKind="orchestrator" autoOpen />);
    expect(header(view.container).textContent).toContain('1:15');
    expect(within(body(view.container)).getByText(/1:15/)).toBeTruthy();
  });

  it('ticks once a second while running', async () => {
    let now = 0;
    const restore = setTimingClock(() => now);
    try {
      const b = tool('Bash', { command: 'sleep' }, { status: 'running' });
      const view = renderUi(<ToolCard block={b} autoOpen={false} />);
      const text = (): string => header(view.container).textContent ?? '';
      expect(text()).not.toContain('0:0');
      now = 5000;
      await act(async () => {
        await new Promise((r) => setTimeout(r, 1100));
      });
      expect(text()).toContain('0:05');
    } finally {
      restore();
    }
  });
});
