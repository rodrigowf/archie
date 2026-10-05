package com.assistant.core.voicehost.parity

import com.assistant.core.testing.ArchieRoot
import com.assistant.core.testing.OldConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards `tools/parity/old_constants.json` itself (runs now): it was extracted from commit `e871d05`,
 * cites existing files, and its scalar values equal the literals of inv04 §4. If this fails, rerun
 * `tools/parity/extract_old_constants.py` and review the drift — never edit the JSON by hand.
 */
class OldConstantsSnapshotTest {

    @Test
    fun extractedFromTheInventoryBaselineCommit() = assertEquals("e871d05", OldConstants.sourceCommit)

    @Test
    fun everyRowCitesAnExistingOldFile() {
        val repo = ArchieRoot.dir.parentFile
        val bad = OldConstants.all.filter { it.lines.isEmpty() || !File(repo, it.file).isFile }.map { "${it.id} → ${it.file}" }
        assertTrue("rows with a missing file or no lines: $bad", bad.isEmpty())
    }

    @Test
    fun idsAreUnique() {
        val dups = OldConstants.all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate ids: $dups", dups.isEmpty())
    }

    /** The inv04 §4 literals, row by row. */
    @Test
    fun scalarValuesMatchTheInventory() {
        val expected: Map<String, Any> = mapOf(
            "wake.sample_rate_hz" to 16000, "wake.rms_threshold" to 70.0, "wake.activity_hold_ms" to 30,
            "wake.pre_buffer_ms" to 500, "wake.monitor_buffer_min_bytes" to 3200, "wake.mic_retry_ms" to 500,
            "wake.mic_retry_warn_threshold" to 8, "wake.post_wakeword_delay_ms" to 3000,
            "wake.post_recognition_base_ms" to 1000, "wake.post_recognition_max_ms" to 30000,
            "wake.post_vosk_nomatch_ms" to 500, "wake.speech_floor_rms" to 30.0,
            "wake.command_abs_speech_floor" to 30.0, "wake.adaptive_voice_floor_min" to 30.0,
            "wake.silence_floor_attack" to 0.30, "wake.silence_floor_release" to 0.02,
            "wake.default_talk_silence_sensitivity" to 2.0, "wake.onset_sustain_ms" to 300,
            "wake.command_silence_ms" to 1000, "wake.command_max_ms" to 12000,
            "wake.command_speech_onset_timeout_ms" to 4000, "wake.capture_frame_samples" to 3200,
            "vosk.recognition_timeout_ms" to 5000, "vosk.rms_started_threshold" to 30.0, "vosk.read_samples" to 6400,
            "vosk.match_tail_ms" to 400, "vosk.max_confirm_window_ms" to 2000, "vosk.min_prefix_words" to 2,
            "vosk.model_asset_root" to "vosk-model-small-en-us-0.15", "vosk.model_extract_dir" to "vosk-model",
            "vosk.model_stamp" to "vosk-model-small-en-us-0.15", "vosk.android_version" to "0.3.47",
            "sr.hang_watchdog_ms" to 10000, "sr.refresh_after_n" to 20, "sr.refresh_no_speech_spike" to 2,
            "sr.no_speech_health_threshold" to 8, "sr.client_error_delay_ms" to 1000, "sr.no_speech_error_code" to 6,
            "whisper.timeout_ms" to 10000, "whisper.model" to "whisper-1", "whisper.temperature" to "0",
            "whisper.language" to "en", "whisper.response_format" to "json",
            "service.wake_start_dedupe_window_ms" to 3000, "service.screen_rearm_debounce_ms" to 300,
            "service.foreground_wake_lock_ms" to 3000, "service.recents_long_press_ms" to 600,
            "service.prefs_name" to "assistant_service_prefs", "service.pref_enabled" to "wake_word_enabled",
            "service.pref_talk_word" to "turn_talk_word", "service.pref_wake_word" to "realtime_wake_word",
            "service.pref_wake_mic_gain" to "wake_word_mic_gain", "service.pref_talk_silence_sensitivity" to "talk_silence_sensitivity",
            "service.pref_server_url" to "server_url", "service.pref_button_trigger_enabled" to "button_trigger_enabled",
            "settings.default_talk_word" to "my friend", "settings.default_wake_word" to "wake up",
            "session.ending_ack_timeout_ms" to 5000, "session.mic_release_delay_ms" to 1500,
            "session.wake_word_ack_timeout_ms" to 2000, "session.route_reapply_delays_ms" to listOf(1000, 3000, 5000),
            "session.call_volume_raise_fraction" to 0.75, "session.default_mic_gain" to 1.0, "session.mic_gain_max" to 2.0,
            "session.default_echo_ducking_gain" to 0.05, "session.echo_ducking_gain_max" to 1.0,
            "session.pool_probe_retry_ms" to 400,
            // inv04 §4.6 says 0/500/2000; the old code computes 0/500/1000 (`500L shl (attempt - 1)`, cap 3).
            "session.recovery_backoff_ms" to listOf(0, 500, 1000),
            "network.ws_ping_interval_ms" to 30000, "network.ws_reconnect_delay_ms" to 3000,
            "voice.default_provider" to "openai", "voice.default_model" to "gpt-realtime", "voice.default_voice" to "cedar",
            "voice.default_sample_rate_hz" to 24000, "voice.ws_default_sample_rate_hz" to 24000,
            "audio.mic_chunk_frames" to 480, "audio.agent_speech_stale_ms" to 800, "audio.hal_settle_ms" to 200,
            "audio.full_buffer_retry_ms" to 10, "audio.mic_source_switch_sdk" to 24,
            "duck.restore_tail_ms" to 1000, "duck.drain_poll_ms" to 80, "duck.writes_quiet_ms" to 400,
            "webrtc.connection_timeout_ms" to 15000, "webrtc.restore_delay_ms" to 2000, "webrtc.data_channel_label" to "oai-events",
        )
        val wrong = expected.mapNotNull { (id, v) ->
            val actual = OldConstants[id].value.toString().trim('"')
            val want = when (v) {
                is List<*> -> v.joinToString(",", "[", "]")
                is Double -> v.toString()
                else -> v.toString()
            }
            val norm = if (v is List<*>) actual.replace(" ", "") else actual
            if (norm == want || norm.toDoubleOrNull()?.equals((v as? Number)?.toDouble()) == true) null else "$id: json=$actual inventory=$want"
        }
        assertTrue("old_constants.json differs from inv04 §4:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun theWhisperBoilerplateSetIsComplete() {
        assertEquals(12, (OldConstants["whisper.boilerplate"].value as kotlinx.serialization.json.JsonArray).size)
    }
}
