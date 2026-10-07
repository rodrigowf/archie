---
name: gemini-cli
category: archie/harnesses
tags: [gemini, gemini-cli, harness, jsonl, stream-json, skip-trust, glob-resolver, spawn-per-turn]
created: 2026-05-15
modified: 2026-10-07
summary: The Gemini CLI harness — spawn-per-turn gemini --prompt, storage symlink into context/chats, JSONL quirks, glob resolver, landmines.
source: curated (consolidated from memory notes assistant/providers/gemini_cli_adaptation.md, assistant/providers/provider_generalization.md; verified against code 2026-10-06)
references:
  - registry.md
  - qwen-code.md
  - claude-code.md
  - ../architecture/agent-sessions.md
  - ../infrastructure/installation.md
  - ../infrastructure/ssh-remote-execution.md
  - ../voice/gemini-live.md
---

# Gemini CLI harness

Google's `gemini` CLI (npm `@google/gemini-cli`, Node; recon done on v0.42.0) as the third session
harness. Like Qwen it is spawn-per-turn: each `send()` runs `gemini --prompt <text>` with
stream-json output and exits; turns are chained with `--resume`. Authentication is the CLI's own
Google OAuth (`~/.gemini/oauth_creds.json`) or `GEMINI_API_KEY`.

The Gemini CLI harness is unrelated to the Gemini Live **voice** provider
([gemini-live](../voice/gemini-live.md)).

## File map

| File | Contents |
|---|---|
| `backend/manager/gemini/session.py` | `GeminiSessionManager`, `GeminiAbandoned(TurnAbandoned)`, `_gemini_executable()` (`GEMINI_CLI_PATH` or `gemini`) |
| `backend/manager/gemini/adapter.py` | `GeminiAdapter`, `HarnessSpec(name="gemini")`, `_gemini_jsonl_candidates()` (glob), `_gemini_discover_sessions()`, `_read_gemini_session_id()`, `_gemini_project_label()` |
| `install/cli-runtime/gemini/settings.json` | Seeded into the repo's `.gemini/settings.json`: `fileFiltering.respectGitIgnore=false`, `respectGeminiIgnore=true` |
| `GEMINI.md` (repo root) | Symlink → `context/AGENTS.md`, committed in git |
| `backend/tests/test_gemini_adapter.py`, `test_gemini_session.py`, `test_gemini_session_ssh.py` | Tests (subprocess mocked) |

## CLI surface

| Aspect | Value |
|---|---|
| Headless prompt | `-p / --prompt <text>` — on argv, not stdin |
| Streaming | `--output-format stream-json` |
| Session id | `--session-id <uuid>` — starts a NEW session with our id; exits with "Session ID … already exists. Use --resume" if the id is on disk |
| Resume | `--resume <uuid>` (or `latest`, or an index); cannot be combined with `--session-id` |
| Approval | `--approval-mode yolo` |
| Trust gate | headless mode refuses untrusted dirs: pass `--skip-trust` or set `GEMINI_CLI_TRUST_WORKSPACE=true` |
| State | `~/.gemini/` — `projects.json` (cwd → label), `tmp/<label>/`, `oauth_creds.json`, `settings.json`, `trustedFolders.json` |

`_build_argv()` produces:

```
gemini --prompt <text> --skip-trust --output-format stream-json --approval-mode yolo
       (--resume <id> once the session's JSONL exists | --session-id <id> before) [--model <id>]
```

The env is the backend's env plus `GEMINI_CLI_TRUST_WORKSPACE=true`, minus `CLAUDECODE`. Stdin is
closed right after spawn so the CLI never waits for input. `_run_lifecycle()` generates the session
UUID up front when not resuming, so the first turn can pin it with `--session-id`. Every turn
chooses by what is on disk (`_session_written()`, which globs with the adapter's path resolver): if
the session's JSONL exists the turn uses `--resume`, otherwise `--session-id`. That covers a
resumed session (`--resume` from its first turn), a resume id never written (a tab reopened before
its first turn) and a fresh session whose first turn failed before the CLI wrote anything. SSH
sessions can't be checked locally, so there a resume id or a completed turn means `--resume`.
`_run_lifecycle()` also pings SSH targets and pre-warms (remote `which gemini` or local `gemini --version`), like Qwen. There is no
permission gating (`yolo`), no compaction and no cost reporting.

## Storage layout

The CLI hard-codes:

```
~/.gemini/tmp/<project-label>/chats/session-<YYYY-MM-DDTHH-MM>-<first 8 chars of uuid>.jsonl
```

`<project-label>` is what `~/.gemini/projects.json` maps the cwd to (first run: the cwd basename,
`assistant`). The installer (`--with-gemini`, step 3c) replaces that directory with a symlink:

```
~/.gemini/tmp/assistant  ->  /home/rodrigo/assistant/context
```

so Gemini's `chats/session-*.jsonl` lands in `context/chats/` next to Qwen's `<uuid>.jsonl`; the
name patterns never overlap. A pre-existing real directory is backed up to
`context/gemini-backup-<timestamp>/` and its chats lifted first. The session manager passes
`project_dir` as cwd so the label stays stable.

Because the file name carries only an 8-character id prefix:

- `_gemini_discover_sessions()` (the spec's `session_discoverer`) globs `context/chats/session-*.jsonl`
  and reads each header line for the real `sessionId`; header-less files are skipped.
- `_gemini_jsonl_candidates()` (the `jsonl_path_resolver`) globs `session-*-<id[:8]>.jsonl` in
  `context/chats/`, then falls back to every `~/.gemini/tmp/*/chats/` for hosts without the symlink.
  It may return `[]`; the registry contract test allows that.
- `SessionStore` runs discoverers before its own scans and skips `session-*` names in the
  `chats/` scan, so a Gemini file never appears with its file stem as a fake id.

## JSONL format (on disk)

| Line | Shape |
|---|---|
| Header (line 1, rewritten on resume) | `{"sessionId", "projectHash", "startTime", "lastUpdated", "kind": "main"}` |
| User | `{"id", "timestamp", "type": "user", "content": [{"text": "…"}]}` — content is a **list** |
| Assistant | `{"id", "timestamp", "type": "gemini", "content": "<string>", "thoughts": [{subject, description, timestamp}], "tokens", "model"}` |
| Assistant with tools | same line plus `"toolCalls": [{id, name, args, result: [{functionResponse: {response: {output}}}], status, resultDisplay, …}]` |
| Bookkeeping | `{"$set": {"lastUpdated": "…"}}` after every change — skip |

`GeminiAdapter` normalizes: user `content` list → joined text; assistant `content` string → a
`text` block; each thought → a `thinking` block `"<subject>\n<description>"`; a line with
`toolCalls` becomes **two** messages — the assistant turn with one `tool_use` per call, then a
synthetic user message with the matching `tool_result` blocks — so clients pair them by
`tool_use_id`. It overrides `is_visible_message` / `visible_line_indices` because content is flat
and an assistant line with only `toolCalls` is still a visible turn. Detection: a header with
`sessionId` + `projectHash` + `kind`, or any `type: "gemini"` line.

## stream-json (live output)

| `type` | Fields | Normalized |
|---|---|---|
| `init` | `session_id`, `model` | adopts `session_id` if none pinned |
| `message` role `user` | echo of the prompt | skipped |
| `message` role `assistant`, `delta: true` | text chunk | `TextDelta`; accumulated into `TextComplete` |
| `tool_use` | `tool_name`, `tool_id`, `parameters` | `ToolUse` |
| `tool_result` | `tool_id`, `status`, `output` or `error.message` | `ToolResult` (`is_error` when `status == "error"`) |
| `result` | `status`, `stats {input_tokens, output_tokens, total_tokens, cached}` | `TurnComplete` with usage (`cached` → `cache_read_input_tokens`) |

Thoughts are not streamed; they only appear on disk. Some CLI builds print non-JSON lines to stdout
(e.g. "Shell cwd was reset …"); the parser drops any line not starting with `{`.

## HarnessSpec values

`name="gemini"`, `label="Gemini CLI"`, `comm_prefix="node"` (shared with Qwen),
`ssh_control_path_prefix="gemini"`, `requirements_file=None`, `npm_package="@google/gemini-cli"`,
`cli_binary="gemini"`, `env_keys=()` (OAuth-first; `GEMINI_API_KEY` optional).

## Landmines

1. **Trust check.** Without `--skip-trust` the CLI prints one stderr line and exits 1 in headless
   mode. The manager passes the flag and sets `GEMINI_CLI_TRUST_WORKSPACE=true` as a backup.
2. **`type: "gemini"`, not `"assistant"`** — easy to miss when copying the Qwen adapter.
3. **`$set` lines** between messages — skip in `read_messages`, use only for timestamps.
4. **File name ≠ session id** — only the 8-char prefix; glob and read the header.
5. **User content is a list of `{text}`**; assistant content is a plain string.
6. **`thoughts` is top-level**, not a content block.
7. **Tool calls are inline** (`toolCalls` on the assistant line). Without the split into
   tool_use + tool_result messages, reopened conversations lost their tool calls.
8. **`.gitignore` hides `context/`.** The repo's `.gemini/settings.json` sets
   `respectGitIgnore=false` so the agent can read memory, skills and history under the gitignored
   `context/`. Keep it when editing that file.
9. **`--session-id` only creates.** Given an id that already exists, the CLI prints "Session ID
   … already exists. Use --resume to resume it" and exits (checked against CLI 0.42.0). Until
   2026-10-07 the first turn of a resumed manager sent `--session-id` (the switch was
   `self._turns == 0`), so every resumed Gemini session failed its first turn; it now sends
   `--resume` (fixed 2026-10-07). The switch is now the JSONL on disk, not the turn count, so a
   first turn that fails before the CLI writes anything no longer leaves every later turn on a
   `--resume` the CLI can't find.

## History

- 2026-05-15 — added as the third harness on branch `provider-generalization` (`5c9cb0f`); the
  registry contract test for path resolvers was relaxed to allow `[]`.
- 2026-05-16 — `session_discoverer` added so sessions under `~/.gemini/tmp/` showed up (`989fd71`);
  then `~/.gemini/tmp/<label>` symlinked to `context/` and scans de-duplicated (`24ac981`);
  `GEMINI.md` symlink committed (`1a1b0d9`).
- 2026-10-07 — resume vs pin decided by the JSONL on disk: resumed sessions send `--resume` from
  their first turn (they sent `--session-id`, which the CLI rejects for an existing id), and a
  failed first turn no longer leaves the session stuck on `--resume`.
