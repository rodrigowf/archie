package com.assistant.core.markdown

import com.assistant.core.markdown.internal.ParseStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * B-02 DoD: a long-stream stress test with bounded allocations. 5,000 deltas build a 20,000-char
 * assistant message (prose, lists, a 3 KB fence, tables, headings). The engine's work per delta
 * must not grow with the message: parsed characters stay O(tail), and the allocation of the last
 * quarter of deltas is no larger than that of the first quarter (a whole-message re-parse per
 * delta, the 31c2fbf failure shape, would grow linearly and parse ~250× more characters).
 */
class StreamingStressTest {

    private fun message(): String = buildString {
        var i = 0
        while (length < 20_000) {
            append("## Step $i\n\nThe reconnect loop now waits **3,000 ms** between attempts, keeps the `outbox` and ")
            append("logs to [the journal](https://example.com/$i). It never drops frames under load.\n\n")
            for (k in 0 until 6) append("- item $k of step $i with `code` and *emphasis*\n")
            append("\n| Field | Value |\n|---|---:|\n| attempts | $i |\n| delay | ${i * 3} s |\n\n")
            if (i % 4 == 1) {
                append("```kotlin\n")
                for (l in 0 until 60) append("val line$l = compute($l, \"step $i\") // note\n")
                append("```\n\n")
            }
            append("> Quote for step $i with a [link](https://example.com/q$i).\n\n")
            i++
        }
        setLength(20_000)
        append("\n")
    }

    private fun allocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    @Test
    fun fiveThousandDeltasIntoATwentyThousandCharMessage() {
        val text = message()
        // 5,000 deltas of 1..7 chars (mean 4), with a fixed seed.
        val rnd = Random(42)
        val deltas = ArrayList<String>(5_000)
        var pos = 0
        while (pos < text.length) {
            val n = if (deltas.size >= 4_999) text.length - pos else minOf(text.length - pos, 1 + rnd.nextInt(7))
            deltas += text.substring(pos, pos + n)
            pos += n
        }
        assertTrue("${deltas.size} deltas", deltas.size in 4_500..5_000)

        repeat(2) { MarkdownDocument().also { d -> deltas.forEach { d.append(it) } }.finish() } // JIT warm-up

        ParseStats.reset()
        val doc = MarkdownDocument()
        val quarter = deltas.size / 4
        val allocByQuarter = LongArray(4)
        var maxParsedPerDelta = 0L
        var bigParses = 0
        var naiveChars = 0L
        var length = 0
        for ((i, d) in deltas.withIndex()) {
            val before = ParseStats.parsedChars
            val a0 = allocatedBytes()
            doc.append(d)
            allocByQuarter[minOf(3, i / quarter)] += allocatedBytes() - a0
            val parsedNow = ParseStats.parsedChars - before
            maxParsedPerDelta = maxOf(maxParsedPerDelta, parsedNow)
            if (parsedNow > 600) bigParses++
            length += d.length
            naiveChars += length
        }
        val final = doc.finish()
        assertEquals(MarkdownDocument.parse(text), final.stable)

        val parsed = ParseStats.parsedChars
        println(
            "stress: ${deltas.size} deltas, ${text.length} chars, parsed=$parsed (naive=$naiveChars), " +
                "maxPerDelta=$maxParsedPerDelta, alloc/quarter MB=${allocByQuarter.map { it / 1_000_000.0 }}",
        )
        // Work is O(tail): a delta parses at most the largest top-level block (a 2.6 KB fence, parsed
        // once, when it closes; never while open); every other delta parses < 600 chars.
        val fences = text.split("```kotlin").size - 1
        assertTrue("max parsed per delta $maxParsedPerDelta", maxParsedPerDelta <= 3_000)
        assertTrue("$bigParses deltas parsed > 600 chars ($fences fences)", bigParses <= fences)
        assertTrue("parsed $parsed vs naive $naiveChars", parsed * 100 < naiveChars)
        // Allocation does not grow with the message length.
        val first = allocByQuarter[0]
        val last = allocByQuarter[3]
        assertTrue("allocation grew: first=$first last=$last", last <= first * 2)
        assertTrue("total allocation ${allocByQuarter.sum()} bytes", allocByQuarter.sum() < 120_000_000L)
    }
}
