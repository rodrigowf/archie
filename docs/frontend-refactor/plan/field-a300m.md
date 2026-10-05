# Field tests — Samsung A300M, lite app (`com.assistant.peripheral`)

Spec 14 §6.6 (D-01): L-F1–L-F5 + the G-01 items that need the real device. Voice scenarios F1–F22 are in inventory 04 §10.3.
Device: SM-A300M (adb `06e4f224`), Android 5.0.2 / API 21, 540×960. Backend: Jetson `local` 9cbfbaf.
Logcat for every run: `scratchpad/field/a300m-<date>.log` (not committed).

Status: ✅ pass · ❌ fail (bug link) · ⏭️ waived by Rodrigo · ⏳ not run yet

## Install (2026-10-05 19:02)

- Old app v1.0.9 (versionCode 10) archived: `context/secrets/android/a300m-backup-2026-10-05/peripheral-v1.0.9-base.apk`.
- ⚠️ `peripheral-data.ab` (40 MB) is **truncated**: the zlib stream ends inside `f/vosk-model/am/final.mdl`, so it holds
  only the Vosk model, not `shared_prefs/` or `files/datastore/`. It is **not** a usable settings backup. Rollback
  (`adb install -r -d` the archived APK) still works because the in-place upgrade keeps the old files (the old app reads
  the same `settings` DataStore and `assistant_service_prefs`); only `saved_servers` → `saved_servers_v2` is converted
  (0 servers here). `run-as` fails on this Samsung ("Package is unknown"), so app data can only be read through the UI.
- Signing cert SHA-256 `F4:D6:91:…:1E:3D` = the installed app's → `adb install -r` of 2.0.0 (100) succeeded.

## Results

| ID | Scenario | Status | Notes |
|---|---|---|---|
| L-F2 | In-place upgrade keeps settings, phrases, accessibility grant, Vosk model | ✅ 2026-10-05 | `[LegacyMigration] kept 12 settings keys, purged 0 ws_resume_checkpoint keys, converted 0 saved servers, assistant_service_prefs kept, vosk-model kept (stamp ok, no re-extract)`. UI shows the old custom values (mic 90 %, echo ducking 1.5 %, wake sensitivity 120 %, talk auto-stop 2.0 s, talk phrases "hello my friend, hey my friend", wake "wake up"). Assist handler (`secure assistant`) still `com.assistant.peripheral/.VoiceShortcutActivity`. No accessibility grant existed (`accessibility_enabled=0`). Wake word was OFF in `assistant_service_prefs` (old app not running at install); turned ON 19:04 → Vosk loaded in 3.7 s, grammar `["hello my friend","hey my friend","wake up","[unk]"]` |
| Q8 | Raw `/dev/input` recents monitor needed? | ✅ drop it | Device: `/dev/input/event2` (`sec_touchkey`) is `crw-rw---- root input u:object_r:input_device:s0`; the app runs with gids `{50134, 9997, 3003, 3002}` (no `input`=1004) → the old monitor could never open it. Confirms the A-08 analysis |
| ELF | 16 KB alignment | ✅ n/a | Lite APK ships only `armeabi-v7a` (32-bit); the 16 KB page requirement is for 64-bit libs. Report: libdatastore 16K, libjingle/libjnidispatch/libvosk-stderr-shim/libvosk 4K |
| BUG-1 | Conversation opened on another device not shown | 🔧 fixed `1e3fffe` + face fix in progress | Two causes: (1) `OrchestratorChannel` ignored `agent_session_opened{is_orchestrator}` (spec 12 §4.4) — same bug on the main app (POCO, 19:07:42); fixed for both. (2) The lite face only showed VOICE transcripts — typed turns and history never reached it (sub-agent: live frames + last exchange from history on attach) |
| BUG-2 | Talk/wake acknowledgement cues silent | 🔧 fixed `1e3fffe` | `AudioTrack init failed state=2` on both phones: MODE_STATIC reports NO_STATIC_DATA until write(); the old app's check dropped every cue |
| BUG-3 | Talk trigger fires on ordinary speech | ⏳ Rodrigo to choose | "Okay, I opened…" → Vosk "hey my" (A300M, 2-word prefix of "hey my friend") and "up hey friend" (POCO). Whisper rejected all 5, nothing was sent, but the face showed "Recording" for ~5–7 s. Same `MIN_PREFIX_WORDS = 2` as the old app (parity) |
| G-01a | Real "wake up" → voice conversation | ⏳ | Rodrigo speaks |
| G-01b | Real "hello my friend, what time is it" → one voice message + reply | ⏳ | Rodrigo speaks |
| L-F1 | Memory budget at idle, armed, in voice, after 1 h | ⚠️ partial | Armed on Face (debug build): TOTAL PSS **152 MB** vs budget ≤ 140 MB (Native 104 MB = Vosk, Dalvik 6.9 MB ≤ 24 ✅, `.dex mmap` 11.3 MB unminified). Old app measured ~165–185 MB in the same state (inv04 §9.1). Re-measure on the R8 release build before judging; in-voice and 1 h pending |
| L-F3 | Reboot → companion boot launch → wake armed ≤ 60 s; `am kill` → watchdog relaunch ≤ 30 s | ⏳ | |
| L-F4 | 24 h soak (D-01 asks 48 h): no LMK kill of the FGS, no stuck state | ⏳ | |
| L-F5 | Long-press home and recents trigger → voice | ⏳ | recents needs the accessibility service |
| Release | Release (minified) build smoke | ⏳ | after L-F2/L-F4 (Q3) |
