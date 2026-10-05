package com.assistant.core.testing

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Monotonic test clock. Either manual ([advanceBy]) or bound to a coroutine test scheduler so
 * `delay()` in the code under test moves it (spec 14 §6.1: `FakeClock` bound to
 * `testScheduler.currentTime`). Starts at [START_MS], never at 0 (elapsedRealtime is never 0, and
 * old code used 0 as "unset").
 */
class FakeClock private constructor(
    private val scheduler: TestCoroutineScheduler?,
    startMs: Long,
) : MonotonicClock {
    constructor(startMs: Long = START_MS) : this(null, startMs)

    private var manualMs = startMs
    private val offset = startMs

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun nowMs(): Long = if (scheduler != null) offset + scheduler.currentTime else manualMs

    fun advanceBy(ms: Long) {
        check(scheduler == null) { "clock is bound to a test scheduler; advance the scheduler instead" }
        manualMs += ms
    }

    companion object {
        const val START_MS = 10_000L
        fun boundTo(scheduler: TestCoroutineScheduler, startMs: Long = START_MS) = FakeClock(scheduler, startMs)
    }
}

/** Recording [VoiceLog]; assert inv04 §10.3 markers through it. */
class RecordingLog(private val echo: Boolean = false) : VoiceLog {
    data class Line(val level: Char, val tag: String, val message: String, val thread: String)

    private val _lines = Collections.synchronizedList(mutableListOf<Line>())
    val lines: List<Line> get() = synchronized(_lines) { _lines.toList() }
    val messages: List<String> get() = lines.map { it.message }

    private fun add(level: Char, tag: String, message: String) {
        _lines += Line(level, tag, message, Thread.currentThread().name)
        if (echo) println("$level/$tag: $message")
    }

    override fun d(tag: String, message: String) = add('D', tag, message)
    override fun i(tag: String, message: String) = add('I', tag, message)
    override fun w(tag: String, message: String) = add('W', tag, message)
    override fun e(tag: String, message: String, error: Throwable?) = add('E', tag, message)

    fun contains(marker: String): Boolean = messages.any { it.contains(marker) }
    fun count(marker: String): Int = messages.count { it.contains(marker) }
    fun clear() = _lines.clear()
    fun dump(): String = messages.joinToString("\n")
}

/** Cross-fake ordering log ("cue.wakeAck", "voice.markConnecting", ...). Thread-safe. */
class OrderLog {
    private val items = Collections.synchronizedList(mutableListOf<String>())
    fun add(item: String) { items += item }
    val all: List<String> get() = synchronized(items) { items.toList() }
    fun indexOf(item: String): Int = all.indexOf(item)
    fun clear() = items.clear()
}

/**
 * Drives a tick-based pure FSM (`EchoDucker`, `OpenAiDuckPolicy`) on a manual [FakeClock] until
 * [untilMs] (absolute) or until it goes idle. [beforeTick] runs after the clock moved and before
 * each tick (update fakes there); [afterTick] observes state. Fails on a runaway zero-delay loop.
 */
fun driveTicks(
    clock: FakeClock,
    untilMs: Long,
    nextDelay: () -> Long?,
    tick: () -> Unit,
    beforeTick: (nowMs: Long) -> Unit = {},
    afterTick: (nowMs: Long) -> Unit = {},
) {
    var guard = 0
    while (true) {
        val d = nextDelay() ?: run {
            if (clock.nowMs() < untilMs) clock.advanceBy(untilMs - clock.nowMs())
            return
        }
        require(d >= 0) { "negative tick delay $d" }
        if (clock.nowMs() + d > untilMs) {
            clock.advanceBy(untilMs - clock.nowMs())
            return
        }
        clock.advanceBy(d)
        beforeTick(clock.nowMs())
        tick()
        afterTick(clock.nowMs())
        if (++guard > 1_000_000) throw AssertionError("tick loop did not progress")
    }
}

/** `obj("""{"type":"x"}""")` → JsonObject. */
fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

/** Constant-amplitude PCM frame (alternating sign → RMS == amplitude exactly). */
fun frame(samples: Int, amplitude: Int): ShortArray =
    ShortArray(samples) { i -> (if (i % 2 == 0) amplitude else -amplitude).coerceIn(-32768, 32767).toShort() }
