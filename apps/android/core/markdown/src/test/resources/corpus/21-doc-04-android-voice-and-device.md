## 2. Voice providers and transports (behaviour to reproduce)

### 2.1 Backend surface (complete)

| Kind | Endpoint / message | Current caller |
|---|---|---|
| REST | `GET /api/config` → `default_voice_provider/model/name/transcription_language/endpoint`; on any error → `VoiceConfig.DEFAULT` (openai / gpt-realtime / cedar) | `P/network/ApiClient.kt:239-262`; `VoiceProvider.kt:74-80` |
| REST | `POST /api/orchestrator/voice/session?provider&model&voice&transcription_language&endpoint` (empty body) → `connection_info{connection_type, endpoint, ephemeral_token, expires_at, model, voice, audio_in_format{sample_rate,encoding}, audio_out_format{...}}`. Sample rates default to 24000 | `ApiClient.kt:293-340`, :431-448 |
| REST | `GET /api/config/openai-key` → `{api_key}` | `ApiClient.kt:351-368` |
| External | `POST <connection_info.endpoint>` (default `https://api.openai.com/v1/realtime/calls?model=gpt-realtime`), `Content-Type: application/sdp`, `Bearer <ephemeral>` | `OpenAIVoiceProvider.kt:117`, :507-565 |
| External | `POST https://api.openai.com/v1/audio/transcriptions` multipart (`file=wake.wav`, `model=whisper-1`, `response_format=json`, `language=en`, `temperature=0`) | `WhisperConfirmer.kt:105-169` |
| WS out | `voice_start{local_id, resume_sdk_id, voice_provider, voice_model, voice_name, voice_transcription_language, voice_endpoint?}` | `P/network/WebSocketManager.kt:226-239` |
| WS out | `voice_stop` | :240-242 |
| WS out | `voice_event{event}` (WebRTC mirror) | :243-246 |
| WS out | `voice_audio_in{audio}` | :247-249 |
| WS out | `send_audio{audio, format:"wav"}` (talk/PTT) | :259-262 |
| WS out | `start{local_id, resume_sdk_id, resume_from{stream_id,seq}}` | :208-222 |
| WS in | `session_started{session_id, voice, voice_session_update, voice_initiator (default **false**)}` | :331-343 |
| WS in | `voice_command{command}` | :450-453 |
| WS in | `voice_event{event}` (drops `*.transcript.delta`, `response.text.delta`, `function_call_arguments.delta`; shallow map) | :454-481 |
| WS in | `voice_audio_out{audio}` | :482-488 |
| WS in | `voice_ending{reason}`, `voice_ended{reason}`, `voice_stopped` | :489-497 |
| WS in | `voice_owner_active{active, owner_local_id}` | :498-502 |
| WS in | `voice_vad_state{state, duration_ms, silero_prob}` | :504-515 |
| WS in | `voice_error{error{category,message,recoverable,recovery_hint,provider_doc_url,raw_close_code,raw_close_reason,provider}}` | :517-545 |
| WS in | `ping` (backend app-level heartbeat every 15 s, consumed silently) | :321-328 |
| WS transport | okhttp `pingInterval` 30 s; reconnect delay 3 s; `Disconnected(willReconnect=shouldReconnect)` | :42, :47, :99, :175-179 |

### 2.2 OpenAI Realtime over WebRTC

**Mic path:**

- `JavaAudioDeviceModule` with HW AEC/NS **disabled** (:359-364).
- WebRTC software AEC, NS and AGC **enabled** (:335-337).
- Source is `VOICE_RECOGNITION` below API 24, `VOICE_COMMUNICATION` from 24 (:354-357).
- Gain is applied in `AudioRecordDataCallback` (:283-296).
- goog* constraints (:374-387).
- One send-track plus one recv-only transceiver, unified plan, max-bundle (:394-468).
- Ordered DC `oai-events` (:470-472).

**Events:**

- Every DC event is mirrored to the backend as `voice_event` (:629).
- Event → `VoiceState`/`VoiceEvent` mapping (:655-762):

| DC event | State / VoiceEvent |
|---|---|
| `response.created` | Speaking |
| `response.done` | Active + TurnComplete |
| `output_item.added(function_call)` | ToolUse |
| `function_call_arguments.done` | Thinking + ToolUse |
| `speech_started` | Active + SpeechStarted |
| `speech_stopped` | Thinking + SpeechStopped |
| `input_audio_transcription.completed` | UserTranscript |
| `response.output_audio_transcript.*` / legacy `response.audio_transcript.*` | TextDelta / TextComplete (both names accepted, `304aa80`) |
| `output_audio_buffer.started/stopped/cleared` | Ducking (§3.3) |
| `error` | Error + async teardown |

- **Backend → OpenAI:** `voice_command.command` goes over the DC if it is open, otherwise into `pendingCommands` (:769-785).

**Teardown:** never from a WebRTC callback thread (:405-429, :641-652). Order is DC close → track dispose → PC close → PC dispose → factory dispose (:806-853).

### 2.3 WebSocket PCM providers (Qwen, Gemini; unknown WS → Qwen parser `VoiceManager.kt:471-480`)

**Connect** (`WebSocketPcmProvider.kt:174-266`):

1. Guard: already running.
2. Check RECORD_AUDIO.
3. In/out sample rates come from `connection_info`.
4. State → Connecting.
5. Reset ducker.
6. Build playback and mic.
7. `running=true`.
8. **delay 200 ms.**
9. **Mic start, then speaker start.**
10. State Active + SessionCreated.

**`voice_status`** (:287-326):

| Status | Effect |
|---|---|
| `preparing` | Connecting |
| `summarizing` | Summarizing, **only if** state ∈ {Off, Error, Connecting, Summarizing} (`40ce856`) |
| `ready` | Active |
| `reconnect_warning` | ReconnectWarning(parsed Go duration) |
| `reconnecting` | Reconnecting |

**Backend `error`** (:334-346): Error state + VoiceEvent.Error → `cleanup()` → SessionEnded (`94e3e4a`).

**Qwen** (`QwenVoiceProvider.kt:23-81`): same table as OpenAI. `speech_started` → `flushSpeakerOutput()` (barge-in).

**Gemini** (`GeminiVoiceProvider.kt:39-121`):

- `inputTranscription` deltas are buffered into one user transcript. It flushes on the first `outputTranscription`/`modelTurn`, or on `turnComplete` (`ea96ce2`).
- `interrupted` → flush the speaker and drop the staged assistant text.
- `turnComplete` → TextComplete(staged) + TurnComplete.
- `toolCall.functionCalls[]` → ToolUse.
- **But see bug B1:** the `parts` and `functionCalls` arrays are never parsed today.

### 2.4 Ownership

**Ownership flags:**

- `voice_initiator` missing → **false** (`WebSocketManager.kt:335-342`, `9b24d1a`). It sets `amVoiceOwner` on `session_started` when `voice=true` (`VoiceController.kt:310-316`).
- `startVoiceSession` sets owner=true (:716). `finalizeVoiceStop` clears it (:780).

**Owner-only handling:**

- `voice_command`, `voice_ending`, `voice_ended`/`voice_stopped` (:350, :377, :393).
- The `voice_session_update` forward happens only when the initiator flag is set (:317-321).

**Non-owner:** `voice_owner_active` → `remoteVoiceActive`, which renders a disabled button "Active elsewhere" (:358-365; `P/ui/components/VoiceButton.kt:140-160`).

---

