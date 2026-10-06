import { describe, expect, it } from 'vitest';
import { toolCategories } from '@tokens/tokens';
import { normalizeTool, normalizeToolInput, normalizeToolName } from './normalize';
import { getToolSpec, headline, resolveTool, TOOL_SPECS } from './registry';

const r = (tool_name: string, tool_input: Record<string, unknown> = {}, kind: 'agent' | 'orchestrator' = 'agent') => resolveTool({ tool_name, tool_input }, kind);

describe('normalizeToolName (F-05, TC-4)', () => {
  it.each([
    ['read_file', 'Read'],
    ['write_file', 'Write'],
    ['edit', 'Edit'],
    ['replace', 'Edit'],
    ['run_shell_command', 'Bash'],
    ['grep_search', 'Grep'],
    ['search_file_content', 'Grep'],
    ['glob', 'Glob'],
    ['web_fetch', 'WebFetch'],
    ['google_web_search', 'WebSearch'],
    ['todo_write', 'TodoWrite'],
    ['write_todos', 'TodoWrite'],
    ['agent', 'Task'],
    ['ask_user_question', 'AskUserQuestion'],
    ['exit_plan_mode', 'ExitPlanMode'],
    ['save_memory', 'SaveMemory'],
    ['tool_search', 'ToolSearch'],
    ['list_directory', 'ListFiles'],
    ['read_many_files', 'ReadManyFiles'],
    ['Agent', 'Task'],
    ['Bash', 'Bash'],
    ['mcp__x__y', 'mcp__x__y'],
  ])('agent: %s → %s', (raw, canonical) => {
    expect(normalizeToolName(raw, 'agent')).toBe(canonical);
  });

  it('orchestrator tools resolve before the Qwen mapping (fixes inv02 §6.3 #15)', () => {
    expect(normalizeToolName('read_file', 'orchestrator')).toBe('read_file');
    expect(normalizeToolName('write_file', 'orchestrator')).toBe('write_file');
    expect(normalizeToolName('read_file', 'agent')).toBe('Read');
    const orch = r('read_file', { path: 'context/memory/MEMORY.md', start_line: 1, end_line: 40 }, 'orchestrator');
    expect(orch.name).toBe('read_file');
    expect(orch.spec.Body).not.toBe(getToolSpec('UnknownXYZ').Body); // not the generic JSON body
    expect(orch.label).toBe('read_file');
    expect(orch.summary).toBe('context/memory/MEMORY.md');
  });

  it('an orchestrator read_file seen without context still renders as Read via `path`', () => {
    const t = r('read_file', { path: '/a/b/c/d.md' });
    expect(t.name).toBe('Read');
    expect(t.input.file_path).toBe('/a/b/c/d.md');
    expect(t.summary).toBe('…/c/d.md');
  });
});

describe('normalizeToolInput', () => {
  it('maps Qwen/Gemini argument keys onto Claude keys', () => {
    expect(normalizeToolInput('Read', { absolute_path: '/x' })).toEqual({ file_path: '/x' });
    expect(normalizeToolInput('Grep', { pattern: 'p', include: '*.ts' })).toEqual({ pattern: 'p', glob: '*.ts' });
    expect(normalizeToolInput('ListFiles', { dir_path: 'src' })).toEqual({ path: 'src' });
    expect(normalizeToolInput('TodoWrite', { todos: [{ description: 'a', status: 'pending' }] })).toEqual({
      todos: [{ description: 'a', content: 'a', status: 'pending' }],
    });
  });

  it('returns the same object when nothing changes', () => {
    const input = { file_path: '/x' };
    expect(normalizeToolInput('Read', input)).toBe(input);
    expect(normalizeTool('Bash', input).input).toBe(input);
  });

  it('Qwen calls get the Claude renderers end to end', () => {
    expect(r('run_shell_command', { command: 'date', description: 'Check current date' }).label).toBe('Bash');
    expect(r('todo_write', { todos: [{ id: '1', content: 'x', status: 'completed' }] }).summary).toBe('1 of 1 done');
    expect(r('agent', { description: 'Explore project', prompt: 'p' }).spec.category).toBe('task');
    expect(r('grep_search', { pattern: 'TODO', path: 'src' }).summary).toBe('"TODO" in src');
  });
});

/** Every F-05 tool: category (token), icon, header label + summary. */
type Row = readonly [string, Record<string, unknown>, string, string, string, string, ('agent' | 'orchestrator')?];
const TABLE: ReadonlyArray<Row> = [
  ['Read', { file_path: '/home/u/p/src/a.ts' }, 'read', 'description', 'Read', '…/src/a.ts'],
  ['Write', { file_path: 'src/a.ts', content: 'x' }, 'write', 'code', 'Write', 'src/a.ts'],
  ['Edit', { file_path: '/r/api/routes/voice.py', old_string: 'a', new_string: 'b' }, 'write', 'edit_document', 'Edit', '…/routes/voice.py'],
  ['NotebookEdit', { notebook_path: 'n.ipynb' }, 'write', 'book', 'NotebookEdit', 'n.ipynb'],
  ['Bash', { command: 'npm run build', description: 'Build the app' }, 'execute', 'terminal', 'Bash', 'Build the app'],
  ['Bash', { command: 'pytest tests/test_voice_parity.py -q' }, 'execute', 'terminal', 'Bash', 'pytest tests/test_voice_parity.py -q'],
  ['Glob', { pattern: '**/*.kt' }, 'search', 'search', 'Glob', '**/*.kt'],
  ['Grep', { pattern: '_voice =', path: 'orchestrator/' }, 'search', 'search', 'Grep', '"_voice =" in orchestrator/'],
  ['WebFetch', { url: 'https://developer.android.com/media' }, 'navigate', 'explore', 'WebFetch', 'developer.android.com/media'],
  ['WebSearch', { query: 'audio focus' }, 'search', 'search', 'WebSearch', '"audio focus"'],
  ['Task', { description: 'where is voice state set?', subagent_type: 'Explore', prompt: 'p' }, 'task', 'assignment', 'Task', 'Explore: where is voice state set?'],
  ['Task', { description: 'Plan', subagent_type: 'general-purpose', prompt: 'p' }, 'task', 'assignment', 'Task', 'Plan'],
  ['TodoWrite', { todos: [{ content: 'a', status: 'completed' }, { content: 'b', status: 'pending' }] }, 'todo', 'checklist', 'TodoWrite', '1 of 2 done'],
  ['TodoWrite', { todos: [] }, 'todo', 'checklist', 'TodoWrite', 'No todos'],
  ['AskUserQuestion', { questions: [{ question: 'Which branch?', options: [] }] }, 'interact', 'help', 'AskUserQuestion', 'Which branch?'],
  ['Skill', { skill: 'tv-remote' }, 'execute', 'star_shine', 'Skill', '/tv-remote'],
  ['EnterPlanMode', {}, 'execute', 'edit_note', 'EnterPlanMode', 'Enter plan mode'],
  ['ExitPlanMode', { plan: '1. Step\n2. Step' }, 'execute', 'edit_note', 'ExitPlanMode', '1. Step …'],
  ['list_agent_sessions', {}, 'agent', 'list', 'list_agent_sessions', 'List active sessions', 'orchestrator'],
  ['open_agent_session', {}, 'agent', 'open_in_new', 'open_agent_session', '', 'orchestrator'],
  ['open_agent_session', { resume_sdk_id: 'abcdef1234567' }, 'agent', 'open_in_new', 'open_agent_session', 'resume abcdef12', 'orchestrator'],
  ['close_agent_session', { session_id: '4f2a91c0-7d1e' }, 'agent', 'close', 'close_agent_session', 'session 4f2a91c0', 'orchestrator'],
  ['read_agent_session', { session_id: '4f2a91c0-7d1e' }, 'agent', 'visibility', 'read_agent_session', 'session 4f2a91c0', 'orchestrator'],
  ['send_to_agent_session', { session_id: 's', message: 'Draft the TV setup' }, 'agent', 'smart_toy', 'send_to_agent_session', 'Draft the TV setup', 'orchestrator'],
  ['interrupt_agent_session', { session_id: '4f2a91c0-7d1e' }, 'agent', 'stop', 'interrupt_agent_session', 'session 4f2a91c0', 'orchestrator'],
  ['respond_to_agent_permission', { decision: 'allow' }, 'agent', 'smart_toy', 'respond_to_agent_permission', 'allow', 'orchestrator'],
  ['list_history', {}, 'agent', 'history', 'list_history', 'List session history', 'orchestrator'],
  ['search_history', { query: 'tv' }, 'search', 'search', 'search_history', '"tv"', 'orchestrator'],
  ['search_memory', { query: 'tv' }, 'search', 'search', 'search_memory', '"tv"', 'orchestrator'],
  ['read_conversation', { session_id: '96a377c7-8910-450b', turn: 40 }, 'read', 'history', 'read_conversation', 'session 96a377c7 · turn 40', 'orchestrator'],
  ['read_conversation', { session_id: '96a377c7-8910-450b' }, 'read', 'history', 'read_conversation', 'session 96a377c7', 'orchestrator'],
  ['read_file', { path: 'a.md' }, 'read', 'description', 'read_file', 'a.md', 'orchestrator'],
  ['write_file', { path: 'a.md', content: 'x' }, 'write', 'code', 'write_file', 'a.md', 'orchestrator'],
  ['run_script', { script: 'context/scripts/tv_remote.py', args: ['queue'] }, 'script', 'code', 'run_script', 'tv_remote.py queue', 'orchestrator'],
  ['mcp__chrome-devtools__navigate_page', { url: 'https://x.dev/a' }, 'navigate', 'explore', 'Navigate', 'x.dev/a'],
  ['mcp__chrome-devtools__navigate_page', { type: 'reload' }, 'navigate', 'explore', 'Reload page', ''],
  ['mcp__chrome-devtools__navigate_page', { type: 'back' }, 'navigate', 'explore', 'Go back', ''],
  ['mcp__chrome-devtools__click', { uid: '14' }, 'interact', 'touch_app', 'Click', 'ref 14'],
  ['mcp__chrome-devtools__click', { uid: '14', dblClick: true }, 'interact', 'touch_app', 'Double click', 'ref 14'],
  ['mcp__chrome-devtools__fill_form', { elements: [{}, {}] }, 'interact', 'keyboard', 'Fill form', '2 fields'],
  ['mcp__chrome-devtools__press_key', { key: 'Enter' }, 'interact', 'keyboard', 'Press', 'Enter'],
  ['mcp__chrome-devtools__handle_dialog', { action: 'accept' }, 'interact', 'touch_app', 'Accept dialog', ''],
  ['mcp__chrome-devtools__handle_dialog', { action: 'dismiss' }, 'interact', 'touch_app', 'Dismiss dialog', ''],
  ['mcp__chrome-devtools__resize_page', { width: 412, height: 915 }, 'navigate', 'explore', 'Resize', '412×915'],
  ['mcp__chrome-devtools__select_page', { pageId: 2 }, 'navigate', 'explore', 'Select page', '#2'],
  ['mcp__chrome-devtools__wait_for', { text: 'Ready' }, 'navigate', 'hourglass_top', 'Wait for', '"Ready"'],
  ['mcp__chrome-devtools__take_screenshot', { filePath: '/tmp/tv.png' }, 'capture', 'screenshot_monitor', 'Screenshot', 'tv.png'],
  ['mcp__chrome-devtools__take_snapshot', {}, 'capture', 'content_copy', 'Snapshot', ''],
  ['mcp__chrome-devtools__performance_start_trace', {}, 'capture', 'speed', 'Start trace', ''],
  ['mcp__chrome-devtools__evaluate_script', { function: '() => 1' }, 'script', 'bolt', 'Run script', '() => 1'],
  ['mcp__chrome-devtools__list_console_messages', {}, 'read', 'list', 'List console', ''],
  ['mcp__chrome-devtools__list_network_requests', {}, 'read', 'network_check', 'List network', ''],
  ['mcp__chrome-devtools__list_pages', {}, 'read', 'list', 'List pages', ''],
  ['mcp__chrome-devtools__some_new_action', {}, 'system', 'build', 'some new action', ''],
  ['mcp__posthog__exec', { command: 'insights list' }, 'system', 'build', 'exec', 'insights list'],
  ['mcp__claude_ai_Notion__notion_search', { query: 'q' }, 'system', 'build', 'notion search', 'q'],
  ['SomethingNew', { foo: 'bar' }, 'system', 'build', 'SomethingNew', 'bar'],
];

describe('registry: category, icon, label and summary for every F-05 tool', () => {
  it.each(TABLE)('%s %j', (...row) => {
    const [name, input, category, icon, label, summary, kind] = row as unknown as Row;
    const t = r(name, input, kind ?? 'agent');
    expect(t.spec.category).toBe(category);
    expect(t.spec.icon).toBe(icon);
    expect(t.label).toBe(label);
    expect(t.summary).toBe(summary);
  });

  it('every category is one of the 12 token categories', () => {
    for (const s of Object.values(TOOL_SPECS)) expect(toolCategories).toContain(s.category);
    const used = new Set([...Object.values(TOOL_SPECS).map((s) => s.category), ...TABLE.map((t) => t[2])]);
    expect([...used].map(String).sort()).toEqual([...toolCategories].sort());
  });

  it('TodoWrite opens by default; the rest open only while live', () => {
    expect(getToolSpec('TodoWrite').defaultOpen).toBe('always');
    expect(getToolSpec('Task').defaultOpen).toBe('live');
    expect(getToolSpec('Bash').defaultOpen).toBe('live');
  });

  it('headline joins label and summary (F-05 one-line summary)', () => {
    expect(headline(r('Grep', { pattern: 'x' }))).toBe('Grep "x"');
    expect(headline(r('mcp__chrome-devtools__take_snapshot'))).toBe('Snapshot');
  });

  it('tolerates hostile inputs', () => {
    expect(() => r('Bash', { command: 42 })).not.toThrow();
    expect(r('Read', null as unknown as Record<string, unknown>).label).toBe('Read');
    expect(r('TodoWrite', { todos: 'nope' }).summary).toBe('No todos');
  });
});
