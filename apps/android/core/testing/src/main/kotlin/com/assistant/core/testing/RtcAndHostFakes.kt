package com.assistant.core.testing

import com.assistant.core.voice.ports.IceState
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.voice.ports.RtcFactory
import com.assistant.core.voice.ports.RtcPeer
import com.assistant.core.voice.ports.RtcPeerObserver
import com.assistant.core.voice.ports.RtcPeerOptions
import com.assistant.core.voice.ports.RtcPlatform
import com.assistant.core.voice.ports.SdpExchange
import com.assistant.core.voicehost.ports.WakeConfigStore
import com.assistant.core.voicehost.ports.WakeEngineHandle
import com.assistant.core.voicehost.ports.WakeEngineProvider
import com.assistant.core.voicehost.ports.WakeServiceConfig
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake `org.webrtc` stack (spec 14 A-06 "fake PC factory"). Every teardown op is appended to [ops]
 * as "<op>@<thread>" so tests can assert order and that nothing ran on a WebRTC callback thread.
 * Callback threads are simulated by the tests (named threads calling [FakeRtcPeer.observer]).
 */
class FakeRtcPlatform : RtcPlatform {
    val initializeCalls = AtomicInteger(0)
    val factories: MutableList<FakeRtcFactory> = Collections.synchronizedList(mutableListOf())
    val ops: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Whether new peers report the data channel open immediately after the answer is set. */
    var openDataChannelOnAnswer = true

    val lastPeer: FakeRtcPeer get() = factories.last().peers.last()

    override fun initializeGlobals() { initializeCalls.incrementAndGet() }

    override fun createFactory(audio: RtcAudioOptions, micHook: (ByteBuffer) -> Unit): RtcFactory =
        FakeRtcFactory(audio, micHook, this).also { factories += it }

    internal fun op(name: String) { ops += "$name@${Thread.currentThread().name}" }

    /** Ops without the thread suffix. */
    val opNames: List<String> get() = ops.map { it.substringBefore('@') }
}

class FakeRtcFactory internal constructor(
    val audio: RtcAudioOptions,
    val micHook: (ByteBuffer) -> Unit,
    private val platform: FakeRtcPlatform,
) : RtcFactory {
    val peers: MutableList<FakeRtcPeer> = Collections.synchronizedList(mutableListOf())
    val disposes = AtomicInteger(0)

    override fun createPeer(options: RtcPeerOptions, observer: RtcPeerObserver): RtcPeer =
        FakeRtcPeer(options, observer, platform).also { peers += it }

    override fun dispose() { disposes.incrementAndGet(); platform.op("factory.dispose") }
}

class FakeRtcPeer internal constructor(
    val options: RtcPeerOptions,
    val observer: RtcPeerObserver,
    private val platform: FakeRtcPlatform,
) : RtcPeer {
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var remoteAnswer: String? = null
    @Volatile override var isDataChannelOpen: Boolean = false
    var trackEnabled = true
    val dcCloses = AtomicInteger(0)
    val trackDisposes = AtomicInteger(0)
    val closes = AtomicInteger(0)
    val disposes = AtomicInteger(0)

    override suspend fun createOffer(): String = "v=0 offer"

    override suspend fun setRemoteAnswer(sdp: String) {
        remoteAnswer = sdp
        if (platform.openDataChannelOnAnswer) openDataChannel()
    }

    /** Simulates the `oai-events` channel reaching OPEN (delivered to the observer on this thread). */
    fun openDataChannel() {
        isDataChannelOpen = true
        observer.onDataChannelOpen()
    }

    override fun sendOnDataChannel(text: String): Boolean {
        if (!isDataChannelOpen) return false
        sent += text
        return true
    }

    override fun setSendTrackEnabled(enabled: Boolean) { trackEnabled = enabled; platform.op("track.enabled=$enabled") }
    override fun closeDataChannel() { dcCloses.incrementAndGet(); isDataChannelOpen = false; platform.op("dc.close") }
    override fun disposeSendTrack() { trackDisposes.incrementAndGet(); platform.op("track.dispose") }
    override fun close() { closes.incrementAndGet(); platform.op("pc.close") }
    override fun dispose() { disposes.incrementAndGet(); platform.op("pc.dispose") }

    /** Deliver an ICE state change from a named "signalling" thread and wait for the callback to return. */
    fun iceStateFromThread(state: IceState, threadName: String = "signaling_thread") =
        onThread(threadName) { observer.onIceConnectionState(state) }

    /** Deliver a data-channel message from a named WebRTC network thread. */
    fun messageFromThread(text: String, threadName: String = "network_thread") =
        onThread(threadName) { observer.onDataChannelMessage(text) }

    private fun onThread(name: String, block: () -> Unit) {
        var error: Throwable? = null
        val t = Thread({ try { block() } catch (e: Throwable) { error = e } }, name)
        t.start()
        t.join(5_000)
        error?.let { throw it }
    }
}

class FakeSdpExchange(var answer: String? = "v=0 answer") : SdpExchange {
    data class Call(val endpoint: String, val token: String, val offer: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    override suspend fun exchange(endpoint: String, ephemeralToken: String, offerSdp: String): String? {
        calls += Call(endpoint, ephemeralToken, offerSdp)
        return answer
    }
}

/** In-memory `assistant_service_prefs`. [load] applies the old defaults (enabled=false, …). */
class FakeWakeConfigStore(var stored: WakeServiceConfig? = null) : WakeConfigStore {
    val saves: MutableList<WakeServiceConfig> = Collections.synchronizedList(mutableListOf())

    override fun load(): WakeServiceConfig = stored ?: DEFAULTS

    override fun save(config: WakeServiceConfig) { stored = config; saves += config }

    companion object {
        val DEFAULTS = WakeServiceConfig(false, "my friend", "wake up", 1.0f, 2.0f, "")
    }
}

class FakeWakeEngineHandle(val config: WakeServiceConfig, private val order: OrderLog? = null) : WakeEngineHandle {
    override var isActive = false
    override var isPaused = false
    override var isBusy = false
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun start() { calls += "start"; order?.add("engine.start"); isActive = true; isPaused = false }
    override fun pause() { calls += "pause"; order?.add("engine.pause"); if (isActive) isPaused = true }
    override fun resume() { calls += "resume"; order?.add("engine.resume"); isPaused = false }
    override fun stop() { calls += "stop"; order?.add("engine.stop"); isActive = false; isPaused = false }
}

class FakeWakeEngineProvider(private val order: OrderLog? = null) : WakeEngineProvider {
    val created: MutableList<FakeWakeEngineHandle> = Collections.synchronizedList(mutableListOf())
    val last: FakeWakeEngineHandle get() = created.last()

    override fun create(config: WakeServiceConfig): WakeEngineHandle =
        FakeWakeEngineHandle(config, order).also { created += it }

    /** Number of engines that were started. */
    val starts: Int get() = created.count { "start" in it.calls }
}
