# Voice

These docs cover how Archie talks. Realtime voice is a mode of the
orchestrator conversation, served by three providers: OpenAI Realtime over
WebRTC, and Qwen-Omni and Gemini Live through the backend WebSocket relay.
The folder also covers one-shot voice messages (`gpt-audio`), the voice
lifecycle and ownership across devices, the 16,384-token voice prompt
budget, and the Android wake word and talk word. Start with
`architecture.md`. The client wire contract is normative in
[spec 12 §7](../specs/12-client-protocol.md); the Android
voice stack's full behavioural spec is
[inventory 04](../projects/frontend-refactor/inventory/04-android-voice-and-device.md).

- [architecture.md](architecture.md) — the multi-provider design: `BaseVoiceProvider` and the registry, canonical events, the WebRTC and WS-relay transports, the backend voice modules, the web and Android clients, voice to device control, observability, invariants, the file map
- [lifecycle.md](lifecycle.md) — the `VoiceLifecycle` FSM, `end_voice` / `restart_voice`, wake after stop, the duplicate-handshake guard, the ghost-voice fix, `voice_initiator` and owner-scoped voice ("Active elsewhere"), the `session.update` self-heal, the parked-frame rule, clearing provider handles; stated as rules
- [prompt-budget.md](prompt-budget.md) — keeping the voice prompt under OpenAI's 16,384-token cap: buckets, estimators, the verbatim/summary split, the summary cache, the stale-reuse spike, Rodrigo's two sizing rules, `measure_voice_prompt.py`
- [openai-realtime.md](openai-realtime.md) — OpenAI WebRTC: ephemeral token, the GA `session.update`, VAD, models and voices, the `response.create` gate; plus the `gpt-audio` (formerly `gpt-4o-audio-preview`) and `whisper-1` paths
- [qwen-omni.md](qwen-omni.md) — DashScope: the "Common error!" validators and bisector, sanitisers, client-side Silero VAD and the 50 s safety commit, the gate, reconnect, keepalive, voices
- [gemini-live.md](gemini-live.md) — Vertex vs AI Studio backends, the camelCase wire, the schema sanitiser, the bundled-transcription bug, manual VAD (no safety commit), goAway and resumption handles
- [wake-word.md](wake-word.md) — the Android two-layer wake word (Vosk then Whisper, fail-closed), same-mic talk-word capture with the adaptive VAD, re-arm after voice, the frozen tuned constants, the deferred V6 cleanup, echo-ducking rule, history
