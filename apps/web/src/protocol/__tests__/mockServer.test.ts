/**
 * The mock backend (mock-server/, spec 13 §6.5) end to end: real WebSockets and HTTP, a client
 * driven only by this module's effects, and the result compared with each scenario's `expected`.
 * This proves the mock replays faithfully, so later work packages can trust it.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Fixture } from '../../../mock-server/scenarios.mjs';
import { startMockServer, type MockServer } from '../../../mock-server/server.mjs';
import type { Conversation, ConversationInput, Effect, MessagesPage } from '../index';
import { decodeFrame, encodeClientMessage, initialConversation, stepConversation } from '../index';
import { actionInput, normalise } from './harness';

let server: MockServer;

beforeAll(async () => {
  server = await startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true });
});
afterAll(async () => {
  await server.close();
});

const WATCHER = new Set(['agent_session_opened', 'agent_session_closed', 'ping']);
const EMPTY_PAGE: MessagesPage = { messages: [], total_count: 0, has_more: false, start_index: 0 };

/** A minimal session runtime: socket + REST, all decisions taken by stepConversation's effects. */
class Client {
  conv: Conversation;
  ws: WebSocket | null = null;
  received = 0;
  pending = 0;
  closedByServer = 0;
  private readonly path: string;

  constructor(
    readonly fx: Fixture,
    readonly localId: string,
  ) {
    this.path = fx.session.kind === 'agent' ? '/api/sessions/chat' : '/api/orchestrator/chat';
    this.conv = initialConversation({
      localId,
      kind: fx.session.kind,
      sdkId: fx.session.sdk_id,
      provider: fx.session.provider,
      liveStatus: fx.session.live_status ?? null,
      voiceActive: fx.session.voice_active === true,
    });
  }

  step(input: ConversationInput): void {
    const r = stepConversation(this.conv, input);
    this.conv = r.state;
    for (const e of r.effects) this.run(e);
  }

  run(e: Effect): void {
    if (e.type === 'send') this.ws?.send(encodeClientMessage(e.message));
    else if (e.type === 'reload') void this.fetchPage('replace');
    else if (e.type === 'reconcile') void this.fetchPage('reconcile');
  }

  async fetchPage(mode: 'replace' | 'reconcile'): Promise<void> {
    this.pending += 1;
    const sdk = this.conv.ref.sdkId;
    const res = sdk ? await fetch(`${server.url}/api/sessions/${encodeURIComponent(sdk)}/messages?limit=50`) : null;
    const page = res && res.ok ? ((await res.json()) as MessagesPage) : EMPTY_PAGE;
    this.step({ type: 'history_page', mode, response: page });
    this.pending -= 1;
  }

  connect(onFrame: () => void): void {
    const ws = new WebSocket(`ws://127.0.0.1:${server.port}${this.path}`);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onopen = () => this.step({ type: 'socket_open' });
    ws.onmessage = (ev) => {
      expect(ev.data).toBeInstanceOf(ArrayBuffer); // G-2: binary frames
      const d = decodeFrame(ev.data);
      if (!d.ok) throw new Error(d.reason);
      if (!WATCHER.has(d.frame.type)) this.received += 1; // count scripted frames only
      this.step({ type: 'frame', frame: d.frame });
      onFrame();
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.closedByServer += 1;
      this.step({ type: 'socket_closed' });
      this.connect(onFrame); // reconnect at once (the real runtime backs off)
    };
  }
}

/** Replays a scenario through the mock and resolves with the client's final conversation. */
async function play(fx: Fixture): Promise<Conversation> {
  const localId = `${fx.name}:${Math.random().toString(36).slice(2)}`;
  const c = new Client(fx, localId);
  const actions = (fx.client_actions ?? []).slice();
  const synthesized = fx.events[0]?.type !== 'session_started' ? 1 : 0;
  let applied = 0;
  const applyDue = () => {
    if (c.conv.reloading || c.pending > 0) return; // the runner applies actions after the history (README)
    const count = Math.max(0, c.received - synthesized);
    while (applied < actions.length && (actions[applied]?.at ?? 0) <= count) {
      const a = actions[applied++];
      if (!a || a.type === 'ws_closed' || a.type === 'ws_open' || a.type === 'rest_page') continue; // driven by the wire
      const input = actionInput(a);
      if (input) c.step(input);
      if (a.type === 'send') c.ws?.send(encodeClientMessage({ type: 'send', text: String(a.text) }));
      else if (a.type === 'interrupt') c.ws?.send(encodeClientMessage({ type: 'interrupt' }));
      else if (a.type === 'compact') c.ws?.send(encodeClientMessage({ type: 'compact' }));
      else if (a.type === 'inject') c.ws?.send(encodeClientMessage({ type: 'inject_text', text: String(a.text) }));
      else if (a.type === 'permission_response')
        c.ws?.send(encodeClientMessage({ type: 'permission_response', request_id: String(a.request_id), decision: a.decision === 'allow' ? 'allow' : 'deny' }));
    }
  };
  let fetchedHistory = false;
  const coldOpen = c.conv.ref.sdkId !== null;
  if (coldOpen) c.step({ type: 'begin_reload' }); // §5.2: subscribe, fetch, then apply held frames
  c.connect(() => {
    if (coldOpen && !fetchedHistory) {
      fetchedHistory = true; // the session exists server-side now
      void c.fetchPage('replace').then(applyDue);
    }
    applyDue();
  });
  const deadline = Date.now() + 4000;
  for (;;) {
    const run = server.engine.runs.get(localId);
    const done = run && run.i >= run.steps.length && !run.timer && !run.waitingFor && c.pending === 0 && !c.conv.reloading;
    if (done) {
      await new Promise((r) => setTimeout(r, 30)); // let in-flight frames land
      if (c.pending === 0) break;
    }
    if (Date.now() > deadline) throw new Error(`timeout: ${fx.name} run=${run ? `${run.i}/${run.steps.length} waiting=${run.waitingFor}` : 'none'}`);
    await new Promise((r) => setTimeout(r, 10));
  }
  applyDue();
  c.ws?.close();
  c.ws = null;
  return c.conv;
}

const LIVE = [
  'plain_text_turn',
  'text_tool_interleaving',
  'thinking_and_text',
  'permission_request_resolve',
  'web_bug5_permission_feedback_then_result',
  'stall_notice_repeated_seq',
  'termination_banner',
  'interrupt_mid_tool',
  'compaction',
  'replay_after_reconnect_overlap',
  'replay_overflow_rest_reload',
  'tool_result_empty_id_parallel_reconcile',
  'history_load_live_continuation',
  'voice_passive_viewer_no_wedge',
  'voice_transcript_coalescing_gemini',
  'orchestrator_error_no_turn_complete',
  'orchestrator_parallel_tools',
];

describe('mock server replays scenarios faithfully (W-05 DoD)', () => {
  it.each(LIVE)('%s over real sockets reaches the expected state', async (name) => {
    const fx = server.engine.scenarios.get(name);
    expect(fx, name).toBeDefined();
    const conv = await play(fx as Fixture);
    const exp = (fx as Fixture).expected;
    const keys = Object.keys(exp.state ?? {}).filter((k) => k !== 'last_start' && k !== 'local_id');
    const actual = normalise(conv, keys);
    expect(actual.entries).toStrictEqual(exp.entries);
    expect(actual.orphan_results).toStrictEqual(exp.orphan_results ?? []);
    expect(actual.unattributed_results).toStrictEqual(exp.unattributed_results ?? []);
    if (exp.queue !== undefined) expect(actual.queue).toStrictEqual(exp.queue);
    const expState = Object.fromEntries(Object.entries(exp.state ?? {}).filter(([k]) => keys.indexOf(k) >= 0));
    expect(actual.state).toStrictEqual(expState);
  });

  it('the mock-only orchestrator_parallel_tools scenario also passes the offline runner', async () => {
    const { runFixture } = await import('./harness');
    const fx = server.engine.scenarios.get('orchestrator_parallel_tools') as Fixture;
    const n = normalise(runFixture(fx).conv, Object.keys(fx.expected.state ?? {}));
    expect(n.entries).toStrictEqual(fx.expected.entries);
    expect(n.state).toStrictEqual(fx.expected.state);
  });
});

/** Open a socket, return helpers to send and collect decoded frames. */
async function socket(path: string): Promise<{ ws: WebSocket; frames: Record<string, unknown>[]; until: (pred: (f: Record<string, unknown>) => boolean) => Promise<void> }> {
  const ws = new WebSocket(`ws://127.0.0.1:${server.port}${path}`);
  ws.binaryType = 'arraybuffer';
  const frames: Record<string, unknown>[] = [];
  ws.onmessage = (ev) => {
    const d = decodeFrame(ev.data);
    if (d.ok) frames.push(d.frame as unknown as Record<string, unknown>);
  };
  await new Promise((r) => (ws.onopen = r));
  const until = async (pred: (f: Record<string, unknown>) => boolean) => {
    const end = Date.now() + 3000;
    while (!frames.some(pred)) {
      if (Date.now() > end) throw new Error(`timeout; got ${frames.map((f) => f.type).join(',')}`);
      await new Promise((r) => setTimeout(r, 5));
    }
  };
  return { ws, frames, until };
}

describe('mock server echo mode', () => {
  it('a chat "tool" turn streams valid frames that the reducer turns into a finished run', async () => {
    const { ws, frames, until } = await socket('/api/sessions/chat');
    ws.send(JSON.stringify({ type: 'start', local_id: 'echo-1' }));
    await until((f) => f.type === 'session_started');
    ws.send(JSON.stringify({ type: 'send', text: 'please use a tool' }));
    await until((f) => f.type === 'turn_complete');
    let conv = initialConversation({ localId: 'echo-1', kind: 'agent', provider: 'claude' });
    conv = stepConversation(conv, { type: 'local_send', text: 'please use a tool' }).state;
    for (const f of frames) {
      const d = decodeFrame(JSON.stringify(f));
      if (d.ok) conv = stepConversation(conv, { type: 'frame', frame: d.frame }).state;
    }
    expect(conv.status).toBe('idle');
    expect(conv.ref.sdkId).toMatch(/^mock-/);
    const run = conv.entries[1];
    expect(run?.kind === 'assistant' && run.blocks.map((b) => (b.type === 'tool' ? `${b.tool_name}:${b.status}` : b.type))).toEqual(['text', 'Bash:done', 'Read:done', 'text']);
    const hist = await (await fetch(`${server.url}/api/sessions/${conv.ref.sdkId}/messages?limit=50`)).json();
    expect(hist.messages.map((m: { role: string }) => m.role)).toEqual(['user', 'assistant']);
    const pool = await (await fetch(`${server.url}/api/sessions/pool/live`)).json();
    expect(pool.some((r: { local_id: string }) => r.local_id === 'echo-1')).toBe(true);
    ws.close();
  });

  it('plan: permission request answered by a follow-up send (deny with feedback), queued prompt echo, interrupt', async () => {
    const a = await socket('/api/sessions/chat');
    const b = await socket('/api/sessions/chat');
    a.ws.send(JSON.stringify({ type: 'start', local_id: 'echo-2' }));
    b.ws.send(JSON.stringify({ type: 'start', local_id: 'echo-2' }));
    await a.until((f) => f.type === 'session_started');
    await b.until((f) => f.type === 'session_started');
    a.ws.send(JSON.stringify({ type: 'send', text: 'make a plan' }));
    await b.until((f) => f.type === 'user_message' && f.text === 'make a plan');
    await a.until((f) => f.type === 'permission_request');
    a.ws.send(JSON.stringify({ type: 'send', text: 'no, smaller steps' }));
    await b.until((f) => f.type === 'user_message' && f.queued === true);
    await a.until((f) => f.type === 'permission_resolved' && f.decision === 'deny' && f.message === 'no, smaller steps');
    await a.until((f) => f.type === 'turn_complete' && a.frames.filter((x) => x.type === 'turn_complete').length === 2);
    expect(b.frames.filter((f) => f.type === 'user_message' && f.text === 'no, smaller steps')).toHaveLength(1); // O-6
    a.ws.send(JSON.stringify({ type: 'send', text: 'stall please' }));
    await b.until((f) => f.type === 'session_stalled');
    a.ws.send(JSON.stringify({ type: 'interrupt' }));
    await b.until((f) => f.type === 'status' && f.status === 'interrupted'); // BF-2 broadcast
    a.ws.close();
    b.ws.close();
  });

  it('wire rules: binary from the client drops the socket; chat ping is unknown_type; send before start is not_started', async () => {
    const s = await socket('/api/sessions/chat');
    s.ws.send(JSON.stringify({ type: 'ping' }));
    await s.until((f) => f.type === 'error' && f.error === 'unknown_type');
    s.ws.send(JSON.stringify({ type: 'send', text: 'x' }));
    await s.until((f) => f.type === 'error' && f.error === 'not_started');
    s.ws.send('{bad');
    await s.until((f) => f.type === 'error' && f.error === 'invalid_json');
    const closed = new Promise<number>((r) => (s.ws.onclose = (ev) => r(ev.code)));
    s.ws.send(new Uint8Array([1, 2, 3]));
    expect(await closed).toBe(1006); // no close frame
  });

  it('orchestrator: watcher events, conflict, voice signaling, inject echo, models', async () => {
    const watcher = await socket('/api/orchestrator/chat');
    const o = await socket('/api/orchestrator/chat');
    o.ws.send(JSON.stringify({ type: 'start', local_id: 'orch-1' }));
    await o.until((f) => f.type === 'session_started' && f.voice === false);
    await watcher.until((f) => f.type === 'agent_session_opened' && f.is_orchestrator === true);
    const other = await socket('/api/orchestrator/chat');
    other.ws.send(JSON.stringify({ type: 'start', local_id: 'orch-2' }));
    await other.until((f) => f.type === 'error' && f.error === 'orchestrator_active');
    other.ws.send(JSON.stringify({ type: 'start', local_id: 'orch-1' }));
    await other.until((f) => f.type === 'session_started');
    o.ws.send(JSON.stringify({ type: 'send', text: 'tool time' }));
    await other.until((f) => f.type === 'user_message' && f.text === 'tool time'); // O-3
    await o.until((f) => f.type === 'status' && f.status === 'idle');
    expect(o.frames.map((f) => f.type)).toEqual(expect.arrayContaining(['tool_use', 'tool_executing', 'tool_progress', 'tool_result', 'turn_complete']));
    o.ws.send(JSON.stringify({ type: 'voice_start', local_id: 'orch-1' }));
    await o.until((f) => f.type === 'session_started' && f.voice === true && f.voice_initiator === true);
    await other.until((f) => f.type === 'voice_owner_active' && f.active === true);
    await other.until((f) => f.type === 'voice_event' && (f.event as { status?: string }).status === 'ready');
    o.ws.send(JSON.stringify({ type: 'inject_text', text: '[shared text]\nhi' }));
    await o.until((f) => f.type === 'user_message' && f.source === 'shared_inject');
    o.ws.send(JSON.stringify({ type: 'voice_stop' }));
    await other.until((f) => f.type === 'voice_owner_active' && f.active === false);
    o.ws.send(JSON.stringify({ type: 'get_models' }));
    await o.until((f) => f.type === 'models_list');
    o.ws.send(JSON.stringify({ type: 'compact' }));
    await o.until((f) => f.type === 'compact_complete');
    await fetch(`${server.url}/api/sessions/orch-1/close`, { method: 'POST' });
    await watcher.until((f) => f.type === 'agent_session_closed' && f.is_orchestrator === true);
    for (const s of [watcher, o, other]) s.ws.close();
  });
});

describe('mock server REST', () => {
  const get = async (p: string) => {
    const r = await fetch(`${server.url}${p}`);
    return { status: r.status, body: r.headers.get('content-type')?.includes('json') ? await r.json() : await r.text() };
  };

  it('serves the synthetic catalogs', async () => {
    expect((await get('/api/auth/status')).body).toEqual({ authenticated: true, auth_url: null, headless: true });
    const list = (await get('/api/sessions')).body as { session_id: string }[];
    expect(list.map((s) => s.session_id)).toEqual(expect.arrayContaining(['mock-sess-refactor', 'scn-plain_text_turn']));
    expect(((await get('/api/config')).body as { provider: string }).provider).toBe('claude');
    for (const p of ['/api/config/providers', '/api/config/harness/qwen/models', '/api/config/voice/google/models', '/api/orchestrator/models', '/api/orchestrator/models/audio', '/api/orchestrator/voice/models', '/api/mcp/servers', '/api/mcp/servers/filesystem', '/api/skills', '/api/agents', '/api/visualizations'])
      expect((await get(p)).status, p).toBe(200);
    const tree = (await get('/api/memory/tree')).body as { name: string; is_dir: boolean }[];
    expect(tree.map((n) => n.name)).toEqual(['notes', 'projects', 'MEMORY.md']);
    expect((await get('/memory/projects/garden.md')).body).toContain('# Garden plan');
    expect((await get('/memory/')).body).toContain('# Memory');
    expect((await get('/memory/..%2fconfig.json')).status).toBe(404); // traversal
    expect((await get('/solar-system/index.html')).body).toContain('Synthetic visualization');
  });

  it('pages history like the backend and supports the mutations', async () => {
    const pg = (await get('/api/sessions/mock-sess-refactor/messages?limit=3')).body as MessagesPage;
    expect(pg).toMatchObject({ total_count: 9, has_more: true, start_index: 6 });
    const older = (await get('/api/sessions/mock-sess-refactor/messages?limit=3&before=6')).body as MessagesPage;
    expect(older.start_index).toBe(3);
    expect((await get('/api/sessions/nope/messages')).status).toBe(404);
    const req = (p: string, method: string, body?: unknown) =>
      fetch(`${server.url}${p}`, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    expect((await req('/api/sessions/mock-sess-garden/rename', 'PATCH', { title: 'Garden 2' })).status).toBe(204);
    expect((await req('/api/sessions/mock-sess-garden/rename', 'PATCH', { title: ' ' })).status).toBe(400);
    const fork = await req('/api/sessions/mock-sess-refactor/fork', 'POST', { drop_last_n: 2 });
    expect(fork.status).toBe(201);
    const forkId = ((await fork.json()) as { session_id: string }).session_id;
    expect(((await get(`/api/sessions/${forkId}/messages`)).body as MessagesPage).total_count).toBe(7);
    expect((await req('/api/sessions/mock-sess-weather/duplicate', 'POST')).status).toBe(201);
    expect((await req('/api/sessions/mock-sess-weather/truncate', 'POST', { drop_last_n: -1 })).status).toBe(400);
    expect((await req('/api/sessions/mock-sess-weather', 'DELETE')).status).toBe(204);
    expect((await req('/api/config', 'PUT', { voice_vad_threshold: 0.9 })).status).toBe(400);
    const put = await req('/api/config', 'PUT', { voice_vad_threshold: 0.3 });
    expect(((await put.json()) as { voice_vad_threshold: number }).voice_vad_threshold).toBe(0.3);
    expect((await req('/api/sessions/x/config', 'PUT', { provider: 'qwen', junk: 1 })).status).toBe(200);
    expect((await get('/api/sessions/x/config')).body).toMatchObject({ provider: 'qwen', working_directory: null });
    expect((await get('/api/visualizations/cast')).body).toMatchObject({ available: false });
    const up = await fetch(`${server.url}/api/uploads`, { method: 'POST', body: new Blob(['hello']), headers: { 'content-type': 'text/plain' } });
    const upBody = (await up.json()) as { url: string; size: number };
    expect(upBody.size).toBe(5);
    expect((await get(upBody.url)).status).toBe(200);
    expect((await req('/api/debug/log', 'POST', { level: 'warn', msg: 'hi' })).status).toBe(204);
    expect((await get('/api/debug/log')).body).toContain('[WARN] hi');
  });
});
