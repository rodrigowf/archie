package com.assistant.core.audio.platform

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.assistant.core.audio.policy.DefaultAudioFocusPolicy
import com.assistant.core.audio.policy.DefaultCallVolumePolicy
import com.assistant.core.audio.ports.AudioFocusPolicy
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.CallVolumePolicy
import com.assistant.core.audio.ports.VoiceLog

/**
 * Session audio focus (old `VoiceManager.requestAudioFocus` / `ensureCallStreamAudible` /
 * `releaseAudioFocus`, :487-540). Request shape from [AudioFocusPolicy]: GAIN_TRANSIENT_EXCLUSIVE,
 * USAGE_VOICE_COMMUNICATION / SPEECH via `AudioFocusRequest` on O+, `STREAM_VOICE_CALL` before.
 * Routing (apply / re-apply / release) is the caller's job through the
 * [com.assistant.core.audio.ports.RouteApplier], in the old order: focus → route → volume → callback.
 */
class AndroidAudioFocus(
    private val audioManager: AndroidAudioManagerPort,
    private val sdkInt: Int,
    private val log: VoiceLog,
    private val focusPolicy: AudioFocusPolicy = DefaultAudioFocusPolicy,
    private val volumePolicy: CallVolumePolicy = DefaultCallVolumePolicy,
) {
    private var request: Any? = null // AudioFocusRequest on O+

    fun request() {
        val spec = focusPolicy.forSdk(sdkInt)
        val am = audioManager.platform
        if (spec.useAudioFocusRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            request = r
            am.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AndroidAudioTypes.streamType(spec.legacyStream), AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    /** STREAM_VOICE_CALL ships muted on some devices: raise 0 → 75 % of max (≥ 1). */
    fun ensureCallStreamAudible() {
        val max = audioManager.streamMaxVolume(AudioStream.VOICE_CALL)
        val cur = audioManager.streamVolume(AudioStream.VOICE_CALL)
        val target = volumePolicy.raisedVolume(cur, max) ?: return
        audioManager.setStreamVolume(AudioStream.VOICE_CALL, target)
        log.d(TAG, "STREAM_VOICE_CALL was 0, raised to $target/$max")
    }

    fun abandon() {
        val am = audioManager.platform
        val r = request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (r is AudioFocusRequest) am.abandonAudioFocusRequest(r)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        request = null
    }

    private companion object {
        const val TAG = "VoiceManager"
    }
}
