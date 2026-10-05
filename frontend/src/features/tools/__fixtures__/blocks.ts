/** Tool-block builders and sample calls for every F-05 renderer (tests + gallery). */
import type { ToolBlock } from '@/protocol';

let seq = 0;

export function tool(
  tool_name: string,
  tool_input: Record<string, unknown>,
  over: Partial<Omit<ToolBlock, 'type' | 'tool_name' | 'tool_input'>> = {},
): ToolBlock {
  seq += 1;
  return {
    id: over.id ?? `b${seq}`,
    type: 'tool',
    tool_use_id: over.tool_use_id ?? `toolu_${seq}`,
    tool_name,
    tool_input,
    status: over.status ?? 'done',
    output: over.output === undefined ? (over.status === 'running' || over.status === 'no_result' ? null : 'ok') : over.output,
    scope: over.scope ?? 'turn',
    origin: over.origin ?? 'live',
    ...(over.inferred !== undefined ? { inferred: over.inferred } : {}),
    ...(over.executing !== undefined ? { executing: over.executing } : {}),
    ...(over.progress !== undefined ? { progress: over.progress } : {}),
  };
}

/** Same block with a result applied (as the reducer would: same id, new object). */
export function withResult(b: ToolBlock, output: string, isError = false): ToolBlock {
  return { ...b, status: isError ? 'error' : 'done', output };
}

export const READ_OUT = Array.from({ length: 3 }, (_, i) => `   ${212 + i}→    ${['def end_voice(self, reason):', '    if self._state == VoiceState.IDLE:', '        return'][i]}`).join('\n');

/** One input per F-05 renderer (+ orchestrator / Qwen variants). */
export const SAMPLES: ReadonlyArray<{ readonly title: string; readonly name: string; readonly input: Record<string, unknown>; readonly output: string; readonly kind?: 'agent' | 'orchestrator' }> = [
  { title: 'Read', name: 'Read', input: { file_path: '/home/rodrigo/assistant/orchestrator/session.py', offset: 211, limit: 3 }, output: READ_OUT },
  {
    title: 'Write',
    name: 'Write',
    input: { file_path: '/home/rodrigo/assistant/orchestrator/voice/state.py', content: 'from enum import Enum\n\n\nclass VoiceState(Enum):\n    IDLE = "idle"\n    LISTENING = "listening"\n    SPEAKING = "speaking"\n' },
    output: 'File created successfully at: /home/rodrigo/assistant/orchestrator/voice/state.py',
  },
  {
    title: 'Edit',
    name: 'Edit',
    input: {
      file_path: '/home/rodrigo/assistant/api/routes/voice.py',
      old_string: 'def mint_token(settings):\n    model = settings.voice_model\n    ttl = 60\n    return issue(model, ttl)\n',
      new_string: 'def mint_token(settings):\n    model = settings.voice_model\n    ttl = settings.voice_token_ttl\n    if ttl <= 0:\n        raise ValueError("ttl")\n    log.debug("token ttl %s", ttl)\n    return issue(model, ttl)\n',
    },
    output: 'The file /home/rodrigo/assistant/api/routes/voice.py has been updated.',
  },
  { title: 'Bash', name: 'Bash', input: { command: 'cd /home/rodrigo/assistant && pytest tests/test_voice_parity.py -q', description: 'Run the voice parity tests' }, output: '..............................................  [100%]\n\u001b[32m46 passed\u001b[0m in 3.82s' },
  { title: 'Glob', name: 'Glob', input: { pattern: '**/*.kt', path: '/home/rodrigo/assistant/android/app/src' }, output: 'AudioDucker.kt\nVoiceService.kt\nWakeWordDetector.kt' },
  { title: 'Grep', name: 'Grep', input: { pattern: 'self._voice =', path: 'orchestrator/', output_mode: 'content', '-n': true, context: 2 }, output: 'session.py:212:    self._voice = True\nsession.py:388:    self._voice = False' },
  { title: 'WebFetch', name: 'WebFetch', input: { url: 'https://developer.android.com/media/optimize/audio-focus', prompt: 'How does audio focus ducking work?' }, output: 'Audio focus lets apps duck or pause when another app plays audio…' },
  { title: 'WebSearch', name: 'WebSearch', input: { query: 'Android AudioFocusRequest duck', allowed_domains: ['developer.android.com'] }, output: '1. Manage audio focus — developer.android.com\n2. AudioFocusRequest — developer.android.com' },
  {
    title: 'Task',
    name: 'Task',
    input: { description: 'where is voice state set?', subagent_type: 'Explore', prompt: 'Find every place in orchestrator/ and api/ that assigns _voice or calls end_voice. Report file:line.' },
    output: '## Findings\n\n- `orchestrator/session.py:212` sets `_voice = True`\n- `orchestrator/session.py:388` resets it in **end_voice**',
  },
  {
    title: 'TodoWrite',
    name: 'TodoWrite',
    input: {
      todos: [
        { content: 'Extract VoiceStateMachine', status: 'completed', activeForm: 'Extracting VoiceStateMachine' },
        { content: 'Move transitions into the machine', status: 'completed', activeForm: 'Moving transitions' },
        { content: 'Port parity tests', status: 'in_progress', activeForm: 'Porting parity tests' },
        { content: 'Update voice_subsystem.md', status: 'pending', activeForm: 'Updating docs' },
        { content: 'Open the PR', status: 'pending', activeForm: 'Opening the PR' },
      ],
    },
    output: 'Todos have been modified successfully.',
  },
  {
    title: 'AskUserQuestion',
    name: 'AskUserQuestion',
    input: { questions: [{ header: 'Branch', question: 'Commit on voice-refactor or a new branch?', multiSelect: false, options: [{ label: 'voice-refactor', description: 'Current branch' }, { label: 'New branch' }] }] },
    output: 'User answered: voice-refactor',
  },
  { title: 'Skill', name: 'Skill', input: { skill: 'tv-remote', args: 'volume 20' }, output: 'Launching skill: tv-remote' },
  { title: 'EnterPlanMode', name: 'EnterPlanMode', input: {}, output: 'Entered plan mode.' },
  { title: 'ExitPlanMode', name: 'ExitPlanMode', input: { plan: '1. Check Kodi and Plex on the Fire TV\n2. Queue three films\n3. Set the soundbar to **movie** mode' }, output: 'User approved the plan.' },
  { title: 'NotebookEdit', name: 'NotebookEdit', input: { notebook_path: '/home/rodrigo/qvcm/analysis.ipynb', cell_id: 'c7', edit_mode: 'replace', new_source: 'df.describe()' }, output: 'Updated cell c7' },
  { title: 'list_agent_sessions', name: 'list_agent_sessions', input: {}, output: '[{"session_id":"4f2a91c0","title":"TV setup plan","status":"idle"}]', kind: 'orchestrator' },
  { title: 'open_agent_session', name: 'open_agent_session', input: { title: 'TV setup plan' }, output: '{"session_id":"4f2a91c0-7d1e"}', kind: 'orchestrator' },
  { title: 'close_agent_session', name: 'close_agent_session', input: { session_id: '4f2a91c0-7d1e-4b8b' }, output: 'Closed.', kind: 'orchestrator' },
  { title: 'read_agent_session', name: 'read_agent_session', input: { session_id: '4f2a91c0-7d1e-4b8b', max_messages: 5 }, output: 'assistant: Drafting plan…', kind: 'orchestrator' },
  { title: 'send_to_agent_session', name: 'send_to_agent_session', input: { session_id: '4f2a91c0-7d1e-4b8b', message: 'Draft the living-room TV setup for Friday movie night.' }, output: '{"turn_id":"t-17","status":"running"}', kind: 'orchestrator' },
  { title: 'interrupt_agent_session', name: 'interrupt_agent_session', input: { session_id: '4f2a91c0-7d1e-4b8b' }, output: 'Interrupted.', kind: 'orchestrator' },
  { title: 'respond_to_agent_permission', name: 'respond_to_agent_permission', input: { session_id: '4f2a91c0-7d1e-4b8b', request_id: 'r-3', decision: 'allow' }, output: 'Approved ExitPlanMode', kind: 'orchestrator' },
  { title: 'list_history', name: 'list_history', input: { limit: 10 }, output: '10 sessions', kind: 'orchestrator' },
  { title: 'search_history', name: 'search_history', input: { query: 'soundbar movie mode', max_results: 5 }, output: '3 matches', kind: 'orchestrator' },
  { title: 'search_memory', name: 'search_memory', input: { query: 'fire tv apps' }, output: 'assistant/devices/fire_tv.md', kind: 'orchestrator' },
  { title: 'read_file (orchestrator)', name: 'read_file', input: { path: 'context/memory/MEMORY.md', start_line: 1, end_line: 40 }, output: '# Memory Index\n…', kind: 'orchestrator' },
  { title: 'write_file (orchestrator)', name: 'write_file', input: { path: 'context/memory/notes/tv.md', content: '# TV\n\nKodi + Plex installed.\n' }, output: 'Wrote 28 bytes', kind: 'orchestrator' },
  { title: 'run_script', name: 'run_script', input: { script: 'context/scripts/tv_remote.py', args: ['queue', '3'] }, output: '{"exit_code": 0, "stdout": "3 titles queued\\n", "stderr": ""}', kind: 'orchestrator' },
  { title: 'evaluate_script', name: 'mcp__chrome-devtools__evaluate_script', input: { function: '() => document.title', args: [{ uid: '14' }] }, output: '"Archie"' },
  { title: 'chrome click', name: 'mcp__chrome-devtools__click', input: { uid: '14' }, output: 'Clicked · page navigated' },
  { title: 'chrome navigate', name: 'mcp__chrome-devtools__navigate_page', input: { url: 'https://localhost:5450/#/dev/gallery' }, output: 'Navigated' },
  { title: 'chrome screenshot', name: 'mcp__chrome-devtools__take_screenshot', input: { filePath: '/tmp/living-room-tv.png' }, output: '1920×1080 · 412 KB' },
  { title: 'generic MCP', name: 'mcp__posthog__exec', input: { command: 'insights list' }, output: '12 insights' },
  { title: 'unknown tool', name: 'SomethingNew', input: { foo: 'bar', n: 2 }, output: 'ok' },
];
