---
name: qwen-omni
category: archie/voice
tags: [voice, qwen, qwen-omni, dashscope, alibaba, websocket, voice-relay, silero, vad, schema-sanitiser, common-error]
created: 2026-05-16
modified: 2026-10-07
summary: Qwen-Omni realtime voice over DashScope via the backend relay; the "Common error!" bisector, sanitisers, client-side Silero VAD, reconnect, voices.
source: curated (consolidated from memory notes assistant/voice/qwen_omni_voice_adaptation.md, assistant/voice/voice-multimodel-plan.md, auto-memory feedback_qwen_voice_dashscope_2026_05, project_qwen_voice_gate_no_staleness_clear; verified against code 2026-10-06)
references:
  - architecture.md
  - lifecycle.md
  - gemini-live.md
  - openai-realtime.md
  - ../harnesses/qwen-code.md
  - ../operations/debugging.md
---

# Qwen-Omni (DashScope)

Alibaba's Qwen-Omni realtime models are Archie's second voice provider
(`provider = "qwen"`). The transport is **WebSocket through the backend
relay**: the client sends mic PCM to the orchestrator WS, and
`backend/orchestrator/voice_relay.py` owns the upstream socket. The
`DASHSCOPE_API_KEY` never leaves the backend (it is long-lived and has no
ephemeral exchange). The wire protocol is almost byte-for-byte OpenAI
Realtime's: same event names, same `session.update` shape, same
`conversation.item.create` for tool results.

Code: `backend/orchestrator/providers/qwen_voice.py` (`QwenVoiceProvider`),
`backend/orchestrator/providers/schema_utils.py` (sanitisers),
`backend/orchestrator/voice_vad.py` (Silero) and
`backend/orchestrator/voice_relay.py`.

## Endpoint, models, audio formats

- Endpoint: `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime`
  (Singapore / international console), with a `DASHSCOPE_API_KEY` bearer
  header. The same key works for the Qwen Code CLI.
- Handshake: `server_first`. The server sends `session.created`, then the
  relay sends `session.update`.

| Model | Default voice | Audio in / out |
|---|---|---|
| `qwen3.5-omni-plus-realtime` (default) | `Aiden` | `pcm` 24 kHz / `pcm` 24 kHz |
| `qwen3-omni-flash-realtime` | `Aiden` | `pcm16` 16 kHz / `pcm24` 24 kHz |
| dated snapshots (`…-realtime-2026-03-15`) or other discovered ids | inherit their alias's entry (`qwen_model_entry`) | by family |

Voice catalogues are static (`voice_registry.py`): **55 Plus voices** and **49
Flash voices**. DashScope has no per-model voice listing. `Aiden` is in both
catalogues, so it is the registry default. `QWEN_VOICE_NAME` in
`qwen_voice.py` is the constructor default, used only when code builds
`QwenVoiceProvider` directly; `instantiate_provider` always passes the
registry's voice. It is `Aiden` too, like OpenAI's `cedar` and Gemini's
`Puck` match their registry defaults. (Fixed 2026-10-07: it was still `Tina`,
the default before `e69e38f`; a test now checks the two agree.)

Transcription: `input_audio_transcription = {"model": "qwen3-asr-flash-realtime"}`.
The documented `gummy-realtime-v1` is weak in English and drifts to Chinese on
short fragments. A transcription-language picker exists (`_QWEN_ASR_LANGUAGES`,
default `en`), but see "language is poisoned" below.

`tool_choice` is `"auto"`. Plus calls tools reliably with it. Flash needs
`"required"`, which is not exposed per session yet.

Only the Qwen prompt gets an extra "Voice Mode" directives block appended in
`format_session_config` (pacing, never read tool syntax or formatting markers
aloud, 1–3 sentences). Qwen has no `speed`/`verbosity` knobs, so behaviour has
to be asked for in prose. The OpenAI prompt does not need it.

## DashScope's "Common error!"

One generic error comes from **several different validators**:

```json
{"type": "error", "error": {"code": "InternalError",
 "message": "Parse RealtimeEvent error: Common error!"}}
```

DashScope then closes the WS with **1011** within about a second. Known
causes so far:

1. **URL validator.** Bare hostnames, URLs without a scheme or absolute POSIX
   paths in `instructions` or in `function_call_output.output`
   (`localhost:5432`, `192.168.0.200`, `/home/…`). The related "InvalidParameter:
   The provided URL does not appear to be valid" 400 can fire *late*, when the
   omni pipeline scans the context mid-session. Fix:
   `sanitize_text_for_qwen` wraps URL-shaped tokens in backticks, so they look
   like code spans the validator skips. It is applied to the whole
   instructions string and to tool outputs.
2. **Event-shape validator.** Since a change between 2026-05-02 and
   2026-05-15, **any** value in `input_audio_transcription.language`
   triggers it.
3. **Tool-schema parser.** It rejects JSON-Schema union types (`"type":
   ["integer", "null"]`). This was found by bisection: 14 of 15 tools passed,
   and `read_agent_session.max_messages` failed. Fix: `sanitize_tool_for_qwen`
   (`_scrub_union_types`) folds `[X, "null"]` into `X` (the first non-null
   branch, default `"string"`). DashScope has no `nullable`; fields that may be
   null should simply not be required. It is applied to every tool in
   `format_session_config`. **Don't bypass it.** It is narrower than the Gemini
   sanitiser because DashScope accepts almost all of Draft 7 except unions.

The message is the same for every cause, so **bisecting is the only reliable
way to find which one it is**. Write a small standalone script, about 80 lines
of Python. It opens the WS with the `DASHSCOPE_API_KEY` header, waits for
`session.created`, and sends richer and richer `session.update` payloads:
bare, then + instructions, + turn_detection, + transcription, + each tool on
its own. After each one, check the first inbound event for `type=error`.

### Language is poisoned (for now)

`_build_transcription_config` leaves `language` out (auto-detect) unless
`QWEN_ALLOW_TRANSCRIPTION_LANGUAGE=1` is set. Keep that escape hatch in case
Alibaba accepts the field again. Auto-detect handles English fine; the
regression is only about the parser's strictness.

## VAD: client-side Silero, on by default

DashScope's server VAD **force-commits long utterances mid-speech** (seen at
about 30–40 s of continuous audio). That splits one user turn into two
`conversation.item` entries with a phantom `response.create` between them:
the model talks over the user and later sees only the second half as a new
prompt. No schema setting controls this (checked against Alibaba's prose docs
and Java SDK; there is no `max_segment_duration`). So since 2026-05-20:

- `format_session_config` sends `turn_detection: null` when manual VAD is on
  (`QWEN_MANUAL_VAD`, default on; `QWEN_MANUAL_VAD=0` brings back the server
  VAD in `DEFAULT_VAD`: `server_vad` 0.4 / 300 ms prefix / 2500 ms silence,
  `create_response`, `interrupt_response`).
- The relay feeds every mic chunk through Silero (`VoiceVAD`, see
  [architecture.md](architecture.md#voice-activity-detection)). On
  `speech_stopped` it sends `manual_vad_stop_frames()` =
  `input_audio_buffer.commit` + `response.create`, and emits a synthetic
  `input_audio_buffer.speech_stopped` to the clients so their UI changes state
  as it would with server VAD.
- **Safety commit after 50 s** of continuous speech
  (`manual_vad_safety_commit_s`). DashScope's manual mode documents a 60 s cap
  on audio before a commit is required. The relay sends **only**
  `input_audio_buffer.commit`, with no `response.create`. The segment closes
  on the wire while the model stays silent; the parts are evaluated together
  at the next real `speech_stopped`. (This must *not* be copied to Gemini.
  See [gemini-live.md](gemini-live.md#manual-vad).)
- `graceful_shutdown_frames()` = commit only, before `end_voice` closes the WS.

Server-VAD tuning history, in case you switch back: 0.5/800 (Alibaba
defaults; the model cut in at any 0.8 s pause) → 0.4/1800 (`993c648`) →
0.4/2500 (`4c38b23`).

## The `response.create` gate

Qwen rejects a concurrent `response.create` ("Conversation already has an
active response") and in practice **closes the WS**. The provider gates it
like OpenAI does: active on `response.created`; cleared on `response.done` /
`.cancelled` / `.failed` and on barge-in (`speech_started`,
`output_audio_buffer.cleared`); an optimistic `mark_response_create_sent()`;
and a force-clear after 15 s. Gated frames wait in the **relay's**
`_deferred_events` queue. A polling watchdog (`_arm_deferred_drain`, every
2 s, up to 45 s) drains it even when no inbound event comes. All of this was
ported from OpenAI on 2026-08-01. Before that, Qwen cleared only on
`response.done`, had no barge-in escape, and could wedge for good after a
tool call. See [lifecycle.md](lifecycle.md#rules) for the general rule.

The relay watchdog **must not return right after `send_event`**. If that call
parks the frame again, its own `_arm_deferred_drain` is a no-op (the current
task isn't done yet), and the frame would be left with no watchdog. It loops
until the queue is really empty.

Clients must send `response.cancel` on barge-in only while a response is in
flight. A cancel with no active response makes DashScope close the socket
(spec 12 V-9).

## Reconnect and keepalive

- `is_recoverable_error` returns True for `InvalidParameter`, "The provided
  URL does not appear to be valid" (the misleading 400/1007 that DashScope
  sends mid-session for no reason on our side) and `response_idle_timeout`
  (the 5-minute no-response watchdog). The relay reopens and sends a
  rebuilt `session.update` (`RECOVERABLE_ERROR`, at most 2 attempts).
- A 1011 "Common error!" is **not** recoverable: reopening would hit the same
  validator.
- **Keepalive**: the ASR pipeline dies after a few minutes of silence. The
  next real audio then closes the WS with a misleading `InvalidParameter`.
  `build_keepalive_chunk()` returns 20 ms of zero PCM at the model's input
  rate. The relay sends it every 30 s of mic silence (`keepalive_s`).
- On reconnect the relay calls `VoiceVAD.reset()`. Qwen sends no
  `voice_status: ready` after a reconnect, so clients clear their reconnect
  banner on the next transcript or audio chunk (spec 12 §7.7, G-35).

`classify_close_reason`: balance insufficient (English and Chinese "余额不足")
→ `QUOTA_EXCEEDED`; `InvalidApiKey` → `AUTH`; "model not found" →
`MODEL_UNAVAILABLE`; `Throttling` / "too many" → `RATE_LIMIT` (recoverable);
the recoverable substrings above → `NETWORK` (recoverable).

## Diagnosing Qwen voice

Two failure modes, and both were on DashScope's side. Don't assume a
regression in our code.

| Symptom | Log signature (`logs/voice/<ts>_<session>.log`) | Cause |
|---|---|---|
| Session dies about 1 s after a frame | WS close **1011** "Parse RealtimeEvent error: Common error!" | one of the validators above; bisect |
| Model cuts you off at 30–40 s | `speech_stopped → response.created` while audio chunks keep flowing | server VAD force-commit; make sure manual VAD is on |
| Tools run but results never reach the model | voice log: many `defer event type=response.create (provider gate active)`, no `drain deferred event` after them | gate wedge; check the watchdog |

- `VOICE_DEBUG_QWEN_BODIES=1` (env on the backend) logs the session keys and a
  slim copy of the session block. It also writes the **full** `session.update`
  to `logs/voice/<ts>_<session>.session_update.json`. Use it when "Common
  error!" comes back, or after adding a tool, to check the schema has nothing
  DashScope dislikes (`type: [...]`, `anyOf`).
- `VOICE_DEBUG_DUMP_MIC=1` and `VOICE_DEBUG_VAD=1` work for every relay
  provider. See [architecture.md](architecture.md#observability).

## Out of scope

- Streaming function-call arguments: Qwen sends them complete, and the
  deltas are not handled the way OpenAI's are.
- Setting `tool_choice` per session (Plus vs Flash).
- Client-side echo cancellation on the WS path. The browser's AEC can't see
  `PcmPlayer`'s output. This is mitigated by gated `response.cancel` and VAD
  tuning, and on Android by drain-then-restore ducking.
