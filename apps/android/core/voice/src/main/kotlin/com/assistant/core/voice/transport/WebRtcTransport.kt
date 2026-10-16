package com.assistant.core.voice.transport

import com.assistant.core.audio.policy.DefaultMicSourcePolicy
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.voice.VoiceLogMarkers
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.delivery.LockedDataChannelGate
import com.assistant.core.voice.duck.OpenAiDucker
import com.assistant.core.voice.json.obj
import com.assistant.core.voice.json.optString
import com.assistant.core.voice.json.parseObjectOrNull
import com.assistant.core.voice.json.typeOf
import com.assistant.core.voice.parse.OpenAiEventParser
import com.assistant.core.voice.ports.IceState
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderPhaseState
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.voice.ports.RtcFactory
import com.assistant.core.voice.ports.RtcPeer
import com.assistant.core.voice.ports.RtcPeerObserver
import com.assistant.core.voice.ports.RtcPeerOptions
import com.assistant.core.voice.ports.RtcPlatform
import com.assistant.core.voice.ports.RtcStat
import com.assistant.core.voice.ports.VoiceConnection
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.WebRtcDeps
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI Realtime over WebRTC (old `OpenAIVoiceProvider`, inv04 §2.2, §4.9) on the [RtcPlatform]
 * port, so it runs on the JVM against a fake PeerConnection factory.
 *
 * Threading (`0196e2a`, `91a5df5`): every WebRTC callback (ICE state, data-channel open, message)
 * is only *posted* to a per-session inbox; one consumer coroutine on [WebRtcDeps.scope] handles
 * them in order. Nothing — teardown, phase changes, signal collectors, the data-channel sends of
 * the self-heal — ever runs on a WebRTC native thread, and a callback never waits for anything.
 * Teardown order: DC close → send-track disable + dispose → PC close → PC dispose → factory
 * dispose; each native object is disposed exactly once even with concurrent stops (RS-07).
 *
 * Commands go through a [LockedDataChannelGate] (fixes B2; RS-02/RS-03): never cleared by
 * [connect], drained at DC open, `session.update` self-heal at open and at the `session.updated`
 * echo. An OpenAI `error`, a failed SDP exchange, the 15 s open timeout and an ICE FAILED before
 * the data channel opened end in ERROR + teardown (the controller then finalizes).
 *
 * A live call's network drop is not an error (2026-10-13: a Wi-Fi blip ended voice with only a
 * toast): ICE DISCONNECTED emits [ProviderSignal.LinkDown], a recovery [ProviderSignal.LinkUp],
 * and ICE FAILED tears down and emits [ProviderSignal.LinkFailed], so the controller can tell the
 * user at once and replace the call. A tool result for a call id this OpenAI session never saw
 * (the dead call's, delivered after the replacement) is dropped with its `response.create`:
 * OpenAI answers it with an `error`, which would end the new call.
 */
class WebRtcTransport(private val deps: WebRtcDeps) : VoiceTransport {
    override val providerId: String = "openai"
    override val kind: ProviderKind = ProviderKind.WEBRTC

    private val log = deps.log
    private val scope = CoroutineScope(deps.scope.coroutineContext + SupervisorJob(deps.scope.coroutineContext[Job]))
    private val _phase = MutableStateFlow(ProviderPhaseState(ProviderPhase.OFF))
    private val _signals = MutableSharedFlow<ProviderSignal>(extraBufferCapacity = Channel.UNLIMITED)
    override val phase: StateFlow<ProviderPhaseState> get() = _phase
    override val signals: SharedFlow<ProviderSignal> get() = _signals

    private val parser = OpenAiEventParser()
    private val duck = OpenAiDucker(deps.clock, log)

    @Volatile private var fallback: (() -> JsonObject?)? = null
    private val gate = LockedDataChannelGate(
        transmit = { cmd -> current?.peer?.sendOnDataChannel(cmd.toString()) },
        fallback = { fallback?.invoke() },
        log = log,
    )

    private val lock = Any()
    @Volatile private var current: Session? = null
    @Volatile private var mirror: ((JsonObject) -> Unit)? = null
    @Volatile private var muted = false
    private var duckTimer: Job? = null
    @Volatile private var rmsCounter = 0
    private val micLevel = MicLevel()
    private val meters = AtomicInteger(0)

    /** Function-call ids this OpenAI session announced; a tool result for any other id is stale. */
    private val knownCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var dropNextResponseCreate = false

    private sealed interface Input {
        data class Ice(val state: IceState) : Input
        data object DcOpen : Input
        data class Message(val text: String) : Input
    }

    /** Native objects + the inbox of one connect. Fields guarded by [lock]. */
    private inner class Session {
        val inbox = Channel<Input>(Channel.UNLIMITED)
        val dcOpen = CompletableDeferred<Boolean>()
        @Volatile var factory: RtcFactory? = null
        @Volatile var peer: RtcPeer? = null
        @Volatile var tornDown = false
        @Volatile var consumer: Job? = null
        /** ICE reported DISCONNECTED on the open call and has not recovered (consumer coroutine only). */
        var linkDown = false

        val observer = object : RtcPeerObserver {
            override fun onIceConnectionState(state: IceState) { inbox.trySend(Input.Ice(state)) }
            override fun onDataChannelOpen() { inbox.trySend(Input.DcOpen) }
            override fun onDataChannelMessage(text: String) { inbox.trySend(Input.Message(text)) }
        }
    }

    // ── VoiceTransport ─────────────────────────────────────────────────────────────────────────

    override fun setSessionUpdateFallback(fallback: () -> JsonObject?) { this.fallback = fallback }
    override fun setMicGain(level: Float) = duck.setMicGain(level)
    override fun setEchoDuckingGain(gain: Float) = duck.setEchoDuckingGain(gain)
    override fun setEchoDuckTailMs(ms: Long) = duck.setEchoDuckTailMs(ms)

    override fun toggleMute(): Boolean {
        val nowMuted = synchronized(lock) { muted = !muted; muted }
        // The send track only: gain ducking continues independently, so an unmute mid-duck can barge in.
        try {
            current?.peer?.setSendTrackEnabled(!nowMuted)
        } catch (e: Exception) {
            log.w(TAG, "setSendTrackEnabled failed: ${e.message}")
        }
        log.i(TAG, "${VoiceLogMarkers.MIC_STATE} TOGGLE_MUTE → userMuted=$nowMuted trackEnabled=${!nowMuted}")
        return nowMuted
    }

    /** The JavaAudioDeviceModule owns its AudioTrack; it cannot switch planes without a new peer. */
    override fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?) = Unit

    override fun handleBackendCommand(command: JsonObject) {
        if (isStaleToolResult(command)) return
        gate.send(command)
    }

    /** True for a tool result (and the `response.create` right behind it) of a call this session never saw. */
    private fun isStaleToolResult(command: JsonObject): Boolean {
        val type = typeOf(command)
        if (type == "response.create" && dropNextResponseCreate) {
            dropNextResponseCreate = false
            log.w(TAG, "dropping the response.create of a stale tool result")
            return true
        }
        dropNextResponseCreate = false
        if (type != "conversation.item.create") return false
        val item = command.obj("item") ?: return false
        if (item.optString("type") != "function_call_output") return false
        val callId = item.optString("call_id")
        if (callId in knownCallIds) return false
        log.w(TAG, "dropping a tool result for call_id=$callId — not a call of this OpenAI session (a replaced call's)")
        dropNextResponseCreate = true
        return true
    }

    override fun handleProviderEvent(event: JsonObject) = Unit

    override fun pushSpeakerChunk(audioB64: String) = Unit

    /**
     * Mic: the pre-gain RMS of each ADM record buffer (peak-held between polls; only computed while
     * collected). Speaker: `getStats()` inbound audio level, polled with the same period.
     */
    override val levels: Flow<VoiceLevels> = flow {
        meters.incrementAndGet()
        val speaker = StatsSpeakerLevel()
        try {
            while (true) {
                val stats = pollStats()
                val mic = micLevel.take()
                emit(VoiceLevels(if (muted) 0f else mic, stats?.let(speaker::update) ?: 0f))
                delay(VoiceLevels.PERIOD_MS)
            }
        } finally {
            meters.decrementAndGet()
        }
    }

    override suspend fun connect(info: VoiceConnection, mirrorEvent: (JsonObject) -> Unit, sendMicChunk: (String, Boolean) -> Unit) {
        val now = _phase.value.phase
        if (now != ProviderPhase.OFF && now != ProviderPhase.ERROR) {
            log.w(TAG, "[VM] Voice session already active, state=$now")
            return
        }
        if (info.ephemeralToken.isEmpty()) {
            fail("OpenAI voice session missing ephemeral token")
            return
        }
        mirror = mirrorEvent
        val endpoint = info.endpoint.ifEmpty { VoiceTuning.DEFAULT_SDP_ENDPOINT }
        val session = Session()
        val previous = synchronized(lock) {
            muted = false
            current.also { current = session }
        }
        // A previous session that failed on its own (ICE FAILED, error event) is already torn down.
        previous?.let { teardown(it); it.consumer?.cancel(); it.inbox.close() }
        session.consumer = scope.launch { for (input in session.inbox) handle(session, input) }
        rmsCounter = 0
        _phase.value = ProviderPhaseState(ProviderPhase.CONNECTING)
        // Do NOT reset the gate here: commands drained in before connect (the session.update) must survive (`9515576`).
        log.i(TAG, "[VM] ===== SESSION START ===== endpoint=$endpoint pendingCommands=${gate.pendingCount}")

        try {
            val ok = withTimeoutOrNull(VoiceTuning.CONNECTION_TIMEOUT_MS) { establish(session, endpoint, info.ephemeralToken) }
            if (ok == null) {
                log.e(TAG, "[VM] ERROR: WebRTC connection timed out after ${VoiceTuning.CONNECTION_TIMEOUT_MS}ms")
                fail("Voice connection timed out")
                teardown(session)
            }
        } catch (e: CancellationException) {
            teardown(session)
            throw e
        } catch (e: Exception) {
            log.e(TAG, "[VM] ERROR: Failed to start voice session: ${e.javaClass.simpleName}: ${e.message}", e)
            fail(e.message ?: "Unknown error: ${e.javaClass.simpleName}")
            teardown(session)
        }
    }

    override suspend fun disconnect() {
        log.i(TAG, "[VM] ===== SESSION STOP =====")
        val session = current
        if (session != null) {
            teardown(session)
            session.consumer?.cancel()
            session.inbox.close()
        }
        _phase.value = ProviderPhaseState(ProviderPhase.OFF)
        _signals.tryEmit(ProviderSignal.SessionEnded)
    }

    // ── connect ────────────────────────────────────────────────────────────────────────────────

    /** Returns true once the data channel is open, false when the session failed or was torn down. */
    private suspend fun establish(session: Session, endpoint: String, token: String): Boolean {
        initializeGlobalsOnce(deps.platform)
        val factory = deps.platform.createFactory(audioOptions(deps.sdkInt), ::onRecordedAudio)
        if (!adopt(session) { session.factory = factory }) {
            factory.dispose()
            return false
        }
        val peer = factory.createPeer(PEER_OPTIONS, session.observer)
        if (!adopt(session) { session.peer = peer }) return false.also { teardownObjects(peer, null) }
        val offer = peer.createOffer()
        val answer = deps.sdp.exchange(endpoint, token, offer)
        if (answer == null) {
            log.e(TAG, "SDP exchange failed")
            _phase.value = ProviderPhaseState(ProviderPhase.ERROR, "SDP exchange failed")
            _signals.tryEmit(ProviderSignal.Error("OpenAI SDP exchange failed"))
            teardown(session)
            return false
        }
        peer.setRemoteAnswer(answer)
        return session.dcOpen.await()
    }

    /** Stores a native object on [session] unless it was torn down meanwhile. */
    private inline fun adopt(session: Session, store: () -> Unit): Boolean = synchronized(lock) {
        if (session.tornDown) false else { store(); true }
    }

    private fun fail(message: String) {
        _phase.value = ProviderPhaseState(ProviderPhase.ERROR, message)
        _signals.tryEmit(ProviderSignal.Error(message))
    }

    // ── callbacks (on the consumer coroutine, never on a WebRTC thread) ────────────────────────

    private fun handle(session: Session, input: Input) {
        if (session.tornDown) return
        when (input) {
            is Input.Ice -> onIce(session, input.state)
            Input.DcOpen -> onDcOpen(session)
            is Input.Message -> onMessage(session, input.text)
        }
    }

    private fun onIce(session: Session, state: IceState) {
        when (state) {
            IceState.CONNECTED, IceState.COMPLETED -> {
                log.d(TAG, "ICE $state")
                if (session.linkDown) {
                    session.linkDown = false
                    log.i(TAG, "ICE recovered — the call's media path is back")
                    _signals.tryEmit(ProviderSignal.LinkUp)
                }
            }
            IceState.DISCONNECTED -> {
                log.w(TAG, "ICE disconnected (transient — waiting for FAILED before teardown)")
                if (isOpen(session) && !session.linkDown) {
                    session.linkDown = true
                    _signals.tryEmit(ProviderSignal.LinkDown)
                }
            }
            IceState.FAILED -> {
                log.e(TAG, "ICE connection failed")
                if (isOpen(session)) {
                    // A live call lost its network: not an error — the controller replaces the call.
                    teardown(session)
                    _signals.tryEmit(ProviderSignal.LinkFailed("Connection failed"))
                } else {
                    fail("Connection failed")
                    teardown(session)
                }
            }
            IceState.CLOSED -> log.d(TAG, "ICE closed")
            else -> log.d(TAG, "ICE connection state: $state")
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun isOpen(session: Session) = session.dcOpen.isCompleted && session.dcOpen.getCompleted()

    private fun onDcOpen(session: Session) {
        _phase.value = ProviderPhaseState(ProviderPhase.ACTIVE)
        _signals.tryEmit(ProviderSignal.SessionCreated)
        log.i(TAG, "[VM] ===== DATA CHANNEL OPEN — session ready =====")
        gate.onOpen()
        session.dcOpen.complete(true)
    }

    private fun onMessage(session: Session, text: String) {
        val event = parseObjectOrNull(text) ?: run {
            log.w(TAG, "Unparseable data channel message (${text.length} chars)")
            return
        }
        val type = typeOf(event) ?: ""
        if (type !in NOISY) log.d(TAG, "[VOICE_EVENT] type=$type state=${_phase.value.phase} agentPlaying=${duck.agentPlaying}")
        // Before the mirror: the backend answers a function call with its tool result.
        rememberCallId(type, event)
        // Mirror EVERY event to the backend (persistence, tools).
        try {
            mirror?.invoke(event)
        } catch (e: Exception) {
            log.w(TAG, "voice_event mirror failed: ${e.message}")
        }
        if (type == "session.updated") gate.onSessionUpdatedEcho()
        if (type != "error") {
            duck.onDcEvent(type)
            rescheduleDuckTimer()
        }
        for (signal in parser.parse(event, _phase.value.phase)) {
            when (signal) {
                is ProviderSignal.Phase -> _phase.value = ProviderPhaseState(signal.phase, signal.message)
                ProviderSignal.Teardown -> teardown(session)
                ProviderSignal.FlushSpeaker -> Unit
                else -> _signals.tryEmit(signal)
            }
        }
    }

    private fun rememberCallId(type: String, event: JsonObject) {
        val id = when {
            type.startsWith("response.function_call_arguments") -> event.optString("call_id")
            else -> event.obj("item")?.takeIf { it.optString("type") == "function_call" }?.optString("call_id").orEmpty()
        }
        if (id.isNotEmpty()) knownCallIds += id
    }

    private fun rescheduleDuckTimer() {
        synchronized(lock) {
            duckTimer?.cancel()
            duckTimer = if (duck.nextTickDelayMs == null) null else scope.launch {
                while (true) {
                    val d = duck.nextTickDelayMs ?: break
                    delay(d)
                    duck.tick()
                }
            }
        }
    }

    /** ADM record hook (record thread): the gain, incl. the duck gain, before WebRTC processing. */
    private fun onRecordedAudio(buffer: ByteBuffer) {
        if (meters.get() > 0) micLevel.offer(rms(buffer))
        val gain = duck.currentMicGain
        if (gain != 1.0f) applyGain(buffer, gain)
        if (++rmsCounter >= VoiceTuning.RMS_LOG_INTERVAL) {
            rmsCounter = 0
            log.d(TAG, "${VoiceLogMarkers.AUDIO_RMS} rms=${rms(buffer).toInt()} gain=$gain agentPlaying=${duck.agentPlaying}")
        }
    }

    /**
     * One `getStats()` of the live peer, or null (no open session, unsupported, no answer in time).
     * Requested under [lock] so it can never reach a peer that teardown is disposing.
     */
    private suspend fun pollStats(): List<RtcStat>? {
        val session = current ?: return null
        return withTimeoutOrNull(STATS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val asked = synchronized(lock) {
                    val peer = session.peer
                    val open = session.dcOpen.isCompleted && !session.tornDown
                    if (!open || peer == null) {
                        false
                    } else {
                        try {
                            peer.requestStats { stats -> if (cont.isActive) cont.resume(stats) }
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                if (!asked) cont.resume(null)
            }
        }
    }

    // ── teardown ───────────────────────────────────────────────────────────────────────────────

    private fun teardown(session: Session) {
        val (peer, factory) = synchronized(lock) {
            if (session.tornDown) return
            session.tornDown = true
            duckTimer?.cancel()
            duckTimer = null
            (session.peer to session.factory).also { session.peer = null; session.factory = null }
        }
        log.i(TAG, "[VM] cleanup() | agentPlaying=${duck.agentPlaying} gain=${duck.currentMicGain}")
        duck.cleanup()
        gate.reset()
        session.dcOpen.complete(false)
        teardownObjects(peer, factory)
    }

    private fun teardownObjects(peer: RtcPeer?, factory: RtcFactory?) {
        if (peer != null) {
            step("dc.close") { peer.closeDataChannel() }
            step("track.disable") { peer.setSendTrackEnabled(false) }
            step("track.dispose") { peer.disposeSendTrack() }
            // ORDERING BARRIER: close() only schedules the native teardown on the signaling thread;
            // dispose() blocks until it is done. Disposing the factory first frees the mutexes that
            // thread still locks ("FORTIFY: pthread_mutex_lock called on a destroyed mutex").
            step("pc.close") { peer.close() }
            step("pc.dispose") { peer.dispose() }
        }
        if (factory != null) step("factory.dispose") { factory.dispose() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.w(TAG, "teardown $name failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OpenAIVoiceProvider"
        private const val STATS_TIMEOUT_MS = 250L

        /** `PeerConnectionFactory.initialize` is process-wide: at most once (`2a33e8d`, RS-08). */
        private val globalsInitialized = AtomicBoolean(false)

        private val NOISY = setOf(
            "response.audio.delta", "response.output_audio_transcript.delta", "response.audio_transcript.delta",
        )

        internal val PEER_OPTIONS = RtcPeerOptions(
            unifiedPlan = true,
            maxBundle = true,
            sendTrack = true,
            recvOnlyAudioTransceiver = true,
            dataChannelLabel = VoiceTuning.DATA_CHANNEL_LABEL,
            dataChannelOrdered = true,
        )

        /** Software-only AEC/NS/AGC (field choice, **LB**); mic source per API level (RS-24). */
        internal fun audioOptions(sdkInt: Int) = RtcAudioOptions(
            useHardwareAec = false,
            useHardwareNs = false,
            webRtcBasedAec = true,
            webRtcBasedNs = true,
            webRtcBasedAgc = true,
            micSource = DefaultMicSourcePolicy.sourceFor(sdkInt),
            mandatoryConstraints = VoiceTuning.GOOG_CONSTRAINTS.associateWith { "true" },
        )

        private fun initializeGlobalsOnce(platform: RtcPlatform) {
            if (!globalsInitialized.compareAndSet(false, true)) return
            try {
                platform.initializeGlobals()
            } catch (e: Exception) {
                globalsInitialized.set(false)
                throw e
            }
        }

        internal fun applyGain(buffer: ByteBuffer, gain: Float) {
            val order = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            var i = buffer.position()
            val limit = buffer.limit()
            while (i < limit - 1) {
                val amplified = (buffer.getShort(i) * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer.putShort(i, amplified.toShort())
                i += 2
            }
            buffer.order(order)
        }

        private fun rms(buffer: ByteBuffer): Double {
            val order = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var n = 0
            var i = buffer.position()
            while (i < buffer.limit() - 1) {
                val s = buffer.getShort(i).toDouble()
                sum += s * s
                n++
                i += 2
            }
            buffer.order(order)
            return if (n > 0) sqrt(sum / n) else 0.0
        }
    }
}
