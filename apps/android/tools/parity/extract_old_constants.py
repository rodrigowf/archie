#!/usr/bin/env python3
"""Extract the OLD Android app's tuned voice constants into old_constants.json (spec 14 §6.2, A-04).

Every row of inventory 04 §4 marked **LB** or *wire* (plus a few plumb/UX rows that the new code
also names) is read from the old app AT A GIVEN GIT COMMIT (default e871d05, the inventory's
baseline), not from the working tree. Each row cites file:line(s); a regex must match every cited
line, otherwise the script fails — that is how a drifted citation is caught. Scalar rows carry
the parsed value and the name of the NEW Tuning constant that must equal it
(`OldConstantsCrossCheckTest`); behavioural rows carry a descriptive value and are pinned by
behaviour tests (`@PinsConstant`).

Usage (from android/):
    python3 tools/parity/extract_old_constants.py            # regenerate tools/parity/old_constants.json
    python3 tools/parity/extract_old_constants.py --check    # fail if the checked-in JSON is stale
    python3 tools/parity/extract_old_constants.py --rev HEAD # read another commit
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_REV = "e871d05"
HERE = Path(__file__).resolve().parent
ANDROID_NEXT = HERE.parent.parent
REPO = ANDROID_NEXT.parent.parent
OUT = HERE / "old_constants.json"

# Paths at the baseline rev (git show); the old app now lives under legacy/ (2026-10 cutover), which
# is where the snapshot's "file" fields point so OldConstantsSnapshotTest can open them.
OLD_TREE = "legacy/"
P = "android/app/src/main/java/com/assistant/peripheral/"
WWD = P + "voice/WakeWordDetector.kt"
RE = P + "voice/VoskRecognitionEngine.kt"
WE = P + "voice/VoskWakeWordEngine.kt"
ML = P + "voice/VoskModelLoader.kt"
SR = P + "voice/SpeechRecognizerEngine.kt"
WC = P + "voice/WhisperConfirmer.kt"
AS = P + "service/AssistantService.kt"
BAS = P + "service/ButtonAccessibilityService.kt"
VC = P + "voice/VoiceController.kt"
VM = P + "voice/VoiceManager.kt"
VP = P + "voice/VoiceProvider.kt"
MC = P + "voice/MicCapture.kt"
PB = P + "voice/PcmPlayback.kt"
WSPP = P + "voice/WebSocketPcmProvider.kt"
ED = P + "voice/EchoDuckController.kt"
OAI = P + "voice/OpenAIVoiceProvider.kt"
AR = P + "voice/AudioRouter.kt"
AVM = P + "viewmodel/AssistantViewModel.kt"
MODELS = P + "data/Models.kt"
API = P + "network/ApiClient.kt"
WSM = P + "network/WebSocketManager.kt"
OCC = P + "connection/OrchestratorConnectionController.kt"
GRADLE = "android/app/build.gradle.kts"

AUDIO = "com.assistant.core.audio.AudioTuning#"
VOICE = "com.assistant.core.voice.VoiceTuning#"
WAKE = "com.assistant.core.wakeword.WakeTuning#"
HOST = "com.assistant.core.voicehost.HostTuning#"
SESSION = "com.assistant.core.session.SessionTuning#"
NETWORK = "com.assistant.core.network.NetworkTuning#"


def row(id, module, label, file, lines, regex, type, new=None, value=None, note=None, alias=None, superseded=None):
    """One constant. `regex` is applied to every cited line (str) or per line (list).
    For scalar types the first capture group of the FIRST line is the value unless `value` is given.
    `superseded` = the spec rule that intentionally replaces the old behaviour: the extracted value
    stays the OLD one, and the new-vs-old cross-check skips the row (the new value is pinned by its
    own test instead)."""
    return dict(id=id, module=module, label=label, file=file, lines=lines, regex=regex,
                type=type, new=new, value=value, note=note, alias=alias, superseded=superseded)


ROWS = [
    # ---- inv04 §4.1 wake-word detector ------------------------------------------------------
    row("wake.sample_rate_hz", "wakeword", "wire", WWD, [211], r"SAMPLE_RATE = (\d+)", "int", WAKE + "SAMPLE_RATE_HZ"),
    row("wake.rms_threshold", "wakeword", "LB", WWD, [239], r"RMS_THRESHOLD = ([\d.]+)", "double", WAKE + "RMS_THRESHOLD"),
    row("wake.activity_hold_ms", "wakeword", "LB", WWD, [243], r"ACTIVITY_HOLD_MS = ([\d_]+)L", "long", WAKE + "ACTIVITY_HOLD_MS"),
    row("wake.pre_buffer_ms", "wakeword", "LB", WWD, [397], r"PRE_BUFFER_MS = (\d+)", "int", WAKE + "PRE_BUFFER_MS"),
    row("wake.monitor_buffer_min_bytes", "wakeword", "LB", WWD, [648], r"coerceAtLeast\((\d+)\)", "int", WAKE + "MONITOR_BUFFER_MIN_BYTES"),
    row("wake.mic_retry_ms", "wakeword", "LB", WWD, [688, 702, 723], r"delay\((\d+)L\)", "long", WAKE + "MIC_RETRY_MS"),
    row("wake.mic_retry_warn_threshold", "wakeword", "LB", WWD, [377], r"MIC_RETRY_WARN_THRESHOLD = (\d+)", "int", WAKE + "MIC_RETRY_WARN_THRESHOLD"),
    row("wake.post_wakeword_delay_ms", "wakeword", "LB", WWD, [261], r"POST_WAKEWORD_DELAY_MS = ([\d_]+)L", "long", WAKE + "POST_WAKEWORD_DELAY_MS"),
    row("wake.post_recognition_base_ms", "wakeword", "LB", WWD, [269], r"POST_RECOGNITION_BASE_MS = ([\d_]+)L", "long", WAKE + "POST_RECOGNITION_BASE_MS"),
    row("wake.post_recognition_max_ms", "wakeword", "LB", WWD, [270], r"POST_RECOGNITION_MAX_MS = ([\d_]+)L", "long", WAKE + "POST_RECOGNITION_MAX_MS"),
    row("wake.post_vosk_nomatch_ms", "wakeword", "LB", WWD, [296], r"POST_VOSK_NOMATCH_MS = ([\d_]+)L", "long", WAKE + "POST_VOSK_NOMATCH_MS"),
    row("wake.speech_floor_rms", "wakeword", "LB", WWD, [258], r"SPEECH_FLOOR_RMS = ([\d.]+)", "double", WAKE + "SPEECH_FLOOR_RMS"),
    row("wake.command_abs_speech_floor", "wakeword", "LB", WWD, [137], r"COMMAND_ABS_SPEECH_FLOOR = ([\d.]+)", "double", WAKE + "COMMAND_ABS_SPEECH_FLOOR"),
    row("wake.adaptive_voice_floor_min", "wakeword", "LB", WWD, [182], r"ADAPTIVE_VOICE_FLOOR_MIN = (COMMAND_ABS_SPEECH_FLOOR)", "double",
        WAKE + "ADAPTIVE_VOICE_FLOOR_MIN", alias="wake.command_abs_speech_floor"),
    row("wake.silence_floor_attack", "wakeword", "LB", WWD, [159], r"SILENCE_FLOOR_ATTACK = ([\d.]+)", "double", WAKE + "SILENCE_FLOOR_ATTACK"),
    row("wake.silence_floor_release", "wakeword", "LB", WWD, [160], r"SILENCE_FLOOR_RELEASE = ([\d.]+)", "double", WAKE + "SILENCE_FLOOR_RELEASE"),
    row("wake.default_talk_silence_sensitivity", "wakeword", "LB", WWD, [174], r"DEFAULT_TALK_SILENCE_SENSITIVITY = ([\d.]+)", "double",
        WAKE + "DEFAULT_TALK_SILENCE_SENSITIVITY"),
    row("wake.seed_floor_min_preroll", "wakeword", "LB", WWD, [1205, 1207], [r"computeRms\(it, it\.size\)", r"\.minOrNull\(\)"], "behaviour",
        value="seed = min non-zero per-frame RMS of the wake-phrase pre-roll, else unseeded (-1)"),
    row("wake.onset_sustain_ms", "wakeword", "LB", WWD, [192], r"ONSET_SUSTAIN_MS = ([\d_]+)L", "long", WAKE + "ONSET_SUSTAIN_MS"),
    row("wake.command_silence_ms", "wakeword", "LB", WWD, [123], r"COMMAND_SILENCE_MS = ([\d_]+)L", "long", WAKE + "COMMAND_SILENCE_MS"),
    row("wake.command_max_ms", "wakeword", "LB", WWD, [200], r"COMMAND_MAX_MS = ([\d_]+)L", "long", WAKE + "COMMAND_MAX_MS"),
    row("wake.command_speech_onset_timeout_ms", "wakeword", "LB", WWD, [209], r"COMMAND_SPEECH_ONSET_TIMEOUT_MS = ([\d_]+)L", "long",
        WAKE + "COMMAND_SPEECH_ONSET_TIMEOUT_MS"),
    row("wake.capture_frame_samples", "wakeword", "LB", WWD, [1211], r"ShortArray\((\d+)\)", "int", WAKE + "CAPTURE_FRAME_SAMPLES"),
    row("wake.mic_source_by_sdk", "wakeword", "LB", WWD, [656, 657, 659],
        [r"SDK_INT < Build\.VERSION_CODES\.N", r"AudioSource\.VOICE_RECOGNITION", r"AudioSource\.VOICE_COMMUNICATION"], "behaviour",
        value="VOICE_RECOGNITION below API 24, VOICE_COMMUNICATION from 24 (same as the call)"),
    row("wake.variant_parse", "wakeword", "LB", WWD, [307, 468, 470], [r"lowercase\(\)\.trim\(\)", r'split\(","\)', r"\.distinct\(\)"], "behaviour",
        value="comma-split, trim, drop empty, lowercase, distinct; no phonetic substitutions"),

    # ---- inv04 §4.2 Vosk ---------------------------------------------------------------------
    row("vosk.recognition_timeout_ms", "wakeword", "LB", RE, [54], r"VOSK_RECOGNITION_TIMEOUT_MS = ([\d_]+)L", "long", WAKE + "VOSK_RECOGNITION_TIMEOUT_MS"),
    row("vosk.rms_started_threshold", "wakeword", "LB", RE, [62], r"RMS_STARTED_THRESHOLD = ([\d.]+)", "double", WAKE + "VOSK_RMS_STARTED_THRESHOLD"),
    row("vosk.read_samples", "wakeword", "LB", RE, [171], r"ShortArray\((\d+)\)", "int", WAKE + "VOSK_READ_SAMPLES"),
    row("vosk.match_tail_ms", "wakeword", "LB", RE, [70], r"MATCH_TAIL_MS = ([\d_]+)L", "long", WAKE + "MATCH_TAIL_MS"),
    row("vosk.max_confirm_window_ms", "wakeword", "LB", RE, [78], r"MAX_CONFIRM_WINDOW_MS = ([\d_]+)L", "long", WAKE + "MAX_CONFIRM_WINDOW_MS"),
    row("vosk.min_prefix_words", "wakeword", "LB", WE, [132], r"MIN_PREFIX_WORDS = (\d+)", "int", WAKE + "MIN_PREFIX_WORDS"),
    row("vosk.grammar_unk", "wakeword", "LB", WE, [245], r'\.distinct\(\) \+ "(\[unk\])"', "behaviour",
        value='JSON array of distinct (talk + wake) phrases followed by "[unk]"'),
    row("vosk.match_precedence", "wakeword", "LB", WE, [173, 176], [r"wakeVariants\.firstOrNull \{ lower\.contains", r"talkVariants\.firstOrNull \{ lower\.contains"],
        "behaviour", value="wake (realtime) variants before talk variants; substring contains on lowercased text"),
    row("vosk.reset_after_match", "wakeword", "LB", WE, [91], r"recognizer\.reset\(\)", "behaviour", value="recognizer.reset() after every match"),
    row("vosk.fresh_recognizer_per_cycle", "wakeword", "LB", RE, [130, 232], [r"VoskWakeWordEngine\(", r"closeFeeder\(\)"], "behaviour",
        value="new Recognizer per recognition cycle, closed at the end of the cycle"),
    row("vosk.model_asset_root", "wakeword", "wire", ML, [42], r'ASSET_ROOT = "([^"]+)"', "string", WAKE + "VOSK_MODEL_ASSET_ROOT"),
    row("vosk.model_extract_dir", "wakeword", "wire", ML, [45], r'EXTRACT_DIRNAME = "([^"]+)"', "string", WAKE + "VOSK_MODEL_DIR"),
    row("vosk.model_stamp", "wakeword", "wire", ML, [52], r'MODEL_STAMP = "([^"]+)"', "string", WAKE + "VOSK_MODEL_STAMP"),
    row("vosk.stamp_file", "wakeword", "wire", ML, [216], r'File\(targetDir, "(\.stamp)"\)', "string", WAKE + "VOSK_STAMP_FILE"),
    row("vosk.no_compress", "wakeword", "LB", GRADLE, [46], r'noCompress \+= "(vosk-model-small-en-us-0\.15)"', "string",
        note="the model directory must be stored uncompressed (Model(path) needs raw files)"),
    row("vosk.android_version", "wakeword", "LB", GRADLE, [122], r'com\.alphacephei:vosk-android:([\d.]+)"', "string", "catalog#vosk",
        note="patched libvosk.so must match the AAR version"),

    # ---- inv04 §4.3 SpeechRecognizer fallback ------------------------------------------------
    row("sr.hang_watchdog_ms", "wakeword", "LB", SR, [63], r"RECOGNIZER_HANG_WATCHDOG_MS = ([\d_]+)L", "long", WAKE + "SR_HANG_WATCHDOG_MS"),
    row("sr.refresh_after_n", "wakeword", "LB", SR, [66], r"RECOGNIZER_REFRESH_AFTER_N = (\d+)", "int", WAKE + "SR_REFRESH_AFTER_N"),
    row("sr.refresh_no_speech_spike", "wakeword", "LB", SR, [69], r"RECOGNIZER_REFRESH_NO_SPEECH_SPIKE = (\d+)", "int", WAKE + "SR_REFRESH_NO_SPEECH_SPIKE"),
    row("sr.no_speech_health_threshold", "wakeword", "LB", SR, [73], r"NO_SPEECH_HEALTH_THRESHOLD = (\d+)", "int", WAKE + "SR_NO_SPEECH_HEALTH_THRESHOLD"),
    row("sr.client_error_delay_ms", "wakeword", "LB", SR, [76], r"CLIENT_ERROR_DELAY_MS = ([\d_]+)L", "long", WAKE + "SR_CLIENT_ERROR_DELAY_MS"),
    row("sr.no_speech_error_code", "wakeword", "LB", SR, [400], r"error == (\d+) /\* ERROR_NO_SPEECH", "int", WAKE + "SR_ERROR_NO_SPEECH"),
    row("sr.recognizer_extras", "wakeword", "LB", SR, list(range(320, 331)), r"putExtra\(", "behaviour",
        value={"EXTRA_LANGUAGE_MODEL": "free_form", "EXTRA_CALLING_PACKAGE": "<packageName>", "EXTRA_MAX_RESULTS": 5,
               "EXTRA_PARTIAL_RESULTS": True, "EXTRA_LANGUAGE": "en-US", "EXTRA_LANGUAGE_PREFERENCE": "en-US",
               "EXTRA_PREFER_OFFLINE": True, "android.speech.extra.DICTATION_MODE": True,
               "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS": 200, "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS": 1500,
               "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS": 1000}),
    row("sr.beep_streams", "wakeword", "LB", SR, [91, 92, 93, 94],
        [r"STREAM_RING", r"STREAM_NOTIFICATION", r"STREAM_SYSTEM", r"STREAM_MUSIC"], "behaviour", value=["RING", "NOTIFICATION", "SYSTEM", "MUSIC"]),
    row("sr.beep_mute_method", "wakeword", "LB", SR, [293, 296], [r"ADJUST_MUTE", r"setStreamMute\(s, true\)"], "behaviour",
        value="adjustStreamVolume(ADJUST_MUTE) on API 23+, setStreamMute(true) below"),
    row("sr.audio_mode_single_owner", "wakeword", "LB", SR, [147, 213, 249, 285],
        [r"weChangedAudioMode = false", r"weChangedAudioMode = true", r"weChangedAudioMode = false", r"weChangedAudioMode = false"], "behaviour",
        value="revert MODE_IN_COMMUNICATION only if this engine set it; a match hands ownership to the call"),

    # ---- inv04 §4.4 Whisper gate -------------------------------------------------------------
    row("whisper.timeout_ms", "wakeword", "LB", WC, [54], r"timeoutMs: Long = ([\d_]+)L", "long", WAKE + "WHISPER_TIMEOUT_MS"),
    row("whisper.model", "wakeword", "LB", WC, [222], r'WHISPER_MODEL = "([^"]+)"', "string", WAKE + "WHISPER_MODEL"),
    row("whisper.temperature", "wakeword", "LB", WC, [133], r'addFormDataPart\("temperature", "([^"]+)"\)', "string", WAKE + "WHISPER_TEMPERATURE"),
    row("whisper.language", "wakeword", "LB", WC, [127], r'addFormDataPart\("language", "([^"]+)"\)', "string", WAKE + "WHISPER_LANGUAGE"),
    row("whisper.response_format", "wakeword", "LB", WC, [123], r'addFormDataPart\("response_format", "([^"]+)"\)', "string", WAKE + "WHISPER_RESPONSE_FORMAT"),
    row("whisper.upload_filename", "wakeword", "wire", WC, [119], r'"file", "([^"]+)"', "string", WAKE + "WHISPER_UPLOAD_FILENAME"),
    row("whisper.endpoint", "wakeword", "wire", WC, [218], r'"(https://api\.openai\.com/v1/audio/transcriptions)"', "string", WAKE + "WHISPER_TRANSCRIPTIONS_URL"),
    row("whisper.normalize", "wakeword", "LB", WC, [216], r'replace\(Regex\("\[\^a-z0-9\\\\s\]"\), " "\)', "behaviour",
        value="lowercase, non-[a-z0-9\\s] -> space, collapse whitespace, trim"),
    row("whisper.boilerplate", "wakeword", "LB", WC, list(range(233, 245)), r'^\s+"([^"]+)",', "string_set", WAKE + "WHISPER_HALLUCINATION_BOILERPLATE"),
    row("whisper.fail_closed", "wakeword", "LB", WC, [84, 141, 145],
        [r"withTimeoutOrNull\(timeoutMs\)", r"response\.code == 401", r"cachedKey = null"], "behaviour",
        value="timeout / error / no key reject; HTTP 401 clears the cached key"),

    # ---- inv04 §4.5 service / triggers -------------------------------------------------------
    row("service.wake_start_dedupe_window_ms", "voice-host", "LB", AS, [122], r"WAKE_START_DEDUPE_WINDOW_MS = ([\d_]+)L", "long", HOST + "WAKE_START_DEDUPE_WINDOW_MS"),
    row("service.dedupe_strict_lt", "voice-host", "LB", AS, [143], r"\(nowMs - lastAtMs\) < WAKE_START_DEDUPE_WINDOW_MS", "behaviour",
        value="strict < on elapsedRealtime"),
    row("service.dedupe_key", "voice-host", "LB", AS, [584], r"Triple\(talkWord, wakeWord, micGain\)", "behaviour",
        value="key = (talk, wake, gain); serverUrl and talkSilenceSensitivity are NOT part of it"),
    row("service.screen_rearm_debounce_ms", "voice-host", "LB", AS, [361], r"postDelayed\(rearmRunnable, (\d+)\)", "long", HOST + "SCREEN_REARM_DEBOUNCE_MS"),
    row("service.foreground_wake_lock_ms", "voice-host", "LB", AS, [212], r"wl\.acquire\((\d+)L\)", "long", HOST + "FOREGROUND_WAKE_LOCK_MS"),
    row("service.recents_long_press_ms", "voice-host", "UX", AS, [625], r"LONG_PRESS_MS = (\d+)L", "long", HOST + "RECENTS_LONG_PRESS_MS"),
    row("service.accessibility_long_press_ms", "voice-host", "UX", BAS, [23], r"LONG_PRESS_MS = (\d+)L", "long", HOST + "RECENTS_LONG_PRESS_MS"),
    row("service.notification_id", "voice-host", "plumb", AS, [43], r"NOTIFICATION_ID = (\d+)", "int", HOST + "NOTIFICATION_ID"),
    row("service.notification_channel", "voice-host", "plumb", AS, [44], r'CHANNEL_ID = "([^"]+)"', "string", HOST + "NOTIFICATION_CHANNEL_ID"),
    row("service.prefs_name", "voice-host", "wire", AS, [99], r'PREFS_NAME = "([^"]+)"', "string", HOST + "PREFS_NAME"),
    row("service.pref_enabled", "voice-host", "wire", AS, [100], r'PREF_ENABLED = "([^"]+)"', "string", HOST + "PREF_ENABLED"),
    row("service.pref_talk_word", "voice-host", "wire", AS, [101], r'PREF_TALK_WORD = "([^"]+)"', "string", HOST + "PREF_TALK_WORD"),
    row("service.pref_wake_word", "voice-host", "wire", AS, [102], r'PREF_WAKE_WORD = "([^"]+)"', "string", HOST + "PREF_WAKE_WORD"),
    row("service.pref_wake_mic_gain", "voice-host", "wire", AS, [103], r'PREF_WAKE_MIC_GAIN = "([^"]+)"', "string", HOST + "PREF_WAKE_MIC_GAIN"),
    row("service.pref_talk_silence_sensitivity", "voice-host", "wire", AS, [104], r'PREF_TALK_SILENCE_SENSITIVITY = "([^"]+)"', "string",
        HOST + "PREF_TALK_SILENCE_SENSITIVITY"),
    row("service.pref_server_url", "voice-host", "wire", AS, [105], r'PREF_SERVER_URL = "([^"]+)"', "string", HOST + "PREF_SERVER_URL"),
    row("service.pref_button_trigger_enabled", "voice-host", "wire", BAS, [46], r'getBoolean\("([^"]+)", false\)', "string", HOST + "PREF_BUTTON_TRIGGER_ENABLED"),
    row("service.mic_stalled_text", "voice-host", "UX", AS, [714], r'"(Wake word stalled — mic held by another app)"', "string", HOST + "MIC_STALLED_TEXT"),
    row("settings.default_talk_word", "voice-host", "UX", MODELS, [425], r'talkWord: String = "([^"]+)"', "string", HOST + "DEFAULT_TALK_WORD"),
    row("settings.default_wake_word", "voice-host", "UX", MODELS, [426], r'wakeWord: String = "([^"]+)"', "string", HOST + "DEFAULT_WAKE_WORD"),
    row("cue.reconnect_beep_stream", "voice-host", "LB", AVM, [601], r"AudioManager\.(STREAM_MUSIC)", "string", HOST + "CUE_STREAM",
        value="MUSIC", note="STREAM_NOTIFICATION is muted during call audio on the A300M (b586e4b)"),
    row("cue.wake_ack_tones", "voice-host", "UX", AVM, [494], r"playTones\(listOf\(660\.0, 880\.0\), toneMs = 90, gapMs = 30, amplitude = 0\.45\)", "behaviour",
        value="660 Hz then 880 Hz, 90 ms each, 30 ms gap, amplitude 0.45"),
    row("cue.talk_ack_tone", "voice-host", "UX", AVM, [503], r"playTones\(listOf\(440\.0\), toneMs = 150", "behaviour", value="440 Hz, 150 ms"),

    # ---- inv04 §4.6 voice session ------------------------------------------------------------
    row("session.ending_ack_timeout_ms", "voice", "LB", VC, [107], r"ENDING_ACK_TIMEOUT_MS = ([\d_]+)L", "long", VOICE + "ENDING_ACK_TIMEOUT_MS"),
    row("session.mic_release_delay_ms", "voice", "LB", VC, [109], r"MIC_RELEASE_DELAY_MS = ([\d_]+)L", "long", VOICE + "MIC_RELEASE_DELAY_MS"),
    row("session.wake_word_ack_timeout_ms", "voice", "plumb", VC, [111], r"WAKE_WORD_ACK_TIMEOUT_MS = ([\d_]+)L", "long", VOICE + "WAKE_WORD_ACK_TIMEOUT_MS"),
    row("session.route_reapply_delays_ms", "voice", "LB", VM, [356], r"longArrayOf\(([\dL, ]+)\)", "long_list", VOICE + "ROUTE_REAPPLY_DELAYS_MS",
        note="SEQUENTIAL delays (delay(1000); delay(3000); delay(5000)) => re-applies at +1 s, +4 s, +9 s after focus; "
             "the inventory's '+1/+3/+5 s' describes the literals, not the schedule"),
    row("session.audio_focus", "voice", "LB", VM, [491, 494, 495, 504, 505],
        [r"AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE", r"USAGE_VOICE_COMMUNICATION", r"CONTENT_TYPE_SPEECH", r"STREAM_VOICE_CALL", r"AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"],
        "behaviour", value="GAIN_TRANSIENT_EXCLUSIVE; USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH via AudioFocusRequest on O+; STREAM_VOICE_CALL pre-O"),
    row("session.call_volume_raise_fraction", "audio", "LB", VM, [524], r"\(maxVoice \* ([\d.]+)\)\.toInt\(\)\.coerceAtLeast\(1\)", "double", AUDIO + "CALL_VOLUME_RAISE_FRACTION"),
    row("session.default_mic_gain", "audio", "LB", VM, [74], r"pendingMicGain: Float = ([\d.]+)f", "float", AUDIO + "DEFAULT_MIC_GAIN"),
    row("session.mic_gain_max", "audio", "LB", VM, [453], r"coerceIn\(0\.0f, ([\d.]+)f\)", "float", AUDIO + "MIC_GAIN_MAX"),
    row("session.default_echo_ducking_gain", "audio", "LB", VM, [75], r"pendingEchoDuckingGain: Float = ([\d.]+)f", "float", AUDIO + "DEFAULT_ECHO_DUCKING_GAIN"),
    row("session.echo_ducking_gain_max", "audio", "LB", VM, [460], r"coerceIn\(0\.0f, ([\d.]+)f\)", "float", AUDIO + "ECHO_DUCKING_GAIN_MAX"),
    row("session.pool_probe_retry_ms", "session", "LB", OCC, [85], r"POOL_PROBE_RETRY_MS = ([\d_]+)L", "long", SESSION + "POOL_PROBE_RETRY_MS"),
    row("session.recovery_backoff_ms", "session", "LB", OCC, [82, 267], [r"MAX_RECOVERY_RETRIES = (3)", r"delay\(500L shl \(attempt - 1\)\)"], "long_list",
        SESSION + "RECOVERY_BACKOFF_MS", value=[0, 500, 1000],
        note="attempt 0: no delay; attempt n>0: 500 shl (n-1) => 0/500/1000 with the cap of 3 (the code comment and inv04 say 2000)"),
    row("network.ws_ping_interval_ms", "network", "LB", WSM, [47], r"PING_INTERVAL_MS = ([\d_]+)L", "long", NETWORK + "WS_PING_INTERVAL_MS"),
    row("network.ws_reconnect_delay_ms", "network", "LB", WSM, [42], r"RECONNECT_DELAY_MS = ([\d_]+)L", "long", NETWORK + "WS_RECONNECT_BASE_DELAY_MS",
        superseded="spec 12 T-13 (A-3.3)",
        note="superseded by spec 12 T-13 (A-3.3): exponential min(15 s, 1 s x 2^attempt) +/-20 % jitter, reset on session_started; "
             "the LB label of this inv04 row (f77cd62) belongs to the 30 s ping, which is kept"),
    row("protocol.voice_initiator_default", "protocol", "wire", WSM, [342], r'optBoolean\("voice_initiator", (false)\)', "behaviour", value=False),
    row("voice.default_provider", "voice", "wire", VP, [75], r'provider = "([^"]+)"', "string", VOICE + "DEFAULT_PROVIDER"),
    row("voice.default_model", "voice", "wire", VP, [76], r'model = "([^"]+)"', "string", VOICE + "DEFAULT_MODEL"),
    row("voice.default_voice", "voice", "wire", VP, [77], r'voice = "([^"]+)"', "string", VOICE + "DEFAULT_VOICE"),
    row("voice.default_sample_rate_hz", "voice", "wire", API, [443, 445], r'optInt\("sample_rate", (\d+)\)', "int", VOICE + "DEFAULT_SAMPLE_RATE_HZ"),
    row("voice.ws_default_sample_rate_hz", "voice", "wire", WSPP, [118, 119], r"SampleRate: Int = (\d+)", "int", VOICE + "DEFAULT_SAMPLE_RATE_HZ"),
    row("voice.default_sdp_endpoint", "voice", "wire", OAI, [117], r'sdpEndpoint: String = "([^"]+)"', "string", VOICE + "DEFAULT_SDP_ENDPOINT"),
    row("voice.provider_selection", "voice", "wire", VM, [468, 469, 470, 478, 479],
        [r'"openai" -> OpenAIVoiceProvider', r'"qwen" -> QwenVoiceProvider', r'"google" -> GeminiVoiceProvider',
         r"WEBRTC -> OpenAIVoiceProvider", r"WEBSOCKET -> QwenVoiceProvider\(context, providerId = providerId\)"], "behaviour",
        value="openai->WebRTC, qwen->Qwen, google->Gemini, unknown: WEBRTC->OpenAI, WEBSOCKET->Qwen parser"),

    # ---- inv04 §4.7 audio I/O ----------------------------------------------------------------
    row("audio.mic_chunk_frames", "audio", "wire", MC, [217], r"MIC_CHUNK_FRAMES = (\d+)", "int", AUDIO + "MIC_CHUNK_FRAMES"),
    row("audio.agent_speech_stale_ms", "audio", "LB", MC, [224], r"AGENT_SPEECH_STALE_MS = ([\d_]+)L", "long", AUDIO + "AGENT_SPEECH_STALE_MS"),
    row("audio.mic_buffer_formula", "audio", "LB", MC, [74], r"max\(minBuf \* 4, inSampleRate \* 2 / 5\)", "behaviour", value="max(minBuf*4, rate*2/5)"),
    row("audio.speaker_buffer_formula", "audio", "LB", PB, [149], r"max\(minBuf \* 4, \(bytesPerSecond \* 1\.5\)\.toInt\(\)\)", "behaviour",
        value="max(minBuf*4, bytesPerSecond*1.5) = 72000 at 24 kHz"),
    row("audio.write_policy", "audio", "LB", PB, [195, 200, 203, 205],
        [r"SDK_INT >= Build\.VERSION_CODES\.M", r"WRITE_NON_BLOCKING", r"t\.write\(data, offset, data\.size - offset\)$", r"catch \(e: Throwable\)"], "behaviour",
        value="WRITE_NON_BLOCKING on M+, blocking 3-arg write on API 21/22, catch Throwable"),
    row("audio.full_buffer_retry_ms", "audio", "plumb", PB, [219], r"delay\((\d+)\)", "long", AUDIO + "PLAYBACK_FULL_RETRY_MS"),
    row("audio.barge_in_flush", "audio", "LB", PB, [113, 114, 115, 119], [r"it\.pause\(\)", r"it\.flush\(\)", r"it\.play\(\)", r"totalFramesWritten = 0L"],
        "behaviour", value="pause -> flush -> play, reset totalFramesWritten"),
    row("audio.legacy_track_streams", "audio", "LB", PB, [339, 340], [r"CALL -> AudioManager\.STREAM_VOICE_CALL", r"MEDIA -> AudioManager\.STREAM_MUSIC"],
        "behaviour", value="API < 23: AudioTrack(stream) with CALL->STREAM_VOICE_CALL, MEDIA->STREAM_MUSIC"),
    row("audio.hal_settle_ms", "audio", "LB", WSPP, [251], r"delay\((\d+)L\)", "long", AUDIO + "HAL_SETTLE_MS"),
    row("audio.mic_before_speaker", "audio", "LB", WSPP, [255, 256], [r"mic!!\.start\(\)", r"playback!!\.start\("], "behaviour", value="mic starts before the speaker"),
    row("audio.mic_source_switch_sdk", "audio", "LB", MC, [79], r"SDK_INT < Build\.VERSION_CODES\.(N)\b", "int", AUDIO + "MIC_SOURCE_SWITCH_SDK", value=24,
        note="Build.VERSION_CODES.N = 24; same switch in WakeWordDetector.kt:656 and OpenAIVoiceProvider.kt:354"),

    # ---- inv04 §4.8 echo duck ----------------------------------------------------------------
    row("duck.restore_tail_ms", "audio", "LB", ED, [301], r"MIC_RESTORE_TAIL_MS = ([\d_]+)L", "long", AUDIO + "MIC_RESTORE_TAIL_MS"),
    row("duck.drain_poll_ms", "audio", "LB", ED, [305], r"MIC_RESTORE_DRAIN_POLL_MS = ([\d_]+)L", "long", AUDIO + "MIC_RESTORE_DRAIN_POLL_MS"),
    row("duck.writes_quiet_ms", "audio", "LB", ED, [310], r"MIC_RESTORE_WRITES_QUIET_MS = ([\d_]+)L", "long", AUDIO + "MIC_RESTORE_WRITES_QUIET_MS"),
    row("duck.no_drain_timeout", "audio", "LB", ED, [216], r"while \(true\) \{", "behaviour", value="the drain loop has no overall timeout (2ccee40)"),
    row("duck.head_stuck_fallback", "audio", "LB", ED, [257], r"writesAreQuiet && nowMs - lastHeadAtMs >= MIC_RESTORE_WRITES_QUIET_MS", "behaviour",
        value="writes quiet AND head unchanged for 400 ms counts as drained"),
    row("duck.unsigned_head", "audio", "LB", ED, [206, 225], r"and 0xFFFFFFFFL", "behaviour", value="head position read as unsigned 32-bit"),

    # ---- inv04 §4.9 OpenAI WebRTC ------------------------------------------------------------
    row("webrtc.connection_timeout_ms", "voice", "plumb", OAI, [58], r"CONNECTION_TIMEOUT_MS = ([\d_]+)L", "long", VOICE + "CONNECTION_TIMEOUT_MS"),
    row("webrtc.restore_delay_ms", "voice", "LB", OAI, [253, 750, 757], r"delayMs(?:: Long)? = (\d+)L", "long", VOICE + "OPENAI_RESTORE_DELAY_MS"),
    row("webrtc.sw_aec_ns_agc", "voice", "LB", OAI, [335, 336, 337],
        [r"setWebRtcBasedAcousticEchoCanceler\(true\)", r"setWebRtcBasedNoiseSuppressor\(true\)", r"setWebRtcBasedAutomaticGainControl\(true\)"],
        "behaviour", value="WebRTC software AEC, NS and AGC enabled"),
    row("webrtc.hw_aec_ns_off", "voice", "LB", OAI, [360, 361], [r"setUseHardwareAcousticEchoCanceler\(false\)", r"setUseHardwareNoiseSuppressor\(false\)"],
        "behaviour", value="hardware AEC and NS disabled"),
    row("webrtc.goog_constraints", "voice", "LB", OAI, list(range(375, 387)), r'KeyValuePair\("(\w+)", "true"\)', "string_list",
        value=None, note="all twelve mandatory audio constraints are \"true\""),
    row("webrtc.initialize_once", "voice", "LB", OAI, [61, 340, 345],
        [r"peerConnectionFactoryInitialized = false", r"if \(!peerConnectionFactoryInitialized\)", r"peerConnectionFactoryInitialized = true"],
        "behaviour", value="PeerConnectionFactory.initialize once per process"),
    row("webrtc.data_channel_label", "voice", "wire", OAI, [471], r'createDataChannel\("([^"]+)"', "string", VOICE + "DATA_CHANNEL_LABEL"),
    row("webrtc.data_channel_ordered", "voice", "wire", OAI, [470], r"ordered = true", "behaviour", value=True),
    row("webrtc.unified_plan_max_bundle", "voice", "plumb", OAI, [395, 396, 467], [r"UNIFIED_PLAN", r"MAXBUNDLE", r"RECV_ONLY"], "behaviour",
        value="unified plan, max-bundle, one send track + one recv-only audio transceiver"),

    # ---- inv04 §4.10 routing -----------------------------------------------------------------
    row("route.system_default_normal_call", "audio", "LB", AR, [117, 126, 380, 381],
        [r"is BluetoothMedia -> SpeakerMode\.MEDIA", r"else -> SpeakerMode\.CALL", r"route is Route\.BluetoothMedia \|\| route is Route\.SystemDefault",
         r"AudioManager\.MODE_NORMAL"], "behaviour",
        value="SystemDefault and BluetoothMedia use MODE_NORMAL; only BluetoothMedia tags MEDIA, everything else (incl. SystemDefault) CALL"),
]


def git_show(rev: str, path: str) -> list[str]:
    out = subprocess.run(["git", "-C", str(REPO), "show", f"{rev}:{path}"], capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"git show {rev}:{path} failed: {out.stderr.strip()}")
    return out.stdout.split("\n")


def parse(type_: str, raw: str):
    s = raw.replace("_", "")
    if type_ in ("int", "long"):
        return int(s.rstrip("L"))
    if type_ in ("double", "float"):
        return float(s.rstrip("f"))
    if type_ == "long_list":
        return [int(x.strip().rstrip("L").replace("_", "")) for x in raw.split(",")]
    return raw


def extract(rev: str) -> dict:
    cache: dict[str, list[str]] = {}
    errors: list[str] = []
    results = []
    by_id = {}
    for r in ROWS:
        lines = cache.setdefault(r["file"], git_show(rev, r["file"]))
        regexes = r["regex"] if isinstance(r["regex"], list) else [r["regex"]] * len(r["lines"])
        if len(regexes) != len(r["lines"]):
            errors.append(f"{r['id']}: {len(regexes)} regexes for {len(r['lines'])} lines")
            continue
        captures = []
        for ln, rx in zip(r["lines"], regexes):
            text = lines[ln - 1] if 0 < ln <= len(lines) else ""
            m = re.search(rx, text)
            if not m:
                errors.append(f"{r['id']}: {r['file']}:{ln} does not match /{rx}/ — got: {text.strip()!r}")
                break
            captures.append(m.group(1) if m.groups() else m.group(0))
        else:
            if r["alias"]:
                value = by_id[r["alias"]]["value"]
            elif r["value"] is not None:
                value = r["value"]
            elif r["type"] in ("string_set", "string_list"):
                value = captures
            elif r["type"] == "behaviour":
                value = captures[0]
            else:
                value = parse(r["type"], captures[0])
                for c in captures[1:]:  # multi-line scalar rows must agree on every line
                    if parse(r["type"], c) != value:
                        errors.append(f"{r['id']}: lines disagree ({captures})")
            entry = {
                "id": r["id"], "module": r["module"], "label": r["label"],
                "file": OLD_TREE + r["file"], "lines": r["lines"], "type": r["type"], "value": value,
                "new": r["new"],
            }
            if r["note"]:
                entry["note"] = r["note"]
            if r["superseded"]:
                entry["superseded"] = r["superseded"]
            results.append(entry)
            by_id[r["id"]] = entry
    if errors:
        raise SystemExit("extract_old_constants: citation drift at " + rev + ":\n  " + "\n  ".join(errors))
    return {
        "source_commit": rev,
        "generated_by": "android/tools/parity/extract_old_constants.py",
        "inventory": "docs/frontend-refactor/inventory/04-android-voice-and-device.md §4",
        "count": len(results),
        "constants": results,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rev", default=DEFAULT_REV)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true", help="fail if the output file differs")
    a = ap.parse_args()
    data = extract(a.rev)
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    out = Path(a.out)
    if a.check:
        if not out.exists() or out.read_text(encoding="utf-8") != text:
            raise SystemExit(f"{out} is stale; rerun without --check")
        print(f"{out.name}: up to date ({data['count']} constants at {a.rev})")
        return
    out.write_text(text, encoding="utf-8")
    print(f"wrote {out} ({data['count']} constants at {a.rev})")


if __name__ == "__main__":
    sys.exit(main())
