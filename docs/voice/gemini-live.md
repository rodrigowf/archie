---
name: gemini-live
category: archie/voice
tags: [voice, gemini, gemini-live, google, vertex-ai, ai-studio, websocket, voice-relay, goaway, session-resumption, manual-vad, schema-sanitiser]
created: 2026-05-15
modified: 2026-10-06
summary: Gemini Live via the backend relay (AI Studio or Vertex); camelCase wire, schema sanitiser, bundled-transcription bug, manual VAD, goAway and resumption.
source: curated (consolidated from memory notes assistant/voice/gemini_live_voice_adaptation.md, assistant/voice/voice_lifecycle_and_wake_after_stop.md, assistant/voice/qwen_omni_voice_adaptation.md, auto-memory feedback_clear_provider_state_on_rebuild; verified against code 2026-10-06)
references:
  - architecture.md
  - lifecycle.md
  - qwen-omni.md
  - ../harnesses/gemini-cli.md
  - ../clients/android.md
  - ../operations/debugging.md
---

# Gemini Live

Google's Gemini Live API is Archie's third voice provider
(`provider = "google"`). Like Qwen it uses a **WebSocket through the backend
relay** (`backend/orchestrator/voice_relay.py`): 16 kHz PCM16 in, 24 kHz PCM16
out. The credential stays on the backend. Both clients support it: the web
app and the Android `WsPcmTransport`. The A300M has run Gemini Live as its
everyday voice provider with wake word and auto-reconnect.

Code: `backend/orchestrator/providers/gemini_voice_base.py`
(`GeminiVoiceProviderBase`, all protocol logic),
`backend/orchestrator/providers/gemini_voice.py` (the two backends +
`select_backend`), `backend/orchestrator/providers/schema_utils.py`
(`sanitize_schema_for_gemini`).

## Two backends

The protocol is the same on both. They differ in URL, auth and how the
`setup.model` value is written.

| Backend (`endpoint` id) | Class | URL | Auth | Model value |
|---|---|---|---|---|
| `vertex` (**default**, `DEFAULT_ENDPOINT`) | `VertexAIBackend` | `wss://{GCP_LOCATION}-aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent` | `Authorization: Bearer` token from Application Default Credentials, minted per session (`get_adc_access_token`) | `projects/<GCP_PROJECT_ID>/locations/<loc>/publishers/google/models/<id>` |
| `aistudio` | `GeminiAIStudioBackend` | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` | `?key=$GEMINI_API_KEY` | `models/<id>` |

The backend is picked by the explicit `endpoint` (the `voice_endpoint` field
of `voice_start`, or `default_voice_endpoint` in `assistant_config.json`),
then the `GEMINI_VOICE_BACKEND` env var, then `vertex`. Vertex became the
default after AI Studio began closing previously working keys with **1008
"Your project has been denied access"** for preview Live models. Google's
documented workaround is Vertex, which uses GCP IAM instead of the AI Studio
allowlist. Vertex needs `GCP_PROJECT_ID`, optionally `GCP_LOCATION` (default
`us-central1`), and ADC (`gcloud auth application-default login` or
`GOOGLE_APPLICATION_CREDENTIALS`). The key name is `GEMINI_API_KEY`; it was
renamed from `GOOGLE_GEMINI_API_KEY` in `580ea36`.

### Models and voices

| Model id | Backend |
|---|---|
| `gemini-3.1-flash-live-preview` (static default) | AI Studio |
| `gemini-2.5-flash-native-audio-latest` (`AI_STUDIO_DEFAULT_MODEL`) | AI Studio |
| `gemini-live-2.5-flash-native-audio` (`VERTEX_DEFAULT_MODEL`) | Vertex (it kept the `-live-` prefix) |

`GET /api/config/voice/google/models?endpoint=…` lists the live catalogue of
whichever backend is chosen (`models.list` filtered to `bidiGenerateContent`,
paginated past the default 50). The static list in `voice_registry.py` is the
fallback. Voices are a static catalogue (the Live API has no per-model list):
`Puck` (default), `Charon`, `Kore`, `Fenrir`, `Aoede`, `Leda`, `Orus`,
`Zephyr`. Gemini detects the language itself, so there is no transcription
language picker.

## Wire shape: camelCase, no `type`

OpenAI and Qwen put a `type` field on every event. Gemini Live uses
**camelCase top-level keys** (`setupComplete`, `serverContent`, `toolCall`,
`toolResponse`, `sessionResumptionUpdate`, `goAway`) and no `type` at all.
That is why Gemini has its own dispatch path in every layer:

- **Handshake `client_first`**: the client sends `setup` first, and the server
  answers `setupComplete`. Audio sent before `setupComplete` makes Gemini
  close with 1008, so the relay holds outbound frames until the handshake
  completes.
- **Translation**: `_EVENT_TRANSLATORS` is an *ordered tuple of (predicate,
  method)*, not a dict, because one frame can carry several keys. Order:
  setupComplete → interrupted → turnComplete → toolCall → error short-circuit.
  inputTranscription, outputTranscription and modelTurn text fall through
  when their text is empty.
- **Persistence** (`VoicePersister._handle_gemini_event`): build up
  `inputTranscription` → a `[voice]` user line; build up
  `outputTranscription` → the assistant line, written at `turnComplete`;
  `interrupted` → a `voice_interrupted` entry and the cancelled fragment is
  dropped.
- **Tools**: `toolCall.functionCalls: [{id, name, args}]` comes in whole. The
  `toolResponse` must echo the `name` together with the `id`, so the
  provider remembers `id → name` (`ToolCallAccumulator`). The route has its
  own `_handle_gemini_voice_tool_call`. Without it, tools run but the chat
  shows no tool cards.
- **Client turn completion**: Gemini signals `turnComplete` with no final
  text, because the deltas already carried it. Reducers must finalise the
  streaming block in place, not overwrite it with an empty string. The web
  conversation reducer and the Android `ProviderParsers` both do this.
- **Barge-in**: `serverContent.interrupted: true` → the client flushes local
  playback right away (no cancel frame is sent). Otherwise the buffered tail
  keeps playing.
- **Upstream filter**: `accepts_upstream_event` drops anything with a `type`
  key, i.e. stray OpenAI-shaped events that leak in after a provider switch.
  Forwarding them made Gemini close with WS 1007 "Unknown name 'type'".
- Silent text injection (a file shared during a call) has its own
  `format_text_input` (`clientContent`).

## Tool-schema sanitiser

`functionDeclarations[].parameters` must be **OpenAPI 3.0**, a strict subset
of the Draft-7 schemas the tool registry produces. `sanitize_schema_for_gemini`:

- `"type": ["X", "null"]` → `"type": "X", "nullable": true`;
- `anyOf: [{…}, {type: null}]` → the non-null branch + `nullable: true`
  (Gemini rejects a raw `anyOf`, even with one branch);
- strips `$schema`, `$id`, `$ref`, `$defs`, `definitions`,
  `additionalProperties`, `patternProperties` and the conditional keywords;
- recurses into `properties`, `items` and combinators.

Tools are converted from either OpenAI or Anthropic shape.

## The bundled-transcription bug (the one that took longest to find)

`outputAudioTranscription: {}` at the top of `setup` is correct (confirmed
against the google-genai SDK source). But Gemini sends the model's
`outputTranscription` text **inside the same `serverContent` frame as the
audio chunk**:

```json
{"serverContent": {
   "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "…"}}]},
   "outputTranscription": {"text": " Yes,"}}}
```

`inputTranscription` comes in its own frame, which is why user transcripts
worked from day one. The relay's `_drain` used to send the audio and then
`continue`, which threw the transcript away. Now, after sending the audio, it
makes a copy without the audio (`_strip_audio_parts`) and passes it to the
event pipeline, so transcription, `turnComplete` and `interrupted` reach
`process_voice_event` and the chat UI. Before this was found, the
`gemini-3.1-flash-live-preview` model was wrongly suspected. The bug was
found with `VOICE_DEBUG_GEMINI_BODIES=1`. **Keep that switch**: it is the
last-resort diagnostic if Gemini's wire shape changes again.

## Manual VAD

Gemini's server VAD ends turns in the middle of a sentence at natural
breathing pauses, even with `endOfSpeechSensitivity: LOW` and
`silenceDurationMs: 2500`. This was seen on the A300M: "…for instance, uh…",
the model started replying, and the rest of the sentence arrived 4 s later as
a barge-in. So by default (`GEMINI_MANUAL_VAD`, on unless set to `0`):

- `setup.realtimeInputConfig.automaticActivityDetection = {disabled: true}`;
- the relay runs Silero and sends `realtimeInput.activityStart` on
  `speech_started` and `realtimeInput.activityEnd` on `speech_stopped`.
  `activityEnd` is also the request for a reply;
- `audio_in_sample_rate = 16000` must be declared. Without it the relay never
  starts Silero, and audio piles up with no turn boundaries (a silent failure
  mode);
- `GEMINI_MANUAL_VAD=0` brings back server VAD:
  `{disabled: false, START_SENSITIVITY_LOW, END_SENSITIVITY_LOW, prefixPaddingMs: 300, silenceDurationMs: 2500}`.

**Safety commit: none.** `manual_vad_safety_commit_frames()` returns `[]`. On
Gemini, `activityEnd` **always** produces a reply: there is no commit-only
step like Qwen's `input_audio_buffer.commit`. An early version copied Qwen's
50 s safety chunking (`activityEnd` + `activityStart`). On 2026-06-04 Gemini
answered 290 ms after that `activityEnd` and cut the user off. Gemini has no
documented per-utterance cap and none was seen in 200 s+ monologues.

| | Qwen / OpenAI Realtime | Gemini Live (manual) |
|---|---|---|
| Close a segment | `input_audio_buffer.commit` | `realtimeInput.activityEnd` |
| Ask for a reply | `response.create` (separate) | included in `activityEnd` |
| Safety-chunk a long utterance? | yes, commit only | **no**, any `activityEnd` triggers a reply |
| Per-utterance cap | 60 s (DashScope) | none |

`graceful_shutdown_frames()` = `activityEnd`. The reply it triggers doesn't
matter, because the WS closes right after.

## goAway, session resumption and reconnect

Gemini closes the WS after a per-session limit (about 10–15 min). About
30–60 s before, it sends `goAway{timeLeft: "50s"}` (a Go duration string).
If the client does not close the socket itself, Google closes it with a
punitive **1008 "policy violation"**.

1. The first `setup` asks for resumption (`sessionResumption: {}`). The
   provider keeps the latest `sessionResumptionUpdate.newHandle`.
2. When `goAway` arrives, the relay broadcasts
   `voice_status: reconnect_warning{time_left}`. Clients show "Pausing in ~Ns
   to reconnect…" and Android plays a two-tone beep on `STREAM_MUSIC`.
   `should_close_after_event` returns True, so the relay closes cleanly (1000).
3. `_try_reconnect(reason=PROVIDER_GOAWAY)`: no attempt cap. The relay
   broadcasts `voice_status: reconnecting`, reopens with
   `sessionResumption.handle` (Gemini restores its in-memory context), and must
   finish within `reconnect_handshake_s = 15` s. One reconnect once hung for
   40 s or more in `websockets.connect`. Frames sent during the gap go into
   `_held_outbound` (cap 64) and are replayed after `setupComplete`, except a
   frame that would trigger another close.
4. After the reconnect the relay resets Silero (`VoiceVAD.reset()`; a long
   audio gap leaves the recurrent net stuck with flat output) and the
   speech-start timestamp (otherwise a phantom safety commit fires: "speech
   ran 79s").
5. **Stale handle**: `1008 "BidiGenerateContent session expired"` right at
   setup, with no `goAway` first, means the saved handle is poisoned.
   `is_recoverable_error` clears it **once** (`_stale_handle_recovery_used`)
   and reconnects with no resumption (`STALE_HANDLE`, silent). A second 1008
   is fatal.

**The handle is valid only for the upstream WS that issued it.**
`OrchestratorSession.stop_voice_relay()` clears `_resumption_handle` on every
teardown (`5eb7804`). `restart_voice` always creates a fresh provider. Only
the goAway reconnect inside the relay keeps the handle. Reusing a dead handle
makes Google accept the handshake (you even get `setupComplete`) and then
close the WS with 1008 "The operation was aborted." about 150 s later. On
2026-06-04 three quick rebuilds all reused one dead handle, which showed up as
a spike of 400 BadRequest errors in AI Studio. See
[lifecycle.md](lifecycle.md#clear-provider-server-side-state-on-rebuild).

`classify_close_reason`: "exceeded its monthly spending cap" → `QUOTA_EXCEEDED`
(fatal, 1011); session expired → `NETWORK` (recoverable); "denied access"
(1008) → `AUTH` (fatal, the hint suggests switching to Vertex); model
unavailable → `MODEL_UNAVAILABLE`; rate limit → `RATE_LIMIT` (recoverable).

## Diagnostics

- `VOICE_DEBUG_GEMINI_BODIES=1`: the per-session voice log gets `setup_keys`,
  `setup_minus_instructions_tools`, and for every inbound frame `raw_keys` and
  `raw_full`, with audio replaced by `<audio:NB>` so the log stays a few KB/s.
- Android: the new `WsPcmTransport` keeps the `[MIC_PROBE]` and `[MIC_STATE]`
  log lines. See [architecture.md](architecture.md#observability).
- Open question: during the 2026-06 Android refactor validation, Gemini audio
  reached the A300M's AudioTrack but sometimes played silently. It was never
  root-caused and is worth checking if it happens again.

## Out of scope

Video input (Live supports it; the clients don't capture it), voice cloning,
and a text-only Gemini conversation model (that would be a separate provider
class).
