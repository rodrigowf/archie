/**
 * Tool registry (spec 13 §3.6 W-10): canonical tool name → category (token colour), icon, header
 * `label` + `summary`, expanded `Body`, default expansion. Sources: inv02 F-05 (categories,
 * summaries, icons, renderers) and the approved mockups ("Tool cards, all 12 categories").
 *
 * The header reads `<label> <summary>`: the label is the tool (category-coloured), the summary its
 * argument in mono, e.g. `Grep  "_voice" in orchestrator/`. `headline()` joins them into the
 * one-line sentence of F-05's `formatToolSummary` for accessible names and group summaries.
 */
import type { ComponentType } from 'react';
import type { ToolCategory } from '@tokens/tokens';
import type { SessionKind, ToolBlock } from '@/protocol';
import type { IconName } from '@/ui/primitives';
import { arr, baseName, bool, firstLine, firstStringArg, num, shortId, shortPath, str, stripScheme, type ToolInput } from './format';
import { normalizeTool } from './normalize';
import { BashBody, EvaluateScriptBody, PlanBody, RunScriptBody, ShellIdBody, SkillBody, scriptArgs } from './renderers/exec';
import {
  EditBody,
  GlobBody,
  GrepBody,
  ListFilesBody,
  MultiEditBody,
  NotebookEditBody,
  OrchestratorReadBody,
  OrchestratorWriteBody,
  ReadBody,
  WriteBody,
} from './renderers/files';
import { EditMeta } from './renderers/DiffView';
import { ReadConversationBody, SearchHistoryBody, readConversationSummary } from './renderers/history';
import { AgentSessionBody, GenericBody, SearchBody, SendToAgentBody, WebFetchBody, WebSearchBody } from './renderers/other';
import { AskUserQuestionBody, TaskBody, TodoBody, parseQuestions, todoProgress } from './renderers/tasks';
import type { ToolBodyProps } from './renderers/types';

export type { ToolCategory };

export interface ToolSpec {
  readonly category: ToolCategory;
  readonly icon: IconName;
  /** Header name, category-coloured ("Read", "Click", "send_to_agent_session"). */
  readonly label: (input: ToolInput) => string;
  /** Header argument, mono ("…/src/a.ts", `"query"`); may be empty. */
  readonly summary: (input: ToolInput) => string;
  /** The expanded part: input details + output region. */
  readonly Body: ComponentType<ToolBodyProps>;
  /**
   * Default expansion: `live` (open while the card is in the live tail, folded once text follows)
   * or `always` (open by default, still collapsible: TodoWrite).
   */
  readonly defaultOpen: 'live' | 'always';
  /** Output-region text while running without output (default "Running…"). */
  readonly runningText?: string;
  /** Extra header status content before the status icon (Edit: "+4 −1"). */
  readonly Meta?: ComponentType<{ readonly input: ToolInput }>;
}

const fixed = (s: string) => (): string => s;
const path = (key: string) => (i: ToolInput): string => {
  const p = str(i, key);
  return p ? shortPath(p) : '';
};
const quoted = (key: string) => (i: ToolInput): string => {
  const q = str(i, key);
  return q ? `"${firstLine(q, 80)}"` : '';
};
const session = (i: ToolInput): string => {
  const id = str(i, 'session_id');
  return id ? `session ${shortId(id)}` : '';
};

function spec(
  category: ToolCategory,
  icon: IconName,
  label: string | ((i: ToolInput) => string),
  summary: (i: ToolInput) => string,
  Body: ComponentType<ToolBodyProps> = GenericBody,
  extra: Partial<Pick<ToolSpec, 'defaultOpen' | 'runningText' | 'Meta'>> = {},
): ToolSpec {
  return {
    category,
    icon,
    label: typeof label === 'string' ? fixed(label) : label,
    summary,
    Body,
    defaultOpen: extra.defaultOpen ?? 'live',
    ...(extra.runningText ? { runningText: extra.runningText } : {}),
    ...(extra.Meta ? { Meta: extra.Meta } : {}),
  };
}

function grepSummary(i: ToolInput): string {
  const pattern = quoted('pattern')(i);
  const where = str(i, 'path');
  return where ? `${pattern} in ${shortPath(where)}` : pattern;
}

function globSummary(i: ToolInput): string {
  const pattern = str(i, 'pattern') ?? '';
  const where = str(i, 'path');
  return where ? `${pattern} in ${shortPath(where)}` : pattern;
}

function taskSummary(i: ToolInput): string {
  const d = str(i, 'description') ?? '';
  const agent = str(i, 'subagent_type');
  return agent && agent !== 'general-purpose' ? `${agent}: ${d}` : d;
}

function openSessionSummary(i: ToolInput): string {
  const resume = str(i, 'resume_sdk_id');
  if (resume) return `resume ${shortId(resume)}`;
  return str(i, 'title') ?? '';
}

/** Built-in and orchestrator tools by canonical name. */
export const TOOL_SPECS: Readonly<Record<string, ToolSpec>> = {
  // read
  Read: spec('read', 'description', 'Read', path('file_path'), ReadBody),
  Glob: spec('search', 'search', 'Glob', globSummary, GlobBody),
  Grep: spec('search', 'search', 'Grep', grepSummary, GrepBody),
  WebSearch: spec('search', 'search', 'WebSearch', quoted('query'), WebSearchBody),
  ListFiles: spec('read', 'folder_open', 'ListFiles', path('path'), ListFilesBody),
  ReadManyFiles: spec('read', 'description', 'ReadManyFiles', (i) => `${(arr(i, 'paths') ?? []).length} files`, ListFilesBody),
  // navigate (mockups: WebFetch is a navigation, "explore")
  WebFetch: spec('navigate', 'explore', 'WebFetch', (i) => stripScheme(str(i, 'url') ?? ''), WebFetchBody, { runningText: 'Waiting for response…' }),
  // write
  Write: spec('write', 'code', 'Write', path('file_path'), WriteBody),
  Edit: spec('write', 'edit_document', 'Edit', path('file_path'), EditBody, { Meta: EditMeta }),
  MultiEdit: spec('write', 'edit_document', 'MultiEdit', path('file_path'), MultiEditBody),
  NotebookEdit: spec('write', 'book', 'NotebookEdit', path('notebook_path'), NotebookEditBody),
  // todo / task
  TodoWrite: spec('todo', 'checklist', 'TodoWrite', todoProgress, TodoBody, { defaultOpen: 'always' }),
  Task: spec('task', 'assignment', 'Task', taskSummary, TaskBody),
  // execute
  Bash: spec('execute', 'terminal', 'Bash', (i) => str(i, 'description') ?? firstLine(str(i, 'command') ?? ''), BashBody),
  BashOutput: spec('execute', 'terminal', 'BashOutput', (i) => str(i, 'bash_id') ?? '', ShellIdBody),
  KillShell: spec('execute', 'stop', 'KillShell', (i) => str(i, 'shell_id') ?? str(i, 'bash_id') ?? '', ShellIdBody),
  Skill: spec('execute', 'star_shine', 'Skill', (i) => {
    const s = str(i, 'skill') ?? str(i, 'command');
    return s ? `/${s.replace(/^\//, '')}` : '';
  }, SkillBody),
  EnterPlanMode: spec('execute', 'edit_note', 'EnterPlanMode', fixed('Enter plan mode')),
  ExitPlanMode: spec('execute', 'edit_note', 'ExitPlanMode', (i) => firstLine(str(i, 'plan') ?? 'Exit plan mode'), PlanBody),
  // interact
  AskUserQuestion: spec('interact', 'help', 'AskUserQuestion', (i) => firstLine(parseQuestions(i)[0]?.question ?? 'Ask user', 80), AskUserQuestionBody),
  // system (Qwen / Gemini extras)
  SaveMemory: spec('system', 'book_2', 'SaveMemory', (i) => firstLine(str(i, 'fact') ?? ''), GenericBody),
  ToolSearch: spec('search', 'search', 'ToolSearch', quoted('query'), GenericBody),

  // Orchestrator tools (resolved before the Qwen mapping in orchestrator conversations, TC-4)
  read_file: spec('read', 'description', 'read_file', path('path'), OrchestratorReadBody),
  write_file: spec('write', 'code', 'write_file', path('path'), OrchestratorWriteBody),
  run_script: spec('script', 'code', 'run_script', (i) => `${baseName(str(i, 'script') ?? '')} ${scriptArgs(i)}`.trim(), RunScriptBody),
  list_agent_sessions: spec('agent', 'list', 'list_agent_sessions', fixed('List active sessions'), AgentSessionBody),
  open_agent_session: spec('agent', 'open_in_new', 'open_agent_session', openSessionSummary, AgentSessionBody),
  close_agent_session: spec('agent', 'close', 'close_agent_session', session, AgentSessionBody),
  read_agent_session: spec('agent', 'visibility', 'read_agent_session', session, AgentSessionBody),
  send_to_agent_session: spec('agent', 'smart_toy', 'send_to_agent_session', (i) => firstLine(str(i, 'message') ?? ''), SendToAgentBody),
  interrupt_agent_session: spec('agent', 'stop', 'interrupt_agent_session', session, AgentSessionBody),
  respond_to_agent_permission: spec('agent', 'smart_toy', 'respond_to_agent_permission', (i) => str(i, 'decision') ?? '', AgentSessionBody),
  list_history: spec('agent', 'history', 'list_history', fixed('List session history'), AgentSessionBody),
  search_history: spec('search', 'search', 'search_history', quoted('query'), SearchHistoryBody),
  read_conversation: spec('read', 'history', 'read_conversation', readConversationSummary, ReadConversationBody),
  search_memory: spec('search', 'search', 'search_memory', quoted('query'), SearchBody),
  get_assistant_config: spec('system', 'tune', 'get_assistant_config', fixed(''), GenericBody),
  update_assistant_config: spec('system', 'tune', 'update_assistant_config', (i) => Object.keys(i).join(', '), GenericBody),
  end_voice_session: spec('system', 'call_end', 'end_voice_session', (i) => str(i, 'reason') ?? '', GenericBody),
  listen_recording: spec('system', 'hearing', 'listen_recording', (i) => {
    const s = num(i, 'start_ms');
    const e = num(i, 'end_ms');
    return s !== undefined && e !== undefined ? `${Math.round(s / 1000)}–${Math.round(e / 1000)} s` : '';
  }, GenericBody),
};

/* ---------- chrome-devtools MCP (~25 phrasings, inv02 F-05) ---------- */

const CHROME = 'mcp__chrome-devtools__';

interface ChromeSpec {
  readonly category: ToolCategory;
  readonly icon: IconName;
  readonly label: (i: ToolInput) => string;
  readonly summary: (i: ToolInput) => string;
}

const ref = (i: ToolInput): string => {
  const uid = str(i, 'uid');
  return uid ? `ref ${uid}` : '';
};
const none = fixed('');

const CHROME_SPECS: Readonly<Record<string, ChromeSpec>> = {
  navigate_page: {
    category: 'navigate',
    icon: 'explore',
    label: (i) => (i.type === 'reload' ? 'Reload page' : i.type === 'back' ? 'Go back' : i.type === 'forward' ? 'Go forward' : 'Navigate'),
    summary: (i) => (i.type === 'reload' || i.type === 'back' || i.type === 'forward' ? '' : stripScheme(str(i, 'url') ?? '')),
  },
  new_page: { category: 'navigate', icon: 'open_in_new', label: fixed('New page'), summary: (i) => stripScheme(str(i, 'url') ?? '') },
  close_page: { category: 'navigate', icon: 'close', label: fixed('Close page'), summary: (i) => (str(i, 'pageId') ? `#${str(i, 'pageId')}` : '') },
  select_page: { category: 'navigate', icon: 'explore', label: fixed('Select page'), summary: (i) => (str(i, 'pageId') ? `#${str(i, 'pageId')}` : '') },
  resize_page: { category: 'navigate', icon: 'explore', label: fixed('Resize'), summary: (i) => `${str(i, 'width') ?? '?'}×${str(i, 'height') ?? '?'}` },
  wait_for: { category: 'navigate', icon: 'hourglass_top', label: fixed('Wait for'), summary: quoted('text') },
  // mockups: Click is an interaction ("touch_app")
  click: { category: 'interact', icon: 'touch_app', label: (i) => (bool(i, 'dblClick') ? 'Double click' : 'Click'), summary: ref },
  hover: { category: 'interact', icon: 'touch_app', label: fixed('Hover'), summary: ref },
  drag: {
    category: 'interact',
    icon: 'touch_app',
    label: fixed('Drag'),
    summary: (i) => (str(i, 'from_uid') && str(i, 'to_uid') ? `ref ${str(i, 'from_uid')} → ref ${str(i, 'to_uid')}` : ''),
  },
  fill: { category: 'interact', icon: 'keyboard', label: fixed('Fill input'), summary: ref },
  fill_form: { category: 'interact', icon: 'keyboard', label: fixed('Fill form'), summary: (i) => `${(arr(i, 'elements') ?? []).length} fields` },
  press_key: { category: 'interact', icon: 'keyboard', label: fixed('Press'), summary: (i) => str(i, 'key') ?? '' },
  handle_dialog: { category: 'interact', icon: 'touch_app', label: (i) => (i.action === 'accept' ? 'Accept dialog' : 'Dismiss dialog'), summary: none },
  upload_file: { category: 'interact', icon: 'upload_file', label: fixed('Upload file'), summary: (i) => baseName(str(i, 'filePath') ?? '') },
  take_screenshot: {
    category: 'capture',
    icon: 'screenshot_monitor',
    label: fixed('Screenshot'),
    summary: (i) => (str(i, 'filePath') ? baseName(str(i, 'filePath') ?? '') : bool(i, 'fullPage') ? 'full page' : ref(i)),
  },
  take_snapshot: { category: 'capture', icon: 'content_copy', label: fixed('Snapshot'), summary: none },
  performance_start_trace: { category: 'capture', icon: 'speed', label: fixed('Start trace'), summary: none },
  performance_stop_trace: { category: 'capture', icon: 'speed', label: fixed('Stop trace'), summary: none },
  performance_analyze_insight: { category: 'capture', icon: 'speed', label: fixed('Analyze insight'), summary: (i) => str(i, 'insightName') ?? '' },
  evaluate_script: { category: 'script', icon: 'bolt', label: fixed('Run script'), summary: (i) => firstLine(str(i, 'function') ?? '') },
  list_console_messages: { category: 'read', icon: 'list', label: fixed('List console'), summary: none },
  get_console_message: { category: 'read', icon: 'list', label: fixed('Get console message'), summary: (i) => (str(i, 'msgid') ? `#${str(i, 'msgid')}` : '') },
  list_network_requests: { category: 'read', icon: 'network_check', label: fixed('List network'), summary: none },
  get_network_request: { category: 'read', icon: 'network_check', label: fixed('Get network request'), summary: (i) => (str(i, 'reqid') ? `#${str(i, 'reqid')}` : '') },
  list_pages: { category: 'read', icon: 'list', label: fixed('List pages'), summary: none },
  emulate: { category: 'read', icon: 'mobile', label: fixed('Emulate device'), summary: (i) => firstStringArg(i) },
};

function chromeSpec(action: string): ToolSpec {
  const c = CHROME_SPECS[action];
  if (!c) return spec('system', 'build', action.replace(/_/g, ' '), (i) => firstStringArg(i));
  return {
    category: c.category,
    icon: c.icon,
    label: c.label,
    summary: c.summary,
    Body: action === 'evaluate_script' ? EvaluateScriptBody : GenericBody,
    defaultOpen: 'live',
  };
}

const specCache = new Map<string, ToolSpec>();

/** The spec of a canonical tool name: built-ins, orchestrator tools, chrome-devtools, generic MCP, default. */
export function getToolSpec(name: string): ToolSpec {
  const known = TOOL_SPECS[name];
  if (known) return known;
  const cached = specCache.get(name);
  if (cached) return cached;
  let s: ToolSpec;
  if (name.startsWith(CHROME)) {
    s = chromeSpec(name.slice(CHROME.length));
  } else if (name.startsWith('mcp__')) {
    const parts = name.split('__');
    const tool = parts.length >= 3 ? parts.slice(2).join('__') : name;
    s = spec('system', 'build', tool.replace(/_/g, ' '), (i) => firstStringArg(i));
  } else {
    s = spec('system', 'build', name, (i) => firstStringArg(i));
  }
  specCache.set(name, s);
  return s;
}

export interface ResolvedTool {
  /** Canonical name. */
  readonly name: string;
  readonly input: ToolInput;
  readonly spec: ToolSpec;
  readonly label: string;
  readonly summary: string;
}

/** Normalise + look up a block's tool (context-aware for orchestrator conversations). */
export function resolveTool(block: Pick<ToolBlock, 'tool_name' | 'tool_input'>, kind: SessionKind = 'agent'): ResolvedTool {
  const { name, input } = normalizeTool(block.tool_name, block.tool_input, kind);
  const s = getToolSpec(name);
  let label: string;
  let summary: string;
  try {
    label = s.label(input) || name;
    summary = s.summary(input);
  } catch {
    label = name;
    summary = '';
  }
  return { name, input, spec: s, label, summary };
}

/** One-line sentence for accessible names and group summaries: `Grep "x" in src/`. */
export function headline(resolved: Pick<ResolvedTool, 'label' | 'summary'>): string {
  return resolved.summary ? `${resolved.label} ${resolved.summary}` : resolved.label;
}
