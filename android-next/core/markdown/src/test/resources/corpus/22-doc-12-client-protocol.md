## 8. Settings and configuration

### 8.1 Server-global (shared by every device)

| Store | Endpoint | Keys |
|---|---|---|
| Global config | `GET/PUT /api/config` (partial PUT, full object back) | `working_directory`, `working_directory_history`, `enabled_mcps`, `chrome_extension`, `provider`, `default_model`, `summarizer_model`, `harness_model`, `default_voice_provider`, `default_voice_model`, `default_voice_name`, `default_voice_transcription_language`, `default_voice_endpoint`, `voice_recording_enabled`, `voice_vad_threshold`, `voice_vad_min_silence_ms`, `voice_mic_gain` |
| Per-session config | `GET/PUT /api/sessions/{sdkId}/config` | `working_directory`, `enabled_mcps`, `chrome_extension`, `provider`, `harness_model` (`null` = inherit) |
| Titles | `PATCH /api/sessions/{sdkId}/rename`, `PATCH /api/visualizations/rename` | — |
| Catalogs (read-only) | `/api/config/providers`, `/api/config/harness/qwen/models`, `/api/orchestrator/models`, `/api/orchestrator/voice/models`, `/api/config/voice/google/models`, `/api/mcp/servers`, `/api/skills`, `/api/agents` | — |
| Claude CLI auth | `/api/auth/status`, `/api/auth/login`, `/api/auth/credentials` | Both clients SHOULD check status after connecting and offer the credential-paste flow on headless backends (Android lacks it, 03 §5). |

- **CFG-1.** Each control saves immediately with a partial `PUT`; the response replaces the local copy. Sliders MUST commit on release, not on every tick (W-6.2). Controls of the section in flight are disabled.
- **CFG-2.** On 400 the client MUST show the backend `detail` string (W-6.2).
- **CFG-3.** Config is not broadcast (G-27): every settings screen refetches on open.
- **CFG-4.** `enabled_mcps: []` means "all enabled" (`api/routes/config.py:125`). The UI MUST show every server checked when the list is empty. Unchecking server X when the list is empty writes the explicit list of all other servers. Checking every server writes `[]`. (Fixes A-8.1; the web's all-unchecked display, W-6.2.)
- **CFG-5.** Voice defaults cascade server-side (01 §3.6); after a PUT the client re-renders from the returned object instead of patching locally.
- **CFG-6.** Google auto-correct (02 §7.12): if `default_voice_provider == "google"` and the saved model is not in a non-empty discovered list, PUT the discovered default once and show the dismissible notice.
- **CFG-7.** `working_directory_history` PUT is a full replacement; local paths must exist on the server. Android gets full add/edit/delete parity (03 §5).
- **CFG-8.** `default_model` is stored but the backend ignores it (G-37). Clients MUST label it accurately ("Default for new orchestrator sessions") and see Appendix B Q3.

### 8.2 Device-local (never sent to the backend)

| Setting | Web storage | Android storage |
|---|---|---|
| Backend URL, saved servers, auto-connect | n/a (page origin) | DataStore |
| Theme (system/light/dark) | localStorage | DataStore |
| Open views, active view, sidebar section, drafts | sessionStorage (per browser tab) | saved state + DataStore |
| Last orchestrator `localId` (hint only; validated by `syncPool`) | memory | DataStore |
| Mic gain, speaker volume, echo ducking, audio output route | — | DataStore (chapter 04) |
| Wake/talk words, sensitivities, button trigger | — | DataStore (chapter 04) |
| Remote console logging (default on for compat and lite) | localStorage flag | n/a |
| Conversation snapshots + checkpoint (T-10) | memory only | optional snapshot on `onStop` |

Storage writes MUST NOT happen per streamed event (A-8.14). Every storage access on the web is wrapped in try/catch (private mode, Safari 12).

---

