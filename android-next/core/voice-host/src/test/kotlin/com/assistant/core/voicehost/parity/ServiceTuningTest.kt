package com.assistant.core.voicehost.parity

import org.junit.Ignore
import org.junit.Test

/**
 * inv04 §10.1 `ServiceTuningTest` (inv04 §4.5, §4.10). The pref keys are the on-disk contract of
 * `assistant_service_prefs` (the lite app inherits the A300M's file in place).
 */
@Ignore("A-08")
class ServiceTuningTest {
    private val pins = hostPins()

    /** `0b2cbb5`: intent redelivery gaps of 20 ms and 1.3 s. Strict `<` is pinned in `WakeStartDedupeParityTest`. */
    @Test fun wakeStartDedupeWindowIs3s() = pins.long("service.wake_start_dedupe_window_ms", "WAKE_START_DEDUPE_WINDOW_MS", 3_000L)

    @Test fun screenRearmDebounceIs300ms() = pins.long("service.screen_rearm_debounce_ms", "SCREEN_REARM_DEBOUNCE_MS", 300L)

    @Test fun foregroundWakeLockIs3s() = pins.long("service.foreground_wake_lock_ms", "FOREGROUND_WAKE_LOCK_MS", 3_000L)

    @Test fun recentsLongPressIs600ms() = pins.long("service.recents_long_press_ms", "RECENTS_LONG_PRESS_MS", 600L)

    @Test fun accessibilityLongPressIs600ms() = pins.long("service.accessibility_long_press_ms", "RECENTS_LONG_PRESS_MS", 600L)

    @Test fun notificationId() = pins.int("service.notification_id", "NOTIFICATION_ID", 1001)

    @Test fun notificationChannel() = pins.string("service.notification_channel", "NOTIFICATION_CHANNEL_ID", "assistant_service_channel")

    @Test fun prefsFile() = pins.string("service.prefs_name", "PREFS_NAME", "assistant_service_prefs")

    @Test fun prefEnabled() = pins.string("service.pref_enabled", "PREF_ENABLED", "wake_word_enabled")

    @Test fun prefTalkWord() = pins.string("service.pref_talk_word", "PREF_TALK_WORD", "turn_talk_word")

    @Test fun prefWakeWord() = pins.string("service.pref_wake_word", "PREF_WAKE_WORD", "realtime_wake_word")

    @Test fun prefWakeMicGain() = pins.string("service.pref_wake_mic_gain", "PREF_WAKE_MIC_GAIN", "wake_word_mic_gain")

    @Test fun prefTalkSilenceSensitivity() =
        pins.string("service.pref_talk_silence_sensitivity", "PREF_TALK_SILENCE_SENSITIVITY", "talk_silence_sensitivity")

    @Test fun prefServerUrl() = pins.string("service.pref_server_url", "PREF_SERVER_URL", "server_url")

    @Test fun prefButtonTrigger() = pins.string("service.pref_button_trigger_enabled", "PREF_BUTTON_TRIGGER_ENABLED", "button_trigger_enabled")

    @Test fun micStalledNotificationText() = pins.string("service.mic_stalled_text", "MIC_STALLED_TEXT", "Wake word stalled — mic held by another app")

    @Test fun defaultTalkWord() = pins.string("settings.default_talk_word", "DEFAULT_TALK_WORD", "my friend")

    @Test fun defaultWakeWord() = pins.string("settings.default_wake_word", "DEFAULT_WAKE_WORD", "wake up")

    /** `b586e4b`: STREAM_NOTIFICATION is muted during call audio on the A300M; DND total silence (inv04 §6.4). */
    @Test fun cuesPlayOnStreamMusic() = pins.string("cue.reconnect_beep_stream", "CUE_STREAM", "MUSIC")
}
