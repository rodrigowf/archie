package com.assistant.core.wakeword.loop

import com.assistant.core.audio.pcm.Pcm
import com.assistant.core.audio.policy.DefaultMicSourcePolicy
import com.assistant.core.audio.ports.MicFailure
import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.policy.ActivityGate
import com.assistant.core.wakeword.policy.AdaptiveTalkVad
import com.assistant.core.wakeword.policy.Clips
import com.assistant.core.wakeword.policy.PhraseMatcher
import com.assistant.core.wakeword.policy.PreBuffer
import com.assistant.core.wakeword.policy.WakeRearmPolicy
import com.assistant.core.wakeword.policy.WhisperGateDecision
import com.assistant.core.wakeword.ports.CycleOutcome
import com.assistant.core.wakeword.ports.EngineKind
import com.assistant.core.wakeword.ports.SrOutcome
import com.assistant.core.wakeword.ports.StreamingRecognizer
import com.assistant.core.wakeword.ports.StreamingRecognizerFactory
import com.assistant.core.wakeword.ports.TalkVadStep
import com.assistant.core.wakeword.ports.VariantMatch
import com.assistant.core.wakeword.ports.WakeConfig
import com.assistant.core.wakeword.ports.WakeEngineDeps
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakePhase
import com.assistant.core.wakeword.ports.WakeWordEngine
import com.assistant.core.wakeword.ports.WhisperOutcome
import com.assistant.core.wakeword.ports.WhisperTranscriber
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The wake-word loop (inv04 §3.1): RMS gate → Vosk (pre-buffer first, 400 ms reads, 5 s window) →
 * match tail → (wake) speech-floor gate → Whisper → [WakeLoopEvent.WakeDetected], or (talk)
 * same-mic command capture with the adaptive VAD → speech-floor gate → Whisper →
 * [WakeLoopEvent.TalkMessageCaptured]. Behaviour and every timing are the old
 * `WakeWordDetector` + `VoskRecognitionEngine` (`e871d05`); the differences are structural:
 *
 * - **Explicit phases (R3).** Confirm, capture and cooldown are [WakePhase]s of their own, so a
 *   host can tell a busy loop from an idle one (the old FSM said `Idle` during all three).
 * - **One coroutine owns the mic and the recognizer (R4).** Mic reads, `Recognizer`
 *   create/feed/reset/close and `AudioRecord` release all run in the loop coroutine on
 *   [WakeEngineDeps.scope]; [pause]/[stop] only cancel it, and the loop's `finally` releases
 *   both after the in-flight read returns. The engine never switches dispatcher.
 * - **Vosk without a SpeechRecognizer service (R6).** The old `start()` required
 *   `SpeechRecognizer.isRecognitionAvailable()` even for Vosk.
 * - The mic is held through the cooldown and released at re-arm, exactly as the old code.
 *
 * Accepted fixes over the old code (coordinator ruling, A-07):
 * - A negative read in MONITORING releases the mic and re-acquires it after
 *   [WakeTuning.MIC_RETRY_MS] (the old monitor loop spun on `read <= 0` forever).
 * - The SpeechRecognizer miss backoff bounds its shift ([WakeRearmPolicy]); the old
 *   `1000L shl misses` overflowed to a negative delay after 54 misses. Reachable values unchanged.
 * - The wake gain is taken at its decimal value ([ActivityGate]: `1.3f` → 1.3); the old Double/Float
 *   promotion differed by < 2e-6 RMS units.
 *
 * Whisper gate ([WakeEngineDeps.whisper]): fail-CLOSED on timeout / error / no key / 401 /
 * boilerplate. **Flagged parity quirk (inv04 §12 errata #5):** when no Whisper client is
 * configured (old: blank `serverUrl`), matches fire UNCONFIRMED and skip the speech-floor gate,
 * exactly as the old `whisperConfirmer ?: return true`. Kept for parity; needs Rodrigo's review.
 */
internal class WakeLoop(
    private val deps: WakeEngineDeps,
    private val config: WakeConfig,
) : WakeWordEngine {
    private val clock = deps.clock
    private val log = deps.log
    private val talk: List<String> = PhraseMatcher.parseVariants(config.talkPhrases)
    private val wake: List<String> = PhraseMatcher.parseVariants(config.wakePhrases)
    private val grammar: String = PhraseMatcher.keywordGrammar(talk, wake)
    private val rearm = WakeRearmPolicy()

    private val _phase = MutableStateFlow(WakePhase.STOPPED)
    override val phase: StateFlow<WakePhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<WakeLoopEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<WakeLoopEvent> = _events.asSharedFlow()

    @Volatile
    override var engineKind: EngineKind? = null
        private set

    // Loop-confined (the loop jobs are serialized: each joins the previous one).
    private var engineResolved = false
    private var recognizers: StreamingRecognizerFactory? = null

    // Control state, guarded by [lock].
    private val lock = Any()
    private var generation = 0L
    private var loopJob: Job? = null
    private var tail: Job? = null
    private var released = false

    // ── Control ───────────────────────────────────────────────────────────────────────────

    override fun start(): Unit = synchronized(lock) {
        if (released) {
            log.w(TAG, "start() after release() — ignored")
            return
        }
        val current = _phase.value
        if (current != WakePhase.STOPPED && current != WakePhase.PAUSED) {
            log.d(TAG, "start() ignored — already active")
            return
        }
        log.d(TAG, "Starting — talk variants: $talk")
        if (wake.isNotEmpty()) log.d(TAG, "Wake variants: $wake")
        arm(resetMisses = false, refreshRecognizer = false)
    }

    override fun pause(): Unit = synchronized(lock) {
        val current = _phase.value
        if (current == WakePhase.STOPPED || current == WakePhase.PAUSED) return
        log.d(TAG, "Pausing wake word detection")
        halt(WakePhase.PAUSED, resetMisses = false, refreshRecognizer = true)
    }

    override fun resume(): Unit = synchronized(lock) {
        if (released || _phase.value != WakePhase.PAUSED) return
        log.d(TAG, "Resuming wake word detection")
        arm(resetMisses = true, refreshRecognizer = true)
    }

    override fun stop(): Unit = synchronized(lock) {
        halt(WakePhase.STOPPED, resetMisses = true, refreshRecognizer = false)
    }

    override fun release(): Unit = synchronized(lock) {
        halt(WakePhase.STOPPED, resetMisses = true, refreshRecognizer = false)
        released = true
    }

    /** Caller holds [lock]. Cancels the running loop and starts a new one after it has unwound. */
    private fun arm(resetMisses: Boolean, refreshRecognizer: Boolean) {
        val gen = ++generation
        loopJob?.cancel()
        val previous = tail
        _phase.value = WakePhase.ACQUIRING_MIC
        val job = deps.scope.launch {
            previous?.join()
            if (resetMisses) rearm.reset()
            if (refreshRecognizer) deps.speechRecognizer?.markNeedsRefresh()
            runArmed(gen)
        }
        loopJob = job
        tail = job
    }

    /** Caller holds [lock]. Cancels the loop (no event, no re-arm); SR teardown runs after it unwound. */
    private fun halt(target: WakePhase, resetMisses: Boolean, refreshRecognizer: Boolean) {
        ++generation
        loopJob?.cancel()
        loopJob = null
        val previous = tail
        _phase.value = target
        tail = deps.scope.launch {
            previous?.join()
            if (resetMisses) rearm.reset()
            val sr = deps.speechRecognizer
            if (sr != null && engineKind == EngineKind.SPEECH_RECOGNIZER) {
                sr.tearDown()
                if (refreshRecognizer) sr.markNeedsRefresh()
            }
        }
    }

    private fun setPhase(gen: Long, phase: WakePhase) = synchronized(lock) {
        if (gen == generation) _phase.value = phase
    }

    /** Events of a superseded loop (paused / stopped while finishing a step) are dropped. */
    private fun emit(gen: Long, event: WakeLoopEvent) = synchronized(lock) {
        if (gen == generation && !_events.tryEmit(event)) log.w(TAG, "Wake event buffer full — dropped $event")
    }

    // ── Loop ──────────────────────────────────────────────────────────────────────────────

    private sealed interface CycleEnd {
        data class Done(val outcome: CycleOutcome) : CycleEnd
        data object MicLost : CycleEnd
        data object NoEngine : CycleEnd
    }

    private suspend fun runArmed(gen: Long) {
        // The AudioRecord of the last cycle is held through the cooldown and released at re-arm,
        // right before the new open (old `startSilenceMonitor` → `stopAudioRecord()`,
        // `WakeWordDetector.kt:643-645`). Mic hand-off timing is tuned; do not move the release.
        var held: MicSource? = null
        try {
            while (true) {
                held?.release()
                held = null
                val mic = acquireMic(gen)
                held = mic
                when (val end = runCycle(gen, mic)) {
                    is CycleEnd.Done -> {
                        val delayMs = rearm.delayAfter(end.outcome)
                        if (delayMs == null) {
                            setPhase(gen, WakePhase.STOPPED)
                            return
                        }
                        logRearm(end.outcome, delayMs)
                        setPhase(gen, WakePhase.COOLDOWN)
                        delay(delayMs)
                    }
                    CycleEnd.MicLost -> {
                        mic.release()
                        setPhase(gen, WakePhase.ACQUIRING_MIC)
                        delay(WakeTuning.MIC_RETRY_MS)
                    }
                    CycleEnd.NoEngine -> {
                        setPhase(gen, WakePhase.STOPPED)
                        return
                    }
                }
                setPhase(gen, WakePhase.ACQUIRING_MIC)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Wake loop failed — stopping", e)
            setPhase(gen, WakePhase.STOPPED)
        } finally {
            held?.release() // pause / stop / end: released on the loop, after the in-flight read (R4)
        }
    }

    private fun logRearm(outcome: CycleOutcome, delayMs: Long) {
        when (outcome) {
            CycleOutcome.SR_NO_MATCH, CycleOutcome.ERROR -> {
                val misses = rearm.consecutiveMisses
                if (misses >= 10) log.w(TAG, "No match — miss #$misses (at max backoff)")
                else log.d(TAG, "No match — miss #$misses, waiting ${delayMs}ms")
            }
            else -> log.d(TAG, "Re-arming after ${outcome.name} in ${delayMs}ms")
        }
    }

    /** One arm on an opened mic: monitor, recognize, confirm / capture. The caller owns [mic]. */
    private suspend fun runCycle(gen: Long, mic: MicSource): CycleEnd {
        val effective = ActivityGate(config.wakeGain).effectiveThreshold
        log.d(
            TAG,
            "Silence monitor started (threshold=${WakeTuning.RMS_THRESHOLD}, gain=${config.wakeGain}, effective=${effective.toInt()})",
        )
        val kind = resolveEngine() ?: return CycleEnd.NoEngine
        if (kind == EngineKind.SPEECH_RECOGNIZER) deps.speechRecognizer?.warm()
        setPhase(gen, WakePhase.MONITORING)
        val preRoll = monitor(mic) ?: return CycleEnd.MicLost
        return when (kind) {
            EngineKind.VOSK -> CycleEnd.Done(runVosk(gen, mic, preRoll))
            EngineKind.SPEECH_RECOGNIZER -> {
                mic.release() // the SpeechRecognizer opens its own mic (old `WakeWordDetector.kt:790-791`)
                CycleEnd.Done(runSpeechRecognizer(gen))
            }
        }
    }

    /** ACQUIRING_MIC: retry every 500 ms; `MicUnavailable` once at 8 consecutive failures. */
    private suspend fun acquireMic(gen: Long): MicSource {
        val mics = deps.mics
        val spec = MicSpec(
            sampleRateHz = WakeTuning.SAMPLE_RATE_HZ,
            source = DefaultMicSourcePolicy.sourceFor(deps.sdkInt),
            bufferBytes = Clips.monitorBufferBytes(mics.minBufferBytes(WakeTuning.SAMPLE_RATE_HZ)),
        )
        var failures = 0
        var notifiedUnavailable = false
        while (true) {
            when (val result = mics.open(spec)) {
                is MicOpenResult.Opened -> {
                    if (notifiedUnavailable) {
                        log.d(TAG, "Mic acquired after $failures failures — broadcasting clear")
                        emit(gen, WakeLoopEvent.MicAvailable)
                    }
                    return result.mic
                }
                is MicOpenResult.Failed -> {
                    if (result.reason == MicFailure.START_RECORDING_FAILED) {
                        // Old code: the AudioRecord initialised (clearing any warning), then
                        // startRecording failed and the monitor restarted with fresh counters.
                        if (notifiedUnavailable) emit(gen, WakeLoopEvent.MicAvailable)
                        log.w(TAG, "AudioRecord.startRecording() failed — mic busy, will retry")
                        failures = 0
                        notifiedUnavailable = false
                    } else {
                        failures++
                        log.w(TAG, "AudioRecord not initialized (${result.reason}, mic busy, will retry)")
                        if (!notifiedUnavailable && failures >= WakeTuning.MIC_RETRY_WARN_THRESHOLD) {
                            log.w(TAG, "Mic unavailable for $failures consecutive attempts — broadcasting warning")
                            emit(gen, WakeLoopEvent.MicUnavailable)
                            notifiedUnavailable = true
                        }
                    }
                    delay(WakeTuning.MIC_RETRY_MS)
                }
            }
        }
    }

    /** Vosk when the model loads, else the SpeechRecognizer fallback; sticky for this engine. */
    private suspend fun resolveEngine(): EngineKind? {
        if (engineResolved) return engineKind
        val factory = try {
            deps.vosk.load()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.w(TAG, "Vosk model load threw (${t.message}) — falling back to SpeechRecognizer")
            null
        }
        engineResolved = true
        recognizers = factory
        engineKind = when {
            factory != null -> EngineKind.VOSK.also { log.d(TAG, "Engine selected: Vosk (model loaded)") }
            deps.speechRecognizer != null ->
                EngineKind.SPEECH_RECOGNIZER.also { log.d(TAG, "Engine selected: SpeechRecognizer (Vosk unavailable)") }
            else -> null.also { log.e(TAG, "No wake-word recognizer: Vosk unavailable and no SpeechRecognizer service") }
        }
        return engineKind
    }

    /** MONITORING: rolling pre-buffer + RMS gate. Returns the pre-buffer, or null when the mic died. */
    private suspend fun monitor(mic: MicSource): List<ShortArray>? {
        val readSamples = mic.spec.bufferBytes / 2
        val buffer = ShortArray(readSamples)
        val preBuffer = PreBuffer(Clips.preBufferCapacity(readSamples))
        val gate = ActivityGate(config.wakeGain)
        while (true) {
            val read = mic.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read < 0) {
                log.w(TAG, "Mic read error $read in the silence monitor — re-acquiring")
                return null
            }
            if (read == 0) {
                delay(EMPTY_READ_BACKOFF_MS)
                continue
            }
            preBuffer.add(buffer.copyOf(read))
            val rms = Pcm.rms(buffer, read)
            if (gate.onFrame(rms, clock.nowMs())) {
                log.d(TAG, "Audio activity detected (rms=${fmt0(rms)}) — starting recognizer")
                return preBuffer.snapshot()
            }
        }
    }

    // ── Vosk path ─────────────────────────────────────────────────────────────────────────

    private suspend fun runVosk(gen: Long, mic: MicSource, preRoll: List<ShortArray>): CycleOutcome {
        val factory = checkNotNull(recognizers)
        setPhase(gen, WakePhase.RECOGNIZING)
        val captured = ArrayList<ShortArray>(preRoll.size + 16)
        val recognizer = factory.create(grammar, WakeTuning.SAMPLE_RATE_HZ)
        val match = try {
            val m = recognize(recognizer, mic, preRoll, captured) ?: return CycleOutcome.VOSK_NO_MATCH
            setPhase(gen, WakePhase.MATCH_TAIL)
            readMatchTail(mic, captured)
            m
        } finally {
            recognizer.close() // fresh recognizer per cycle, closed on the loop (R4)
        }
        val clip = Clips.trimToTrailingWindow(captured, WakeTuning.MAX_CONFIRM_WINDOW_MS, WakeTuning.SAMPLE_RATE_HZ)
        val samples = clip.sumOf { it.size }
        log.d(
            VOSK_TAG,
            "Captured ${clip.size} frames / $samples samples (${samples * 1000 / WakeTuning.SAMPLE_RATE_HZ}ms) for Whisper confirmation" +
                if (clip.size < captured.size) " (trimmed from ${captured.size} frames)" else "",
        )
        if (match.isRealtime) {
            setPhase(gen, WakePhase.CONFIRMING_WAKE)
            if (confirmWake(gen, clip, match)) emit(gen, WakeLoopEvent.WakeDetected)
        } else {
            // TALK: Vosk fired early (maybe on "hello my"); capture the whole utterance on this
            // mic first, then confirm over all of it. The recording UI waits for a real onset.
            setPhase(gen, WakePhase.TALK_PRE_ONSET)
            val utterance = captureTalkCommand(mic, clip) {
                setPhase(gen, WakePhase.TALK_CAPTURING)
                emit(gen, WakeLoopEvent.TalkDetected)
            }
            if (utterance != null) {
                setPhase(gen, WakePhase.CONFIRMING_TALK)
                confirmTalk(gen, utterance)
            }
        }
        return CycleOutcome.MATCHED
    }

    /** RECOGNIZING: pre-buffer first (full match only), then 400 ms reads with the talk prefix trigger. */
    private suspend fun recognize(
        recognizer: StreamingRecognizer,
        mic: MicSource,
        preRoll: List<ShortArray>,
        captured: MutableList<ShortArray>,
    ): VariantMatch? {
        for (frame in preRoll) {
            captured += frame
            fullMatch(recognizer, frame)?.let { m ->
                log.d(VOSK_TAG, "Vosk match in pre-buffer: \"${m.variant}\" (realtime=${m.isRealtime}) in \"${m.rawText}\"")
                return m
            }
        }
        val preSamples = preRoll.sumOf { it.size }
        log.d(VOSK_TAG, "Pre-buffer fed: $preSamples samples (${preSamples * 1000 / WakeTuning.SAMPLE_RATE_HZ}ms of leading-edge audio)")

        val started = clock.nowMs()
        val buffer = ShortArray(WakeTuning.VOSK_READ_SAMPLES)
        var speechStarted = false
        var lastPartial = ""
        while (true) {
            if (clock.nowMs() - started >= WakeTuning.VOSK_RECOGNITION_TIMEOUT_MS) {
                log.d(VOSK_TAG, "Vosk recognition window elapsed — heard: \"${recognizer.partial()}\" (last partial: \"$lastPartial\")")
                return null
            }
            val read = mic.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read <= 0) {
                delay(EMPTY_READ_BACKOFF_MS)
                continue
            }
            val frame = buffer.copyOf(read)
            captured += frame
            if (!speechStarted && Pcm.rms(frame, read) >= WakeTuning.VOSK_RMS_STARTED_THRESHOLD) {
                speechStarted = true
                log.d(VOSK_TAG, "Speech energy in the recognition window")
            }
            fullMatch(recognizer, frame)?.let { m ->
                log.d(VOSK_TAG, "Vosk match: \"${m.variant}\" (realtime=${m.isRealtime}) in \"${m.rawText}\"")
                return m
            }
            val partial = recognizer.partial()
            if (partial.isNotEmpty() && partial != lastPartial) {
                log.d(VOSK_TAG, "Vosk partial: \"$partial\"")
                lastPartial = partial
            }
            // Vosk's grammar stalls on "hello my friend <command>" in one breath; fire on the
            // phrase's leading words and let Whisper confirm the whole utterance (talk only).
            PhraseMatcher.findTalkPrefixMatch(partial, talk)?.let { m ->
                log.d(VOSK_TAG, "Vosk talk-prefix trigger: \"${m.variant}\" from partial \"$partial\"")
                return m
            }
        }
    }

    /** Feed one frame; on a full match reset the recognizer (no re-trigger storms). */
    private fun fullMatch(recognizer: StreamingRecognizer, frame: ShortArray): VariantMatch? {
        val text = recognizer.accept(frame, frame.size).text
        val match = PhraseMatcher.findMatch(text, talk, wake) ?: return null
        try {
            recognizer.reset()
        } catch (e: Exception) {
            log.w(VOSK_TAG, "Recognizer reset failed: ${e.message}")
        }
        return match
    }

    /** MATCH_TAIL: keep reading [WakeTuning.MATCH_TAIL_MS] so Whisper hears the end of the phrase. */
    private suspend fun readMatchTail(mic: MicSource, captured: MutableList<ShortArray>) {
        val tailEnd = clock.nowMs() + WakeTuning.MATCH_TAIL_MS
        val buffer = ShortArray(WakeTuning.MATCH_TAIL_READ_SAMPLES)
        while (clock.nowMs() < tailEnd) {
            val read = mic.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read <= 0) {
                delay(EMPTY_READ_BACKOFF_MS)
                continue
            }
            captured += buffer.copyOf(read)
        }
    }

    /** CONFIRMING_WAKE. Returns true when [WakeLoopEvent.WakeDetected] should fire. */
    private suspend fun confirmWake(gen: Long, clip: List<ShortArray>, match: VariantMatch): Boolean {
        val whisper = deps.whisper ?: return true // errata #5: unconfigured → unconfirmed (parity)
        if (!Clips.hasSpeech(clip)) {
            log.d(
                TAG,
                "Pre-Whisper gate: captured clip below speech floor " +
                    "(peak=${Clips.peakFrameRms(clip).toInt()} < ${WakeTuning.SPEECH_FLOOR_RMS.toInt()}) " +
                    "— rejecting phantom \"${match.variant}\" without Whisper",
            )
            emit(gen, WakeLoopEvent.ConfirmFailed(match.isRealtime))
            return false
        }
        emit(gen, WakeLoopEvent.Confirming(match.isRealtime))
        val confirmed = whisperConfirms(whisper, Pcm.wav(clip, WakeTuning.SAMPLE_RATE_HZ))
        if (!confirmed) {
            log.d(TAG, "Whisper gate rejected \"${match.variant}\"")
            emit(gen, WakeLoopEvent.ConfirmFailed(match.isRealtime))
        }
        return confirmed
    }

    /**
     * TALK_PRE_ONSET → TALK_CAPTURING. Returns the whole utterance (pre-roll + command) or null for
     * a phantom trigger (no sustained onset within 4 s: no UI, nothing sent).
     */
    private suspend fun captureTalkCommand(
        mic: MicSource,
        preRoll: List<ShortArray>,
        onSpeechOnset: () -> Unit,
    ): List<ShortArray>? {
        val vad = AdaptiveTalkVad(clock.nowMs(), Clips.seedFloor(preRoll), config.talkSilenceSensitivity)
        val command = ArrayList(preRoll)
        val buffer = ShortArray(WakeTuning.CAPTURE_FRAME_SAMPLES)
        var loggedAtMs = Long.MIN_VALUE
        log.d(
            TAG,
            "Talk command capture started (same mic, adaptive VAD sensitivity=${String.format(Locale.US, "%.1f", vad.sensitivity)}, " +
                "onset needs ${WakeTuning.ONSET_SUSTAIN_MS}ms sustained)",
        )
        loop@ while (true) {
            val now = clock.nowMs()
            when (vad.limitAt(now)) {
                TalkVadStep.END_MAX -> {
                    log.d(TAG, "Talk command hit max ${WakeTuning.COMMAND_MAX_MS}ms — sending")
                    break@loop
                }
                TalkVadStep.ABORT_NO_ONSET -> {
                    log.d(TAG, "No sustained speech within ${WakeTuning.COMMAND_SPEECH_ONSET_TIMEOUT_MS}ms — phantom trigger, aborting (no UI shown)")
                    return null
                }
                else -> Unit
            }
            val read = mic.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read <= 0) {
                delay(EMPTY_READ_BACKOFF_MS)
                continue
            }
            command += buffer.copyOf(read)
            val rms = Pcm.rms(buffer, read)
            val step = vad.onFrame(rms, now)
            if (now - loggedAtMs >= CAPTURE_LOG_INTERVAL_MS) {
                log.d(
                    TAG,
                    "  cmd rms=${rms.toInt()} floor=${vad.floor.toInt()} voice≥${vad.lastThreshold.toInt()} " +
                        "voice=${rms >= vad.lastThreshold} onset=${vad.onsetFired} sustained=${vad.sustainedVoiceMs}ms " +
                        "silenceFor=${if (vad.onsetFired) now - vad.lastVoiceMs else 0}ms",
                )
                loggedAtMs = now
            }
            when (step) {
                TalkVadStep.ONSET -> {
                    log.d(TAG, "Talk command speech onset confirmed — showing recording UI")
                    onSpeechOnset()
                }
                TalkVadStep.END_SILENCE -> {
                    log.d(TAG, "Command ended on ${WakeTuning.COMMAND_SILENCE_MS}ms silence — sending")
                    break@loop
                }
                TalkVadStep.END_MAX, TalkVadStep.ABORT_NO_ONSET, TalkVadStep.CONTINUE -> Unit // limits handled above
            }
        }
        if (!vad.onsetFired) {
            log.d(TAG, "Talk command ended with no confirmed onset — dropping (no UI shown)")
            return null
        }
        val samples = command.sumOf { it.size }
        log.d(TAG, "Talk command captured: ${samples * 1000 / WakeTuning.SAMPLE_RATE_HZ}ms — confirming")
        return command
    }

    /** CONFIRMING_TALK: never emits `Confirming` (`3bee23a`); a reject clears the UI via `ConfirmFailed(false)`. */
    private suspend fun confirmTalk(gen: Long, utterance: List<ShortArray>) {
        val wav = Pcm.wav(utterance, WakeTuning.SAMPLE_RATE_HZ)
        val whisper: WhisperTranscriber? = deps.whisper
        val confirmed = when {
            whisper == null -> true // errata #5: unconfigured → unconfirmed (parity)
            !Clips.hasSpeech(utterance) -> {
                log.d(
                    TAG,
                    "Pre-Whisper gate (talk): captured clip below speech floor " +
                        "(peak=${Clips.peakFrameRms(utterance).toInt()} < ${WakeTuning.SPEECH_FLOOR_RMS.toInt()}) — rejecting",
                )
                false
            }
            else -> whisperConfirms(whisper, wav).also { if (!it) log.d(TAG, "Whisper gate rejected talk utterance") }
        }
        if (confirmed) {
            log.d(TAG, "Talk message confirmed: ${wav.size} bytes — handing off for send")
            emit(gen, WakeLoopEvent.TalkMessageCaptured(wav))
        } else {
            emit(gen, WakeLoopEvent.ConfirmFailed(isRealtime = false))
        }
    }

    /** whisper-1 within [WakeTuning.WHISPER_TIMEOUT_MS]; fail-closed on anything but a matching transcript. */
    private suspend fun whisperConfirms(whisper: WhisperTranscriber, wav: ByteArray): Boolean {
        val outcome = try {
            withTimeoutOrNull(WakeTuning.WHISPER_TIMEOUT_MS) { whisper.transcribe(wav) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WhisperOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
        return when (outcome) {
            null -> {
                log.w(WHISPER_TAG, "Whisper confirmation timed out (${WakeTuning.WHISPER_TIMEOUT_MS}ms) — rejecting (fail-closed)")
                false
            }
            is WhisperOutcome.Transcript -> {
                val verdict = WhisperGateDecision.decide(outcome.text, talk, wake)
                if (verdict.confirmed) log.d(WHISPER_TAG, "Whisper CONFIRMED (realtime=${verdict.isRealtime}) in \"${outcome.text}\"")
                else log.d(WHISPER_TAG, "Whisper REJECTED \"${outcome.text}\"")
                verdict.confirmed
            }
            else -> {
                log.w(WHISPER_TAG, "Whisper confirmation failed ($outcome) — rejecting (fail-closed)")
                false
            }
        }
    }

    // ── SpeechRecognizer fallback ─────────────────────────────────────────────────────────

    /** SR_CYCLE: the recognizer opens its own mic; a match carries no PCM, so it fires without Whisper. */
    private suspend fun runSpeechRecognizer(gen: Long): CycleOutcome {
        val sr = checkNotNull(deps.speechRecognizer)
        setPhase(gen, WakePhase.SR_CYCLE)
        return when (val outcome = sr.recognize(talk, wake)) {
            is SrOutcome.Matched -> {
                log.d(TAG, "No captured PCM (SpeechRecognizer path) — firing \"${outcome.phrase}\" without Whisper confirmation")
                emit(gen, if (outcome.isRealtime) WakeLoopEvent.WakeDetected else WakeLoopEvent.TalkDetected)
                CycleOutcome.MATCHED
            }
            SrOutcome.NoMatch -> CycleOutcome.SR_NO_MATCH
            is SrOutcome.NoSpeech -> {
                if (outcome.unhealthy) emit(gen, WakeLoopEvent.RecognizerUnhealthy)
                CycleOutcome.NO_SPEECH
            }
            is SrOutcome.Error -> if (outcome.flatDelay) CycleOutcome.ERROR_FLAT else CycleOutcome.ERROR
            SrOutcome.Cancelled -> CycleOutcome.CANCELLED
        }
    }

    private fun fmt0(v: Double) = String.format(Locale.US, "%.0f", v)

    private companion object {
        const val TAG = "WakeWordDetector"
        const val VOSK_TAG = "VoskRecogEngine"
        const val WHISPER_TAG = "WhisperConfirmer"
        const val EVENT_BUFFER = 256
        const val EMPTY_READ_BACKOFF_MS = 10L
        const val CAPTURE_LOG_INTERVAL_MS = 500L
    }
}
