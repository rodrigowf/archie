#!/usr/bin/env node
/**
 * Archie mock backend (spec 13 §6.5, W-05). REST from mock-server/data/ (synthetic content) and
 * the two WebSockets, so UI work, QA screenshots and tests never need the Jetson.
 *
 *   node mock-server/server.mjs [--port 8799] [--host 0.0.0.0] [--fast] [--speed 1]
 *                               [--scenario <name>] [--nginx-413] [--quiet] [--list]
 *   ARCHIE_BACKEND=http://localhost:8799 npm run dev      # point the Vite proxy at it
 *
 * Wire behaviour like the backend: server frames are BINARY (UTF-8 JSON, G-2); a binary frame
 * from the client drops the socket with no close frame (T-2); `ping` on the chat WS answers
 * `error{unknown_type}` (G-24); the orchestrator WS sends `{type:"ping"}` every 15 s and watcher
 * events (`agent_session_opened/closed`) to every connected socket, subscribed or not.
 *
 * Scenarios (any apps/protocol-fixtures file, plus mock-server/data/scenarios/*.json) replay on
 * `start`. Chosen, in order, by: the WS URL query `?scenario=<name>`; a `local_id` of the form
 * `<name>:<anything>`; `resume_sdk_id` = `scn-<name>` (scenarios are listed in GET /api/sessions);
 * the `--scenario` default. The replay waits for the client's own messages where the fixture has
 * client actions (send, interrupt, permission_response, …), drops the socket at `ws_closed`, and
 * serves the fixture's REST pages (`initial_history`, `rest_page`) for the session's history.
 * After the script (or with no scenario) a session runs in "echo" mode: replies are generated.
 * Echo keywords: "tool", "plan" (ExitPlanMode permission), "think", "stall", "long", "fail",
 * "crash", "agents" (spec 12 §4.8: two foreground subagents, one nested under the first, and a
 * background one that finishes after the turn and triggers a task-notification turn; their
 * transcripts are served by GET …/subagents[/{id}/messages], `stop_subagent` stops one);
 * anything else is echoed back. Orchestrator: "tool", voice_start / voice_stop (Qwen-like
 * signaling only, no audio), inject_text, send_audio, compact, get_models / get_model / set_model.
 */
import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { WebSocketServer } from 'ws';
import { pageMessages, createRest } from './rest.mjs';
import { buildScript, firstCursor, loadScenarioMap, sdkIdsOf } from './scenarios.mjs';

const MODEL_INFO = {
  model: 'claude-sonnet-4-5-20250929',
  provider: 'anthropic',
  max_tokens: 8192,
  supports_audio: false,
  model_info: { provider: 'anthropic', model_id: 'claude-sonnet-4-5-20250929', display_name: 'Claude Sonnet 4.5', supports_audio: false, supports_vision: true, supports_tools: true, max_tokens: 8192, context_window: 200000 },
};
/** `set_model` answer: the catalog's capabilities for the chosen model (audio models accept `send_audio`). */
function modelInfoFor(model) {
  const audio = /audio/.test(String(model));
  return { ...MODEL_INFO, model, provider: audio ? 'openai' : MODEL_INFO.provider, supports_audio: audio, model_info: { ...MODEL_INFO.model_info, model_id: model, display_name: String(model), supports_audio: audio } };
}
const VOICE_FIELDS = {
  voice_provider: 'qwen',
  voice_model: 'qwen3.5-omni-plus-realtime',
  voice_name: 'Aiden',
  voice_transcription_language: 'en',
  voice_recording_enabled: false,
};
const LONG_ANSWER = [
  '## Plan',
  '',
  'Here is a longer answer so the UI has something to scroll and wrap.',
  '',
  '1. **Read** the inputs and check `config.json`.',
  '2. Split the work into *small* steps.',
  '3. Verify with tests.',
  '',
  '```ts',
  'export function add(a: number, b: number): number {',
  '  return a + b;',
  '}',
  '```',
  '',
  '| Step | Status |',
  '|---|---|',
  '| Read | done |',
  '| Split | running |',
  '',
  '- [x] task list item',
  '- [ ] another one, with a link to https://example.com',
].join('\n');

const now = () => new Date().toISOString().replace('Z', '+00:00');

function chunks(text, size = 6) {
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

class Cancelled extends Error {}

/** One pool session (agent or orchestrator): its subscribers, script cursor and echo state. */
class Run {
  constructor(engine, { localId, kind, fixture, provider }) {
    this.engine = engine;
    this.localId = localId;
    this.kind = kind;
    this.fixture = fixture ?? null;
    this.provider = kind === 'agent' ? (fixture?.session.provider ?? provider ?? 'claude') : null;
    this.sockets = new Set();
    this.steps = fixture ? buildScript(fixture) : [];
    this.i = 0;
    this.waitingFor = null;
    this.early = []; // client messages that arrived before the script waited for them
    this.awaitingReconnect = false;
    this.started = false;
    this.timer = null;
    this.sdkId = fixture?.session.sdk_id ?? (kind === 'orchestrator' ? localId : null);
    this.stream = `${localId}:${Date.now()}`;
    this.seq = 0;
    this.ring = [];
    this.history = [];
    this.restPage = null;
    this.pages = fixture?.initial_history ? [fixture.initial_history] : [];
    this.startedAt = now();
    this.lastActivity = this.startedAt;
    this.status = 'idle';
    this.cost = 0;
    this.turns = 0;
    this.queue = [];
    this.steers = []; // I-16: messages sent into the running turn
    this.busy = false;
    this.cancel = null;
    this.permissionWaiter = null;
    this.voice = { active: !!fixture?.session.voice_active, owner: null };
    this.closed = false;
    /** spec 12 §4.8: tool_use_id → { row: SubagentSummary, messages: MessagePreview[], stop: bool } */
    this.subagents = new Map();
  }

  title() {
    const first = this.history.find((m) => m.role === 'user');
    return first ? first.text.slice(0, 100) : this.kind === 'orchestrator' ? 'Orchestrator' : '(active session)';
  }

  // ───────────── sending ─────────────

  rewrite(frame) {
    const f = { ...frame };
    const fxId = this.fixture?.session.local_id;
    if (fxId) {
      if (f.session_id === fxId) f.session_id = this.localId;
      if (f.owner_local_id === fxId) f.owner_local_id = this.localId;
    }
    return f;
  }

  sendTo(ws, frame) {
    if (ws.readyState === 1) ws.send(Buffer.from(JSON.stringify(frame), 'utf8'), { binary: true });
  }

  broadcast(frame, exclude = null) {
    if (typeof frame.seq === 'number' && typeof frame.stream_id === 'string') {
      this.ring.push(frame);
      if (this.ring.length > 500) this.ring.shift();
    }
    this.lastActivity = now();
    for (const ws of this.sockets) if (ws !== exclude) this.sendTo(ws, frame);
  }

  /** Echo-mode frame with the resume cursor (Claude agent sessions only, spec 12 §2.2). */
  emit(frame, exclude = null) {
    const f = { ...frame };
    if (this.kind === 'agent' && this.provider === 'claude' && SEQ_TYPES.has(f.type)) {
      this.seq += 1;
      f.seq = this.seq;
      f.stream_id = this.stream;
    }
    this.broadcast(f, exclude);
  }

  sessionStarted(ws, extra = {}) {
    if (this.kind === 'agent') {
      const f = { type: 'session_started', session_id: this.localId, context_window: 200000 };
      if (this.subagents.size) f.subagents = [...this.subagents.values()].map((a) => ({ ...a.row })); // SA-3
      const cur = !this.started && this.fixture ? firstCursor(this.fixture) : this.seq > 0 ? { stream_id: this.stream, next_seq: this.seq + 1 } : null;
      if (cur) f.resume_state = cur;
      return { ...f, ...extra };
    }
    const f = { type: 'session_started', session_id: this.localId, voice: this.voice.active, model_info: MODEL_INFO, jsonl_id: this.sdkId };
    if (this.voice.active) Object.assign(f, VOICE_FIELDS, { voice_initiator: this.voice.owner === ws });
    return { ...f, ...extra };
  }

  // ───────────── subscription ─────────────

  attach(ws, msg) {
    this.sockets.add(ws);
    ws.run = this;
    if (this.awaitingReconnect) {
      // the script continues: its pre-start frames and its own session_started follow
      this.awaitingReconnect = false;
      if (!this.scriptHasSessionStartedNext()) this.sendTo(ws, this.sessionStarted(ws));
      this.started = true;
      return this.advance();
    }
    if (!this.started) {
      this.started = true;
      const next = this.steps[this.i];
      if (next && next.kind === 'emit' && next.frame.type === 'session_started') {
        this.i += 1;
        this.broadcast(this.rewrite(next.frame));
      } else {
        this.sendTo(ws, this.sessionStarted(ws));
      }
      return this.advance();
    }
    // re-start on a live session: session_started + replay of what the client missed (§4.5)
    this.sendTo(ws, this.sessionStarted(ws));
    const rf = msg.resume_from;
    if (rf && typeof rf.seq === 'number')
      for (const f of this.ring) if (f.stream_id === rf.stream_id && f.seq > rf.seq) this.sendTo(ws, f);
    if (!this.timer && !this.waitingFor) this.advance();
  }

  scriptHasSessionStartedNext() {
    for (let k = this.i; k < this.steps.length; k++) {
      const s = this.steps[k];
      if (s.kind !== 'emit') return false;
      if (s.frame.type === 'session_started') return true;
    }
    return false;
  }

  detach(ws) {
    this.sockets.delete(ws);
    if (this.voice.owner === ws) this.endVoice('client_disconnect');
  }

  // ───────────── script replay ─────────────

  get scripted() {
    return this.i < this.steps.length;
  }

  advance() {
    clearTimeout(this.timer);
    this.timer = null;
    while (this.i < this.steps.length) {
      const s = this.steps[this.i];
      if (s.kind === 'rest') {
        this.setRest(s);
        this.i += 1;
        continue;
      }
      if (s.kind === 'wait') {
        const k = this.early.indexOf(s.for);
        if (k >= 0) {
          this.early.splice(k, 1);
          this.i += 1;
          continue;
        }
        this.waitingFor = s.for;
        return;
      }
      if (s.kind === 'drop') {
        this.i += 1;
        this.awaitingReconnect = true;
        for (const ws of this.sockets) ws.terminate();
        this.sockets.clear();
        return;
      }
      if (this.sockets.size === 0) return; // resumes on the next attach
      this.timer = setTimeout(() => {
        this.timer = null;
        this.i += 1;
        const f = this.rewrite(s.frame);
        if (f.type === 'session_started' && f.replay_overflow) this.activateNextReplace();
        if (f.type === 'turn_complete' && typeof f.session_id === 'string') this.engine.alias(f.session_id, this);
        this.broadcast(f);
        this.advance();
      }, this.engine.delayFor(s.frame));
      return;
    }
  }

  /** The client reacts to `replay_overflow` with a REST reload at once: serve the scripted page now. */
  activateNextReplace() {
    for (let k = this.i; k < this.steps.length; k++) {
      const s = this.steps[k];
      if (s.kind === 'rest' && s.mode === 'replace') {
        this.setRest(s);
        return;
      }
    }
  }

  setRest(step) {
    if (step.mode === 'prepend') this.pages.push(step.response);
    else this.restPage = step.response;
  }

  historyPage(limit, before) {
    if (this.fixture) {
      if (before !== undefined) {
        const older = this.pages.find((pg) => pg.start_index + pg.messages.length === before);
        return older ?? { messages: [], total_count: 0, has_more: false, start_index: 0 };
      }
      return this.restPage ?? this.fixture.initial_history ?? (this.history.length ? pageMessages(this.history, limit) : null);
    }
    return pageMessages(this.history, limit, before);
  }

  /** A client message the script may be waiting for. Returns true when consumed by the script. */
  scriptReceive(type) {
    if (this.waitingFor !== type) {
      const later = this.steps.slice(this.i).some((st) => st.kind === 'wait' && st.for === type);
      if (later) this.early.push(type);
      return this.scripted;
    }
    this.waitingFor = null;
    this.i += 1;
    this.advance();
    return true;
  }

  // ───────────── echo mode ─────────────

  sleep(ms) {
    const token = this.cancel;
    return new Promise((resolve, reject) => {
      setTimeout(() => (token && token.cancelled ? reject(new Cancelled()) : resolve()), this.engine.fast ? 0 : ms * this.engine.speed);
    });
  }

  ensureSdkId() {
    if (!this.sdkId) {
      this.sdkId = `mock-${this.localId.replace(/[^A-Za-z0-9]/g, '').slice(0, 12) || 'session'}`;
      this.engine.alias(this.sdkId, this);
    }
    return this.sdkId;
  }

  record(role, text, blocks = [{ type: 'text', text }]) {
    this.history.push({ role, text, blocks: blocks.map((b) => ({ text: null, tool_use_id: null, tool_name: null, tool_input: null, output: null, is_error: false, ...b })), timestamp: now() });
  }

  prompt(text, sender) {
    if (this.busy && this.kind === 'agent' && this.provider === 'claude' && !this.queue.length) {
      // I-16 (pool.steer): into the running turn, echoed to everyone, the sender included
      this.steers.push(text);
      this.record('user', text);
      this.emit({ type: 'user_message', text, steered: true });
      return;
    }
    if (this.busy) {
      this.queue.push({ text, sender });
      if (this.kind === 'agent') this.emit({ type: 'user_message', text, queued: true }, sender);
      return;
    }
    this.runTurn(text, sender, true);
  }

  async runTurn(text, sender, announce) {
    this.busy = true;
    this.cancel = { cancelled: false };
    const agent = this.kind === 'agent';
    if (announce) this.emit({ type: 'user_message', text }, sender); // O-3 / pool.send: everyone but the sender
    this.record('user', text);
    this.emit({ type: 'status', status: agent ? 'processing' : 'streaming' });
    try {
      await (agent ? this.agentReply(text) : this.orchestratorReply(text));
      if (agent && this.steers.length) {
        const heard = this.steers.splice(0).join('; ');
        await this.streamText(`(Mock) Noted mid-turn: ${heard}.`);
      }
      if (agent) {
        this.turns += 1;
        this.cost += 0.0042;
        this.emit({ type: 'turn_complete', cost: 0.0042, usage: { input_tokens: 12, cache_read_input_tokens: 1800, cache_creation_input_tokens: 0, output_tokens: 64 }, input_tokens: 1812, output_tokens: 64, num_turns: 1, session_id: this.ensureSdkId(), is_error: false, result: 'ok' });
      } else {
        this.emit({ type: 'turn_complete', input_tokens: 2100, output_tokens: 64 });
        this.emit({ type: 'status', status: 'idle' });
      }
    } catch (e) {
      if (!(e instanceof Cancelled)) {
        this.emit({ type: 'error', error: 'send_failed', detail: String(e?.message ?? e) });
        if (!agent) this.emit({ type: 'status', status: 'idle' });
      }
      if (e instanceof Cancelled && !agent) {
        this.emit({ type: 'error', error: 'interrupted', detail: 'Interrupted by user' });
        this.emit({ type: 'status', status: 'idle' });
      }
    }
    this.busy = false;
    this.cancel = null;
    const next = this.queue.shift();
    if (next && !this.closed) this.runTurn(next.text, next.sender, false); // O-6: no second echo
  }

  async streamText(text, delta = 45) {
    for (const c of chunks(text)) {
      this.emit({ type: 'text_delta', text: c });
      await this.sleep(delta);
    }
    this.emit({ type: 'text_complete', text });
  }

  async tool(name, input, output, { isError = false, orchestrator = false, ms = 500 } = {}) {
    const id = `${orchestrator ? 'call' : 'toolu'}_${Math.random().toString(36).slice(2, 10)}`;
    this.emit({ type: 'tool_use', tool_use_id: id, tool_name: name, tool_input: input });
    if (orchestrator) this.emit({ type: 'tool_executing', tool_use_id: id, tool_name: name });
    await this.sleep(ms);
    if (orchestrator) this.emit({ type: 'tool_progress', tool_use_id: id, tool_name: name, elapsed_seconds: 5.0, message: `Still executing ${name}...` });
    await this.sleep(ms);
    this.emit({ type: 'tool_result', tool_use_id: id, output, is_error: isError });
    return id;
  }

  async agentReply(text) {
    const t = text.toLowerCase();
    if (t.includes('crash')) {
      this.engine.terminate(this, 'subprocess_crashed', 'claude exited with code 1 (mock)');
      throw new Cancelled();
    }
    if (t.includes('fail')) {
      this.emit({ type: 'error', error: 'send_failed', detail: 'The mock agent failed on purpose.' });
      throw new Cancelled();
    }
    if (t.includes('think')) {
      for (const c of chunks('Weighing the options before answering.')) {
        this.emit({ type: 'thinking_delta', text: c });
        await this.sleep(40);
      }
      this.emit({ type: 'thinking_complete', text: 'Weighing the options before answering.' });
    }
    if (t.includes('plan')) {
      const plan = '1. Split utils.py\n2. Add tests\n3. Update imports';
      await this.streamText('Here is my plan.');
      const id = `toolu_${Math.random().toString(36).slice(2, 10)}`;
      const rid = `perm_${Math.random().toString(36).slice(2, 10)}`;
      this.emit({ type: 'tool_use', tool_use_id: id, tool_name: 'ExitPlanMode', tool_input: { plan } });
      this.emit({ type: 'permission_request', request_id: rid, tool_name: 'ExitPlanMode', tool_input: { plan } });
      const d = await new Promise((resolve) => (this.permissionWaiter = { rid, resolve }));
      this.permissionWaiter = null;
      this.emit({ type: 'permission_resolved', request_id: rid, decision: d.decision, responder: d.responder, message: d.message ?? null });
      const allow = d.decision === 'allow';
      this.emit({ type: 'tool_result', tool_use_id: id, output: allow ? 'User has approved your plan.' : "The user doesn't want to proceed with this tool use.", is_error: !allow });
      return this.streamText(allow ? 'Starting now.' : 'Understood, revising the plan.');
    }
    if (t.includes('stall')) {
      const id = `toolu_${Math.random().toString(36).slice(2, 10)}`;
      this.emit({ type: 'tool_use', tool_use_id: id, tool_name: 'Bash', tool_input: { command: 'sleep 300', description: 'Wait' } });
      for (const s of [120.2, 180.4]) {
        await this.sleep(1500);
        this.emit({ type: 'session_stalled', elapsed_seconds: s, last_tool_name: 'Bash', last_tool_use_id: id });
      }
      await this.sleep(1500);
      this.emit({ type: 'tool_result', tool_use_id: id, output: 'done', is_error: false });
      return this.streamText('The command finished after a long wait.');
    }
    if (t.includes('agents')) return this.agentsDemo();
    if (t.includes('tool')) {
      await this.streamText('Let me look.');
      await this.tool('Bash', { command: 'ls', description: 'List files' }, 'README.md\nsrc\ntests');
      await this.tool('Read', { file_path: 'README.md' }, '# Mock project\n\nNothing to see here.');
      const answer = 'Found a README, a `src` folder and tests.';
      await this.streamText(answer);
      this.record('assistant', answer);
      return;
    }
    const answer = t.includes('long') ? LONG_ANSWER : `You said: ${text}`;
    await this.streamText(answer);
    this.record('assistant', answer);
  }

  // ───────────── subagents (spec 12 §4.8) ─────────────

  /** Record one line of a subagent transcript (MessagePreview shape, like the parent's history). */
  subRecord(id, role, text, blocks) {
    const a = this.subagents.get(id);
    if (!a) return;
    a.messages.push({ role, text, blocks: blocks.map((b) => ({ text: null, tool_use_id: null, tool_name: null, tool_input: null, output: null, is_error: false, ...b })), timestamp: now() });
  }

  /** Spawn card + subagent_started. */
  spawnSubagent({ id, parent = null, type, description, prompt, background = false }) {
    const input = { subagent_type: type, description, prompt, ...(background ? { run_in_background: true } : {}) };
    const row = { tool_use_id: id, parent_tool_use_id: parent, agent_type: type, description, background, status: 'running', activity: null, usage: { tokens: 0, tool_uses: 0, duration_ms: 0 }, summary: null, started_at: new Date().toISOString(), ended_at: null };
    this.subagents.set(id, { row, messages: [], stop: false, startedMs: Date.now() });
    const spawn = { type: 'tool_use', tool_use_id: id, tool_name: 'Agent', tool_input: input };
    if (parent) this.subEvent(parent, spawn);
    else {
      this.emit(spawn);
      this.record('assistant', '', [{ type: 'tool_use', tool_use_id: id, tool_name: 'Agent', tool_input: input }]); // the parent's history keeps the spawn card
    }
    this.emit({ type: 'subagent_started', tool_use_id: id, parent_tool_use_id: parent, agent_type: type, description, background, started_at: row.started_at });
    this.subRecord(id, 'user', prompt, [{ type: 'text', text: prompt }]);
  }

  /** One frame of a subagent's own stream (wrapped; recorded into its transcript). */
  subEvent(id, frame) {
    const a = this.subagents.get(id);
    if (!a) return;
    if (frame.type === 'tool_use') {
      a.row.activity = frame.tool_name;
      a.row.usage.tool_uses += 1;
      this.subRecord(id, 'assistant', '', [{ type: 'tool_use', tool_use_id: frame.tool_use_id, tool_name: frame.tool_name, tool_input: frame.tool_input }]);
    } else if (frame.type === 'tool_result') {
      this.subRecord(id, 'user', '', [{ type: 'tool_result', tool_use_id: frame.tool_use_id, output: frame.output, is_error: !!frame.is_error }]);
    } else if (frame.type === 'text_complete') {
      this.subRecord(id, 'assistant', frame.text, [{ type: 'text', text: frame.text }]);
    }
    this.emit({ type: 'subagent_event', tool_use_id: id, frame });
  }

  subProgress(id, tokens) {
    const a = this.subagents.get(id);
    if (!a) return;
    a.row.usage.tokens += tokens;
    a.row.usage.duration_ms = Date.now() - a.startedMs;
    this.emit({ type: 'subagent_progress', tool_use_id: id, activity: a.row.activity ?? 'Thinking', usage: { ...a.row.usage } });
  }

  subFinish(id, status, summary) {
    const a = this.subagents.get(id);
    if (!a || a.row.status !== 'running') return false;
    a.row.status = status;
    a.row.summary = summary;
    a.row.ended_at = new Date().toISOString();
    a.row.usage.duration_ms = Date.now() - a.startedMs;
    this.emit({ type: 'subagent_finished', tool_use_id: id, status, summary, usage: { ...a.row.usage }, ended_at: a.row.ended_at });
    return true;
  }

  /** A scripted subagent: `steps` = [tool, input, output] | ['text', text] | ['spawn', spec]; stops on stop_subagent. */
  async subScript(id, steps, { summary, status = 'done', token = null, pace = 900 }) {
    let n = 0;
    for (const st of steps) {
      if (this.subagents.get(id)?.stop || this.closed) break;
      if (token && token.cancelled) break;
      if (st[0] === 'text') {
        const text = st[1];
        for (const c of chunks(text, 8)) this.subEvent(id, { type: 'text_delta', text: c });
        this.subEvent(id, { type: 'text_complete', text });
      } else if (st[0] === 'await') {
        await st[1](); // a step that runs when reached (e.g. a nested agent)
        continue;
      } else {
        n += 1;
        const tid = `${id}_t${n}`;
        this.subEvent(id, { type: 'tool_use', tool_use_id: tid, tool_name: st[0], tool_input: st[1] });
        this.subProgress(id, 600 + n * 350);
        await new Promise((r) => setTimeout(r, this.engine.fast ? 0 : pace * this.engine.speed));
        if (this.subagents.get(id)?.stop) break;
        this.subEvent(id, { type: 'tool_result', tool_use_id: tid, output: st[2], is_error: false });
      }
      await new Promise((r) => setTimeout(r, this.engine.fast ? 0 : (pace / 2) * this.engine.speed));
    }
    const stopped = this.subagents.get(id)?.stop || (token && token.cancelled) || this.closed;
    this.subFinish(id, stopped ? 'stopped' : status, stopped ? 'Stopped by user' : summary);
    return stopped ? 'Agent stopped' : summary;
  }

  /** A spawn card's result on the parent stream (recorded in the parent's history too). */
  spawnResult(id, output, isError = false) {
    this.emit({ type: 'tool_result', tool_use_id: id, output, is_error: isError });
    this.record('user', '', [{ type: 'tool_result', tool_use_id: id, output, is_error: isError }]);
  }

  stopSubagent(id, ws) {
    const a = this.subagents.get(id);
    if (!a) return this.sendTo(ws, { type: 'error', error: 'unknown_subagent', detail: `No subagent ${id}` });
    if (a.row.status !== 'running') return;
    a.stop = true;
  }

  async agentsDemo() {
    this.ensureSdkId(); // the real backend knows the sdk id from the CLI's init, before the turn ends
    const token = this.cancel;
    const tag = Math.random().toString(36).slice(2, 7);
    const A = `toolu_agentA_${tag}`;
    const B = `toolu_agentB_${tag}`;
    const C = `toolu_agentC_${tag}`;
    const N = `toolu_agentN_${tag}`;
    await this.streamText('I will split this across three agents: two now, one in the background.');
    this.spawnSubagent({ id: A, type: 'Explore', description: 'Find the scroll logic', prompt: 'Find where the chat list decides to follow new messages.' });
    this.spawnSubagent({ id: B, type: 'general-purpose', description: 'Review the tests', prompt: 'Check which scroll tests exist and what they cover.' });
    this.spawnSubagent({ id: C, type: 'general-purpose', description: 'Digest the logs', prompt: 'Read logs/ and summarise any errors.', background: true });
    this.spawnResult(C, `Async agent launched successfully. agentId: ${C.slice(-6)}`);
    const runC = this.subScript(
        C,
        [
          ['Bash', { command: 'ls logs', description: 'List the logs' }, 'backend.log\nremote_console.log'],
          ['Read', { file_path: 'logs/backend.log' }, 'INFO started\nWARN slow request /api/sessions (2.1s)'],
          ['Grep', { pattern: 'ERROR', path: 'logs' }, 'no matches'],
          ['text', 'No errors; one slow request warning.'],
        ],
        { summary: 'Logs are clean: no errors, one slow-request warning in backend.log.', pace: 3200 },
      );
    let nested = null;
    const runA = this.subScript(
      A,
      [
        ['Grep', { pattern: 'pinned|nearBottom', path: 'apps/web/src' }, 'useMessageWindow.ts:41\nmessageWindow.ts:12'],
        ['Read', { file_path: 'apps/web/src/features/conversation/useMessageWindow.ts' }, 'export function useMessageWindow(o) {\n  // pinned within 150 px …\n}'],
        ['spawnNested'],
        ['text', 'The list follows new messages while it is within 150 px of the bottom (useMessageWindow.ts).'],
      ].map((st) =>
        st[0] === 'spawnNested'
          ? [
              'await',
              async () => {
                this.spawnSubagent({ id: N, parent: A, type: 'Explore', description: 'Count the callers', prompt: 'Count every caller of useMessageWindow.' });
                nested = this.subScript(N, [['Grep', { pattern: 'useMessageWindow\\(' }, 'MessageList.tsx:212'], ['text', 'One caller: MessageList.']], { summary: 'One caller: MessageList.tsx.', token, pace: 700 });
                this.subEvent(A, { type: 'tool_result', tool_use_id: N, output: await nested, is_error: false });
              },
            ]
          : st,
      ),
      { summary: 'The chat list follows new messages within 150 px of the bottom (useMessageWindow.ts); MessageList is its only caller.', token, pace: 1100 },
    );
    const runB = this.subScript(
      B,
      [
        ['Glob', { pattern: 'apps/web/src/**/scroll*.test.tsx' }, 'apps/web/src/features/conversation/__tests__/scroll.test.tsx'],
        ['Read', { file_path: 'apps/web/src/features/conversation/__tests__/scroll.test.tsx' }, "describe('scroll', () => { … })"],
        ['Bash', { command: 'npx vitest run scroll', description: 'Run the scroll tests' }, '✓ 12 tests passed'],
        ['text', 'The scroll tests cover pinning, the freeze buffer and prepends; all 12 pass.'],
      ],
      { summary: 'scroll.test.tsx covers pinning, the freeze buffer and prepends; 12 tests pass.', token, pace: 1300 },
    );
    const [ra, rb] = await Promise.all([runA, runB]);
    this.spawnResult(A, ra, ra === 'Agent stopped');
    this.spawnResult(B, rb, rb === 'Agent stopped');
    const answer = 'Both foreground agents are done; the log digest keeps running in the background.';
    await this.streamText(answer);
    this.record('assistant', answer);
    // the background agent outlives the turn (out-of-turn frames, PLAN §4.3)
    void (async () => {
      const r = await runC;
      while (this.busy && !this.closed) await new Promise((res) => setTimeout(res, 50)); // the CLI injects it after the running turn
      if (this.closed) return;
      const note = `<task-notification>\n<task-id>${C.slice(-6)}</task-id>\n<status>${r === 'Agent stopped' ? 'killed' : 'completed'}</status>\n<summary>Agent "Digest the logs" ${r === 'Agent stopped' ? 'was stopped' : 'finished'}</summary>\n</task-notification>`;
      this.busy = true;
      this.cancel = { cancelled: false };
      this.emit({ type: 'user_message', text: note, source: 'task_notification' });
      this.record('user', note);
      this.emit({ type: 'status', status: 'processing' });
      try {
        const reply = r === 'Agent stopped' ? 'The log digest was stopped before it finished.' : 'The background agent found the logs clean: no errors, one slow-request warning.';
        await this.streamText(reply);
        this.record('assistant', reply);
        this.emit({ type: 'turn_complete', cost: 0.001, usage: { input_tokens: 12, output_tokens: 20 }, input_tokens: 1900, output_tokens: 20, num_turns: 1, session_id: this.ensureSdkId(), is_error: false, result: 'ok' });
      } catch {
        // interrupted
      }
      this.busy = false;
      this.cancel = null;
      const next = this.queue.shift();
      if (next && !this.closed) this.runTurn(next.text, next.sender, false);
    })();
  }

  async orchestratorReply(text) {
    if (text.toLowerCase().includes('tool')) {
      await this.streamText('Checking the agent sessions.');
      await this.tool('list_agent_sessions', {}, JSON.stringify([...this.engine.runs.values()].filter((r) => r.kind === 'agent').map((r) => ({ session_id: r.localId, status: r.busy ? 'streaming' : 'idle' }))), { orchestrator: true });
    }
    const answer = `Archie (mock) heard: ${text}`;
    await this.streamText(answer);
    this.record('assistant', answer);
  }

  interrupt(ws) {
    if (!this.busy) {
      if (this.kind === 'agent') this.sendTo(ws, { type: 'status', status: 'interrupted' }); // direct when no turn ran
      return;
    }
    this.queue = [];
    this.steers = []; // Stop stops the turn the CLI would start for them too
    if (this.cancel) this.cancel.cancelled = true;
    if (this.permissionWaiter) this.permissionWaiter.resolve({ decision: 'deny', responder: 'system', message: 'interrupted' });
    this.emit({ type: 'status', status: 'interrupted' }); // BF-2: every subscriber
  }

  permissionResponse(msg) {
    const w = this.permissionWaiter;
    if (!w || (msg.request_id && msg.request_id !== w.rid)) return false;
    w.resolve({ decision: msg.decision === 'allow' ? 'allow' : 'deny', responder: 'user', message: msg.message ?? null });
    return true;
  }

  injectFromRest(text) {
    this.prompt(text, null);
  }

  // ───────────── voice (orchestrator; signaling only) ─────────────

  startVoice(ws) {
    this.voice = { active: true, owner: ws };
    this.sendTo(ws, this.sessionStarted(ws, { voice_connection_info: { connection_type: 'websocket', audio_relay: 'backend', audio_in_format: { sample_rate: 16000, encoding: 'pcm16' }, audio_out_format: { sample_rate: 24000, encoding: 'pcm16' } } }));
    this.broadcast({ type: 'voice_owner_active', active: true, owner_local_id: this.localId });
    this.broadcast({ type: 'voice_event', event: { type: 'voice_status', status: 'preparing' } });
    setTimeout(() => this.voice.active && this.broadcast({ type: 'voice_event', event: { type: 'voice_status', status: 'ready' } }), this.engine.fast ? 0 : 400);
  }

  endVoice(reason) {
    if (!this.voice.active) return;
    this.voice = { active: false, owner: null };
    this.broadcast({ type: 'voice_ending', reason, session_id: this.localId });
    this.broadcast({ type: 'voice_ended', reason, session_id: this.localId });
    this.broadcast({ type: 'voice_stopped' });
    this.broadcast({ type: 'voice_owner_active', active: false, owner_local_id: this.localId });
  }
}

/** Frame types the Claude agent stream stamps with seq (pool._wrap_payload). */
const SEQ_TYPES = new Set([
  'text_delta',
  'text_complete',
  'thinking_delta',
  'thinking_complete',
  'tool_use',
  'tool_result',
  'turn_complete',
  'permission_request',
  'permission_resolved',
  'subagent_started',
  'subagent_progress',
  'subagent_finished',
  'subagent_event',
]);

class Engine {
  constructor(opts) {
    this.fast = !!opts.fast;
    this.speed = opts.speed ?? 1;
    this.defaultScenario = opts.scenario ?? null;
    this.scenarios = loadScenarioMap(opts.fixturesDir);
    this.runs = new Map();
    this.aliases = new Map();
    this.watchers = new Set();
    this.log = opts.quiet ? () => {} : opts.log ?? ((...a) => console.log('[mock]', ...a));
    if (this.defaultScenario && !this.scenarios.has(this.defaultScenario)) throw new Error(`unknown scenario ${this.defaultScenario}`);
  }

  delayFor(frame) {
    if (this.fast) return 0;
    const t = frame.type;
    let ms = 250;
    if (t === 'text_delta' || t === 'thinking_delta') ms = 45;
    else if (t === 'voice_event') ms = frame.event?.delta !== undefined || frame.event?.serverContent ? 70 : 200;
    else if (t === 'tool_result') ms = 600;
    else if (t === 'session_stalled') ms = 1500;
    else if (t === 'status' || t === 'user_message') ms = 120;
    return ms * this.speed;
  }

  alias(sdkId, run) {
    if (sdkId) this.aliases.set(sdkId, run);
  }

  runForSdk(id) {
    if (!id) return undefined;
    const r = this.aliases.get(id);
    return r && !r.closed ? r : undefined;
  }

  liveRuns() {
    return [...this.runs.values()].filter((r) => !r.closed);
  }

  poolRows() {
    const rows = this.liveRuns().map((r) => ({
      local_id: r.localId,
      sdk_session_id: r.sdkId,
      status: r.kind === 'orchestrator' ? 'idle' : r.busy ? 'streaming' : 'idle',
      cost: r.kind === 'orchestrator' ? 0 : r.cost,
      turns: r.kind === 'orchestrator' ? 0 : r.turns,
      title: r.kind === 'orchestrator' ? 'Orchestrator' : r.title(),
      is_orchestrator: r.kind === 'orchestrator',
    }));
    return rows.sort((a, b) => Number(b.is_orchestrator) - Number(a.is_orchestrator));
  }

  pickScenario(url, msg) {
    const q = url.searchParams.get('scenario');
    if (q) return this.scenarios.get(q) ?? null;
    const lid = String(msg.local_id ?? '');
    const c = lid.indexOf(':');
    if (c > 0 && this.scenarios.has(lid.slice(0, c))) return this.scenarios.get(lid.slice(0, c));
    const rs = String(msg.resume_sdk_id ?? msg.session_id ?? '');
    if (rs.startsWith('scn-') && this.scenarios.has(rs.slice(4))) return this.scenarios.get(rs.slice(4));
    return this.defaultScenario ? this.scenarios.get(this.defaultScenario) : null;
  }

  watcherEvent(frame) {
    for (const ws of this.watchers) if (ws.readyState === 1) ws.send(Buffer.from(JSON.stringify(frame)), { binary: true });
  }

  createRun(kind, localId, fixture, msg) {
    const run = new Run(this, { localId, kind, fixture });
    if (!fixture && msg.resume_sdk_id) {
      run.sdkId = msg.resume_sdk_id;
    }
    if (fixture) {
      this.alias(`scn-${fixture.name}`, run);
      for (const id of sdkIdsOf(fixture)) this.alias(id, run);
    }
    if (run.sdkId) this.alias(run.sdkId, run);
    this.runs.set(localId, run);
    this.log(`${kind} session ${localId}${fixture ? ` → scenario ${fixture.name}` : ' (echo)'}`);
    this.watcherEvent({ type: 'agent_session_opened', session_id: localId, sdk_session_id: run.sdkId, is_orchestrator: kind === 'orchestrator' });
    return run;
  }

  closeRun(localId, { silent = false } = {}) {
    const run = this.runs.get(localId);
    if (!run || run.closed) return;
    run.closed = true;
    clearTimeout(run.timer);
    if (run.cancel) run.cancel.cancelled = true;
    if (run.kind === 'agent' && !silent) run.broadcast({ type: 'session_stopped' });
    run.endVoice('shutdown');
    for (const ws of run.sockets) ws.run = null;
    run.sockets.clear();
    this.runs.delete(localId);
    this.onRunClosed?.(run); // the transcript outlives the pool session (the backend's JSONL)
    this.watcherEvent({ type: 'agent_session_closed', session_id: localId, is_orchestrator: run.kind === 'orchestrator' });
  }

  terminate(run, reason, detail) {
    run.broadcast({ type: 'session_terminated', reason, detail, sdk_session_id: run.sdkId });
    this.closeRun(run.localId);
  }

  onConnection(ws, kind, url) {
    ws.run = null;
    if (kind === 'orchestrator') this.watchers.add(ws);
    const send = (f) => ws.readyState === 1 && ws.send(Buffer.from(JSON.stringify(f)), { binary: true });
    ws.on('message', (data, isBinary) => {
      if (isBinary) return ws.terminate(); // T-2: the backend drops the socket, no close frame
      let msg;
      try {
        msg = JSON.parse(data.toString('utf8'));
      } catch {
        return send({ type: 'error', error: 'invalid_json' });
      }
      if (!msg || typeof msg !== 'object') return send({ type: 'error', error: 'invalid_json' });
      try {
        this.onMessage(ws, kind, url, msg, send);
      } catch (e) {
        this.log('handler error', e);
      }
    });
    ws.on('close', () => {
      this.watchers.delete(ws);
      ws.run?.detach(ws);
    });
  }

  onMessage(ws, kind, url, msg, send) {
    const type = msg.type;
    const run = ws.run;
    if (type === 'start' || type === 'voice_start') {
      const localId = typeof msg.local_id === 'string' && msg.local_id ? msg.local_id : `mock-${Math.random().toString(36).slice(2)}`;
      if (kind === 'agent' && type === 'voice_start') return send({ type: 'error', error: 'unknown_type', detail: `Unknown message type: '${type}'` });
      if (run && run.localId !== localId) run.detach(ws);
      let target = this.runs.get(localId);
      // spec 12 OPEN-2: a reattach start never re-creates a closed conversation
      if (!target && msg.reattach === true) return send({ type: 'error', error: 'session_closed', detail: 'This conversation is not open.' });
      if (!target) {
        const fixture = this.pickScenario(url, msg);
        if (kind === 'orchestrator' && !fixture) {
          const other = this.liveRuns().find((r) => r.kind === 'orchestrator' && !r.fixture);
          if (other) return send({ type: 'error', error: 'orchestrator_active', detail: `Another orchestrator is active (${other.localId}).` });
        }
        if (!fixture) send({ type: 'status', status: 'connecting' });
        target = this.createRun(kind, localId, fixture, msg);
      }
      if (type === 'voice_start') {
        target.sockets.add(ws);
        ws.run = target;
        target.started = true;
        return target.startVoice(ws);
      }
      return target.attach(ws, msg);
    }
    if (type === 'ping' && kind === 'orchestrator') return;
    if (type === 'pong' && kind === 'orchestrator') return;
    if (type === 'get_models' && kind === 'orchestrator') return send({ type: 'models_list', models: [MODEL_INFO.model_info] });
    const known = ['send', 'interrupt', 'compact', 'stop', 'permission_response', 'command', 'stop_subagent', 'inject_text', 'send_audio', 'set_model', 'get_model', 'voice_stop', 'voice_event', 'voice_audio_in', 'voice_recording_chunk', 'voice_recording_end'];
    const agentOnly = ['command', 'stop_subagent'];
    const orchOnly = ['inject_text', 'send_audio', 'set_model', 'get_model', 'voice_stop', 'voice_event', 'voice_audio_in', 'voice_recording_chunk', 'voice_recording_end'];
    if (known.indexOf(type) < 0 || (kind === 'agent' && orchOnly.indexOf(type) >= 0) || (kind === 'orchestrator' && agentOnly.indexOf(type) >= 0))
      return send({ type: 'error', error: 'unknown_type', detail: `Unknown message type: '${type}'` });
    if (type === 'voice_audio_in' || type === 'voice_recording_chunk' || type === 'voice_recording_end') return; // no audio in the mock
    if (!run) {
      if (type === 'permission_response' && kind === 'agent') {
        const target = this.runs.get(msg.session_id); // T-8: unsubscribed socket answering for a session
        return target && target.permissionResponse(msg) ? undefined : send({ type: 'error', error: 'invalid_permission_response' });
      }
      return send({ type: 'error', error: 'not_started', detail: 'Send start first' });
    }
    const scriptWait = { inject_text: 'inject_text' }[type] ?? type;
    if (run.scripted && type !== 'stop' && run.scriptReceive(scriptWait)) return;
    switch (type) {
      case 'send':
      case 'command':
        if (run.permissionWaiter && msg.text) {
          run.permissionWaiter.resolve({ decision: 'deny', responder: 'user', message: msg.text });
          // the denial carries the text into the turn: a steering harness only shows it (I-16, §6.9)
          if (run.provider === 'claude') return run.emit({ type: 'user_message', text: String(msg.text), steered: true });
        }
        return run.prompt(String(msg.text ?? ''), ws);
      case 'inject_text':
        if (run.voice.active) {
          run.record('user', msg.text);
          return run.broadcast({ type: 'user_message', text: msg.text, source: 'shared_inject' });
        }
        return run.prompt(String(msg.text ?? ''), ws);
      case 'send_audio':
        return run.prompt(`[audio:${msg.format ?? 'webm'}] (${String(msg.audio ?? '').length} base64 chars)`, ws);
      case 'interrupt':
        return run.interrupt(ws);
      case 'stop_subagent':
        return run.stopSubagent(String(msg.tool_use_id ?? ''), ws);
      case 'compact':
        if (kind === 'orchestrator') {
          run.broadcast({ type: 'status', status: 'streaming' });
          run.broadcast({ type: 'compact_complete', trigger: 'manual', tokens_before: 18000, tokens_after: 2400 });
          return run.broadcast({ type: 'status', status: 'idle' });
        }
        run.broadcast({ type: 'compact_complete', trigger: 'manual', summary: 'Summary of the conversation so far (mock).' });
        return run.broadcast({ type: 'turn_complete', cost: 0.001, usage: { input_tokens: 2400, output_tokens: 120 }, input_tokens: 2400, output_tokens: 120, num_turns: 1, session_id: run.ensureSdkId(), is_error: false, result: null });
      case 'permission_response':
        if (!run.permissionResponse(msg)) send({ type: 'error', error: 'invalid_permission_response' });
        return;
      case 'stop':
        if (kind === 'orchestrator') {
          send({ type: 'session_stopped' });
          return this.closeRun(run.localId, { silent: true });
        }
        run.detach(ws);
        ws.run = null;
        return send({ type: 'session_stopped' });
      case 'set_model':
        return run.broadcast({ type: 'model_changed', model_info: modelInfoFor(msg.model) });
      case 'get_model':
        return send({ type: 'model_info', model_info: MODEL_INFO });
      case 'voice_stop':
        if (!run.voice.active) return send({ type: 'error', error: 'not_voice_session' });
        return run.endVoice('user_stop');
      case 'voice_event':
        if (!run.voice.active) return send({ type: 'error', error: 'not_voice_session' });
        return;
      default:
        return;
    }
  }
}

/** Start the mock. Resolves once listening; `port: 0` picks a free port (tests). */
export async function startMockServer(opts = {}) {
  const engine = new Engine(opts);
  const rest = createRest(engine, { nginx413: opts.nginx413 });
  const server = http.createServer((req, res) => {
    rest.handle(req, res).catch((e) => {
      engine.log('REST error', e);
      if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ detail: String(e?.message ?? e) }));
    });
  });
  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://mock');
    const kind = url.pathname === '/api/sessions/chat' ? 'agent' : url.pathname === '/api/orchestrator/chat' ? 'orchestrator' : null;
    if (!kind) return socket.destroy();
    wss.handleUpgrade(req, socket, head, (ws) => engine.onConnection(ws, kind, url));
  });
  const pinger = setInterval(() => engine.watcherEvent({ type: 'ping' }), 15000);
  pinger.unref?.();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(opts.port ?? 8799, opts.host ?? '0.0.0.0', resolve);
  });
  const port = server.address().port;
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    engine,
    async close() {
      clearInterval(pinger);
      for (const r of engine.liveRuns()) clearTimeout(r.timer);
      for (const ws of wss.clients) ws.terminate();
      await new Promise((resolve) => wss.close(() => server.close(() => resolve())));
    },
  };
}

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--fast') o.fast = true;
    else if (a === '--quiet') o.quiet = true;
    else if (a === '--nginx-413') o.nginx413 = true;
    else if (a === '--list') o.list = true;
    else if (a === '--port') o.port = Number(argv[++i]);
    else if (a === '--host') o.host = argv[++i];
    else if (a === '--speed') o.speed = Number(argv[++i]);
    else if (a === '--scenario') o.scenario = argv[++i];
    else if (a === '--help' || a === '-h') o.help = true;
    else throw new Error(`unknown argument ${a}`);
  }
  return o;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    console.log('usage: node mock-server/server.mjs [--port 8799] [--host 0.0.0.0] [--fast] [--speed 1] [--scenario name] [--nginx-413] [--quiet] [--list]');
    process.exit(0);
  }
  if (o.list) {
    for (const [name, fx] of loadScenarioMap()) console.log(`${name.padEnd(48)} ${fx.session.kind}`);
    process.exit(0);
  }
  if (process.env.MOCK_FAST === '1') o.fast = true;
  const s = await startMockServer(o);
  console.log(`[mock] Archie mock backend on http://${o.host ?? '0.0.0.0'}:${s.port} (${s.engine.scenarios.size} scenarios${o.fast ? ', fast' : ''})`);
  console.log('[mock] use it with: ARCHIE_BACKEND=http://localhost:' + s.port + ' npm run dev');
}
