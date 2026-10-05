package com.assistant.core.voicehost.cue

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.ports.VoiceCues
import com.assistant.core.voice.session.VoiceLinkEvent
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.HostTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** A sequence of sine tones: each [toneMs] long, [gapMs] of silence between them. */
data class ToneCue(val freqsHz: List<Double>, val toneMs: Int, val gapMs: Int, val amplitude: Double)

/**
 * Every audible cue of the host. The first three are the old app's (inv04 §4.10, `3c4dbba`,
 * `b586e4b`), bit-for-bit; the LINK_* ones are the new P-2 call-app cues. Each ships as a WAV
 * under `assets/voicehost/cues/` generated from [tone] by [ToneSynth] (a unit test keeps the
 * shipped files equal to the synthesis); the player falls back to synthesis if an asset is missing.
 */
enum class CueKind(val tone: ToneCue) {
    /** Rising 660 → 880 Hz chirp, 90 ms each, 30 ms gap (old `playWakeWordAckBeep`). */
    WAKE_ACK(ToneCue(listOf(660.0, 880.0), toneMs = 90, gapMs = 30, amplitude = 0.45)),

    /** Single lower 440 Hz tone, 150 ms (old `playTalkWordAckBeep`); asymmetric on purpose. */
    TALK_ACK(ToneCue(listOf(440.0), toneMs = 150, gapMs = 0, amplitude = 0.45)),

    /** Falling 880 → 660 Hz, 130 ms each, 40 ms gap (old `playReconnectBeep`: provider goAway, RS-19). */
    RECONNECT_WARNING(ToneCue(listOf(880.0, 660.0), toneMs = 130, gapMs = 40, amplitude = 0.50)),

    /**
     * P-2 "reconnecting": a soft double pulse at the 425 Hz call-progress pitch, repeated every
     * [HostTuning.LINK_CUE_REPEAT_MS] while the link is down (call-app style).
     */
    LINK_RECONNECTING(ToneCue(listOf(425.0, 425.0), toneMs = 150, gapMs = 150, amplitude = 0.20)),

    /** P-2 "reconnected": a short rising C5–E5–G5 arpeggio (three notes: unlike the two-note wake chirp). */
    LINK_RESTORED(ToneCue(listOf(523.25, 659.25, 783.99), toneMs = 90, gapMs = 20, amplitude = 0.35)),

    /** P-2 "failed": a falling A4–F4–C4 phrase, longer and lower than anything else. */
    LINK_FAILED(ToneCue(listOf(440.0, 349.23, 261.63), toneMs = 160, gapMs = 40, amplitude = 0.40)),
    ;

    /** Shipped asset path (inside the module's `assets/`). */
    val assetPath: String get() = "voicehost/cues/${name.lowercase()}.wav"
}

/**
 * Tone synthesis, ported verbatim from the old `AssistantViewModel.playTones` /
 * `playReconnectBeep`: 22 050 Hz mono PCM16, a 15 ms linear fade in and out per tone,
 * `(sin(2πft) × env × amplitude × Short.MAX_VALUE).toInt().toShort()`.
 */
object ToneSynth {
    fun pcm(cue: ToneCue, sampleRateHz: Int = HostTuning.CUE_SAMPLE_RATE_HZ): ShortArray {
        val toneFrames = sampleRateHz * cue.toneMs / 1000
        val gapFrames = sampleRateHz * cue.gapMs / 1000
        val n = cue.freqsHz.size
        val total = toneFrames * n + gapFrames * (n - 1).coerceAtLeast(0)
        val pcm = ShortArray(total)
        val fadeFrames = sampleRateHz * HostTuning.CUE_FADE_MS / 1000
        var offset = 0
        for (freq in cue.freqsHz) {
            val twoPiF = 2.0 * PI * freq
            for (i in 0 until toneFrames) {
                val env = when {
                    i < fadeFrames -> i.toDouble() / fadeFrames
                    i > toneFrames - fadeFrames -> (toneFrames - i).toDouble() / fadeFrames
                    else -> 1.0
                }
                pcm[offset + i] = (sin(twoPiF * i / sampleRateHz) * env * cue.amplitude * Short.MAX_VALUE).toInt().toShort()
            }
            offset += toneFrames + gapFrames
        }
        return pcm
    }

    fun durationMs(cue: ToneCue, sampleRateHz: Int = HostTuning.CUE_SAMPLE_RATE_HZ): Long =
        pcm(cue, sampleRateHz).size * 1000L / sampleRateHz

    /** Standard 44-byte RIFF/WAVE, mono, PCM16 little-endian. */
    fun wav(pcm: ShortArray, sampleRateHz: Int = HostTuning.CUE_SAMPLE_RATE_HZ): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(44 + dataBytes)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int32(at: Int, v: Int) { for (b in 0 until 4) out[at + b] = (v ushr (8 * b)).toByte() }
        fun int16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        str(0, "RIFF"); int32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, sampleRateHz); int32(28, sampleRateHz * 2); int16(32, 2); int16(34, 16)
        str(36, "data"); int32(40, dataBytes)
        for (i in pcm.indices) int16(44 + 2 * i, pcm[i].toInt())
        return out
    }

    /** PCM of a mono PCM16 WAV produced by [wav]; null when the header is not that shape. */
    fun parseWav(bytes: ByteArray): Pair<ShortArray, Int>? {
        if (bytes.size < 44) return null
        fun str(at: Int, n: Int) = String(CharArray(n) { bytes[at + it].toInt().toChar() })
        fun int32(at: Int) = (0 until 4).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }
        fun int16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        if (str(0, 4) != "RIFF" || str(8, 4) != "WAVE" || str(36, 4) != "data") return null
        if (int16(20) != 1 || int16(22) != 1 || int16(34) != 16) return null
        val rate = int32(24)
        val len = int32(40).coerceAtMost(bytes.size - 44)
        val pcm = ShortArray(len / 2) { i -> int16(44 + 2 * i).toShort() }
        return pcm to rate
    }
}

/** Plays one cue, asynchronously, on STREAM_MUSIC ([HostTuning.CUE_STREAM]). Never throws. */
fun interface CuePlayer {
    fun play(kind: CueKind)
}

/**
 * The host's [VoiceCues] (the voice core's port) plus the P-2 link cues. [onCue] mirrors every cue
 * to the UI event stream (`VoiceUiEvent.Cue`), e.g. for a lite-face flash.
 */
class HostCues(
    private val player: CuePlayer,
    private val log: VoiceLog,
    private val onCue: (CueKind) -> Unit = {},
) : VoiceCues {
    override fun wakeAck() = play(CueKind.WAKE_ACK)
    override fun talkAck() = play(CueKind.TALK_ACK)
    override fun reconnect() = play(CueKind.RECONNECT_WARNING)

    fun play(kind: CueKind) {
        log.d(TAG, "cue ${kind.name} (stream=${HostTuning.CUE_STREAM})")
        player.play(kind)
        onCue(kind)
    }

    private companion object {
        const val TAG = "VoiceCues"
    }
}

/**
 * Decision P-2: "auto-restart, not quietly". Turns the voice session's link events into the
 * call-app cue pattern:
 *  - [VoiceLinkEvent.Lost] → the soft "reconnecting" cue NOW, then every [repeatMs] while the link
 *    stays `Reconnecting` (the last pulse one interval before the retry budget runs out);
 *  - [VoiceLinkEvent.Restored] → stop the pattern, play the distinct "reconnected" cue;
 *  - [VoiceLinkEvent.Failed] (30 s budget) → stop the pattern, play the failure cue.
 * A link state that leaves `Reconnecting` without an event (user stop, error, new start) just stops
 * the pattern.
 */
class LinkCueScheduler(
    private val scope: CoroutineScope,
    private val cues: HostCues,
    /** The session's current link state (the pattern only plays while it is `Reconnecting`). */
    private val linkState: () -> VoiceLinkState,
    private val clock: MonotonicClock,
    private val repeatMs: Long = HostTuning.LINK_CUE_REPEAT_MS,
) {
    private val lock = Any()
    private var loop: Job? = null

    val isRepeating: Boolean get() = synchronized(lock) { loop?.isActive == true }

    fun onLinkEvent(event: VoiceLinkEvent) {
        when (event) {
            VoiceLinkEvent.Lost -> synchronized(lock) {
                loop?.cancel()
                loop = scope.launch {
                    // The session publishes Reconnecting before it emits Lost, so the first cue is immediate.
                    while (true) {
                        val s = linkState() as? VoiceLinkState.Reconnecting ?: break
                        // No pulse in the last interval of the budget: the failure cue follows on its own,
                        // instead of queueing behind a "reconnecting" pulse that starts at the same instant.
                        if (clock.nowMs() - s.sinceMs + repeatMs > s.budgetMs) break
                        cues.play(CueKind.LINK_RECONNECTING)
                        delay(repeatMs)
                    }
                }
            }
            is VoiceLinkEvent.Restored -> {
                stopPattern()
                cues.play(CueKind.LINK_RESTORED)
            }
            is VoiceLinkEvent.Failed -> {
                stopPattern()
                cues.play(CueKind.LINK_FAILED)
            }
        }
    }

    fun onLinkState(state: VoiceLinkState) {
        // Re-read the live value: a conflated, stale emission must not cancel a fresh outage.
        if (state !is VoiceLinkState.Reconnecting && linkState() !is VoiceLinkState.Reconnecting) stopPattern()
    }

    fun stopPattern() = synchronized(lock) {
        loop?.cancel()
        loop = null
    }
}
