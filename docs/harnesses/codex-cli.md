---
name: codex-cli
category: archie/harnesses
tags: [codex, codex-cli, openai, harness, app-server, json-rpc, rollout, persistent-process]
created: 2026-10-07
modified: 2026-10-12
summary: The OpenAI Codex harness — one persistent `codex app-server` per session (JSON-RPC over stdio), CODEX_HOME choice, rollout storage and adapter, catalog/options, landmines.
source: curated (implemented and verified against Codex CLI 0.161.0 on 2026-10-07)
references:
  - authentication.md
  - registry.md
  - claude-code.md
  - gemini-cli.md
  - qwen-code.md
  - ../architecture/agent-sessions.md
  - ../infrastructure/installation.md
  - ../infrastructure/ssh-remote-execution.md
  - ../architecture/memory-and-search.md
  - ../specs/12-client-protocol.md
  - ../projects/subagent-view/PLAN.md
---

# Codex CLI harness

OpenAI's `codex` CLI (npm `@openai/codex`, a Rust binary; built and verified on **0.161.0**) as the
fourth session harness, `provider="codex"`. Unlike Qwen and Gemini it is **not** spawn-per-turn:
each Archie session owns one long-lived `codex app-server` process and talks newline-delimited
JSON-RPC 2.0 to it over stdio, the same shape as the Claude harness (lifecycle task owns the
process, a persistent receive loop feeds the active `send()`). Authentication is the CLI's own
ChatGPT login (`codex login`); no API credits are used.

## File map

| File | Contents |
|---|---|
| `backend/manager/codex/session.py` | `CodexSessionManager`, `CodexAbandoned(TurnAbandoned)`, `kill_codex_subprocess()`, the notification → event mapping, the SSH bootstrap |
| `backend/manager/codex/rpc.py` | `CodexRpc` — minimal JSON-RPC peer (request/notify, response futures, server→client requests answered from tasks) |
| `backend/manager/codex/items.py` | `ThreadItem` → tool call / tool result / TodoWrite mapping, shared by the live stream and the history adapter; multi-agent helpers (`collab_tool`, `agent_states`, `spawn_input`) |
| `backend/manager/codex/subagent_files.py` | Subagent history: spawn items of a rollout → child-thread rollouts → `SubagentSummary` rows ([Subagents](#subagents)) |
| `backend/manager/codex/adapter.py` | `CodexAdapter` (rollout JSONL, three generations), `HarnessSpec(name="codex")`, `_codex_jsonl_candidates()`, `_codex_discover_sessions()` |
| `backend/manager/codex/catalog.py` | `load_codex_catalog()` — models (live `model/list` → `models_cache.json` → built-in) and options |
| `backend/manager/codex/home.py` | `codex_home()` (dedicated vs shared), `sessions_roots()`, `home_for_thread()`, `codex_env()`, `codex_executable()` |
| `AGENTS.md` (repo root) | Symlink → `context/AGENTS.md` — the file Codex reads natively; committed like `CLAUDE.md` / `QWEN.md` / `GEMINI.md` |
| `install/{linux,apple}/install.sh`, `install/windows/install.ps1` | `--with-codex` (`-WithCodex`): seeds `~/.codex-archie/config.toml` (from `install/cli-runtime/codex-home/`; an existing file only gets `[features] memories = false` added or replaced, via `install/codex-home-config.py`), links its `sessions/`, links `.agents/skills` → `context/skills`, installs the CLI pinned to `CODEX_CLI_VERSION` (0.161.0, `install/harness-versions.env`; warns on a different version), login hint; root `AGENTS.md` link. Without Node/npm (`--no-node`, e.g. the Jetson) it installs the static `codex-<arch>-unknown-linux-musl` binary from GitHub release `rust-v<version>` into `/usr/local/bin` (or `~/.local/bin` + `CODEX_CLI_PATH`). `install-with-agent.*` can install Codex as the driver CLI; `install/doctor.sh` checks binary, auth, config (incl. `memories = false`) and links |
| `backend/tests/test_codex_session.py`, `test_codex_adapter.py`, `test_codex_subagents.py`, `fixtures/codex/` | Tests: a scripted fake app-server (`fake_app_server.py`, incl. multi-agent `spawn` scenarios) and a trimmed real rollout |

## Binary and process

`codex_executable()` resolves, in order: `CODEX_CLI_PATH`; the **native** binary inside the global
npm package (`…/@openai/codex/node_modules/@openai/codex-linux-<arch>/vendor/<triple>/bin/codex`);
`codex` on `PATH`; the VS Code extension's bundled copy. The npm `bin/codex.js` is a Node shim
that forwards signals; exec'ing the native binary means the PID the pool tracks is the agent
itself (`/proc/<pid>/comm` = `codex`, the spec's `comm_prefix`).

The process is spawned as

```
codex app-server --listen stdio:// -c project_doc_max_bytes=131072 --disable plugins --disable apps
```

with `cwd = project_dir`, `start_new_session=True` (teardown closes stdin, then SIGTERM/SIGKILL the
process group, so shell children die too), a 64 MB line limit on stdout, and an env that is the
backend's minus `CLAUDECODE`, `OPENAI_API_KEY`, `OPENAI_BASE_URL` plus `CODEX_HOME`.

## Protocol (app-server JSON-RPC)

Connect (`_connect()`):

1. `initialize {clientInfo: {name: "archie", …}}` → `initialized` notification. The client name
   becomes the rollout's `originator`.
2. `model/list {includeHidden: true}` — per-model reasoning efforts and the account's default model.
3. `thread/start` | `thread/resume {threadId, excludeTurns: true}` | `thread/fork {threadId}` with
   `{cwd, model, sandbox: "danger-full-access", approvalPolicy: "never", config?}`. The returned
   `thread.id` (UUIDv7) is the provider session id.

Each `send()` is one `turn/start {threadId, input: [{type: "text", text, text_elements: []}],
summary, effort?}`. `interrupt()` is `turn/interrupt {threadId, turnId}`; the process stays up. If a
previous turn is still running when `send()` starts (its caller stopped listening), it is
interrupted first so its `turn/completed` cannot end the new turn.

| Notification | Normalized |
|---|---|
| `item/agentMessage/delta` (and `item/plan/delta`) | `TextDelta` |
| `item/completed` `agentMessage` / `plan` | `TextComplete` |
| `item/reasoning/summaryTextDelta` (`summaryPartAdded` > 0 adds `"\n\n"`) | `ThinkingDelta` |
| `item/reasoning/textDelta` (only when no summary streamed) | `ThinkingDelta` |
| `item/completed` `reasoning` | `ThinkingComplete` (summary parts joined) |
| `item/started` tool-like item | `ToolUse` |
| `item/completed` tool-like item | `ToolUse` (if not started) + `ToolResult` |
| `turn/plan/updated` | `ToolUse("TodoWrite", {todos})` + `ToolResult` |
| `thread/tokenUsage/updated` | stashed → `TurnComplete.usage` (`last`; `cachedInputTokens` → `cache_read_input_tokens`, `input_tokens` excludes cached) |
| `error` (`willRetry: false`) | stashed; shown on a failed turn |
| `thread/compacted` / `contextCompaction` item | `CompactComplete("auto")` (once per turn) |
| `turn/completed` | open texts → `TextComplete`, open tools → error `ToolResult`, then `TurnComplete(cost=None, num_turns=1, session_id=thread, is_error=status=="failed", result=last answer / "interrupted" / error)`; a failed turn also emits `Codex error: …` as text |
| any notification whose `threadId` is not the session's thread | a multi-agent child thread: its own conversation ([Subagents](#subagents)), never the parent's turn, usage or errors |

Tool items (`items.py`), live camelCase and rollout PascalCase/snake_case alike:

| Item | Tool card |
|---|---|
| `commandExecution` | `Bash {command}` — `/bin/bash -lc '<cmd>'` unwrapped; result `aggregatedOutput`, error when `exitCode ≠ 0` or status failed/declined |
| `fileChange`, one file | add → `Write {file_path, content}`; update → `Edit {file_path, old_string, new_string}` (built from the unified diff); delete → `Delete {file_path}` |
| `fileChange`, several files | `apply_patch {changes: [{path, kind, diff}]}` |
| `mcpToolCall` | `mcp__<server>__<tool>` with the arguments |
| `webSearch` | `WebSearch {query}` |
| `collabAgentToolCall` `spawnAgent` | `Task {description: <prompt's first line>, prompt, subagent_type: "agent", model?}` — the subagent's card; result `Started agent <nickname or thread id>` |
| `collabAgentToolCall` other tools | `wait_agent` / `send_input` / `close_agent` / `resume_agent` / `interrupt_agent` / `send_message` / `followup_task` / `list_agents` `{agents: [thread ids], prompt?}`; result one `<agent>: <status> — <message>` line per agent |
| `dynamicToolCall`, `imageView` (`Read`), `imageGeneration` | as named |

Server → client requests: command/file approvals are accepted (`accept` / legacy `approved`),
permission requests granted for the turn, `requestUserInput` answered empty, MCP elicitations
declined, anything else gets JSON-RPC `-32601` — each one logged. With approval policy `never`
they should not occur. **There is no `PermissionRequest` gating for Codex** (v1).

The stall watchdog matches the other harnesses (first `SessionStalled` after 120 s, then every
60 s; `CodexAbandoned` after 240 s without a single event); notifications that produce no event
(command output deltas, MCP progress) still count as activity. When the process exits without a
`stop()`, the receive loop records `SUBPROCESS_CRASHED` with the exit code and the last stderr
lines, unblocks the active `send()` with an error `TurnComplete`, and the pool's dead-session
reaper broadcasts `session_terminated`; later `send()`s raise `SessionDeadError`. The manager
keeps the same `(stream_id, seq)` replay ring as Claude for WebSocket resume.

## Subagents

Contract: [spec 12 §4.8](../specs/12-client-protocol.md#48-subagents-sa-); plan:
[subagent view](../projects/subagent-view/PLAN.md); the harness-neutral pieces are in
[claude-code.md → Subagents](claude-code.md#subagents) and
[agent sessions](../architecture/agent-sessions.md#out-of-turn-events-and-subagents).

**What 0.161 sends** (`features.multi_agent` is on by default; live probes 2026-10-12, gpt-5.6-luna,
ChatGPT free plan). The model calls `spawn_agent` / `wait_agent` / `close_agent` …; the parent thread
gets `collabAgentToolCall` items `{id, tool: "spawnAgent"|"wait"|"closeAgent"|…, status,
senderThreadId, receiverThreadIds, prompt, model, agentsStates: {thread: {status, message}}}`.
Each sub-agent is **its own thread on the same app-server connection**: its `turn/started`,
`item/*`, deltas, `thread/tokenUsage/updated`, `error` and `turn/completed` notifications carry the
child's `threadId`. Facts that shape the code:

- no `thread/started` arrives for a child (no nickname / role on the live wire);
- the child's first notifications (`thread/status/changed`, `turn/started`) arrive **before** the
  parent's `spawnAgent` `item/completed` that names the child in `receiverThreadIds` (the
  `item/started` has `receiverThreadIds: []`);
- a spawn returns at once; the parent normally `wait`s, but when it doesn't, its turn completes while
  the child keeps streaming;
- `turn/interrupt {threadId: <child>, turnId: <child turn>}` stops one child (its `turn/completed`
  comes back `interrupted`).

**Mapping.** The subagent key is the `spawnAgent` item id (the `Task` card). `_on_notification`
routes every other-thread notification to `_on_child_notification`: a thread not mapped yet is held
(≤ 16 threads × 500 notifications) until a `spawnAgent` `item/completed` names it, then replayed.
`spawnAgent` `item/started` → the `Task` `ToolUse` (registry spawn); `item/completed` →
`SubagentStarted` **before** the card's `ToolResult` (SA-2), then the held child events. Child items
→ `SubagentEvent(<spawn id>, …)` with the same item mapping as the parent (per-child open
texts / reasoning / tools); child `thread/tokenUsage/updated` → `SubagentProgress(usage.tokens =
total)`; child `turn/completed` → `SubagentFinished` (`completed`→`done` with the last answer,
`failed`→`failed` with the error, `interrupted`→`stopped`; usage = tokens, tool calls, turn
`durationMs`). A final state in a later `wait`/`closeAgent` `agentsStates` (`shutdown`, `errored`, …)
finishes an agent whose own end was not seen. A child that spawns makes a nested subagent
(`parent_tool_use_id` = the child's key). Later turns of the same child (`send_input`) keep routing
as `subagent_event` frames; the lifecycle frames stay one start / one finish. Not flagged
`background` (the parent normally waits).

**Out of turn.** Child events after the parent's `TurnComplete` go to the pool's out-of-turn sink
(seq-stamped in the session's stream); events dispatched after the `TurnComplete` was queued but
before `send()` returns are held and flushed in order — the same contract as the Claude manager.
**Stop (SA-9)**: `stop_subagent` sends `turn/interrupt` for the child's running turn (verified live:
`subagent_finished{stopped}` ~2 s later). App-server teardown finishes every running child as
`stopped`.

**History (SA-8).** A child's rollout sits next to the parent's:
`sessions/YYYY/MM/DD/rollout-<ts>-<child thread>.jsonl`, `session_meta` `{parent_thread_id,
source: {subagent: {thread_spawn: {parent_thread_id, depth, agent_nickname, agent_role}}}}`. In
rollouts the collab items are snake case (`tool: "spawn_agent"`, `receiver_thread_ids`,
`receiver_agents: [{thread_id, agent_nickname}]`, `agents_states: {thread: "pending_init" |
{"completed": msg} | {"errored": err} | "shutdown"}`). `subagent_files.scan()` walks the parent's
spawn items, finds each child rollout (same sessions root first, then every root), and reads its
`task_complete` (`last_agent_message`, `duration_ms`) / `turn_aborted`, last `token_count` and tool
items; nested spawns recurse (depth ≤ 6). The transcript is the child rollout through
`CodexAdapter.read_messages()` (its first user message is the prompt). **Child rollouts are never
listed as sessions**: `_codex_discover_sessions()` skips rollouts whose `session_meta` names a
parent thread (`_rollout_meta()`, cached per path), so the history list and the history index only
see parents.

## CODEX_HOME, login and storage

| Home | When | Rollouts |
|---|---|---|
| `~/.codex-archie` (dedicated) | it contains `auth.json` | `sessions/` → symlink to `context/codex/sessions` (installer), synced by context-sync |
| `~/.codex` (shared; `$CODEX_HOME` if exported) | fallback | stay in `~/.codex/sessions` |
| `ARCHIE_CODEX_HOME` | env override | wherever that home keeps them |

Create the dedicated login with `CODEX_HOME=~/.codex-archie codex login --device-auth` (each
machine needs its own) — or from Settings → Accounts, which runs the same device-code login (and
the browser login, API key, `auth.json` paste, sign out) for that home ([authentication.md](authentication.md)).

**Skills.** Codex 0.161 lists skills from `<cwd>/.agents/skills` up to the repo root,
`<repo>/.codex/skills`, `$CODEX_HOME/skills` (its own `.system/` skills live there — so do not link
that one into `context/`) and `~/.agents/skills` (checked with `codex debug prompt-input` in a
scratch repo; symlinked directories are followed). The installers link `<repo>/.agents/skills` →
`../context/skills`, which gives Codex the same skills as the other harnesses. The catalog shows a warning with that command while Archie runs on the
shared fallback, and another when no login exists at all.

Rollout path: `$CODEX_HOME/sessions/YYYY/MM/DD/rollout-<local time>-<thread id>.jsonl`; resume
appends to the same file. The nested date directories keep rollouts out of `SessionStore`'s flat
scans.

- `_codex_jsonl_candidates(id)` globs `*/*/*/rollout-*-<id>.jsonl` under every root in
  `sessions_roots()`: `context/codex/sessions`, the active home, the dedicated home, the shared home.
- `_codex_discover_sessions()` lists every rollout under `context/codex/sessions` and the dedicated
  home, but in the **shared** home only those whose `session_meta.payload.originator` is `archie`
  (VS Code and the TUI write there too), and never a multi-agent child thread's rollout
  (`session_meta` names a parent thread — it is shown inside its parent, [Subagents](#subagents)).
  Originator and parent are cached per path.
- The homes are per machine, so they are only searched when the store's project dir is this
  install's own (`PROJECT_ROOT`); any other project dir (tests, a second checkout) sees only its own
  `context/codex/sessions`.
- `thread/resume` only finds rollouts under the running `CODEX_HOME`, so `home_for_thread()` runs a
  resumed session with the home that holds its rollout (a session started on the shared fallback
  keeps resuming there after a dedicated login is created).
- The home is chosen per session start (`codex_home()` checks for `auth.json` every time), so a new
  login takes effect without a backend restart. Since 2026-10-08 both machines have the dedicated
  login: a new session's rollout lands in `context/codex/sessions/YYYY/MM/DD/` and syncs.
- History search: `history_index.session_sources()` adds the same rollouts through
  `_codex_discover_sessions()`, and the `HistoryIndexer` change hash includes them.

**Memory.** Codex has no counterpart to Claude's auto-memory that could point at the wiki: its
`memories` feature (off by default; `CODEX_HOME/memories` + `memories_1.sqlite`) keeps its own
store. Archie keeps it off — the installers pin `[features] memories = false` in
`~/.codex-archie/config.toml` (the backend passes no flag for it) — and, for sessions in the repo, passes the shared memory block
(`backend/manager/memory_context.py`: the live `context/memory/MEMORY.md` plus the rule to write
memory with file tools per `AGENTS.md`) as `developerInstructions` on `thread/start` only. Codex
records it once as a developer message at the head of the rollout (hidden by the adapter and the
history index); `thread/resume` and `thread/fork` reuse it from the history. Verified live
2026-10-08: quoted `MEMORY.md` without tools on a new thread and again after a resume, and saved a
test fact by extending an existing wiki note ([memory and search](../architecture/memory-and-search.md#every-harness-reads-and-writes-the-same-memory)).

## Rollout format (adapter)

Every line is `{timestamp, type, payload}` (current CLIs add `ordinal`); line 0 is `session_meta`
(`id`, `timestamp`, `cwd`, `originator`, `cli_version`, an ~18 KB `base_instructions`).
`CodexAdapter` reads three generations:

| Generation | Visible content from |
|---|---|
| **items** (≥ 0.15x) | `event_msg` `item_completed` items: `UserMessage`, `AgentMessage`, `Reasoning` (`summary_text`), `CommandExecution`, `FileChange` (`changes: {path: {type, content \| unified_diff}}`), … |
| **events** (0.4x–0.6x) | `event_msg` `user_message` / `agent_message` / `agent_reasoning`; tools from `response_item` `function_call` / `function_call_output` / `custom_tool_call` (`apply_patch`) |
| **responses** (2025-09) | bare Responses items after a `{id, timestamp, instructions}` header |

`response_item` `message` lines are the raw model input (the `developer` instructions, the
`<environment_context>` user message) and are never shown; injected user texts are filtered by
prefix. Consecutive assistant blocks merge into one message and tool results become a synthetic
user message (the Gemini split), so clients pair them by `tool_use_id`. `visible_line_indices()`
returns one index per visible REST message (its last contributing line), consistent with
`read_messages()` for `truncate_session`. Detection: line 0 is `session_meta` with an id (or the
2025-09 bare header) — no other harness writes that. Title = first real user message.

## Configuration and catalog

Nothing is written to `config.toml`. Fixed overrides are on argv (above); the model is always passed
explicitly — the chosen one, else the default from `model/list` — because a stale `model` in the
user's `config.toml` would fail every turn. A configured model starting with `claude` is ignored
(the global `.manager.json` model reaches every harness when no harness model is set).

`load_codex_catalog()` (≤ 5 s, never raises): live `model/list` from a short-lived app-server with
Archie's `CODEX_HOME` (rows carry `efforts` / `default_effort`; `context_window` comes from
`models_cache.json`), else `models_cache.json`, else a built-in 2026-10-07 list — each fallback adds
a warning.

| Option | Maps to | Values |
|---|---|---|
| `effort` | `turn/start.effort` | `low` `medium` `high` `xhigh` `max` (`ultra` on some models); restricted per model — an unsupported level is dropped with a log line |
| `reasoning_summary` | `turn/start.summary` | `auto` `concise` `detailed` `none`; **unset sends `concise`** (models default to `none`, which hides all thinking) |
| `verbosity` | thread `config.model_verbosity` | `low` `medium` `high` |
| `web_search` | thread `config.web_search` | `disabled` `cached` `live` |

Thread-level options apply at session start (restart the session to change them); effort and
summary apply per turn.

## SSH working directories

Supported through the generic helpers: `build_remote_argv()` runs `/bin/sh -c <bootstrap> <remote
codex> app-server …` on the host (`ControlPath=/tmp/codex-ssh-<host>-%r`). The bootstrap uses
`~/.codex-archie` on the remote when it holds a login and puts the CLI's directory on `PATH` (the npm
shim needs `node`). The remote writes rollouts into its own home; they appear locally only if that
host's dedicated `sessions/` link into a synced `context/`. The orphan reaper sees the local `ssh`
PID, not `codex`.

## HarnessSpec values

`name="codex"`, `label="Codex"`, `comm_prefix="codex"`, `ssh_control_path_prefix="codex"`,
`requirements_file=None`, `npm_package="@openai/codex"`, `cli_binary="codex"`, `env_keys=()`,
`session_discoverer` and `catalog_loader` set.

## Landmines

1. **Never copy `auth.json`** between homes or machines. ChatGPT refresh tokens rotate; two homes
   holding one token family break each other (`refresh_token_reused`) — VS Code or Archie stops
   working. A second home or machine needs its own `codex login`.
2. **`AGENTS.md` size.** Codex reads `AGENTS.md` from the git root down to the cwd, capped by
   `project_doc_max_bytes` (default 32 KiB). `context/AGENTS.md` is ~51 KB, so without the
   `-c project_doc_max_bytes=131072` override it is silently truncated. It also costs ~20 K input
   tokens per turn in the repo cwd — noticeable on a free plan's monthly quota.
3. **`OPENAI_API_KEY` in the backend env.** Codex would use it and bill API credits; `codex_env()`
   strips it. Set `CODEX_API_KEY` to opt into API-key auth deliberately.
4. **Stale `model` in `config.toml`** (`gpt-5.3-codex` on the laptop) is not in the catalog any
   more; always passing a model avoids it.
5. **Reasoning summaries default to `none`** — without `summary` on `turn/start` the UI shows no
   thinking.
6. **Large JSON-RPC lines.** asyncio's default 64 KB line limit breaks on big command output or
   `thread/resume`; the reader uses 64 MB and resume passes `excludeTurns: true`.
7. **Plugins/apps** enabled in the user's config inject ~10 KB of instructions per turn;
   `--disable plugins --disable apps` keeps them out of Archie sessions.
8. **Duplicate / fork from the history menu** copy the rollout to `<new uuid>.jsonl`, which Codex
   cannot resume (wrong name, old id inside). Use a resumed session instead; the harness's own
   `fork` flag maps to `thread/fork`. Truncation edits the rollout in place and Codex rebuilds the
   thread from what is left.
9. **Interrupted turns** leave no agent message in the rollout (`turn_aborted` only), so a reopened
   conversation shows the prompt without the partial answer.
10. A new `thread/start` reads `MEMORY.md` once; a long-lived thread keeps the index it started
    with (like a Claude session). Start a new session to see index edits.

## History

- 2026-05-15 — deferred during the harness generalization (recon notes in [registry.md](registry.md)).
- 2026-10-07 — added on branch `harness-upgrade`: app-server session manager, rollout adapter
  (three generations), catalog/options, installer `--with-codex`, root `AGENTS.md` symlink.
  Verified live on CLI 0.161.0 with a ChatGPT free-plan login: text, reasoning summary, `ls`,
  file write, resume in a new process, `turn/interrupt`, history through `/api/sessions/<id>`.
- 2026-10-08 — dedicated `~/.codex-archie` logins on both machines; memory block as
  `developerInstructions`; Codex rollouts in the history indexer's change hash.
- 2026-10-12 — subagents (spec 12 §4.8): child-thread notifications routed into `subagent_*` frames
  (held until the spawn item names the thread), out-of-turn delivery after the parent turn,
  `stop_subagent` via `turn/interrupt`, only `spawnAgent` is a `Task` card, history from child
  rollouts, child rollouts excluded from session discovery.
