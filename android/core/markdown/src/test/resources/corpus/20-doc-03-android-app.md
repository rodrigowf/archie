## 7. Device targeting

| | Samsung Galaxy A300M (dedicated terminal) | Xiaomi POCO X7 (personal phone) |
|---|---|---|
| OS / API | Android 5.0.2 / API 21, not rooted | modern Android (API 34-class) |
| Screen | 540×960 (about 360×640 dp at hdpi) | large, high-density, edge-to-edge |
| RAM / SoC | 888 MB usable, Snapdragon 410, old GPU (`android_peripheral_project.md`, "A300M memory budget") | ample |
| Management | companion app `com.assistant.device` (boot, watchdog, WiFi ADB at static 192.168.0.225), 125 bloat packages removed | none |

**Code that exists only for the A300M or Lollipop** (this chapter's scope; the voice/audio ones are listed for completeness and detailed in chapter 4):

- **UI and app layer:**
  - `VoiceShortcutActivity` + `AssistantVoiceInteractionService`: the Lollipop assist gesture (§1.8).
  - `ButtonAccessibilityService` and the raw `/dev/input/event2` recents monitor: the A300M's capacitive recents key (`AssistantService.kt:617-675`).
  - Pre-O_MR1 window-flag branches (`MainActivity.kt:175-185`, `VoiceShortcutActivity.kt:28-38`) and the bright-screen wake lock (`AssistantService.kt:201-221`).
  - Markdown performance hardening, motivated by a native `libhwui` RenderThread stack overflow on the A300M (commit `31c2fbf`): incremental parse of the stable prefix (`ui/components/markdown/MarkdownText.kt:60-96`), code blocks that wrap instead of scrolling horizontally, an inline-depth cap of 8, and non-animated stick-to-bottom. Keep these as general good practice.
  - Syntax highlighting is gated on API 24+ **and** ≥2 GB RAM (`ui/components/markdown/MarkdownStyles.kt:55-67`), so it is off on the A300M.
  - Audio-frame log suppression (`WebSocketManager.kt:34-38, 284-289`).
- **Voice/audio (chapter 4):**
  - The Vosk `stderr` shim (CMake + `VoskModelLoader` for API < 23).
  - `VOICE_RECOGNITION` audio source on API < 24 (`voice/MicCapture.kt:76-80`, `voice/OpenAIVoiceProvider.kt:352-355`).
  - AudioRouter and PcmPlayback branches for API < M (`AudioDeviceInfo` unavailable).
  - The Samsung HAL quirks (MODE_NORMAL, STREAM_MUSIC beeps).

**Modern-only code:** the `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` runtime requests, typed `getParcelableExtra` (33+), `setCommunicationDevice` (31+), dynamic color support (`ui/theme/Theme.kt:114-118`, present but disabled).

**Constraints minSdk 21 imposes on the main app today:**
- Java 8 target.
- No `AudioDeviceInfo`/`setCommunicationDevice` without fallbacks.
- No `enableEdgeToEdge` assumptions.
- The Compose BOM is pinned at `2023.10.01`. Any upgrade must be checked against each AndroidX artifact's minSdk; recent AndroidX releases are moving off API 21. Verify before bumping.
- No predictive back or per-app language.
- WebRTC and Vosk native ABIs limited to armeabi-v7a and arm64.

Raising the main app to minSdk 26–29 removes most branches. **targetSdk 35 forces edge-to-edge**, which the current non-inset-aware shell (manual bottom bar, `MainActivity.kt:721-765`) would break.

**Proposed split and shared modules:**
- **Shared (`:core`):**
  - `data/` models and protocol types.
  - `network/` (`WebSocketManager`, `ApiClient`, discovery).
  - `settings/SettingsRepository` (after fixing checkpoint growth).
  - `connection/OrchestratorConnectionController`.
  - A rewritten chat reducer (pure Kotlin, tail-based).
  - `voice/` (`VoiceManager`, providers, EchoDuck, PcmPlayback, AudioRouter).
  - `audio/`, the wake-word stack (`WakeWordDetector`, Vosk, Whisper), and `service/AssistantService`.
- **Main app (modern):** the new M3 UI, tabs/multi-session, Memory and Viz panels, permissions, tool renderers, the System tab.
- **Lite voice-first app (A300M):**
  - Service-owned voice, wake word and the beeps.
  - A minimal transcript view (no heavy markdown, or the hardened renderer).
  - Server selection, plus the assist, recents and accessibility triggers.
  - It keeps minSdk 21, the Lollipop branches and the Vosk shim.
  - The companion `com.assistant.device` app stays separate.

---

