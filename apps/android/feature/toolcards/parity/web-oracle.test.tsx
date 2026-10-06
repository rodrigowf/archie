/**
 * Web oracle for B-05's ToolCatalogParityTest (spec 14 §7 B-05 DoD).
 *
 * Runs the committed web tool cards (frontend/src/features/tools, W-10) on a table of tool
 * calls and writes what the web shows (category, icon, label, summary, body renderer, default
 * expansion, running text, header meta, field rows, code views, todo lines, output-region state)
 * to android/feature/toolcards/src/test/resources/web-tool-oracle.json. The Kotlin test
 * replays the same inputs through ToolCatalog / ToolBodies and expects identical results.
 *
 * Regenerate (from the repo root): android/feature/toolcards/parity/run-web-oracle.sh
 */
import fs from 'node:fs';
import { render, cleanup } from '@testing-library/react';
import { resolveTool, ToolCard } from '@/features/tools';
import { SAMPLES } from '@/features/tools/__fixtures__/blocks';
import { exitLabel } from '@/features/tools/renderers/exec';
import type { SessionKind, ToolBlock } from '@/protocol';

type Input = Record<string, unknown>;
interface Row {
  readonly name: string;
  readonly input: Input;
  readonly kind?: SessionKind;
  readonly output?: string;
}

/* 1. The web registry test table (registry.test.ts), verbatim inputs. */
const REGISTRY_TABLE: Row[] = [
  { name: 'Read', input: { file_path: '/home/u/p/src/a.ts' } },
  { name: 'Write', input: { file_path: 'src/a.ts', content: 'x' } },
  { name: 'Edit', input: { file_path: '/r/api/routes/voice.py', old_string: 'a', new_string: 'b' } },
  { name: 'NotebookEdit', input: { notebook_path: 'n.ipynb' } },
  { name: 'Bash', input: { command: 'npm run build', description: 'Build the app' } },
  { name: 'Bash', input: { command: 'pytest tests/test_voice_parity.py -q' } },
  { name: 'Glob', input: { pattern: '**/*.kt' } },
  { name: 'Grep', input: { pattern: '_voice =', path: 'orchestrator/' } },
  { name: 'WebFetch', input: { url: 'https://developer.android.com/media' } },
  { name: 'WebSearch', input: { query: 'audio focus' } },
  { name: 'Task', input: { description: 'where is voice state set?', subagent_type: 'Explore', prompt: 'p' } },
  { name: 'Task', input: { description: 'Plan', subagent_type: 'general-purpose', prompt: 'p' } },
  { name: 'TodoWrite', input: { todos: [{ content: 'a', status: 'completed' }, { content: 'b', status: 'pending' }] } },
  { name: 'TodoWrite', input: { todos: [] } },
  { name: 'AskUserQuestion', input: { questions: [{ question: 'Which branch?', options: [] }] } },
  { name: 'Skill', input: { skill: 'tv-remote' } },
  { name: 'EnterPlanMode', input: {} },
  { name: 'ExitPlanMode', input: { plan: '1. Step\n2. Step' } },
  { name: 'list_agent_sessions', input: {}, kind: 'orchestrator' },
  { name: 'open_agent_session', input: {}, kind: 'orchestrator' },
  { name: 'open_agent_session', input: { resume_sdk_id: 'abcdef1234567' }, kind: 'orchestrator' },
  { name: 'close_agent_session', input: { session_id: '4f2a91c0-7d1e' }, kind: 'orchestrator' },
  { name: 'read_agent_session', input: { session_id: '4f2a91c0-7d1e' }, kind: 'orchestrator' },
  { name: 'send_to_agent_session', input: { session_id: 's', message: 'Draft the TV setup' }, kind: 'orchestrator' },
  { name: 'interrupt_agent_session', input: { session_id: '4f2a91c0-7d1e' }, kind: 'orchestrator' },
  { name: 'respond_to_agent_permission', input: { decision: 'allow' }, kind: 'orchestrator' },
  { name: 'list_history', input: {}, kind: 'orchestrator' },
  { name: 'search_history', input: { query: 'tv' }, kind: 'orchestrator' },
  { name: 'search_memory', input: { query: 'tv' }, kind: 'orchestrator' },
  { name: 'read_conversation', input: { session_id: '96a377c7-8910-450b', turn: 40 }, kind: 'orchestrator' },
  { name: 'read_conversation', input: { session_id: '96a377c7-8910-450b' }, kind: 'orchestrator' },
  { name: 'read_file', input: { path: 'a.md' }, kind: 'orchestrator' },
  { name: 'write_file', input: { path: 'a.md', content: 'x' }, kind: 'orchestrator' },
  { name: 'run_script', input: { script: 'context/scripts/tv_remote.py', args: ['queue'] }, kind: 'orchestrator' },
  { name: 'mcp__chrome-devtools__navigate_page', input: { url: 'https://x.dev/a' } },
  { name: 'mcp__chrome-devtools__navigate_page', input: { type: 'reload' } },
  { name: 'mcp__chrome-devtools__navigate_page', input: { type: 'back' } },
  { name: 'mcp__chrome-devtools__click', input: { uid: '14' } },
  { name: 'mcp__chrome-devtools__click', input: { uid: '14', dblClick: true } },
  { name: 'mcp__chrome-devtools__fill_form', input: { elements: [{}, {}] } },
  { name: 'mcp__chrome-devtools__press_key', input: { key: 'Enter' } },
  { name: 'mcp__chrome-devtools__handle_dialog', input: { action: 'accept' } },
  { name: 'mcp__chrome-devtools__handle_dialog', input: { action: 'dismiss' } },
  { name: 'mcp__chrome-devtools__resize_page', input: { width: 412, height: 915 } },
  { name: 'mcp__chrome-devtools__select_page', input: { pageId: 2 } },
  { name: 'mcp__chrome-devtools__wait_for', input: { text: 'Ready' } },
  { name: 'mcp__chrome-devtools__take_screenshot', input: { filePath: '/tmp/tv.png' } },
  { name: 'mcp__chrome-devtools__take_snapshot', input: {} },
  { name: 'mcp__chrome-devtools__performance_start_trace', input: {} },
  { name: 'mcp__chrome-devtools__evaluate_script', input: { function: '() => 1' } },
  { name: 'mcp__chrome-devtools__list_console_messages', input: {} },
  { name: 'mcp__chrome-devtools__list_network_requests', input: {} },
  { name: 'mcp__chrome-devtools__list_pages', input: {} },
  { name: 'mcp__chrome-devtools__some_new_action', input: {} },
  { name: 'mcp__posthog__exec', input: { command: 'insights list' } },
  { name: 'mcp__claude_ai_Notion__notion_search', input: { query: 'q' } },
  { name: 'SomethingNew', input: { foo: 'bar' } },
];

/* 2. Every registry entry and chrome phrasing the table above misses, plus Qwen/Gemini inputs. */
const EXTRA: Row[] = [
  { name: 'ListFiles', input: { path: '/home/rodrigo/assistant/android/app/src' } },
  { name: 'ReadManyFiles', input: { paths: ['a.md', 'b/c.md', 'd.md'] } },
  { name: 'MultiEdit', input: { file_path: '/r/a/b/c.py', edits: [{ old_string: 'a\n', new_string: 'b\n' }, { old_string: 'x', new_string: 'y' }] } },
  { name: 'BashOutput', input: { bash_id: 'bash_3', filter: 'ERROR' } },
  { name: 'KillShell', input: { shell_id: 'bash_3' } },
  { name: 'KillBash', input: { bash_id: 'bash_4' } },
  { name: 'SaveMemory', input: { fact: 'Rodrigo prefers dark mode\nalways' } },
  { name: 'ToolSearch', input: { query: 'slack' } },
  { name: 'get_assistant_config', input: {}, kind: 'orchestrator' },
  { name: 'update_assistant_config', input: { voice: 'cedar', model: 'gpt-realtime' }, kind: 'orchestrator' },
  { name: 'end_voice_session', input: { reason: 'user said goodbye' }, kind: 'orchestrator' },
  { name: 'listen_recording', input: { start_ms: 1500, end_ms: 12400 }, kind: 'orchestrator' },
  { name: 'listen_recording', input: {}, kind: 'orchestrator' },
  { name: 'mcp__chrome-devtools__navigate_page', input: { type: 'forward' } },
  { name: 'mcp__chrome-devtools__new_page', input: { url: 'https://localhost:5450/' } },
  { name: 'mcp__chrome-devtools__close_page', input: { pageId: 3 } },
  { name: 'mcp__chrome-devtools__hover', input: { uid: '7' } },
  { name: 'mcp__chrome-devtools__drag', input: { from_uid: '3', to_uid: '9' } },
  { name: 'mcp__chrome-devtools__drag', input: { from_uid: '3' } },
  { name: 'mcp__chrome-devtools__fill', input: { uid: '22', value: 'hello' } },
  { name: 'mcp__chrome-devtools__upload_file', input: { uid: '5', filePath: '/home/r/photo.jpg' } },
  { name: 'mcp__chrome-devtools__take_screenshot', input: { fullPage: true } },
  { name: 'mcp__chrome-devtools__take_screenshot', input: { uid: '12' } },
  { name: 'mcp__chrome-devtools__performance_stop_trace', input: {} },
  { name: 'mcp__chrome-devtools__performance_analyze_insight', input: { insightName: 'LCPBreakdown' } },
  { name: 'mcp__chrome-devtools__get_console_message', input: { msgid: 4 } },
  { name: 'mcp__chrome-devtools__get_network_request', input: { reqid: 17 } },
  { name: 'mcp__chrome-devtools__emulate', input: { networkConditions: 'Slow 3G' } },
  { name: 'mcp__chrome-devtools__evaluate_script', input: { function: '(el) => {\n  return el.innerText;\n}', args: [{ uid: '14' }, { uid: '15' }] } },
  // Qwen Code / Gemini CLI names and argument keys (TC-4, normalizeToolInput)
  { name: 'read_file', input: { absolute_path: '/home/rodrigo/assistant/api/app.py', offset: 10, limit: 20 } },
  { name: 'read_file', input: { path: '/a/b/c/d.md' } },
  { name: 'write_file', input: { file_path: '/home/r/x/y.kt', content: 'fun main() {}\n' } },
  { name: 'edit', input: { file_path: 'a.ts', old_string: 'x', new_string: 'y' } },
  { name: 'replace', input: { file_path: 'a.ts', old_string: 'x\ny\n', new_string: 'x\nz\n' } },
  { name: 'run_shell_command', input: { command: 'date', description: 'Check current date', directory: 'api' } },
  { name: 'grep_search', input: { pattern: 'TODO', path: 'src', include: '*.ts' } },
  { name: 'search_file_content', input: { pattern: 'x', dir_path: 'lib' } },
  { name: 'glob', input: { pattern: '*.md', dir_path: 'docs' } },
  { name: 'list_directory', input: { dir_path: 'src' } },
  { name: 'web_fetch', input: { url: 'http://example.com/a', prompt: 'summarise' } },
  { name: 'google_web_search', input: { query: 'kotlin flow' } },
  { name: 'web_search', input: { query: 'compose' } },
  { name: 'todo_write', input: { todos: [{ id: '1', content: 'x', status: 'completed' }] } },
  { name: 'write_todos', input: { todos: [{ description: 'from gemini', status: 'in_progress' }, { description: 'next', status: 'pending' }] } },
  { name: 'agent', input: { description: 'Explore project', prompt: 'p' } },
  { name: 'task', input: { description: 'Review', subagent_type: 'code-reviewer', prompt: 'Look at the diff', model: 'opus' } },
  { name: 'Agent', input: { description: 'Find usages', subagent_type: 'Explore', prompt: 'grep for it' } },
  { name: 'ask_user_question', input: { questions: [{ question: 'Proceed?', multiSelect: true, options: [{ label: 'Yes' }, { label: 2 }] }] } },
  { name: 'exit_plan_mode', input: { plan: 'Do it' } },
  { name: 'save_memory', input: { fact: 'x' } },
  { name: 'tool_search', input: { query: 'y' } },
  { name: 'read_many_files', input: { paths: ['a', 'b'] } },
  { name: 'skill', input: { skill: '/debug-app' } },
  // Renderer field variants
  { name: 'Read', input: { file_path: '/x/y.ts', offset: 5 } },
  { name: 'Read', input: { file_path: '/x/y.ts', limit: 50 } },
  { name: 'Read', input: { file_path: '/x/doc.pdf', pages: '1-3' } },
  { name: 'Edit', input: { file_path: '/r/a/b.py', old_string: 'a', new_string: 'b', replace_all: true } },
  { name: 'Bash', input: { command: 'npm run dev', description: 'Start the dev server', run_in_background: true } },
  { name: 'Bash', input: { command: 'line one\nline two', description: '' } },
  { name: 'Bash', input: { command: 42 } },
  { name: 'Grep', input: { pattern: 'x', output_mode: 'files_with_matches' } },
  { name: 'Grep', input: { pattern: 'x', '-C': 3, type: 'py', glob: '*.py' } },
  { name: 'Grep', input: { pattern: 'a very long pattern that goes on and on and on and on and on and on and on and on and on' } },
  { name: 'WebSearch', input: { query: 'q', blocked_domains: ['pinterest.com', 'quora.com'] } },
  { name: 'WebFetch', input: { url: 'ftp://example.com/file' } },
  { name: 'open_agent_session', input: { title: 'Refactor voice', working_directory: '/home/rodrigo/assistant' }, kind: 'orchestrator' },
  { name: 'read_agent_session', input: { session_id: '4f2a91c0-7d1e-4b8b', max_messages: 5 }, kind: 'orchestrator' },
  { name: 'list_history', input: { limit: 10 }, kind: 'orchestrator' },
  { name: 'search_memory', input: { query: 'fire tv apps', max_results: 3 }, kind: 'orchestrator' },
  { name: 'run_script', input: { script: 'context/scripts/weather.py' }, kind: 'orchestrator', output: 'not json' },
  { name: 'NotebookEdit', input: { notebook_path: '/a/b/c/n.ipynb', cell_id: 'c1', cell_type: 'markdown', new_source: '# Title', edit_mode: 'insert' } },
  { name: 'TodoWrite', input: { todos: 'nope' } },
  { name: 'TodoWrite', input: { todos: [{ content: 'Ship', status: 'cancelled' }, { content: 'Test', status: 'in_progress', activeForm: 'Testing' }, { content: 'Weird', status: 'blocked' }] } },
  { name: 'ExitPlanMode', input: {} },
  { name: 'Skill', input: { command: 'review', args: '--fast' } },
];

const ROWS: Row[] = [
  ...REGISTRY_TABLE,
  ...SAMPLES.map((s) => ({ name: s.name, input: s.input, output: s.output, ...(s.kind ? { kind: s.kind } : {}) })),
  ...EXTRA,
];

let seq = 0;
function block(name: string, input: Input, over: Partial<ToolBlock>): ToolBlock {
  seq += 1;
  return {
    id: `b${seq}`,
    type: 'tool',
    tool_use_id: `toolu_${seq}`,
    tool_name: name,
    tool_input: input,
    status: 'done',
    output: 'ok',
    scope: 'turn',
    origin: 'live',
    ...over,
  } as ToolBlock;
}

function bodyOf(container: HTMLElement): HTMLElement {
  const h = container.querySelector('button[aria-expanded]') as HTMLElement;
  return document.getElementById(h.getAttribute('aria-controls') ?? '') as HTMLElement;
}

function outputRegion(body: HTMLElement): HTMLElement | null {
  return body.querySelector('[data-kind]:not([data-kind="diff"])');
}

function describeBody(row: Row): Record<string, unknown> {
  const kind = row.kind ?? 'agent';
  const b = block(row.name, row.input, { output: row.output ?? 'ok' });
  const view = render(<ToolCard block={b} sessionKind={kind} autoOpen />);
  const body = bodyOf(view.container);
  const fields = Array.from(body.querySelectorAll('dl > div')).map((d) => [d.querySelector('dt')?.textContent ?? '', d.querySelector('dd')?.textContent ?? '']);
  const codes = Array.from(body.querySelectorAll('[data-label]')).map((e) => e.getAttribute('data-label'));
  const todos = Array.from(body.querySelectorAll('li')).map((li) => li.textContent ?? '');
  const diffs = body.querySelectorAll('[data-kind="diff"]').length;
  const out = outputRegion(body);
  const result = { fields, codes, todos, diffs, outputKind: out?.getAttribute('data-kind') ?? null };
  cleanup();
  return result;
}

/* 3. Output-region rules (TC-1/TC-2, R7) for a few renderers. */
const STATES: ReadonlyArray<{ readonly id: string; readonly over: Partial<ToolBlock> }> = [
  { id: 'running', over: { status: 'running', output: null } },
  { id: 'no_result_live', over: { status: 'no_result', output: null } },
  { id: 'no_result_history', over: { status: 'no_result', output: null, origin: 'history' } },
  { id: 'done_empty', over: { status: 'done', output: '' } },
  { id: 'error_empty', over: { status: 'error', output: '' } },
  { id: 'done_text', over: { status: 'done', output: 'built in 12s' } },
  { id: 'error_text', over: { status: 'error', output: 'Error: boom' } },
  { id: 'done_null', over: { status: 'done', output: null } },
];

const OUTPUT_TOOLS: Row[] = [
  { name: 'Bash', input: { command: 'npm run build', description: 'Build' } },
  { name: 'WebFetch', input: { url: 'https://a.dev/x' } },
  { name: 'Task', input: { description: 'd', prompt: 'p' } },
  { name: 'read_file', input: { path: 'a.md' }, kind: 'orchestrator' },
];

function outputRules(): unknown[] {
  const rows: unknown[] = [];
  for (const t of OUTPUT_TOOLS) {
    for (const s of STATES) {
      const b = block(t.name, t.input, s.over);
      const view = render(<ToolCard block={b} sessionKind={t.kind ?? 'agent'} autoOpen />);
      const out = outputRegion(bodyOf(view.container));
      rows.push({
        name: t.name,
        input: t.input,
        kind: t.kind ?? 'agent',
        state: s.id,
        status: b.status,
        origin: b.origin,
        output: b.output,
        outputKind: out?.getAttribute('data-kind') ?? null,
        text: (out?.textContent ?? '').trim(),
        exit: t.name === 'Bash' ? exitLabel(b.status, b.output) : null,
      });
      cleanup();
    }
  }
  return rows;
}

test('web tool-card oracle', () => {
  const tools = ROWS.map((row) => {
    const kind = row.kind ?? 'agent';
    const t = resolveTool({ tool_name: row.name, tool_input: row.input }, kind);
    return {
      name: row.name,
      kind,
      input: row.input,
      output: row.output ?? 'ok',
      resolved: {
        name: t.name,
        category: t.spec.category,
        icon: t.spec.icon,
        label: t.label,
        summary: t.summary,
        body: t.spec.Body.name,
        defaultOpen: t.spec.defaultOpen,
        runningText: t.spec.runningText ?? null,
        meta: t.spec.Meta ? t.spec.Meta.name : null,
      },
      body: describeBody(row),
    };
  });
  const out = process.env.ORACLE_OUT;
  if (!out) throw new Error('ORACLE_OUT not set');
  fs.writeFileSync(out, `${JSON.stringify({ generatedFrom: 'frontend/src/features/tools (W-10)', tools, outputRules: outputRules() }, null, 1)}\n`);
});
