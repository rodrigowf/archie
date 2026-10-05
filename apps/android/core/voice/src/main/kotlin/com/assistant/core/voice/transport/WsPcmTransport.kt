package com.assistant.core.voice.transport

import com.assistant.core.audio.AudioTuning
import com.assistant.core.audio.pcm.Pcm
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.EchoDucker
import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.audio.ports.PcmPlayer
import com.assistant.core.audio.ports.PlaybackClock
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.voice.VoiceLogMarkers
import com.assistant.core.voice.ports.ProviderEventParser
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderPhaseState
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.VoiceConnection
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.WsPcmDeps
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * WebSocket-relayed PCM providers (Qwen, Gemini; old `WebSocketPcmProvider` + `MicCapture`,
 * inv04 §2.3, §3.3 WS table, §4.7). The backend relay owns the upstream socket; this class owns
 * the device audio: mic → `voice_audio_in` (through `sendMicChunk`), `voice_audio_out` → the
 * `:core:audio` [PcmPlayer], and the drain-then-restore [EchoDucker] between them.
 *
 * Kept verbatim: 200 ms HAL settle, then mic BEFORE speaker (RS-24); 480-frame chunks; muted
 * chunks are dropped; the duck gain is applied to what is sent (RS-22); 800 ms without a speaker
 * chunk ⇒ drain → 1000 ms tail → restore, never earlier (RS-21); barge-in flushes the speaker and
 * restores at once; a relay `error` tears mic + speaker down (RS-18).
 *
 * The capture loop owns its [MicSource]: it releases it after its last read (never during one,
 * R4). [WsPcmDeps.scope] should run on an IO dispatcher in production (blocking reads/writes).
 */
class WsPcmTransport(
    private val deps: WsPcmDeps,
    private val parser: ProviderEventParser,
    override val providerId: String,
) : VoiceTransport {
    override val kind: ProviderKind = ProviderKind.WEBSOCKET

    private val log = deps.log
    private val tag = "${providerId.replaceFirstChar { it.uppercaseChar() }}VoiceProvider"
    private val scope = CoroutineScope(deps.scope.coroutineContext + SupervisorJob(deps.scope.coroutineContext[Job]))
    private val _phase = MutableStateFlow(ProviderPhaseState(ProviderPhase.OFF))
    private val _signals = MutableSharedFlow<ProviderSignal>(extraBufferCapacity = Channel.UNLIMITED)
    override val phase: StateFlow<ProviderPhaseState> get() = _phase
    override val signals: SharedFlow<ProviderSignal> get() = _signals

    @Volatile private var player: PcmPlayer? = null
    private val playbackClock = object : PlaybackClock {
        override fun headPositionFrames(): Long? = player?.headPositionFrames()
        override fun totalFramesWritten(): Long = player?.totalFramesWritten() ?: 0L
    }
    private val ducker: EchoDucker = deps.audio.echoDucker(deps.clock, playbackClock, log)

    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var muted = false
    @Volatile private var agentSpeaking = false
    @Volatile private var lastSpeakerChunkAtMs = 0L
    @Volatile private var speakerMode = SpeakerMode.CALL
    @Volatile private var preferredDevice: AudioDeviceRef? = null
    private var generation = 0
    private var captureJob: Job? = null
    private var tickJob: Job? = null

    // Level orb taps (observation only).
    private val micLevel = MicLevel()
    private val playout = PlayoutLevels()
    private val meters = AtomicInteger(0)
    @Volatile private var outRateHz = 0

    override fun setSessionUpdateFallback(fallback: () -> JsonObject?) = Unit // the backend relay applies session.update
    override fun setMicGain(level: Float) = ducker.setMicGain(level)
    override fun setEchoDuckingGain(gain: Float) = ducker.setEchoDuckingGain(gain)

    override fun toggleMute(): Boolean {
        muted = !muted
        log.i(tag, "[MIC] TOGGLE_MUTE → userMuted=$muted")
        return muted
    }

    /** Rebuilds the speaker only when the plane or the pinned device changes (the router re-applies freely). */
    override fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?) {
        val changed = speakerMode != mode || this.preferredDevice?.id != preferredDevice?.id
        speakerMode = mode
        this.preferredDevice = preferredDevice
        if (changed) player?.setSpeakerMode(mode, preferredDevice)
    }

    /** The backend relay forwards upstream commands itself. */
    override fun handleBackendCommand(command: JsonObject) = Unit

    override suspend fun connect(info: VoiceConnection, mirrorEvent: (JsonObject) -> Unit, sendMicChunk: (String) -> Unit) {
        // Only guard when the loops are alive: a `voice_status: ready` can flip the phase to ACTIVE before connect.
        val now = _phase.value.phase
        if (running && now != ProviderPhase.OFF && now != ProviderPhase.ERROR) {
            log.w(tag, "Voice session already active, state=$now")
            return
        }
        if (!deps.hasRecordPermission()) {
            fail("RECORD_AUDIO permission not granted")
            return
        }
        val inRate = info.inSampleRateHz
        val outRate = info.outSampleRateHz
        outRateHz = outRate
        playout.clear()
        log.i(tag, "Connecting $providerId voice: in=${inRate}Hz out=${outRate}Hz model=${info.model} voice=${info.voice}")
        _phase.value = ProviderPhaseState(ProviderPhase.CONNECTING)
        muted = false
        agentSpeaking = false
        lastSpeakerChunkAtMs = 0L
        ducker.resetForNewSession()
        val pb = deps.audio.pcmPlayer(deps.tracks, deps.sdkInt, outRate, scope.coroutineContext, log)
        val gen = synchronized(lock) {
            player = pb
            running = true
            ++generation
        }
        try {
            // HAL settle between the wake-word AudioRecord release and the call mic (`55037c2`).
            delay(AudioTuning.HAL_SETTLE_MS)
            if (!isCurrent(gen)) return
            // Mic before speaker: the first speaker chunk can't echo into a mic that isn't up yet.
            val mic = openMic(inRate)
            startCapture(gen, mic, sendMicChunk)
            pb.start(speakerMode, preferredDevice)
            if (!isCurrent(gen)) return
            _phase.value = ProviderPhaseState(ProviderPhase.ACTIVE)
            _signals.tryEmit(ProviderSignal.SessionCreated)
            log.i(tag, "$providerId voice session ready")
        } catch (e: CancellationException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            log.e(tag, "Failed to start $providerId voice: ${e.message}", e)
            fail(e.message ?: "$providerId start failed")
            cleanup()
        }
    }

    override suspend fun disconnect() {
        log.i(tag, "Disconnecting $providerId voice")
        cleanup()
        _phase.value = ProviderPhaseState(ProviderPhase.OFF)
        _signals.tryEmit(ProviderSignal.SessionEnded)
    }

    override fun handleProviderEvent(event: JsonObject) {
        for (signal in parser.parse(event, _phase.value.phase)) {
            when (signal) {
                is ProviderSignal.Phase -> _phase.value = ProviderPhaseState(signal.phase, signal.message)
                ProviderSignal.FlushSpeaker -> flushSpeaker()
                ProviderSignal.Teardown -> {
                    log.w(tag, "Backend relay error — tearing down voice session")
                    cleanup()
                }
                else -> _signals.tryEmit(signal)
            }
        }
    }

    override fun pushSpeakerChunk(audioB64: String) {
        if (!running) return
        val pb = player ?: return
        val pcm = try {
            Base64Pcm.decode(audioB64)
        } catch (e: Exception) {
            log.w(tag, "Failed to decode speaker chunk: ${e.message}")
            return
        }
        if (!pb.enqueue(pcm)) return
        // Every chunk refreshes the staleness clock and cancels a pending restore: the agent is
        // still talking even if the capture loop already went stale (else a self-interrupt loop).
        lastSpeakerChunkAtMs = deps.clock.nowMs()
        ducker.cancelPendingRestore()
        if (!agentSpeaking) {
            agentSpeaking = true
            ducker.duck()
        }
        val rate = outRateHz
        if (meters.get() > 0 && rate > 0) {
            playout.add(lastSpeakerChunkAtMs, pcm.size / 2 * 1000L / rate, PlayoutLevels.rmsPcm16Le(pcm))
        }
    }

    /**
     * Mic: the pre-gain RMS the capture loop already computes for `[MIC_PROBE]` (muted chunks never
     * reach it, so muted reads 0). Speaker: the RMS of each `voice_audio_out` chunk on its playout
     * timeline (computed only while collected).
     */
    override val levels: Flow<VoiceLevels> = flow {
        meters.incrementAndGet()
        try {
            while (true) {
                emit(VoiceLevels(micLevel.take(), playout.levelAt(deps.clock.nowMs())))
                delay(VoiceLevels.PERIOD_MS)
            }
        } finally {
            meters.decrementAndGet()
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────

    private fun isCurrent(gen: Int) = synchronized(lock) { running && generation == gen }

    private fun fail(message: String) {
        _phase.value = ProviderPhaseState(ProviderPhase.ERROR, message)
        _signals.tryEmit(ProviderSignal.Error(message))
    }

    private fun openMic(rate: Int): MicSource {
        val source = deps.audio.micSourcePolicy.sourceFor(deps.sdkInt)
        val bufSize = deps.audio.bufferSizing.micBufferBytes(deps.mics.minBufferBytes(rate), rate)
        return when (val r = deps.mics.open(MicSpec(rate, source, bufSize))) {
            is MicOpenResult.Opened -> r.mic.also {
                log.d(tag, "Mic started: rate=${rate}Hz source=${source.androidValue} bufSize=$bufSize")
            }
            is MicOpenResult.Failed -> throw IllegalStateException("AudioRecord failed to initialize (${r.reason})")
        }
    }

    private fun startCapture(gen: Int, mic: MicSource, send: (String) -> Unit) {
        val job = scope.launch { captureLoop(gen, mic, send) }
        // A loop cancelled before it ever ran still releases its mic (release is idempotent).
        job.invokeOnCompletion { mic.release() }
        synchronized(lock) {
            if (generation == gen && running) captureJob = job else job.cancel()
        }
    }

    private suspend fun captureLoop(gen: Int, mic: MicSource, send: (String) -> Unit) {
        val samples = ShortArray(AudioTuning.MIC_CHUNK_FRAMES)
        val bytes = ByteArray(AudioTuning.MIC_CHUNK_FRAMES * 2)
        var probeRms = 0.0
        var probePeak = 0
        var probeChunks = 0
        try {
            while (scope.isActive && isCurrent(gen)) {
                val read = mic.read(samples, 0, samples.size)
                if (read <= 0) {
                    if (read == MicSource.ERROR_INVALID_OPERATION || read == MicSource.ERROR_BAD_VALUE) {
                        log.w(tag, "AudioRecord.read error: $read — ending capture loop")
                        break
                    }
                    continue
                }
                if (!isCurrent(gen)) break
                if (muted) continue // dropped, not sent

                // Agent turn ended ⇔ no speaker chunk for AGENT_SPEECH_STALE_MS.
                if (agentSpeaking &&
                    deps.clock.nowMs() - lastSpeakerChunkAtMs > AudioTuning.AGENT_SPEECH_STALE_MS &&
                    !ducker.isRestorePending
                ) {
                    agentSpeaking = false
                    ducker.scheduleRestore("stale")
                    kickDuckTicks()
                }

                val len = read * 2
                for (i in 0 until read) {
                    val s = samples[i].toInt()
                    bytes[2 * i] = (s and 0xff).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                val (rms, peak) = deps.audio.pcm.rmsAndPeakPcm16Le(bytes, 0, len)
                micLevel.offer(rms)
                probeRms += rms
                if (peak > probePeak) probePeak = peak
                if (++probeChunks >= AudioTuning.MIC_PROBE_WINDOW_CHUNKS) {
                    log.i(tag, "${VoiceLogMarkers.MIC_PROBE} rms_avg=${(probeRms / probeChunks).toInt()} peak=$probePeak gain=${ducker.currentMicGain} ducking=$agentSpeaking")
                    probeRms = 0.0
                    probePeak = 0
                    probeChunks = 0
                }

                val gain = ducker.currentMicGain
                if (gain != 1.0f) deps.audio.pcm.applyGainPcm16Le(bytes, 0, len, gain)
                try {
                    send(Pcm.base64(bytes, 0, len))
                } catch (e: Exception) {
                    log.w(tag, "sendMicChunk failed: ${e.message}")
                }
            }
        } finally {
            mic.release()
            log.d(tag, "Mic capture loop exited")
        }
    }

    /** Drives the ducker's drain-then-restore ticks until it has nothing pending. */
    private fun kickDuckTicks() {
        synchronized(lock) {
            tickJob?.cancel()
            tickJob = scope.launch {
                while (true) {
                    val d = ducker.nextTickDelayMs ?: break
                    delay(d)
                    ducker.tick()
                }
            }
        }
    }

    /** Barge-in: drop queued + in-flight speaker audio and give the user the mic back now. */
    private fun flushSpeaker() {
        if (!running) return
        val dropped = player?.flush() ?: 0
        playout.clear()
        if (dropped > 0) log.d(tag, "Barge-in: dropped $dropped queued speaker chunks")
        agentSpeaking = false
        ducker.restoreImmediately("flush")
    }

    private fun cleanup() {
        val (job, ticks, pb) = synchronized(lock) {
            running = false
            generation++
            Triple(captureJob, tickJob, player).also {
                captureJob = null
                tickJob = null
                player = null
            }
        }
        job?.cancel()
        ticks?.cancel()
        pb?.cleanup()
        playout.clear()
        // Mid-duck: return to the saved gain so a re-connect doesn't start attenuated.
        ducker.cleanup()
        agentSpeaking = false
    }
}
