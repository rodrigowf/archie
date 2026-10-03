## 6. History / JSONL loading and merging with live streams

### 6.1 Where history lives

| Session kind | File | Writer |
|---|---|---|
| Claude agent session | `context/<sdk_session_id>.jsonl` | bundled Claude Code CLI (one line per content block; also `queue-operation`, `attachment`, `ai-title`, `last-prompt`, `file-history-snapshot`, `mode`, `system`… lines) |
| Qwen agent session | `context/chats/<id>.jsonl` | Qwen CLI (`parts`/`functionCall` shape) |
| Gemini agent session | discovered via the Gemini harness (`~/.gemini/tmp/<label>/chats/session-*.jsonl`) | Gemini CLI |
| Orchestrator | `context/<jsonl_id>.jsonl` | `orchestrator/persistence.py:198-225` (`HistoryWriter`) |

All are read through `SessionStore` + per-provider adapters into one normalized shape (`manager/protocol.py:298-368`), then served by `GET /api/sessions/{id}/messages` (§3.2).

### 6.2 What a REST message is

- **One JSONL `user`/`assistant` line = one `MessagePreview`** (`manager/protocol.py:351-368`). `system` and all internal line types are dropped.
- For **Claude agent sessions** that means an assistant turn is split into **many consecutive `assistant` messages, usually one block each** (text, tool_use, or an *empty* message for a thinking block), and every tool result is a separate **`user` message whose `blocks` is `[tool_result]` and whose `text` is `""`** (**LIVE** verified on `528dbf6f-…`).
- `text` = all `text` blocks joined with `\n` (thinking excluded); `blocks` keeps order within the line (`manager/protocol.py:421-483`).
- Block mapping (`manager/protocol.py:448-481`): `text` → `{type:"text", text}`; **`thinking` → `{type:"text", text: block["text"]}`** — but Claude stores thinking under the key `thinking`, so Claude thinking blocks yield **no block at all** (empty assistant message, **LIVE**), whereas Qwen/Gemini adapters normalise thinking to `{type:"thinking", text}` which then surfaces as an ordinary **`text` block** indistinguishable from the answer (`manager/qwen/adapter.py:45-63`, `manager/gemini/adapter.py:181`). ⚠ G-9.
- `tool_use` → `{type:"tool_use", tool_use_id, tool_name, tool_input}`; `tool_result` → `{type:"tool_result", tool_use_id, output: str, is_error}` (list content joined).
- **No ids:** history messages carry no message id, block id, `seq` or `source`. The only stable identity is the absolute index (`start_index + i`), which is stable while the file is append-only (it changes after `truncate`).
- User lines also include CLI-internal content verbatim (slash-command echoes like `<command-name>…`, compact summaries, "[Request interrupted by user]", system-reminder-like meta lines). The backend does not filter `isMeta`/`isCompactSummary` flags (`manager/claude/adapter.py:64-85`).

### 6.3 Orchestrator history specifics

The orchestrator JSONL (`orchestrator/session.py:572-591,1654-1693,1855-1893,1932-1939`; `orchestrator/voice_persister.py:188-356`) contains:

| JSONL `type` | Fields | Visible via REST? |
|---|---|---|
| `orchestrator_meta` | `orchestrator:true, session_id, model, provider, timestamp, voice?, voice_provider?, voice_model?, voice_name?, voice_transcription_language?, openai_model?` | no (sets `is_orchestrator`) |
| `user` | `message:{role, content: str}`, `timestamp`, optional `source`: `"voice_transcription"` \| `"shared_inject"` \| `"audio_input"` (+`audio_format`), optional `audio_segment` | **yes** (text only; `source` dropped). Voice turns are recognisable only by the content prefix `"[voice] "` or `"[voice, recording: <local_id> <start>-<end>ms] "`; audio turns by `"[audio:<fmt>] "`. |
| `assistant` | `message:{role, content: str}` — **text mode: ALL text blocks of the turn joined with `\n`, written once at the end of the turn** (`session.py:1884-1893`); voice: one line per spoken response, `source:"voice_response"` | **yes** |
| `tool_use` | `tool_call_id, tool_name, tool_input, timestamp, source?:"voice"` | ⚠ **no** — the Claude adapter keeps only `user`/`assistant`/`system` lines (`manager/claude/adapter.py:81`) |
| `tool_result` | `tool_call_id, output, is_error, timestamp, source?` | ⚠ **no** |
| `background_notification` | `notification_id, turn_id, session_id, session_title, origin_tool_use_id, status, cost, turns, duration_seconds, error, timestamp` | ⚠ **no** |
| `voice_interrupted` | `timestamp` | no |
| `model_switch` | `model, provider, timestamp` | no |
| `compact` | `trigger:"manual", summary, timestamp` | no |

**LIVE**: orchestrator `9ed0934e-…` has 16 `tool_use` + 16 `tool_result` + 6 `background_notification` lines, yet `/messages` returns only user/assistant text. **Consequences:** (a) reloading an orchestrator conversation loses every tool call and notification; (b) the relative order of text vs. tool calls inside a text-mode turn is lost even on disk (text is persisted after the tools); (c) a wake turn appears as an assistant message with no preceding user message.

### 6.4 Pagination

```
GET /api/sessions/{id}/messages?limit=50              → newest 50, start_index = total-50, has_more = start_index>0
GET /api/sessions/{id}/messages?limit=50&before=S     → messages [max(0,S-50), S), start_index = max(0,S-50)
```

`total_count` is computed on every call (the whole file is parsed each time — no server cache for messages). `before=0` is treated as "no before" by the route's `start_index` arithmetic but the store returns an empty page, so the result is `messages:[]`, `start_index:0`, `has_more:false` — harmless. If the file grew between pages, indices from the top are still valid (append-only).

### 6.5 Merging history with the live stream (normative guidance)

There is **no server-provided join key** between REST history and live events. A correct client:

1. **Subscribe first, then fetch.** Send `start` (with `resume_from` if you have one), buffer live frames, then `GET …/messages` for the latest page, then apply buffered frames. Messages already persisted to the JSONL *and* re-delivered live (or replayed) will duplicate — dedupe on `seq` for live frames and treat history as authoritative for anything completed before the subscribe.
2. **AMBIGUOUS:** the timing of the CLI's JSONL write relative to the WS broadcast of the same block is not controlled by the backend; a block can be in neither, one, or both views at subscribe time. The robust strategy is to **re-fetch the tail page after `turn_complete`** (and after `replay_overflow`, `session_terminated`, reconnects) and replace the in-memory tail with it.
3. For the **orchestrator**, history has no tool calls and coarser assistant messages (§6.3), and there is no replay. Keep the live-rendered transcript in memory for the current view; on reload accept the reduced fidelity (or see the backend fix in §8).
4. Render history with the same reducer as live events by converting each `MessagePreview` into events: consecutive `assistant` messages (until the next `user` message with a non-`tool_result` block) form one assistant turn; `user` messages consisting only of `tool_result` blocks are **not** user turns — attach each `tool_result` to the `tool_use` with the same `tool_use_id`. Drop empty assistant messages (thinking placeholders).

---

