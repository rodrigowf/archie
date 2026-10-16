package com.assistant.archie.tv.voice

import com.assistant.core.audio.pcm.Pcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the voice overlay shows for hold-to-talk (spec 15 §3.4, §4.4). Live voice has its own panel. */
sealed interface TvVoiceOverlay {
    data object Hidden : TvVoiceOverlay

    /** The mic button is held: level meter, "Release to send", where it goes, timer from [startedAtMs]. */
    data class Holding(val destination: VoiceDestination, val label: String, val startedAtMs: Long) : TvVoiceOverlay

    /** Released: "Sending…" (voice message) or "Transcribing…" (dictation). */
    data class Working(val text: String) : TvVoiceOverlay

    /** Dictation result: the chip with Send (focused) / Edit / Cancel (mockup 9). */
    data class Dictation(val text: String, val target: VoiceDestination.Dictation) : TvVoiceOverlay
}

/** The mic test (Settings → Remote microphone, first-run step 2). */
data class MicTestState(
    val holding: Boolean = false,
    /** Peak meter level of the last (or current) test burst, 0..1. */
    val peak: Float = 0f,
    /** The last test burst as a WAV (Play back), null before the first one. */
    val lastWav: ByteArray? = null,
    val lastDurationMs: Long = 0,
    /** Any burst seen since the test page opened (the first-run check passes on it). */
    val heard: Boolean = false,
)

/** The live call's side of the mic (frames in, turn boundaries). */
interface LiveMicSink {
    fun onLiveBurstStart()
    fun onLiveFrame(pcm: ShortArray)
    fun onLiveBurstEnd()
}

/**
 * Routes every burst of the remote's microphone (spec 15 §4.1) and holds the overlay state.
 * [onBurst] is [RemoteMicSource]'s sink: it runs on the mic threads, under the source's lock, so it
 * only records and posts; network work runs on [scope].
 */
class TvVoiceController(
    private val scope: CoroutineScope,
    private val inputs: () -> RouteInputs,
    private val messages: VoiceMessagePort,
    private val transcriber: Transcriber,
    private val live: LiveMicSink,
    private val toast: (text: String, error: Boolean) -> Unit,
    private val cue: (start: Boolean) -> Unit = {},
    /** "To Archie · <title>" / "To Claude · <title>" for the overlay. */
    private val labelFor: (VoiceDestination) -> String = { defaultLabel(it) },
    /** Dictation Send: the text to the agent conversation, as the composer sends it. False = could not send. */
    private val sendDictation: (VoiceDestination.Dictation, String) -> Boolean = { _, _ -> false },
    private val log: (String) -> Unit = {},
) {
    private val _overlay = MutableStateFlow<TvVoiceOverlay>(TvVoiceOverlay.Hidden)
    val overlay: StateFlow<TvVoiceOverlay> = _overlay.asStateFlow()

    /** Live meter level 0..1 of the burst in progress (hold overlay, live panel, test meter). */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** A walkie-talkie turn is being held during live voice ("Listening…" in the panel, chip). */
    private val _liveHolding = MutableStateFlow(false)
    val liveHolding: StateFlow<Boolean> = _liveHolding.asStateFlow()

    private val _test = MutableStateFlow(MicTestState())
    val test: StateFlow<MicTestState> = _test.asStateFlow()

    /** Mic test pages register here while composed (the router sends bursts to them). */
    @Volatile var testing: Boolean = false
        set(value) {
            field = value
            if (value) _test.value = MicTestState()
        }

    private val lock = Any()
    private var current: VoiceDestination? = null
    private val frames = ArrayList<ShortArray>()

    /** [RemoteMicSource] sink. */
    fun onBurst(b: MicBurst) {
        when (b) {
            is MicBurst.Start -> onStart(b.atMs)
            is MicBurst.Frame -> onFrame(b)
            is MicBurst.End -> onEnd(b)
        }
    }

    private fun onStart(atMs: Long) {
        val dest = TvVoiceRouter.route(inputs().let { if (testing) it.copy(testing = true) else it })
        synchronized(lock) {
            current = dest
            frames.clear()
        }
        _level.value = 0f
        log("burst start → $dest")
        when (dest) {
            VoiceDestination.Live -> {
                _liveHolding.value = true
                live.onLiveBurstStart()
            }
            VoiceDestination.Test -> _test.value = _test.value.copy(holding = true, peak = 0f, heard = true)
            else -> {
                cue(true)
                _overlay.value = TvVoiceOverlay.Holding(dest, labelFor(dest), atMs)
            }
        }
    }

    private fun onFrame(f: MicBurst.Frame) {
        _level.value = f.level
        val dest = synchronized(lock) { current } ?: return
        when (dest) {
            VoiceDestination.Live -> live.onLiveFrame(f.pcm)
            VoiceDestination.Test -> {
                synchronized(lock) { frames += f.pcm }
                if (f.level > _test.value.peak) _test.value = _test.value.copy(peak = f.level)
            }
            else -> synchronized(lock) { frames += f.pcm }
        }
    }

    private fun onEnd(e: MicBurst.End) {
        val (dest, captured) = synchronized(lock) {
            val d = current
            current = null
            d to frames.toList().also { frames.clear() }
        }
        _level.value = 0f
        log("burst end (${e.durationMs} ms, tooShort=${e.tooShort}, capped=${e.capped}) → $dest")
        when (dest) {
            null -> Unit
            VoiceDestination.Live -> {
                _liveHolding.value = false
                live.onLiveBurstEnd()
            }
            VoiceDestination.Test -> _test.value = _test.value.copy(
                holding = false,
                lastWav = if (captured.isEmpty()) _test.value.lastWav else Pcm.wav(captured, TvVoiceTuning.SAMPLE_RATE_HZ),
                lastDurationMs = e.durationMs,
            )
            VoiceDestination.VoiceMessage -> finish(e) {
                _overlay.value = TvVoiceOverlay.Working("Sending…")
                scope.launch {
                    when (val r = messages.send(Pcm.wav(captured, TvVoiceTuning.SAMPLE_RATE_HZ))) {
                        VoiceSendResult.Sent -> Unit
                        is VoiceSendResult.Failed -> toast(r.message, true)
                    }
                    _overlay.compareAndSet(TvVoiceOverlay.Working("Sending…"), TvVoiceOverlay.Hidden)
                }
            }
            is VoiceDestination.Dictation -> finish(e) {
                _overlay.value = TvVoiceOverlay.Working("Transcribing…")
                scope.launch {
                    when (val r = transcriber.transcribe(Pcm.wav(captured, TvVoiceTuning.SAMPLE_RATE_HZ))) {
                        is TranscribeResult.Text -> if (r.text.isBlank()) {
                            _overlay.value = TvVoiceOverlay.Hidden
                            toast("Didn't catch that — hold the mic and try again", true)
                        } else {
                            _overlay.value = TvVoiceOverlay.Dictation(r.text, dest)
                        }
                        is TranscribeResult.Failed -> {
                            _overlay.value = TvVoiceOverlay.Hidden
                            toast(r.message, true)
                        }
                    }
                }
            }
        }
    }

    /** Common end of a hold-to-talk burst: stop cue, too-short / cap handling, then [go]. */
    private inline fun finish(e: MicBurst.End, go: () -> Unit) {
        cue(false)
        if (e.tooShort) {
            _overlay.value = TvVoiceOverlay.Hidden
            toast("Too short — hold the mic button while you speak", true)
            return
        }
        if (e.capped) toast("Stopped at ${TvVoiceTuning.MAX_BURST_MS / 1000} s — sending what you said", false)
        go()
    }

    // ── dictation chip ──

    /** Send (OK on the chip, or IME Go after Edit). */
    fun sendDictation(text: String) {
        val d = _overlay.value as? TvVoiceOverlay.Dictation ?: return
        val t = text.trim()
        _overlay.value = TvVoiceOverlay.Hidden
        if (t.isEmpty()) return
        if (!sendDictation(d.target, t)) toast("Couldn't send — open the conversation and try again", true)
    }

    /** Cancel, or Back on the chip. */
    fun cancelDictation() {
        if (_overlay.value is TvVoiceOverlay.Dictation) _overlay.value = TvVoiceOverlay.Hidden
    }

    companion object {
        fun defaultLabel(d: VoiceDestination): String = when (d) {
            is VoiceDestination.Dictation -> "Dictating to ${d.title ?: "this agent"}"
            VoiceDestination.VoiceMessage -> "To Archie"
            VoiceDestination.Live -> "Live voice"
            VoiceDestination.Test -> "Mic test"
        }
    }
}
