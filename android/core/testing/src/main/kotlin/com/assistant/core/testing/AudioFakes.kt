package com.assistant.core.testing

import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioManagerPort
import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.AudioTrackFactory
import com.assistant.core.audio.ports.AudioTrackPort
import com.assistant.core.audio.ports.MicFailure
import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.PlaybackClock
import com.assistant.core.audio.ports.TrackSpec
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/** Mutable [PlaybackClock] for EchoDucker tests. `head = null` simulates a released AudioTrack. */
class FakePlaybackClock(var head: Long? = 0L, var written: Long = 0L) : PlaybackClock {
    override fun headPositionFrames(): Long? = head
    override fun totalFramesWritten(): Long = written
}

/**
 * [AudioManagerPort] that behaves like the given API level: calling an API the level does not have
 * throws `NoSuchMethodError` (what Lollipop does, `20217b1`), and on API 31+ with
 * [bluetoothConnectGranted] false the Bluetooth profile queries throw `SecurityException`, as
 * `BluetoothAdapter.getProfileConnectionState` does without BLUETOOTH_CONNECT (`ef2aaae`).
 * Every mutation is recorded in [ops].
 */
class FakeAudioManagerPort(
    val sdkInt: Int,
    var devices: List<AudioDeviceRef> = listOf(EARPIECE, SPEAKER),
    var headsetProfileConnected: Boolean = false,
    var a2dpProfileConnected: Boolean = false,
    var wiredPlugged: Boolean = false,
    var bluetoothConnectGranted: Boolean = true,
) : AudioManagerPort {
    val ops: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val apiViolations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var communicationDevice: AudioDeviceRef? = null
        private set
    var scoStarted = false
        private set
    private val volumes = mutableMapOf<AudioStream, Int>()
    private val maxVolumes = mutableMapOf(AudioStream.VOICE_CALL to 7, AudioStream.MUSIC to 15)

    override var mode: AudioMode = AudioMode.NORMAL
        set(value) { ops += "mode=$value"; field = value }
    override var isSpeakerphoneOn: Boolean = false
        set(value) { ops += "speakerphone=$value"; field = value }
    override var isBluetoothScoOn: Boolean = false
        set(value) { ops += "scoOn=$value"; field = value }

    override fun startBluetoothSco() { ops += "startSco"; scoStarted = true }
    override fun stopBluetoothSco() { ops += "stopSco"; scoStarted = false }

    private fun requireApi(min: Int, name: String) {
        if (sdkInt < min) {
            apiViolations += "$name@$sdkInt"
            throw NoSuchMethodError("$name requires API $min (device is $sdkInt)")
        }
    }

    private fun requireBtConnect(name: String) {
        if (sdkInt >= 31 && !bluetoothConnectGranted) throw SecurityException("$name: BLUETOOTH_CONNECT not granted")
    }

    override fun outputDevices(): List<AudioDeviceRef> {
        requireApi(23, "getDevices")
        return devices
    }

    override fun availableCommunicationDevices(): List<AudioDeviceRef> {
        requireApi(31, "getAvailableCommunicationDevices")
        return devices
    }

    override fun setCommunicationDevice(device: AudioDeviceRef): Boolean {
        requireApi(31, "setCommunicationDevice")
        ops += "setCommDevice=${device.type}"
        communicationDevice = device
        return true
    }

    override fun clearCommunicationDevice() {
        requireApi(31, "clearCommunicationDevice")
        ops += "clearCommDevice"
        communicationDevice = null
    }

    override fun isBluetoothHeadsetProfileConnected(): Boolean {
        requireBtConnect("getProfileConnectionState(HEADSET)")
        return headsetProfileConnected
    }

    override fun isBluetoothA2dpProfileConnected(): Boolean {
        requireBtConnect("getProfileConnectionState(A2DP)")
        return a2dpProfileConnected
    }
    override fun isWiredHeadsetPlugged(): Boolean = wiredPlugged
    override fun streamVolume(stream: AudioStream): Int = volumes[stream] ?: 0
    override fun streamMaxVolume(stream: AudioStream): Int = maxVolumes[stream] ?: 15
    override fun setStreamVolume(stream: AudioStream, index: Int) { ops += "volume[$stream]=$index"; volumes[stream] = index }
    fun presetVolume(stream: AudioStream, index: Int, max: Int) { volumes[stream] = index; maxVolumes[stream] = max }

    companion object {
        val EARPIECE = AudioDeviceRef(1, com.assistant.core.audio.ports.DeviceType.BUILTIN_EARPIECE, "earpiece")
        val SPEAKER = AudioDeviceRef(2, com.assistant.core.audio.ports.DeviceType.BUILTIN_SPEAKER, "speaker")
        val WIRED = AudioDeviceRef(3, com.assistant.core.audio.ports.DeviceType.WIRED_HEADPHONES, "wired")
        val BT_SCO = AudioDeviceRef(4, com.assistant.core.audio.ports.DeviceType.BLUETOOTH_SCO, "bt-sco")
        val BT_A2DP = AudioDeviceRef(5, com.assistant.core.audio.ports.DeviceType.BLUETOOTH_A2DP, "bt-a2dp")
    }
}

/**
 * Microphone script: amplitude (constant-magnitude, alternating sign, so RMS == amplitude) as a
 * function of absolute clock time.
 */
fun interface MicScript {
    fun amplitudeAt(timeMs: Long): Int

    companion object {
        val SILENCE = MicScript { 0 }
        fun constant(amplitude: Int) = MicScript { amplitude }

        /** Segments of [Seg] (absolute ms, end exclusive) over a [baseline]. */
        fun segments(baseline: Int, vararg segs: Seg) = MicScript { t ->
            segs.firstOrNull { t >= it.fromMs && t < it.toMs }?.amplitude ?: baseline
        }
    }
}

data class Seg(val fromMs: Long, val toMs: Long, val amplitude: Int)

/**
 * Virtual-time microphone factory. Opens fail while `clock < busyUntilMs` or for the next
 * [failNextOpens] attempts (mic held by another app / WebRTC, RS-32).
 */
class FakeMicFactory(
    private val clock: MonotonicClock,
    var script: MicScript = MicScript.SILENCE,
    var minBuffer: Int = 1280,
) : MicSourceFactory {
    data class OpenRecord(val spec: MicSpec, val atMs: Long, val succeeded: Boolean)

    var busyUntilMs: Long = Long.MIN_VALUE

    /** Called on every successful open (cross-fake ordering assertions). */
    var onOpen: (MicSpec) -> Unit = {}
    var failNextOpens: Int = 0
    var failure: MicFailure = MicFailure.NOT_INITIALIZED
    val opens: MutableList<OpenRecord> = Collections.synchronizedList(mutableListOf())
    val mics: MutableList<FakeMic> = Collections.synchronizedList(mutableListOf())
    private val openCount = AtomicInteger(0)
    var maxConcurrentOpen = 0
        private set

    val successfulOpens: List<OpenRecord> get() = opens.filter { it.succeeded }
    val openNow: Int get() = openCount.get()

    override fun minBufferBytes(sampleRateHz: Int): Int = minBuffer

    override fun open(spec: MicSpec): MicOpenResult {
        val now = clock.nowMs()
        if (failNextOpens > 0 || now < busyUntilMs) {
            if (failNextOpens > 0) failNextOpens--
            opens += OpenRecord(spec, now, false)
            return MicOpenResult.Failed(failure)
        }
        opens += OpenRecord(spec, now, true)
        val n = openCount.incrementAndGet()
        if (n > maxConcurrentOpen) maxConcurrentOpen = n
        val mic = FakeMic(spec, clock, this)
        mics += mic
        onOpen(spec)
        return MicOpenResult.Opened(mic)
    }

    internal fun onReleased() { openCount.decrementAndGet() }
}

class FakeMic internal constructor(
    override val spec: MicSpec,
    private val clock: MonotonicClock,
    private val factory: FakeMicFactory,
) : MicSource {
    @Volatile var released = false
        private set
    @Volatile private var reading = false
    var samplesRead = 0L
        private set
    var reads = 0
        private set
    val readThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    var releaseThread: String? = null
        private set

    /** Releases or reads that overlapped an in-flight read (cross-thread teardown, R4). */
    val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Next read returns this error code once (e.g. [MicSource.ERROR_INVALID_OPERATION]). */
    var nextReadError: Int? = null

    override suspend fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        if (released) { violations += "read after release"; return MicSource.ERROR_INVALID_OPERATION }
        if (reading) violations += "concurrent read"
        nextReadError?.let { nextReadError = null; return it }
        reading = true
        try {
            readThreads += Thread.currentThread().name
            val start = clock.nowMs()
            val durationMs = length * 1000L / spec.sampleRateHz
            delay(durationMs)
            for (i in 0 until length) {
                val t = start + i * 1000L / spec.sampleRateHz
                val a = factory.script.amplitudeAt(t).coerceIn(0, 32767)
                buffer[offset + i] = (if (i % 2 == 0) a else -a).toShort()
            }
            samplesRead += length
            reads++
            return length
        } finally {
            reading = false
        }
    }

    override fun release() {
        if (reading) violations += "release during an in-flight read"
        if (released) return
        released = true
        releaseThread = Thread.currentThread().name
        factory.onReleased()
    }
}

/**
 * Virtual AudioTrack. Records every op, which write variant was used (attempts included), writes
 * after release, write times (when a clock is given) and the maximum number of concurrently
 * executing write calls (two writers ⇒ > 1 under real threads, B3).
 */
class FakeAudioTrack(
    val spec: TrackSpec,
    val index: Int,
    private val sdkInt: Int,
    private val clock: MonotonicClock? = null,
) : AudioTrackPort {
    val ops: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var released = false
        private set
    @Volatile var head: Int = 0

    /** Bytes accepted for a write of `requested` bytes (0 = buffer full). Default: everything. */
    @Volatile var acceptBytes: (requested: Int) -> Int = { it }
    @Volatile var throwOnWrite: Throwable? = null
    @Volatile var writeDelayMs: Long = 0
    val bytesWritten = AtomicInteger(0)
    val nonBlockingWrites = AtomicInteger(0)
    val blockingWrites = AtomicInteger(0)
    val writesAfterRelease = AtomicInteger(0)
    val writeTimes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger(0)
    @Volatile var maxConcurrentWrites = 0
        private set
    val writeThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override val isInitialized: Boolean get() = !released
    override fun play() { ops += "play" }
    override fun pause() { ops += "pause" }
    override fun flush() { ops += "flush" }
    override fun stop() { ops += "stop" }
    override fun release() { ops += "release"; released = true }
    override fun playbackHeadPosition(): Int = head

    private fun doWrite(size: Int): Int {
        if (released) writesAfterRelease.incrementAndGet()
        clock?.let { writeTimes += it.nowMs() }
        throwOnWrite?.let { throw it }
        val n = inFlight.incrementAndGet()
        if (n > maxConcurrentWrites) maxConcurrentWrites = n
        try {
            writeThreads += Thread.currentThread().name
            if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
            val accepted = acceptBytes(size).coerceIn(0, size)
            bytesWritten.addAndGet(accepted)
            return accepted
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun writeNonBlocking(data: ByteArray, offset: Int, size: Int): Int {
        nonBlockingWrites.incrementAndGet()
        if (sdkInt < 23) throw NoSuchMethodError("AudioTrack.write(byte[],int,int,int) on API $sdkInt")
        return doWrite(size)
    }

    override fun writeBlocking(data: ByteArray, offset: Int, size: Int): Int {
        blockingWrites.incrementAndGet()
        return doWrite(size)
    }

    override fun setPreferredDevice(device: AudioDeviceRef): Boolean {
        if (sdkInt < 23) throw NoSuchMethodError("AudioTrack.setPreferredDevice on API $sdkInt")
        ops += "preferred=${device.type}"
        return true
    }
}

class FakeAudioTrackFactory(
    private val sdkInt: Int,
    var minBuffer: Int = 4000,
    private val clock: MonotonicClock? = null,
) : AudioTrackFactory {
    val tracks: MutableList<FakeAudioTrack> = Collections.synchronizedList(mutableListOf())
    var configure: (FakeAudioTrack) -> Unit = {}

    val last: FakeAudioTrack get() = tracks.last()

    override fun minBufferBytes(sampleRateHz: Int): Int = minBuffer

    override fun create(spec: TrackSpec): AudioTrackPort =
        FakeAudioTrack(spec, tracks.size, sdkInt, clock).also { configure(it); tracks += it }
}
