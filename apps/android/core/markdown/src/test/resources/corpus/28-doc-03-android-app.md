## 2. Settings inventory

### 2.1 App settings: `AppSettings` (`data/Models.kt:417-435`), persisted by `settings/SettingsRepository.kt`

All are stored in the Preferences DataStore file `settings` (`SettingsRepository.kt:352`). The key names are wire format and renaming them requires a migration (`:325-343`). **None of the app settings are sent to the backend**, except that `serverUrl` *is* the backend address.

| Setting | DataStore key | Type, default (range) | UI | Consumed by |
|---|---|---|---|---|
| `serverUrl` | `server_url` | String `"ws://192.168.0.200:80"` (a hard-coded Jetson LAN IP) | server list, editor | WS base (`WebSocketManager.kt:154-170`), REST base (`ApiClient.kt:41-53`), `AssistantService` (`server_url` extra, for the wake-word Whisper key fetch), "is default?" checks for auto-scan (`MainActivity.kt:393-401, 437-441`; `OrchestratorConnectionController.kt:326-331`) |
| `savedServers` | `saved_servers` | List encoded as `label\turl\|…` (`SettingsRepository.kt:313-323`). Breaks if a label contains a tab or pipe. | ServersSection | Settings only |
| `autoConnect` | `auto_connect` | Bool `true` | switch | `MainActivity.kt:395-396, 439` |
| `enableWakeWord` | `enable_wake_word` | Bool `true` | switch | `AssistantService.updateWakeWord` |
| `talkWord` | `turn_talk_word` | String `"my friend"`, comma-separated | text field with explicit Save | service (turn-based capture) |
| `wakeWord` | `realtime_wake_word` | String `"wake up"`, comma-separated | text field with explicit Save | service (realtime trigger) |
| `themeMode` | `theme_mode` | enum SYSTEM / LIGHT / DARK, default SYSTEM | radio | `AssistantTheme` |
| `micGainLevel` | `mic_gain_level` | Float 1.0 (0–1.5, 10% steps) | "Mic" slider | `VoiceManager.setMicGain` (`VoiceController.kt:246, 253`) |
| `wakeWordMicGainLevel` | `wake_word_mic_gain_level` | Float 1.0 (0–1.5) | "Wake Word Sensitivity" | service. Scales the RMS gate. |
| `talkSilenceSensitivity` | `talk_silence_sensitivity` | Float 2.0 (1.0–4.0, 0.5 steps) | "Talk Auto-Stop Sensitivity" | service. End-of-utterance VAD multiplier. |
| `speakerVolumeLevel` | `speaker_volume_level` | Float 1.0 (0–1.5) | "Speaker" slider | **Side effect only:** sets the *system* `STREAM_MUSIC` volume (`SettingsRepository.kt:252-259`). The stored value is never read back, so the slider drifts from the real volume after a hardware-key change. Values above 100% clamp to the maximum. |
| `echoDuckingGain` | `echo_ducking_gain` | Float 0.05 (0–1.0, UI limited to 0–10%) | "Echo Ducking" | `VoiceManager.setEchoDuckingGain` |
| `audioOutput` | `audio_output` | enum AUTO / LOUDSPEAKER / EARPIECE / BLUETOOTH / WIRED, default AUTO | five toggle buttons | `VoiceManager.setAudioOutput` |
| `enableButtonTrigger` | `enable_button_trigger` | Bool `false` | "Recents Button Trigger" | Mirrored to SharedPreferences `assistant_service_prefs/button_trigger_enabled` (`SettingsRepository.kt:267-271`, and again on every emission at `AssistantViewModel.kt:188-189`). Read by `ButtonAccessibilityService.kt:45-47`. |

### 2.2 Non-user persisted state (same DataStore)

| Key | Content | Lifecycle |
|---|---|---|
| `orchestrator_local_id` | the local_id of the orchestrator pool entry | Restored on the first settings emission (`AssistantViewModel.kt:181-186`). Rewritten by the connect probe, recovery, `newSession` and `reconcileOrchestrator`. Cleared on a URL change or on close. |
| `ws_resume_checkpoint:<localId>` | `"<streamId>\|<seq>"` | Written on **every** seq-stamped event (`ChatController.kt:496-511`). Each write is a read plus a full-file DataStore edit (`SettingsRepository.kt:158-171`). One key per local_id. Agent sessions mint a **new random local_id on every open** (`ChatController.kt:1106`), so keys accumulate forever and are only removed on overflow or termination. The preferences file grows without bound, and the whole file is rewritten on every streamed token of an agent turn. |
| SharedPreferences `assistant_service_prefs` | `button_trigger_enabled` (plus the service's own `PREFS_NAME`) | legacy glue to the services |

### 2.3 System settings (backend `assistant_config.json`, via `GET/PUT /api/config`)

Everything in the System tab is **sent to the backend** as a `ConfigPatch` (`data/SystemConfig.kt:131-150`, serialized at `network/ApiClient.kt:816-863`). These settings apply to all clients.

| Field (`ConfigPatch`) | JSON key | UI control | Notes |
|---|---|---|---|
| `defaultModel` | `default_model` | Text-mode Provider and Model | Changing the provider picks that provider's first model (`SystemSettingsTab.kt:223-226`). |
| `defaultVoiceProvider` | `default_voice_provider` | Voice Provider | openai / qwen / google, labels at `:18-22` |
| `defaultVoiceEndpoint` | `default_voice_endpoint` | Backend (Google only) | Refetches the Google catalog when it changes (`system/SystemConfigController.kt:139-188`) |
| `defaultVoiceModel` | `default_voice_model` | Voice Model | Auto-corrected when Google deprecates an id (`SystemConfigController.kt:203-229`), which writes a second PUT |
| `defaultVoiceName` | `default_voice_name` | Voice | |
| `defaultVoiceTranscriptionLanguage` | `default_voice_transcription_language` | Transcription language | Shown only if the model lists languages |
| `voiceRecordingEnabled` | `voice_recording_enabled` | Voice recording switch | |
| `voiceVadThreshold` | `voice_vad_threshold` | slider 0.15–0.5 | Parse default 0.28 (`ApiClient.kt:1049`) |
| `voiceVadMinSilenceMs` | `voice_vad_min_silence_ms` | slider 800–5000 | default 2500 |
| `voiceMicGain` | `voice_mic_gain` | slider 0.5–2.0 | Labelled "reserved" in the UI |
| `provider` | `provider` | Session provider | |
| `harnessModel` | `harness_model` | Qwen model (the `"qwen"` key only) | |
| `workingDirectory` | `working_directory` | Active directory | select only; no add, edit or delete |
| `chromeExtension` | `chrome_extension` | switch | |
| `enabledMcps` | `enabled_mcps` | per-server switch | see the bug in §8 |

Voice defaults are also read at the start of every voice session (`ApiClient.getVoiceConfig`, `ApiClient.kt:239-262`) and forwarded in `voice_start` (§3.5).

---

