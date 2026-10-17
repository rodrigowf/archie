package com.assistant.archie.tv.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.assistant.core.audio.ports.MonotonicClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One open recorder on the remote's microphone. [read] BLOCKS while the mic button is up (spec 15 §2). */
interface RemoteRecorder {
    /** Samples read (> 0), 0 (nothing), or < 0 (an AudioRecord error: the recorder is dead). */
    fun read(buffer: ShortArray): Int

    /** Unblocks a pending [read] (AudioRecord.stop). Safe from any thread. */
    fun stop()

    /** Releases the recorder. Called by the reader thread after its last read (R4). */
    fun release()
}

/** Opens the recorder; null when the platform refuses (no permission, mic busy). */
fun interface RemoteRecorderFactory {
    fun open(): RemoteRecorder?
}

/** Production [RemoteRecorder]: `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)`, as the probe. */
object AndroidRemoteRecorders : RemoteRecorderFactory {
    @SuppressLint("MissingPermission") // checked by RemoteMicSource before opening
    override fun open(): RemoteRecorder? {
        val rate = TvVoiceTuning.SAMPLE_RATE_HZ
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(TvVoiceTuning.REMOTE_MIC_SOURCE.androidValue, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3_200) * 2)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed: ${e.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val started = runCatching { record.startRecording(); record.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false)
        if (!started) {
            record.release()
            return null
        }
        return object : RemoteRecorder {
            override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)
            override fun stop() { runCatching { record.stop() } }
            override fun release() { runCatching { record.release() } }
        }
    }

    private const val TAG = "ArchieTv/RemoteMic"
}

/** Where [RemoteMicSource] is. */
enum class RemoteMicState {
    /** Not armed (the app is in the background). */
    OFF,

    /** Armed: holding the mic button produces bursts. */
    READY,

    /** RECORD_AUDIO not granted. */
    NO_PERMISSION,

    /** The recorder could not be opened (retried while in front). */
    UNAVAILABLE,

    /**
     * This device's microphone streams continuously from the moment it is armed (`read()` never
     * blocks, as on phones and most Android TV boxes): there is no "button held" signal, so the
     * source disarmed itself. Probed again the next time the app comes to the front.
     */
    UNSUPPORTED,
}

/**
 * The remote's microphone (spec 15 §4.1): ONE recorder, armed while the app is started
 * ([start] / [stop] from the process lifecycle), a reader thread that blocks in `read()` while
 * the button is up, and a watchdog that measures the gap. Bursts go to [sink] in order
 * (Start → Frame… → End), on the reader / watchdog threads, under one lock: the sink must be quick.
 *
 * The live voice call never opens a second recorder: its transports read the frames this source
 * hands to [sink] (spec 15 §4.2, `LiveMicBridge`).
 *
 * Debug builds feed a WAV through [inject] — exactly the path of a real burst (`DebugMicReceiver`).
 */
class RemoteMicSource(
    private val recorders: RemoteRecorderFactory,
    private val clock: MonotonicClock,
    private val hasPermission: () -> Boolean,
    private val sink: (MicBurst) -> Unit,
    private val log: (String) -> Unit = { Log.d("ArchieTv/RemoteMic", it) },
    /** Watchdog + re-arm timers; null in JVM tests (they call [tick] themselves). */
    private val timers: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-mic-watchdog").apply { isDaemon = true } },
    private val startThread: (Runnable) -> Unit = { r -> Thread(r, "tv-mic-reader").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start() },
) {
    private val lock = Any()
    private val detector = BurstDetector()
    private val _state = MutableStateFlow(RemoteMicState.OFF)
    val state: StateFlow<RemoteMicState> = _state.asStateFlow()

    // ── guarded by [lock] ──
    private var wanted = false
    private var recorder: RemoteRecorder? = null
    private var generation = 0
    private var watchdog: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var injecting = 0

    /** When the current recorder was armed (the continuous-stream probe's reference). */
    private var armedAt = 0L

    /** The continuous-stream probe still runs (the first [TvVoiceTuning.PROBE_MS] after arming). */
    private var probing = false

    /**
     * A burst that began right at arm time, held back until it proves to be a real press (it ends
     * before the probe does) or a continuous stream (it does not: the source is unsupported).
     */
    private var held: MutableList<MicBurst>? = null

    /** [route] as a function value, allocated once (the reader calls it for every frame). */
    private val routeFn: (MicBurst) -> Unit = { route(it) }

    /** Arms the source (idempotent): the app came to the front, or the permission was just granted. */
    fun start() {
        synchronized(lock) {
            wanted = true
            if (recorder != null) return
            retry?.cancel(false)
            retry = null
            if (!hasPermission()) {
                _state.value = RemoteMicState.NO_PERMISSION
                return
            }
            val rec = recorders.open()
            if (rec == null) {
                _state.value = RemoteMicState.UNAVAILABLE
                log("recorder unavailable — retrying in ${TvVoiceTuning.REARM_RETRY_MS} ms")
                retry = timers?.schedule({ startIfWanted() }, TvVoiceTuning.REARM_RETRY_MS, TimeUnit.MILLISECONDS)
                return
            }
            recorder = rec
            armedAt = clock.nowMs()
            probing = true
            held = null
            val gen = ++generation
            ensureWatchdog()
            _state.value = RemoteMicState.READY
            log("armed (VOICE_RECOGNITION, ${TvVoiceTuning.SAMPLE_RATE_HZ} Hz)")
            startThread { readLoop(gen, rec) }
        }
    }

    /**
     * A queued re-arm (after a failed open or a read error): arms only while the app still wants
     * the mic — a retry that fires after [stop] (the app went to the background) does nothing.
     */
    internal fun startIfWanted() {
        synchronized(lock) {
            if (!wanted) return
            start()
        }
    }

    /** Disarms (the app went to the background): a burst in progress ends now. */
    fun stop() {
        val rec = synchronized(lock) {
            wanted = false
            retry?.cancel(false)
            retry = null
            generation++
            endBurst()
            probing = false
            if (injecting == 0) stopWatchdog()
            _state.value = RemoteMicState.OFF
            recorder.also { recorder = null }
        }
        rec?.stop() // unblocks the reader, which releases it
        if (rec != null) log("disarmed")
    }

    private fun readLoop(gen: Int, rec: RemoteRecorder) {
        val buf = ShortArray(TvVoiceTuning.READ_SAMPLES)
        try {
            while (true) {
                val n = rec.read(buf)
                if (!step(gen, buf, n)) break
            }
        } finally {
            rec.release()
        }
    }

    /** One read's result. False = this reader must exit (stopped, replaced, or the recorder died). */
    internal fun step(gen: Int, buf: ShortArray, n: Int): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            if (n < 0) {
                log("read error $n — re-arming")
                endBurst()
                probing = false
                recorder = null
                _state.value = RemoteMicState.UNAVAILABLE
                if (wanted) retry = timers?.schedule({ startIfWanted() }, TvVoiceTuning.REARM_RETRY_MS, TimeUnit.MILLISECONDS)
                return false
            }
            val now = clock.nowMs()
            if (n > 0) detector.onFrame(now, buf.copyOf(n), routeFn)
            if (checkProbe(now)) {
                recorder = null // this reader exits and releases it
                return false
            }
            return true
        }
    }

    /** Watchdog tick: ends a burst after the gap. */
    internal fun tick() {
        val rec = synchronized(lock) {
            val now = clock.nowMs()
            detector.onTick(now, routeFn)
            if (checkProbe(now)) recorder.also { recorder = null } else null
        }
        rec?.stop() // unblocks the reader, which releases it
    }

    /**
     * Every detector event passes here (under [lock]). A burst that starts within
     * [TvVoiceTuning.PROBE_START_MS] of arming is held back while the probe runs; anything else goes
     * straight to [sink].
     */
    private fun route(e: MicBurst) {
        val h = held
        when {
            h != null -> {
                h += e
                if (e is MicBurst.End) { // it paused: a real press at arm time — deliver it now
                    held = null
                    probing = false
                    h.forEach(sink)
                }
            }
            e is MicBurst.Start && probing && e.atMs - armedAt < TvVoiceTuning.PROBE_START_MS -> held = mutableListOf(e)
            else -> sink(e)
        }
    }

    /**
     * Ends the probe once [TvVoiceTuning.PROBE_MS] have passed since arming. True = the source
     * just turned out to stream continuously: the held burst (audio since arming) is dropped, the
     * source is [RemoteMicState.UNSUPPORTED] and disarmed (the caller stops the recorder; a reader
     * exits on the generation change).
     */
    private fun checkProbe(now: Long): Boolean {
        if (!probing || now - armedAt < TvVoiceTuning.PROBE_MS) return false
        probing = false
        if (held == null) return false
        held = null
        detector.reset()
        generation++
        if (injecting == 0) stopWatchdog()
        _state.value = RemoteMicState.UNSUPPORTED
        log("frames never stopped since arming — this microphone streams continuously; hold-to-talk is unsupported, disarmed")
        return true
    }

    /** A burst in progress ends now: a held one is dropped (nothing of it was delivered). */
    private fun endBurst() {
        if (held != null) {
            held = null
            detector.reset()
        } else {
            detector.flush(sink)
        }
    }

    /**
     * Debug builds only (`DebugMicReceiver`): plays [pcm] (16 kHz mono) through the burst path in
     * real time, 20 ms per frame, as if the button were held for its length, then lets the watchdog
     * end the burst. Works whether or not the recorder is armed.
     */
    fun inject(pcm: ShortArray) {
        synchronized(lock) {
            injecting++
            ensureWatchdog()
        }
        startThread {
            try {
                val step = TvVoiceTuning.READ_SAMPLES
                var at = 0
                while (at < pcm.size) {
                    val end = minOf(pcm.size, at + step)
                    synchronized(lock) { detector.onFrame(clock.nowMs(), pcm.copyOfRange(at, end), routeFn) }
                    at = end
                    Thread.sleep(step * 1000L / TvVoiceTuning.SAMPLE_RATE_HZ)
                }
                Thread.sleep(TvVoiceTuning.BURST_END_GAP_MS + 2 * TvVoiceTuning.WATCHDOG_TICK_MS)
            } finally {
                synchronized(lock) {
                    injecting--
                    if (injecting == 0 && recorder == null) stopWatchdog()
                }
            }
        }
    }

    private fun ensureWatchdog() {
        if (watchdog == null) {
            watchdog = timers?.scheduleWithFixedDelay({ tick() }, TvVoiceTuning.WATCHDOG_TICK_MS, TvVoiceTuning.WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopWatchdog() {
        watchdog?.cancel(false)
        watchdog = null
    }
}
