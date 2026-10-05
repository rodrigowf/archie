# Field tests — POCO X7 Pro, main app (`com.assistant.archie`)

Spec 14 §6.6 (D-01). Voice scenarios F1–F22 are defined in inventory 04 §10.3; M-F1–M-F7 in spec 14 §6.6.
Device: POCO X7 Pro (2412DPC0AG), Android 15, HyperOS 2. Backend: Jetson `local` 9cbfbaf (2026-10-05).
Next to run: F3 retest + look at the reacting orb (Rodrigo), then F4–F7, M-F1, M-F3–M-F5, M-F7, the rest in table order.
Logcat for every run: `scratchpad/field/poco-<date>.log` (not committed). Rodrigo runs the physical steps;
the coordinator watches logcat and the server and records the verdict.

Status: ✅ pass · ❌ fail (bug link) · ⏭️ waived by Rodrigo · ⏳ not run yet

## Preconditions

| Item | Status |
|---|---|
| Old app (`com.assistant.peripheral`) wake word not running during the tests | ✅ service not running; Rodrigo does not open the old app |
| New app permissions: microphone, notifications, nearby devices | ✅ granted |
| Logcat capture running | ✅ |

## Results

| ID | Scenario | Status | Notes |
|---|---|---|---|
| M-F6 | Agent permission request → notification → Approve works | ✅ 2026-10-04 | Heads-up on channel "Approvals", lock screen public; Approve → `permission_resolved allow/user`, notification withdrawn. HyperOS shade did not let Rodrigo expand it (buttons visible only in the heads-up / full-width state) — check again in F-tests |
| F1 | "wake up" ×10, arm's length, quiet room | ✅ 2026-10-05 (Rodrigo) | "All work properly" after restarting the app. Some instability at the very start (server still starting / app connecting?) — no logs kept (the laptop rebooted and the logcat capture died); watch for it in later runs |
| F3 | "hello my friend, what time is it" in one breath | ⏳ | Bug found by Rodrigo: the talk-phrase message showed "Archie started on its own" — main app never posted `LocalSendAudio` (`MainTranscriptSink.voiceMessageSent` was a no-op). Fixed: `ConversationRepository.voiceMessageSent()`; retest |
| F4 | Talk phrase then silence (phantom) | ⏳ | |
| BUG | Archie conversation opened on the web not picked up while the app runs | 🔧 fixed `1e3fffe` (installed 19:15) | `recv agent_session_opened` ignored at 19:07:42; retest: app open with no conversation → open one on the web → the app follows it |
| BUG | Talk acknowledgement beep silent | 🔧 fixed `1e3fffe` | `VoiceCues: AudioTrack init failed state=2` |
| F5 | 5 talk turns, each with an audible reply | ⏳ | |
| F6 | Screen off → talk phrase + command | ⏳ | |
| F7 | Screen off → "wake up", 3 cycles | ⏳ | |
| M-F1 | Screen off (60 min) → "wake up" → headless voice, notification, no Activity | ⏳ | |
| M-F3 | Assist gesture → overlay → voice | ⏳ | |
| M-F4 | Quick Settings tile on/off | ⏳ | |
| M-F5 | Share a 3 MB file / share text | ⏳ | |
| M-F7 | Long markdown answer with a 400-line code block, no jank | ⏳ | |
| F8 | Long agent answer (>25 s), no self-interrupt | ⏳ | |
| F9 | Barge-in mid-answer (Qwen/Gemini) | ⏳ | |
| F10 | Back-to-back calls via wake word | ⏳ | |
| F11 | Audio outputs (auto, speaker, earpiece, wired, BT) | ⏳ | |
| F13 | Airplane mode 5 s mid-call | ⏳ | |
| F14 | Close the conversation mid-call | ⏳ | |
| F15 | Two devices (phone + web), voice on one | ⏳ | |
| F17 | Wake word disabled during a call | ⏳ | |
| F18 | Mic held by another app | ⏳ | |
| F22 | Wake slider 150% vs 100% | ⏳ | |
| F12 | Gemini goAway during a call | ⏳ | opportunistic |
| F16 | Backend rejects the session | ⏳ | needs a forced error |
| F21 | Fresh install, persisted settings used | ⏳ | |
| F2 | 30 min silence + 30 min TV/music, no false wakes | ⏳ | long |
| F19 | 2 h+ voice session with a Wi-Fi blip | ⏳ | long |
| M-F2 | HyperOS: service alive after 8 h idle (with / without the reliability checklist) | ⏳ | long |
