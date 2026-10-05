## 2. Identifiers glossary (read this first)

The single biggest source of client bugs is that the field name `session_id` means different things in different payloads.

| Concept | What it is | Where it appears |
|---|---|---|
| **`local_id`** | Stable UUID minted **by the client** for a tab/conversation view; the **pool key**. Never changes across reconnects or backend restarts (as long as the client keeps it). | Chat/orchestrator `start` messages (`local_id`); chat `session_started.session_id`; orchestrator `session_started.session_id`; `GET /api/sessions/pool/live[].local_id`; `GET /api/sessions[].local_id`; watcher events `agent_session_opened/closed.session_id`; `nested_session_event.session_id`; `voice_owner_active.owner_local_id`; voice lifecycle `voice_ending/voice_ended.session_id`. |
| **`sdk_session_id`** | Provider-assigned conversation id (Claude Code SDK id / Qwen / Gemini id). For Claude it is also the **JSONL filename stem** (`context/<id>.jsonl`) and the key for history REST endpoints, titles and per-session config. May be unknown (`null`) for a brand-new session until the CLI reports it. | `start.resume_sdk_id` (input); `turn_complete.session_id` (chat WS); `session_terminated.sdk_session_id`; `GET /api/sessions[].session_id`; `GET /api/sessions/pool/live[].sdk_session_id`; `agent_session_opened.sdk_session_id`. |
| **orchestrator `jsonl_id`** | Filename stem of the orchestrator's JSONL: `resume_sdk_id` if resuming, else the orchestrator's `local_id` (`orchestrator/session.py:218-219,321-323`). Plays the role of `sdk_session_id` for the orchestrator. | `GET /api/sessions/pool/live[].sdk_session_id` for the orchestrator row; `agent_session_opened.sdk_session_id` (orchestrator). **Not** in orchestrator `session_started`. |
| **`stream_id` / `seq`** | Resume-protocol cursor for **Claude agent sessions only**: `stream_id = "<local_id>:<epoch_ms>"` minted each time the SDK subprocess (re)connects; `seq` is a per-stream monotonic integer assigned to every SDK-derived event (`manager/claude/session.py:365-373,745-774`). | Stamped on most chat-WS event payloads (§4.4); `session_started.resume_state`; `start.resume_from`. |
| **`tool_use_id`** | Provider tool-call id (`toolu_…` for Claude, OpenAI `call_…`, Gemini call id). Joins `tool_use` ↔ `tool_result` (and `tool_executing`/`tool_progress` on the orchestrator). | Live events and history blocks. |
| **`request_id`** | UUID4 for one permission prompt (`manager/base_session.py:341`). | `permission_request`, `permission_resolved`, `permission_response` (client). |
| **`turn_id`** | Orchestrator background-runner turn id (fire-and-forget delegation). | Only in orchestrator JSONL `background_notification` lines and orchestrator tool outputs; not a client protocol field. |

**Rules a client must follow**

1. Mint `local_id` (UUID v4) per tab and persist it together with the last known `sdk_session_id`.
2. Use `local_id` for all WebSocket `start` calls and for `POST /api/sessions/{local_id}/close`.
3. Use `sdk_session_id` (or orchestrator `jsonl_id`) for **every** history REST call (`/api/sessions/{id}`, `/messages`, `/preview`, `/rename`, `/config`, `/duplicate`, `/truncate`, `/fork`, `DELETE`). These endpoints do **not** resolve `local_id` (§8 G-12).
4. Learn the `sdk_session_id` of a new chat session from the first `turn_complete.session_id`, or from `GET /api/sessions/pool/live`, or from the watcher event `agent_session_opened.sdk_session_id` (orchestrator WS only). `session_started` on the chat WS does **not** carry it.

---

