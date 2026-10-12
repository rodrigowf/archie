# Subagent view — plan

Status: **in progress** (2026-10-12) — decisions taken (§6), contract in spec 12 §4.8 + fixtures `subagent_*`. Goal: when a chat session spawns subagents
(Claude's Agent/Task tool, Qwen's `agent`, Codex `spawn_agent`, Gemini `invoke_agent`), the user can
see every agent, its live status, and exactly what each one is doing — on web (both builds) and
Android `:app-main`.

## 1. What happens today

The clients have no subagent concept, and the backend mostly throws subagent data away.

**Claude Code** (`backend/manager/claude/session.py`, SDK 0.2.164, CLI 2.1.292)

- Subagent tool calls arrive as `AssistantMessage`/`UserMessage` with `parent_tool_use_id` = the
  spawning Agent `tool_use` id. `_process_message` ignores the field, so they are **flattened** into
  the parent stream as ordinary `tool_use`/`tool_result` frames.
- Subagent text/thinking never arrive: `forward_subagent_text` is `False` (kept off on purpose,
  [harnesses/claude-code.md](../../harnesses/claude-code.md), because it would surface as top-level
  text — routing by parent id removes that objection).
- `TaskStarted` / `TaskProgress` / `TaskNotification` / `TaskUpdated` system messages (task id ↔
  tool_use id, tokens, tool count, duration, `last_tool_name`, status, summary) fall into the
  generic `SystemMessage` branch and are dropped.
- Events produced outside a `send()` are never broadcast (they only enter the replay ring), so a
  **background agent is invisible** after the parent turn ends. `TurnComplete` ignores
  `ResultMessage.origin`, so a task-notification turn can end the wrong `send()`.
- Side effects of the flattening: subagent tool calls flip the session status, clear the stall
  watchdog's `last_tool_name`, confuse the clients' "single running card" inference (R-4), fill
  the orchestrator runner's 200-event ring; the string-result fallback (`:1348-1361`) can attach a
  subagent result to the parent's Agent card.
- History: REST reads only `context/<sid>.jsonl`. Subagent transcripts live in
  `context/<sid>/subagents/agent-<agentId>.jsonl` + `.meta.json`
  (`{agentType, description, toolUseId, spawnDepth, requestShape}`; `toolUseId` only on newer CLIs).
  The parent's `toolUseResult` (agentId, status, isAsync, totals, toolStats) is dropped by
  `extract_blocks`. The SDK ships `list_subagents()` / `get_subagent_messages()` for exactly this.

**Other harnesses**

| Harness | Subagents | Live inner events | Link to parent | Today |
|---|---|---|---|---|
| Model Studio | via Claude Code | as Claude | as Claude | as Claude |
| Qwen Code 0.25 | `agent` tool (+ background) | tool calls/results yes; text only as final result | `parent_tool_use_id` (Claude-style stream-json) + `task_started`/`task_notification` | flattened; a failed subagent's parentless `result` line ends the parent turn early (bug) |
| Codex 0.161 | `spawn_agent` … (`multi_agent` on) | yes — each child is its own thread | child `threadId` ↔ parent `collabAgentToolCall.receiverThreadIds`, `Thread.parentThreadId` | child-thread notifications dropped (`codex/session.py:695`); every collab call (incl. `wait`) renders as a "Task" card |
| Gemini CLI 0.63 | `invoke_agent` (on by default) | **no** — stream-json only carries the final `SubagentProgress` JSON (last ≤ 3 activities) | `tool_id` only | raw JSON blob in a tool card |

Spec 12 has no subagent concept (§5.7 only says subagent events "may appear flat"); no protocol
fixture contains Task/Agent events.

## 2. Is the user's idea possible?

Yes. "A button by the composer listing the agents" + "a nested tab per agent showing exactly what
it does" is feasible for Claude, Model Studio and Codex at full fidelity (tool calls, text,
thinking, live), for Qwen with tool calls live and the text arriving at the end, and for Gemini
only as a post-hoc summary (live would need tailing its child chat file — later phase).

## 3. Best practices (survey of Claude Code, Codex, Cursor, VS Code/Copilot, Zed, Warp, Roo, Cline, Amp)

Every mature product converges on **three layers**:

1. an **inline card** at the spawn point in the parent transcript — collapsed: name, task, current
   activity line, elapsed; the result folds back in as a distinct block;
2. an **index of all agents** (panel/list, grouped by state, needs-input first);
3. the **full transcript** of one agent, on demand.

Plus: status before content (working / needs input / done / failed / stopped, distinct shape and
colour); a one-line live activity per agent (the thing that makes many agents scannable); finished
agents stay reachable (Claude Code's and Cursor's vanishing rows are the top complaint); depth shown
by indentation or breadcrumb, not more tab levels; tabs are **opt-in** (VS Code auto-opening a tab
per subagent is the top clutter complaint, and dumping full child transcripts inline is the other);
polite live-region announcements for state changes, assertive only for needs-input/failure, never
for streamed tokens. Sources in the research notes of the 2026-10-12 planning session.

## 4. Design

### 4.1 One concept: the agent run

Per session the client keeps `agents: Map<agentKey, AgentRun>` where `agentKey` = the spawning
tool-use id (Codex: the child thread id, mapped to the spawning item):

```
AgentRun { key, parentKey|null, harnessAgentId, type, description, background,
           status: running|needs_input|done|failed|stopped,
           startedAt, endedAt, activity (one line), usage {tokens, toolUses, durationMs},
           resultSummary, conv: Conversation }   // the agent's own transcript
```

`conv` is the **same `Conversation` model and reducer** the parent uses, so the agent transcript is
rendered by the existing `MessageList` / `ToolCard` / `StepGroup` code with zero new renderers —
and nested agents fall out recursively (an AgentRun's `conv` has its own Agent cards).

### 4.2 Wire protocol (spec 12 addition — normative text in [spec 12 §4.8](../../specs/12-client-protocol.md#48-subagents-sa-); the sketch below predates it, the spec wins)

- **`subagent_event {tool_use_id, frame}`** — wraps an ordinary agent frame (`tool_use`,
  `tool_result`, `text_complete`, `thinking_*`, `text_delta`, `permission_request`, …) emitted by a
  subagent. The client feeds `frame` into `stepConversation(run.conv, frame)`. This mirrors the
  existing `nested_session_event` path (orchestrator → agent sessions) and keeps subagent frames
  **out of** the parent's frames, so status, stall watchdog and R-4 stop misfiring. Legacy clients
  ignore the unknown frame — they simply stop seeing the flattened noise.
- **`agent_started {agent_key, parent_key, type, description, background, started_at}`**,
  **`agent_progress {agent_key, activity, usage}`**, **`agent_finished {agent_key, status,
  summary, usage, ended_at}`** — lifecycle, harness-neutral.
- **Snapshot:** `session_started` (and the reattach answer) carries `agents: AgentRun[]` without
  `conv`, so a device joining late shows the list and badges immediately.
- **REST:** `GET /api/sessions/{id}/agents` (list, live + persisted) and
  `GET /api/sessions/{id}/agents/{key}/history` (the agent transcript as a history page, same shape
  as the parent's). Opening an agent tab loads history first, then live frames continue it —
  the same replace/reconcile path the parent uses.
- Fixtures in `apps/protocol-fixtures/`: foreground agent, background agent finishing after the
  parent turn, two parallel agents, nested agent, failed agent. Both clients must pass them.

### 4.3 Backend

- `manager/types.py`: optional `parent_key` on the content events + `AgentStarted/Progress/Finished`
  events; `serialize_event` + `pool` wrap parent-keyed events as `subagent_event`.
- **Session-level broadcast for out-of-turn events** (background agents, task-notification turns):
  the pool forwards them to subscribers even with no `send()` in flight. `TurnComplete` matches
  `ResultMessage.origin` so a notification turn never ends the user's `send()`.
- A small per-session **agent registry** (in memory, rebuilt from disk on resume) is the source for
  the snapshot and the list endpoint.
- Claude: read `parent_tool_use_id` on Assistant/User/StreamEvent; enable `forward_subagent_text`;
  map `Task*` messages to lifecycle events; fix the `:1348-1361` fallback; keep the stall watchdog
  on the parent's tool. History: `get_subagent_messages()` / `.meta.json` `toolUseId`; for old
  files without `toolUseId`, match by `agentId` from the parent's `toolUseResult` or the
  async-launch text.
- Qwen: same as Claude (`parent_tool_use_id`, `task_*`); route the parentless subagent error
  `result` to the open agent instead of ending the turn; key `block_meta` by (parent, index).
  History: `context/subagents/<sessionId>/`.
- Codex: stop dropping child-thread notifications; map child thread → spawning item; a child's
  `turn/completed`/errors/usage never touch the parent turn; render only `spawnAgent` as the agent
  card (`wait`/`closeAgent` become small notices). History: child rollouts by `parent_thread_id`.
- Gemini: v1 parses `SubagentProgress` into a finished AgentRun (activities + result); live via
  tailing `context/chats/<parent>/` later.
- Stop an agent: `ClaudeSDKClient.stop_task(task_id)` (Codex `interruptAgent`), exposed as a
  `stop_agent {agent_key}` client frame.
- Orchestrator runner: subagent events go to their own bucket (don't evict the parent's ring);
  `read_agent_session` gains an `agents` summary.

### 4.4 UI (web `apps/web` + Android `:app-main`, same IA)

1. **Inline agent card** — upgrade the Task card (`features/tools/renderers/tasks.tsx`,
   `ToolBodies.kt`): `task` hue tile, type + description, live activity line ("Grep · *subagent*"),
   tools · tokens · elapsed, status glyph; on finish the result folds in as "Report"; an **Open**
   button opens the agent tab. Never expands the child transcript inline.
2. **Agents button in the composer row** — next to the `ContextRing`, shown only while the session
   has agents (zero-state hidden, so it doesn't break the IA §6 "no gear by the composer" rule).
   Badge = running count; amber dot = an agent needs input. Opens a **SideSheet (≥ 600 px) /
   BottomSheet (compact)** — the `SessionSettingsSheet` pattern — with a list grouped *Needs
   input · Running · Done · Failed*: icon, description, activity, elapsed, stop. Nested agents are
   indented under their parent; more than three finished ones collapse into "N more". Tap a row →
   opens its tab.
3. **Nested agent tab row** — M3 **secondary scrollable tabs** inside the conversation panel, above
   the message list (web: the existing, unused `Tabs variant="secondary"`; Android: a new
   `SecondaryScrollableTabRow` wrapper in `core/design`). It appears with the session's first agent.
   **Every agent gets a tab automatically** (decision 1), but a tab is a **placeholder**: only the
   summary (status, activity, elapsed) lives in memory; the transcript is fetched and kept only once the
   tab is entered, and at most 3 transcripts stay in memory per session (spec SA-5). A new tab never
   steals focus (FOCUS-1). Tabs keep spawn order (no reshuffling); status glyph per tab; nested agents
   carry a `↳` and a breadcrumb (`Main › Explore › worker`) inside the tab instead of a third level.
   Many agents: the row scrolls, an "N ⌄" menu lists all, and "Close finished" (sheet + menu) hides
   finished tabs — they stay in the list and come back when opened from the card or the list.
4. **Agent tab body** — the agent's `conv` in the normal `MessageList`; the composer is replaced by
   a read-only bar ("Viewing *Explore: find scroll logic* · Stop · Back to Main"). Messaging a
   subagent is out of scope for v1 (Claude/Qwen subagents can't receive input; Codex `sendInput`
   could come later).
5. **Accessibility / perf** — `role="status"` polite announcements on state changes, assertive for
   needs-input/failure; auto-scroll only when at the bottom; activity line throttled (≤ 1/s);
   agent transcripts load lazily (new UI in a lazy chunk; respect the bundle budgets and the
   compat rules: no flex `gap`, no Pointer Events, `ScrollArea` for scrollers).

## 5. Phases

| Phase | Scope | Done when |
|---|---|---|
| P0 | Spec 12 section + fixtures; backend event model, registry, out-of-turn broadcast, Claude adapter (live + history), latent-bug fixes, tests | Fixtures pass in a backend test; a real session with 2 parallel + 1 background agent streams correctly over a WS probe |
| P1 | Web: AgentRun reducer, inline card, agents sheet, secondary tab row, agent tab, both builds | Seen working in the real app on the laptop and on the iPad (compat) |
| P2 | Android `:app-main` parity | Reached through the app's own navigation on the POCO and seen working |
| P3 | Qwen + Codex adapters; Gemini post-hoc | Same WS-probe check per harness |
| P4 | Stop agent, orchestrator runner/tool integration, Gemini live, polish | — |

## 6. Decisions (2026-10-12)

1. Agent tabs open automatically for every agent, as lazy placeholders (above).
2. Legacy clients stop seeing flattened subagent tool calls — accepted.
3. Order as in §5; all phases approved.
