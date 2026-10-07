/**
 * Tool name and input normalisation (spec 13 §3.6, spec 12 TC-4, inv02 F-05).
 *
 * Qwen Code and Gemini CLI use snake_case names for their built-in tools; Claude Code uses
 * PascalCase. Names are mapped to Claude's so the registry handles one canonical set.
 *
 * **Context-aware (fixes inv02 §6.3 #15):** in an orchestrator conversation the orchestrator's
 * own tools (`read_file`, `write_file`, …) are resolved *before* the Qwen mapping, so the
 * orchestrator `read_file` keeps its own renderer instead of falling back to generic JSON.
 */
import type { SessionKind } from '@/protocol';
import { isRecord, type ToolInput } from './format';

/** Qwen Code (packages/core/src/tools/tool-names.ts) and Gemini CLI built-ins → Claude names. */
export const SNAKE_TO_CLAUDE: Readonly<Record<string, string>> = {
  read_file: 'Read',
  write_file: 'Write',
  edit: 'Edit',
  replace: 'Edit',
  run_shell_command: 'Bash',
  grep_search: 'Grep',
  search_file_content: 'Grep',
  glob: 'Glob',
  web_fetch: 'WebFetch',
  google_web_search: 'WebSearch',
  web_search: 'WebSearch',
  todo_write: 'TodoWrite',
  write_todos: 'TodoWrite',
  agent: 'Task',
  task: 'Task',
  ask_user_question: 'AskUserQuestion',
  exit_plan_mode: 'ExitPlanMode',
  save_memory: 'SaveMemory',
  tool_search: 'ToolSearch',
  list_directory: 'ListFiles',
  read_many_files: 'ReadManyFiles',
  skill: 'Skill',
};

/** Claude Code aliases (newer CLIs call the subagent tool "Agent"). */
const CLAUDE_ALIASES: Readonly<Record<string, string>> = {
  Agent: 'Task',
  KillBash: 'KillShell',
};

/** The orchestrator's own tool names (orchestrator/tools/*.py). */
export const ORCHESTRATOR_TOOLS: ReadonlySet<string> = new Set([
  'read_file',
  'write_file',
  'run_script',
  'list_agent_sessions',
  'open_agent_session',
  'close_agent_session',
  'read_agent_session',
  'send_to_agent_session',
  'interrupt_agent_session',
  'respond_to_agent_permission',
  'list_history',
  'search_history',
  'read_conversation',
  'search_memory',
  'get_assistant_config',
  'update_assistant_config',
  'end_voice_session',
  'listen_recording',
]);

/** Canonical tool name for a raw `tool_name` in a conversation of `kind`. */
export function normalizeToolName(raw: string, kind: SessionKind = 'agent'): string {
  if (kind === 'orchestrator' && ORCHESTRATOR_TOOLS.has(raw)) return raw;
  return SNAKE_TO_CLAUDE[raw] ?? CLAUDE_ALIASES[raw] ?? raw;
}

/**
 * Maps provider-specific argument keys onto Claude's, so renderers read one shape. Returns the
 * same object when nothing changes (memoised renderers keep their identity).
 */
export function normalizeToolInput(name: string, input: ToolInput): ToolInput {
  const src = isRecord(input) ? input : {};
  const out: Record<string, unknown> = { ...src };
  let changed = src !== input;
  const move = (from: string, to: string): void => {
    if (out[to] === undefined && out[from] !== undefined) {
      out[to] = out[from];
      Reflect.deleteProperty(out, from);
      changed = true;
    }
  };
  switch (name) {
    case 'Read':
    case 'Write':
    case 'Edit':
      move('absolute_path', 'file_path');
      move('path', 'file_path'); // an orchestrator read_file/write_file seen without context
      break;
    case 'Grep':
      move('include', 'glob');
      move('dir_path', 'path');
      break;
    case 'Glob':
    case 'ListFiles':
      move('dir_path', 'path');
      break;
    case 'TodoWrite': {
      const todos = out.todos;
      if (Array.isArray(todos) && todos.some((t) => isRecord(t) && t.content === undefined && typeof t.description === 'string')) {
        out.todos = todos.map((t) => (isRecord(t) && t.content === undefined ? { ...t, content: t.description } : t));
        changed = true;
      }
      break;
    }
    default:
      break;
  }
  return changed ? out : input;
}

export interface NormalizedTool {
  /** Canonical name (registry key). */
  readonly name: string;
  /** Input with Claude's argument keys. */
  readonly input: ToolInput;
  /** The provider's original name, when it differs. */
  readonly rawName: string;
}

export function normalizeTool(rawName: string, rawInput: ToolInput, kind: SessionKind = 'agent'): NormalizedTool {
  const name = normalizeToolName(rawName, kind);
  return { name, input: normalizeToolInput(name, rawInput), rawName };
}
