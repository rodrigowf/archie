package com.assistant.core.voicehost

/**
 * Every tuned value of the voice host: the old `AssistantService` / `ButtonAccessibilityService`
 * (inv04 §4.5), the cue tones of the old `AssistantViewModel` (inv04 §4.10), and the new P-2
 * link-loss cues (plan/20 P-2). Old values are ported verbatim from `e871d05`
 * (`tools/parity/old_constants.json` is authoritative; `ServiceTuningTest` and
 * `OldConstantsCrossCheckTest` pin them by name).
 */
object HostTuning {
    // ---- Wake service (old `AssistantService.kt`) ----

    /**
     * `0b2cbb5`: a second `startWakeWord` with the same (talk, wake, gain) key inside this window is
     * an intent redelivery (gaps of 20 ms and 1.3 s seen in the field). Strict `<` on the monotonic
     * clock: a call exactly at the boundary goes through (a real user toggle). **LB**.
     */
    const val WAKE_START_DEDUPE_WINDOW_MS = 3_000L

    /** SCREEN_ON and USER_PRESENT fire ms apart: one re-arm this long after the last of them. **LB**. */
    const val SCREEN_REARM_DEBOUNCE_MS = 300L

    /**
     * `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` held on a wake/talk trigger: the reliable
     * screen-on path on Lollipop when the Activity is in the background. **LB**.
     */
    const val FOREGROUND_WAKE_LOCK_MS = 3_000L

    /** Recents (KEYCODE_APP_SWITCH) long press, both the accessibility and the `/dev/input` path. *UX*. */
    const val RECENTS_LONG_PRESS_MS = 600L

    // ---- Notification (old `AssistantService.kt:43-44`) ----

    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "assistant_service_channel"

    /** Inc 9 (old `AssistantService.kt:714`): the foreground text while the wake mic is held elsewhere. */
    const val MIC_STALLED_TEXT = "Wake word stalled — mic held by another app"

    // ---- `assistant_service_prefs` (survives process death; the lite app inherits the file) ----

    const val PREFS_NAME = "assistant_service_prefs"
    const val PREF_ENABLED = "wake_word_enabled"
    const val PREF_TALK_WORD = "turn_talk_word"
    const val PREF_WAKE_WORD = "realtime_wake_word"
    const val PREF_WAKE_MIC_GAIN = "wake_word_mic_gain"
    const val PREF_TALK_SILENCE_SENSITIVITY = "talk_silence_sensitivity"
    const val PREF_SERVER_URL = "server_url"
    const val PREF_BUTTON_TRIGGER_ENABLED = "button_trigger_enabled"

    /** `687442e` (old `Models.kt:425-426`). */
    const val DEFAULT_TALK_WORD = "my friend"
    const val DEFAULT_WAKE_WORD = "wake up"
    const val DEFAULT_WAKE_GAIN = 1.0f
    const val DEFAULT_TALK_SILENCE_SENSITIVITY = 2.0f

    // ---- Cues (old `AssistantViewModel.kt:493-627`) ----

    /**
     * `b586e4b`: STREAM_NOTIFICATION is muted during call audio on the A300M, and the companion app
     * puts the device in DND total silence (inv04 §6.4 contract 4): every cue plays on STREAM_MUSIC. **LB**.
     */
    const val CUE_STREAM = "MUSIC"

    /** Synthesis rate of every cue (old `playTones` / `playReconnectBeep`). */
    const val CUE_SAMPLE_RATE_HZ = 22_050

    /** Linear fade in/out per tone (old: `sr * 15 / 1000` frames). */
    const val CUE_FADE_MS = 15

    /** The track is stopped this long after the last frame (old: `playMs + 80`). */
    const val CUE_RELEASE_PAD_MS = 80L

    // ---- Conversation switch (NEW, spec 12 §6.11a) ----

    /**
     * After `voice_ended{reason:"switch"}` the host keeps the foreground service (and its microphone
     * FGS type) while it resumes the past conversation and restarts voice on it, at most this long.
     * Longer than the 30 s voice-start budget would be pointless; the resume `start` takes ~1 s. *UX*.
     */
    const val SWITCH_HOLD_MS = 30_000L

    // ---- P-2 link-loss cues (NEW, decision P-2; *UX*, tune with Rodrigo) ----

    /**
     * While the orchestrator link is down during live voice, the soft "reconnecting" pattern repeats
     * this often (call-app style, WhatsApp-like), starting at the moment of the loss.
     */
    const val LINK_CUE_REPEAT_MS = 2_000L

    // ---- Host lifecycle (spec 14 §2.5) ----

    /** Main app: the runtime disconnects this long after the UI stopped, when the service is not running. */
    const val UI_STOPPED_DISCONNECT_DELAY_MS = 60_000L

    /** Push-to-talk capture rate (same as the wake loop's talk capture, 16 kHz mono WAV). */
    const val PTT_SAMPLE_RATE_HZ = 16_000

    /** Push-to-talk read size (100 ms at 16 kHz). */
    const val PTT_READ_SAMPLES = 1_600

    /** Push-to-talk hard cap (a stuck button never records forever). */
    const val PTT_MAX_MS = 120_000L
}
