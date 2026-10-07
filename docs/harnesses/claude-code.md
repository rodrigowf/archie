---
name: claude-code
category: archie/harnesses
tags: [claude-code, claude-agent-sdk, harness, claude-config-dir, jsonl, skills, auth, oauth, chrome, sdk-upgrade]
created: 2026-05-15
modified: 2026-10-06
summary: The Claude Code harness — ClaudeSessionManager over claude-agent-sdk, .claude_config layout, JSONL, SDK pin, auth, Chrome flag.
source: curated (consolidated from memory notes assistant/providers/provider_generalization.md, assistant/providers/qwen_code_adaptation.md, assistant/utilities/project_skills_not_registered.md, auto-memory project_sdk_upgrade_path_2026_06_18.md, feedback_verify_auth_under_backend_config_dir.md, feedback_jetson_oauth_token_expiry.md, feedback_ssh_remote_cli_nvm_path.md; verified against code 2026-10-06)
references:
  - registry.md
  - qwen-code.md
  - gemini-cli.md
  - ../architecture/agent-sessions.md
  - ../architecture/backend.md
  - ../architecture/orchestrator.md
  - ../integrations/skills.md
  - ../infrastructure/ssh-remote-execution.md
  - ../infrastructure/installation.md
  - ../clients/browser-extension.md
  - ../operations/troubleshooting.md
---

# Claude Code harness

The default and most complete harness. Each chat tab with `provider: "claude"` is one
`ClaudeSessionManager` (`backend/manager/claude/session.py`) holding a `ClaudeSDKClient` from
`claude-agent-sdk`, which runs the CLI bundled inside the SDK as a long-lived subprocess. It is the
only harness with permission gating, `/compact`, slash commands, MCP servers, the Chrome flag and
seq-numbered replay for reconnecting clients.

Lifecycle, the persistent receive loop, permission gating, the stall watchdog and per-session
config are shared session machinery — see [agent sessions](../architecture/agent-sessions.md). This
doc covers what is specific to Claude Code.

## File map

| File | Contents |
|---|---|
| `backend/manager/claude/session.py` | `ClaudeSessionManager` (historical alias `SessionManager`), `SessionAbandoned(TurnAbandoned)`, `kill_claude_subprocess()`, `_extract_subprocess_pid()`, `_build_options()`, `_write_ssh_wrapper()`, `_DEFAULT_GATED_TOOLS`, `_PERMISSION_GATING_PROMPT` |
| `backend/manager/claude/adapter.py` | `ClaudeAdapter` (JSONL), `fold_orchestrator_lines()`, `HarnessSpec(name="claude")` |
| `backend/manager/auth.py` | `AuthManager` — `claude auth status`, `claude setup-token`, credentials file under `CLAUDE_CONFIG_DIR` |
| `backend/api/routes/auth.py` | `GET /api/auth/status`, `POST /api/auth/login`, `POST /api/auth/credentials` |
| `backend/api/session_factory.py` | `build_session_config()` — working dir, SSH, provider, model, MCP servers, chrome flag |
| `backend/requirements-claude.txt` | `claude-agent-sdk>=0.1.81,<0.2` |
| `backend/tests/test_claude_adapter.py`, `test_session.py`, `test_ssh_session_churn.py`, `test_orphan_reaper.py`, `test_pool_turn.py` | Tests |

## `CLAUDE_CONFIG_DIR=.claude_config/`

`shared/scripts/run.sh` (reached as `context/scripts/run.sh`, which the backend service uses)
exports `CLAUDE_CONFIG_DIR=<repo>/.claude_config` and sources `context/.env`. The bundled CLI
therefore keeps all its state in the gitignored `.claude_config/`, not in `~/.claude/`:

| Path in `.claude_config/` | What it is |
|---|---|
| `projects/-home-rodrigo-assistant` → `../../context` | Project dir for the repo root. Session JSONL lands at `context/<sdk-session-id>.jsonl`; subagent and tool-result state in `context/<uuid>/`; auto-memory resolves to `context/memory/` |
| `skills` → `../context/skills` | Meant for skill discovery (see Pitfalls) |
| `agents` → `../context/agents` | Subagent definitions |
| `.credentials.json` | OAuth credentials of this machine's grant (not synced) |
| `.claude.json` | CLI user state, including MCP servers configured on this machine |
| `sessions/<pid>.json`, `history.jsonl` | CLI bookkeeping (pid, session id, cwd, version) |
| `settings.json`, `plugins/`, `todos/`, `debug/`, … | Ordinary CLI state |

The project key is the absolute working directory with `/` replaced by `-`. Both machines install
at `/home/rodrigo/assistant`, so the key and the symlink are identical on the laptop and the Jetson
and a session resumes on either ([ssh-remote-execution](../infrastructure/ssh-remote-execution.md)).
A session whose working directory is something else gets a different key, and its JSONL is not in
`context/` unless that project dir is linked too.

The installers create the `projects/` and `skills` symlinks (`install/linux/install.sh` step 3);
`shared/scripts/setup-context.sh` also creates the SDK compatibility symlink. Project instructions
come from `CLAUDE.md` at the repo root, a symlink to `context/AGENTS.md` (shared with
`QWEN.md` and `GEMINI.md`).

## How a session is built (`_build_options()`)

| Option | Value | Why |
|---|---|---|
| `include_partial_messages` | `True` | Token-level `TextDelta` / `ThinkingDelta` |
| `setting_sources` | `["project", "local"]` | Reads `<project_dir>/.claude/settings.json` and `settings.local.json` (seeded from `install/cli-runtime/claude/settings.json`) |
| `can_use_tool` | `_can_use_tool` | Gates `_DEFAULT_GATED_TOOLS = {"ExitPlanMode"}`; everything else auto-allows |
| `system_prompt` | preset `claude_code` + `append=_PERMISSION_GATING_PROMPT` | Agent announces intent before a gated tool |
| `permission_mode` | from config (`"default"`, so the callback fires) | |
| `model`, `max_budget_usd`, `max_turns` | from `ManagerConfig` | Model comes from per-session / global `harness_model.claude` |
| `resume`, `fork_session` | SDK session id / fork flag | Resume and fork from history |
| `mcp_servers` | resolved per session | Overrides `.claude.json` when given |
| `extra_args` | `{"chrome": None}` when the chrome flag is on | Adds `--chrome` |
| `env` | `os.environ` minus `CLAUDECODE` | Lets the backend spawn sessions even when it was launched from inside Claude Code |
| `stderr` | logged as `claude CLI stderr [<local_id>]` | Errors visible in the backend log |
| `cwd` / `cli_path` | `project_dir` locally; for SSH a temp wrapper script and `cwd=$HOME` | [ssh-remote-execution](../infrastructure/ssh-remote-execution.md) |

`_run_lifecycle()` creates the client, `connect()`s, grabs the subprocess PID through the SDK's
private `client._transport._process.pid` (best effort; the pool's orphan reaper is the fallback),
reads the session id from `get_server_info()` (or the resume id), then starts the persistent
`_receive_loop()`. Shutdown cancels the loop, bounds `disconnect()` at 8 s, and SIGTERM/SIGKILLs a
surviving PID (`kill_claude_subprocess` checks `/proc/<pid>/comm` starts with `claude` first).
Constants: stall notice 120 s then every 60 s, abandoned turn 240 s, replay buffer 500 events.

## JSONL shape

One line per SDK event, `type` one of `user`, `assistant`, `system`, plus internal types
`queue-operation`, `ai-title`, `attachment`, `last-prompt`, `file-history-snapshot`. User content is
a string (or `tool_result` blocks); assistant content is a list of `text` / `thinking` / `tool_use`
blocks. That is already the normalized shape, so `ClaudeAdapter.read_messages()` only filters.

The orchestrator's own JSONL files (`context/<uuid>.jsonl` with an `orchestrator_meta` line, plus
top-level `tool_use` / `tool_result` lines carrying `tool_call_id`) are also read by this adapter:
`fold_orchestrator_lines()` folds each turn into one assistant message so truncate/fork counts match
what the clients render ([orchestrator](../architecture/orchestrator.md)).

## SDK version

Pinned `claude-agent-sdk>=0.1.81,<0.2`; installed 0.1.81 with bundled CLI 2.1.139.

- **Stage A (done 2026-06-18, `3d24e80`)**: 0.1.39 → 0.1.81. 0.1.51 (upstream PR #746) replaced
  `anyio.TaskGroup` in `Query` with `asyncio.create_task`, fixing the cross-task `__aexit__` wedge
  (upstream issue #378) that pinned one core in anyio's `_deliver_cancellation` while the loop
  watchdog still saw a live loop. 0.1.40 made the SDK skip unknown message types. Both fixes let
  two monkey-patches (`_patch_sdk_message_parser`, `_patch_sdk_query_close`) be deleted.
- **Stage B (deferred)**: 0.2.x. MCP servers connect in the background by default (0.2.82; set
  `MCP_CONNECTION_NONBLOCKING=0` or `alwaysLoad: true` where a turn needs an MCP ready) and TodoWrite
  becomes TaskCreate/TaskUpdate/TaskGet/TaskList. The clients render tool cards by name, but check
  before upgrading.
- Details and the 2026-08-25 venv-drift incident: [agent sessions — SDK version](../architecture/agent-sessions.md).

## Auth

Two credential paths, in precedence order:

1. **`CLAUDE_CODE_OAUTH_TOKEN` in `context/.env`** — a 1-year token printed by
   `claude setup-token`. `run.sh` exports it, local sessions inherit it through `env`, and
   `_write_ssh_wrapper()` forwards it to SSH-remote sessions. Because `context/` is synced, both
   machines get it. This is the current setup.
2. **`.claude_config/.credentials.json`** — this machine's own refreshing OAuth grant, read and
   written by `AuthManager` and the `/api/auth/*` routes.

Rules:

- **Never copy `.credentials.json` between machines.** Anthropic OAuth rotates refresh tokens; two
  machines sharing one grant revoke each other and one ends up "could not be refreshed". Each
  machine needs its own grant, or use the env token.
- **Verify auth under the backend's config dir.** In a Claude Code Bash tool `CLAUDE_CONFIG_DIR` is
  unset, so a bare `claude -p …` uses `~/.claude/.credentials.json` (kept fresh by the interactive
  CLI) and passes while every wrapper session fails. Test with
  `CLAUDE_CONFIG_DIR=/home/rodrigo/assistant/.claude_config claude -p 'reply OK' < /dev/null`.
- The backend caches credentials in memory: after fixing them, restart it
  (`sudo systemctl restart agentic-backend.service` on the Jetson).
- `claude setup-token` is a TUI that only prints its URL in a real PTY; drive it with Python
  `pty.fork()` and grep the log for the URL and the minted token.

Symptom → fix table: [troubleshooting](../operations/troubleshooting.md).

## Chrome flag

`assistant_config.json` / per-session config `chrome_extension: true` makes
`build_session_config()` set `extra_args={"chrome": None}`, i.e. the CLI runs with `--chrome` —
Anthropic's own Claude-in-Chrome integration. It has nothing to do with Archie's
`apps/browser-extension/` ([browser extension](../clients/browser-extension.md)), which agents drive
through the `/browser-control` skill. Only the Claude harness honors this flag.

## Pitfalls

- **Project skills may not register** as `Skill(<name>)` or `/<name>` inside a wrapper session
  (seen 2026-08-28: "Unknown skill: browser-control" while built-in skills listed fine). The
  `.claude_config/skills` symlink is not enough. Untested but consistent with the code: with
  `CLAUDE_CONFIG_DIR` set, `.claude_config/skills` is the *user* skills dir, and
  `setting_sources=["project","local"]` does not include `"user"`; project-scoped skills would be
  read from `<project_dir>/.claude/skills/`, which does not exist. Test one change at a time if
  fixing it. Workaround: read `context/skills/<name>/SKILL.md` and follow it
  ([skills](../integrations/skills.md)).
- If sessions die with "Unknown message type" / `MessageParseError`, check the **installed** SDK
  version before the pin; a drifted venv is the usual cause.
- Do not bump anyio to fix loop wedges; the SDK was the cause.
- `kill_claude_subprocess` and the reaper rely on the comm name `claude`; a future CLI rename would
  need `comm_prefix` updated.
- SSH-remote sessions that exit 127: two different causes (CLI not found, then `node` not found) —
  [ssh-remote-execution](../infrastructure/ssh-remote-execution.md).

## History

- Before 2026-05-15 the wrapper was Claude-only (`manager/session.py` `SessionManager`). The Qwen
  work renamed it `ClaudeSessionManager`, moved shared state into `BaseSessionManager`, made the SDK
  import lazy, and moved it into `manager/claude/` ([registry](registry.md)).
- 2026-06-18 — SDK floor raised to 0.1.81, two monkey-patches deleted (`3d24e80`).
- 2026-08-24 — long-lived `CLAUDE_CODE_OAUTH_TOKEN` replaced copying `.credentials.json` between
  machines.
- 2026-08-25 — venv found at 0.1.39 despite the pin; the floor comment in `requirements-claude.txt`
  documents the crash (`a997c12`).
