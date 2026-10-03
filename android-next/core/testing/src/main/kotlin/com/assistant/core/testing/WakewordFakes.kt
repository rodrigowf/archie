package com.assistant.core.testing

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.wakeword.ports.OpenAiKeyProvider
import com.assistant.core.wakeword.ports.RecognizerText
import com.assistant.core.wakeword.ports.SrCycleRunner
import com.assistant.core.wakeword.ports.SrOutcome
import com.assistant.core.wakeword.ports.StreamingRecognizer
import com.assistant.core.wakeword.ports.StreamingRecognizerFactory
import com.assistant.core.wakeword.ports.VoskModelSource
import com.assistant.core.wakeword.ports.WhisperOutcome
import com.assistant.core.wakeword.ports.WhisperTranscriber
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Vosk stand-in. The text the recognizer "hears" is a function of absolute clock time ([script]),
 * which matches how a real recognizer's partial grows while the user speaks. Records the thread of
 * every call so single-thread confinement (R4) can be asserted, and flags overlapping calls.
 */
class FakeRecognizerFactory(
    private val clock: MonotonicClock,
    var script: (timeMs: Long) -> String = { "" },
) : StreamingRecognizerFactory {
    val created: MutableList<FakeRecognizer> = Collections.synchronizedList(mutableListOf())
    val grammars: List<String> get() = created.map { it.grammar }

    override fun create(grammarJson: String, sampleRateHz: Int): StreamingRecognizer =
        FakeRecognizer(grammarJson, sampleRateHz, clock, this).also { created += it }

    companion object {
        /** Text schedule: each pair is (absolute start ms, text); the latest started entry wins. */
        fun schedule(vararg entries: Pair<Long, String>): (Long) -> String = { t ->
            entries.filter { it.first <= t }.maxByOrNull { it.first }?.second ?: ""
        }
    }
}

class FakeRecognizer internal constructor(
    val grammar: String,
    val sampleRateHz: Int,
    private val clock: MonotonicClock,
    private val factory: FakeRecognizerFactory,
) : StreamingRecognizer {
    val createThread: String = Thread.currentThread().name
    val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf(createThread))
    val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var accepted = 0L
        private set
    var acceptCalls = 0
        private set
    val acceptSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    var resets = 0
        private set
    @Volatile var closed = false
        private set
    var closeThread: String? = null
        private set
    @Volatile private var inCall = false

    override fun accept(samples: ShortArray, count: Int): RecognizerText {
        if (closed) violations += "accept after close"
        if (inCall) violations += "concurrent accept"
        inCall = true
        try {
            threads += Thread.currentThread().name
            accepted += count
            acceptCalls++
            acceptSizes += count
            return RecognizerText(factory.script(clock.nowMs()), isFinal = false)
        } finally {
            inCall = false
        }
    }

    override fun partial(): String {
        if (closed) violations += "partial after close"
        threads += Thread.currentThread().name
        return factory.script(clock.nowMs())
    }

    override fun reset() {
        threads += Thread.currentThread().name
        resets++
    }

    override fun close() {
        if (inCall) violations += "close during accept"
        threads += Thread.currentThread().name
        closeThread = Thread.currentThread().name
        closed = true
    }
}

class FakeVoskModelSource(var factory: StreamingRecognizerFactory?) : VoskModelSource {
    val loads = AtomicInteger(0)
    override suspend fun load(): StreamingRecognizerFactory? {
        loads.incrementAndGet()
        return factory
    }
}

/** Whisper stand-in: fixed latency (virtual time) + scripted outcome per call. */
class FakeWhisper(
    private val clock: MonotonicClock,
    var latencyMs: Long = 500,
    var outcome: (callIndex: Int) -> WhisperOutcome = { WhisperOutcome.Transcript("") },
) : WhisperTranscriber {
    data class Call(val wav: ByteArray, val atMs: Long)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    override suspend fun transcribe(wav: ByteArray): WhisperOutcome {
        val index = calls.size
        calls += Call(wav, clock.nowMs())
        delay(latencyMs)
        return outcome(index)
    }

    companion object {
        fun saying(clock: MonotonicClock, text: String, latencyMs: Long = 500) =
            FakeWhisper(clock, latencyMs) { WhisperOutcome.Transcript(text) }
    }
}

/** SpeechRecognizer fallback stand-in: each cycle takes [cycleMs] and returns the next scripted outcome. */
class FakeSrRunner(var cycleMs: Long = 1500, outcomes: List<SrOutcome> = emptyList()) : SrCycleRunner {
    private val queue = ArrayDeque(outcomes)
    var fallback: SrOutcome = SrOutcome.NoMatch
    val warms = AtomicInteger(0)
    val cycles = AtomicInteger(0)
    val tearDowns = AtomicInteger(0)
    val refreshMarks = AtomicInteger(0)

    fun enqueue(vararg outcomes: SrOutcome) { queue.addAll(outcomes) }

    override suspend fun warm() { warms.incrementAndGet() }

    override suspend fun recognize(talkVariants: List<String>, wakeVariants: List<String>): SrOutcome {
        cycles.incrementAndGet()
        delay(cycleMs)
        return queue.removeFirstOrNull() ?: fallback
    }

    override fun markNeedsRefresh() { refreshMarks.incrementAndGet() }
    override suspend fun tearDown() { tearDowns.incrementAndGet() }
}

class FakeKeyProvider(var key: String? = "sk-test") : OpenAiKeyProvider {
    val fetches = AtomicInteger(0)
    override suspend fun fetchKey(): String? { fetches.incrementAndGet(); return key }
}

/** Parses a canonical 44-byte-header mono PCM16 WAV. */
object WavReader {
    data class Wav(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val samples: Int)

    fun read(bytes: ByteArray): Wav {
        fun le32(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or ((bytes[o + 3].toInt() and 0xff) shl 24)
        fun le16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
        check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not WAVE" }
        val channels = le16(22)
        val rate = le32(24)
        val bits = le16(34)
        val dataSize = le32(40)
        return Wav(rate, channels, bits, dataSize / (bits / 8) / channels)
    }
}
