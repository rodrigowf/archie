---
name: qwen-code
category: archie/harnesses
tags: [qwen, qwen-code, harness, jsonl, stream-json, dashscope, spawn-per-turn, models, lazy-loading]
created: 2026-05-15
modified: 2026-10-07
summary: The Qwen Code harness — spawn-per-turn qwen CLI, ~/.qwen symlinks into context/, JSONL normalization, model catalog, landmines.
source: curated (consolidated from memory notes assistant/providers/qwen_code_adaptation.md, assistant/providers/provider_generalization.md; verified against code 2026-10-06)
references:
  - registry.md
  - claude-code.md
  - gemini-cli.md
  - ../architecture/agent-sessions.md
  - ../infrastructure/installation.md
  - ../infrastructure/ssh-remote-execution.md
  - ../voice/qwen-omni.md
---

# Qwen Code harness

Alibaba's `qwen` CLI (npm `@qwen-code/qwen-code`, a Node program) as a session harness. Unlike
Claude Code there is no persistent process: every turn spawns a fresh `qwen` that reads one
stream-json prompt on stdin, runs its agent loop, streams stream-json on stdout and exits. Turns are
chained with `--resume <session-id>`.

Qwen Code as a chat harness is unrelated to the Qwen-Omni **voice** provider
([qwen-omni](../voice/qwen-omni.md)); they only share the DashScope account.

## File map

| File | Contents |
|---|---|
| `backend/manager/qwen/session.py` | `QwenSessionManager`, `QwenAbandoned(TurnAbandoned)`, `_qwen_executable()` |
| `backend/manager/qwen/adapter.py` | `QwenAdapter` (JSONL → normalized), `HarnessSpec(name="qwen")`, `_qwen_jsonl_candidates()` |
| `backend/manager/qwen/models.py` | `list_qwen_models()` / `QwenModelInfo` — model catalog from `~/.qwen/settings.json` |
| `backend/api/routes/config.py` | `GET /api/config/harness/qwen/models` |
| `backend/manager/context_windows.py` | Reads `contextWindowSize` per model from the same settings file |
| `backend/tests/test_qwen_session.py`, `test_qwen_adapter.py`, `test_store_qwen.py`, `test_qwen_models.py`, `test_qwen_session_ssh.py`, `test_qwen_session_e2e.py` | Tests (e2e runs the real CLI only with `QWEN_E2E=1`) |

## Storage and config symlinks

Qwen keeps per-project state in `~/.qwen/projects/<mangled-path>/` and writes chats to its `chats/`
subfolder. The installer (`--with-qwen`, `install/linux/install.sh` step 3b) replaces that
directory with a symlink:

```
~/.qwen/projects/-home-rodrigo-assistant  ->  /home/rodrigo/assistant/context
~/.qwen/skills                            ->  /home/rodrigo/assistant/context/skills
```

so sessions land at `context/chats/<session-id>.jsonl` with a `<session-id>.runtime.json` sibling
(schema version, pid, session id, work dir, hostname, start time, qwen version), next to Claude's
flat `context/*.jsonl`, and the same skills are visible. If a real directory already exists the
installer copies it to `context/qwen-backup-<timestamp>/`, lifts `chats/*.jsonl` and
`*.runtime.json` into `context/chats/`, then links. An existing symlink that points at another
project is left alone with a warning.

Other Qwen state stays in `~/.qwen/` per machine: `settings.json` (model providers, env, auth
type, default model), `tmp/`, `todos/`, `debug/`. Project instructions come from `QWEN.md` at the
repo root, a symlink to `context/AGENTS.md`. `install/cli-runtime/qwen/settings.json` seeds the
repo-local `.qwen/settings.json` (a small command allowlist).

Why everything under `context/`: separate `claude/` and `qwen/` subdirs would have broken every
existing Claude session, and a `context/qwen-data/` tree would split the synced context. With the
symlink, context-sync carries Qwen sessions between machines like everything else.

## Turn protocol

Argv built by `_build_argv()`:

```
qwen --input-format stream-json --output-format stream-json --include-partial-messages \
     --approval-mode yolo --channel SDK [--resume <id>] [--model <id>] [--max-session-turns N]
```

- The binary is `QWEN_CLI_PATH` or `qwen` on `PATH`.
- The prompt is written to stdin as one line:
  `{"type":"user","message":{"role":"user","content":[{"type":"text","text":…}]}}`, then stdin closes.
- `--approval-mode yolo`: Qwen auto-approves every tool. There is **no permission gating** for
  Qwen sessions (no `PermissionRequest`, no ExitPlanMode popup).
- `--channel SDK` tags wrapper-driven runs in Qwen's logs.
- The env is the backend's env minus `CLAUDECODE`. API keys (`DASHSCOPE_API_KEY`) come from
  `context/.env` via `run.sh`, or from Qwen's own OAuth / settings.
- Fork has no Qwen equivalent; a fork starts a fresh session.

Qwen's stdout is Anthropic-shaped, and `_translate_event()` maps it:

| Qwen event | Normalized |
|---|---|
| `system` `subtype: init` | captures `session_id` (first turn) |
| `system` `subtype: compact` | `CompactComplete` |
| `stream_event` `content_block_delta` `text_delta` / `thinking_delta` | `TextDelta` / `ThinkingDelta` |
| `stream_event` `content_block_stop` | `TextComplete` / `ThinkingComplete` |
| `assistant` (complete message) | `ToolUse` for each `tool_use` block |
| `user` with `tool_result` blocks | `ToolResult` |
| `result` | `TurnComplete` (`cost=None` — Qwen reports no cost; `session_id`, usage) |

Lifecycle: `_run_lifecycle()` pings SSH targets first, adopts the resume id, runs `_prewarm()`
(remote `which qwen`, or a local `qwen --version` to fault Node and the CLI bundle into the page
cache, 10 s cap — so the cold start is paid at tab open, not on the first prompt), then idles.
`interrupt()` sends SIGINT; stop sends SIGTERM, then SIGKILL after 2 s. Each turn's PID is reported
through the pool's PID callbacks so the orphan reaper sees it. Watchdog constants match Claude:
stall notice 120 s then every 60 s, `QwenAbandoned` after 240 s of total silence.

SSH: `_maybe_wrap_with_ssh()` resolves the remote `qwen` path and builds an
`ssh … "cd '<dir>' && exec '<qwen>' …"` argv with `build_remote_argv()`. One SSH connection per
turn, kept cheap by `ControlMaster`/`ControlPersist=60s` under the `qwen` control-path prefix. The
local env is deliberately not forwarded (the remote has its own `.env`)
([ssh-remote-execution](../infrastructure/ssh-remote-execution.md)).

## JSONL format and the adapter

Native lines (written by the CLI):

```
{"type":"user","message":{"role":"user","parts":[{"text":"…"}]}, "uuid","parentUuid","sessionId","timestamp",…}
{"type":"assistant","message":{"role":"model","parts":[{"text":"…","thought":true},{"text":"…"},{"functionCall":{"id","name","args"}}]},"usageMetadata":{…},"model":"…"}
{"type":"tool_result","message":{"role":"user","parts":[{"functionResponse":{"id","name","response":{"output"|"error":…}}}]},"toolCallResult":{"callId","status":"success"|"error"|"cancelled","resultDisplay"}}
{"type":"system","subtype":"ui_telemetry"|"attribution_snapshot","systemPayload":{…}}
```

`QwenAdapter` normalizes: `role: "model"` → `"assistant"`; `parts` → `content` blocks;
`{text, thought: true}` → `thinking`; `functionCall` → `tool_use`; a `type: "tool_result"` line
becomes a `user` message of `tool_result` blocks (`functionResponse.id` → `tool_use_id`,
`response.output` — or `response.error` — → `content`, `is_error` when there is an error or
`toolCallResult.status` is `error`/`cancelled`), the same shape Claude writes and the Gemini adapter
builds, so clients pair each result with its call; keeps `usageMetadata` and `model`; skips
`system` lines. Tool-result lines are not visible turns and do not count as messages. Detection: `message.parts` without `content`, `role: "model"`, or a
`system` line with `subtype`. Sessions are never marked orchestrator.

## Models

`GET /api/config/harness/qwen/models` returns whatever `~/.qwen/settings.json` `modelProviders`
lists (`id`, `name`, `baseUrl`, `generationConfig.contextWindowSize`, modalities, `enable_thinking`)
— the same catalog `qwen --model` validates against, so DeepSeek, GLM or a local Ollama endpoint
added there appear automatically. `QWEN_HOME` overrides the location (tests). The web and Android
settings show this list as the Qwen harness-model dropdown; the choice is stored in
`assistant_config.json` `harness_model.qwen` or per session. Qwen-only installs default the model
to `qwen3.6-plus`.

## Lazy SDK loading

Qwen needs no Python SDK (`requirements_file=None`). Because `manager/__init__.py` resolves
`ClaudeSessionManager` lazily (PEP 562) and the orchestrator providers do the same, a Qwen-only
install can skip `requirements-claude.txt`, `requirements-anthropic.txt` and even
`requirements-openai.txt`, and the backend still boots; an SDK is imported only when something
asks for that provider. Saving an orchestrator model whose SDK is missing returns 400 with the
`pip install -r requirements-<x>.txt` hint. `install.sh --qwen-only` = `--with-qwen
--without-claude` plus OpenAI-only orchestrator.

## Landmines

- **Tool results live on their own `type: "tool_result"` lines** (not `user`), with
  `functionResponse` parts. Code that keeps only `user` / `assistant` lines drops them — the
  adapter did until 2026-10-07, so reopened Qwen chats showed tool calls without outputs (fixed
  2026-10-07; live turns were always fine, those come from stdout).
- `role: "model"` and `parts` are the two shapes that break code copied from the Claude path.
- No cost: `TurnComplete.cost` is `None`, so cost badges stay empty.
- Qwen and Gemini both have `comm_prefix="node"`; harmless (see [registry](registry.md)).
- The `.runtime.json` siblings are not sessions; the store's `*.jsonl` glob never matches them.
- After changing the remote CLI location, restart the backend — the resolved remote path is cached
  in-process.

## Why spawn-per-turn instead of `qwen serve`

Qwen has an HTTP+SSE server mode (ACP protocol, port 4170). It was rejected because the wrapper
needs the same control it has over Claude — tool events, mid-run interrupt, streaming intermediate
steps — and the subprocess plus stdin/stdout path gives that uniformly.

## History

- 2026-05-15 — branch `qwen-code`: `f5eaa7d` (harness, adapters, store, badge), `115a05b` (lazy
  SDK loading, `manager/session.py` shim removed, `_proc.py`, shared `TurnAbandoned`, per-turn PID
  tracking), `3bab53d` (per-provider requirements, two-axis install prompt), `0250dcb` (tests,
  e2e gated by `QWEN_E2E`). Then the harness registry and the `manager/qwen/` subpackage
  ([registry](registry.md)).
- The old web app showed a `C` / `Q` letter badge per session; the current apps show the
  harness name as a tag (orchestrator sessions show "Archie" instead).
- 2026-10-07 — `QwenAdapter` converts `type: "tool_result"` lines, so reopened Qwen chats show
  tool outputs.
