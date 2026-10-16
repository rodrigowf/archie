package com.assistant.archie.tv.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** TV-only voice preferences (spec 15 §3.5 Remote microphone, §4.2). */
class TvVoicePrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tv_voice", Context.MODE_PRIVATE)

    private val _cues = MutableStateFlow(sp.getBoolean(KEY_CUES, true))
    /** Start / stop sounds on a burst (spec 15 §4.4, default on). */
    val cues: StateFlow<Boolean> = _cues.asStateFlow()

    fun setCues(on: Boolean) {
        sp.edit { putBoolean(KEY_CUES, on) }
        _cues.value = on
    }

    /** Live voice ends after this many seconds with nobody talking; 0 = never (spec 15 §4.2, default 120). */
    var liveIdleSeconds: Int
        get() = sp.getInt(KEY_IDLE, TvVoiceTuning.DEFAULT_LIVE_IDLE_SECONDS)
        set(v) { sp.edit { putInt(KEY_IDLE, v) } }

    private companion object {
        const val KEY_CUES = "cues"
        const val KEY_IDLE = "live_idle_seconds"
    }
}

/** Whether Alexa's voice UI also reacts to the mic button (spec 15 TV-3, §4.3). */
enum class AlexaState { QUIET, LISTENS, ABSENT }

object AlexaProbe {
    const val VIZZINI = "com.amazon.vizzini"

    fun state(context: Context): AlexaState {
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(VIZZINI, 0)
            val setting = pm.getApplicationEnabledSetting(VIZZINI)
            val disabled = !info.enabled ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            if (disabled) AlexaState.QUIET else AlexaState.LISTENS
        } catch (_: PackageManager.NameNotFoundException) {
            AlexaState.ABSENT
        } catch (_: Exception) {
            AlexaState.ABSENT
        }
    }
}

/** Plays a short mono PCM16 WAV once on the media stream (the mic test's Play back). */
object WavPlayback {
    @Volatile private var track: AudioTrack? = null

    fun play(wav: ByteArray) {
        val parsed = Wav.parse(wav) ?: return
        val (pcm, rate) = parsed
        stop()
        val bytes = pcm.size * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/** Minimal RIFF/WAVE reader for mono or stereo PCM16 (stereo is mixed down). */
object Wav {
    fun parse(bytes: ByteArray): Pair<ShortArray, Int>? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16 || rate <= 0 || channels !in 1..2) return null
                val end = minOf(bytes.size, body + size)
                val frames = (end - body) / (2 * channels)
                val out = ShortArray(frames)
                for (i in 0 until frames) {
                    out[i] = if (channels == 1) {
                        bb.getShort(body + 2 * i)
                    } else {
                        ((bb.getShort(body + 4 * i) + bb.getShort(body + 4 * i + 2)) / 2).toShort()
                    }
                }
                return out to rate
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /** Linear resample of a whole clip (debug injection of non-16 kHz files). */
    fun resample(pcm: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        if (fromHz == toHz) return pcm
        val r = LinearResampler(fromHz).also { it.setOutputRate(toHz) }
        var i = 0
        val n = (pcm.size.toLong() * toHz / fromHz).toInt()
        return ShortArray(n) { r.next { if (i < pcm.size) pcm[i++] else null } }
    }
}
