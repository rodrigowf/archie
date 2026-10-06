/** Small builders shared by the unit tests. */
import type { Conversation, ConversationInput, Effect, InitialConversationOptions, ToolBlock } from '../index';
import { initialConversation, stepConversation } from '../index';
import { frameInput } from './harness';

export const f = frameInput;

export function agent(o: Partial<InitialConversationOptions> = {}): Conversation {
  return initialConversation({ localId: 'L1', kind: 'agent', sdkId: 'sdk-1', provider: 'claude', subscribed: true, ...o });
}

export function orch(o: Partial<InitialConversationOptions> = {}): Conversation {
  return initialConversation({ localId: 'O1', kind: 'orchestrator', sdkId: 'O1', subscribed: true, ...o });
}

/** Apply inputs (frame objects are wrapped as frames); returns the state and all effects. */
export function feed(conv: Conversation, ...inputs: (ConversationInput | Record<string, unknown>)[]): { conv: Conversation; effects: Effect[] } {
  const effects: Effect[] = [];
  for (const x of inputs) {
    const input = isInput(x) ? x : f(x);
    const r = stepConversation(conv, input);
    effects.push(...r.effects);
    conv = r.state;
  }
  return { conv, effects };
}

const INPUT_TYPES = [
  'frame',
  'history_page',
  'datachannel_event',
  'local_send',
  'local_send_audio',
  'local_inject',
  'local_interrupt',
  'local_compact',
  'local_stop',
  'voice_local_end',
  'socket_open',
  'socket_closed',
  'resend_start',
  'begin_reload',
  'reload_failed',
  'pool_status',
  'sdk_id',
  'dismiss_banner',
  'clear_agent_approvals',
];

function isInput(x: ConversationInput | Record<string, unknown>): x is ConversationInput {
  return INPUT_TYPES.indexOf(String(x.type)) >= 0 && !(x.type === 'frame' && !('frame' in x));
}

export function texts(conv: Conversation): string[] {
  return conv.entries.map((e) =>
    e.kind === 'assistant'
      ? `A[${e.blocks.map((b) => (b.type === 'tool' ? `tool:${b.tool_use_id}:${b.status}` : b.type === 'permission' ? `perm:${b.request_id}:${b.state}` : `${b.type}:${b.text}`)).join('|')}]`
      : e.kind === 'user'
        ? `U(${e.origin}${e.state === 'pending' ? ',pending' : ''}):${e.text}`
        : `N(${e.notice}):${e.text}`,
  );
}

export function page(messages: Record<string, unknown>[], start = 0, total?: number): ConversationInput {
  return {
    type: 'history_page',
    mode: 'replace',
    response: { messages, start_index: start, total_count: total ?? start + messages.length, has_more: start > 0 } as never,
  };
}

export const userLine = (text: string) => ({ role: 'user', text, blocks: [{ type: 'text', text }] });
export const asstText = (text: string) => ({ role: 'assistant', text, blocks: [{ type: 'text', text }] });
export const toolUseLine = (id: string, name = 'Bash', input: Record<string, unknown> = {}) => ({
  role: 'assistant',
  text: '',
  blocks: [{ type: 'tool_use', tool_use_id: id, tool_name: name, tool_input: input }],
});
export const toolResultLine = (id: string, output: unknown, is_error = false) => ({
  role: 'user',
  text: '',
  blocks: [{ type: 'tool_result', tool_use_id: id, output, is_error }],
});

/** The blocks of an entry that the test knows is an assistant run. */
export function runOf(e: unknown): { blocks: ToolBlock[] } {
  return e as { blocks: ToolBlock[] };
}
