package com.assistant.archie.feature.toolcards

import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolProgressInfo
import com.assistant.core.conversation.ToolStatus
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `format.test.ts` + `computeDiff` tests of the web (W-10), plus the timing rules. */
class ToolFormatTest {
    @After fun restoreClock() {
        ToolTiming.clock = System::currentTimeMillis
        ToolTiming.reset()
    }

    @Test fun shortPathKeepsTheLastTwoSegmentsOfDeepPaths() {
        assertEquals("…/orchestrator/session.py", shortPath("/home/rodrigo/assistant/orchestrator/session.py"))
        assertEquals("orchestrator/session.py", shortPath("orchestrator/session.py"))
        assertEquals("a/b/c", shortPath("a/b/c"))
    }

    @Test fun stripAnsiRemovesCsiOscAndNormalisesCrlf() {
        assertEquals("46 passed in 3.8s", stripAnsi("\u001b[32m46 passed\u001b[0m in 3.8s"))
        assertEquals("ok\nnext", stripAnsi("\u001b]0;title\u0007ok\r\nnext"))
        assertEquals("E", stripAnsi("\u001b[1;31mE\u001b[K"))
        assertEquals("plain", stripAnsi("plain"))
    }

    @Test fun truncateOutputCutsAt200LinesOr20Kb() {
        val many = (1..450).joinToString("\n") { "line $it" }
        val t = truncateOutput(many)
        assertTrue(t.truncated)
        assertEquals(450, t.totalLines)
        assertEquals(200, t.shownLines)
        assertTrue(t.text.endsWith("line 200"))
        val wide = "x".repeat(50_000)
        val w = truncateOutput(wide)
        assertTrue(w.truncated)
        assertEquals(20 * 1024, w.text.length)
        assertFalse(truncateOutput("a\nb").truncated)
    }

    @Test fun countLinesIgnoresATrailingNewline() {
        assertEquals(0, countLines(""))
        assertEquals(1, countLines("a"))
        assertEquals(2, countLines("a\nb\n"))
    }

    @Test fun clockAndDurationFormats() {
        assertEquals("0:12", formatClock(12.0))
        assertEquals("2:14", formatClock(134.0))
        assertEquals("1:02:03", formatClock(3723.0))
        assertEquals("0.2s", formatDuration(200))
        assertEquals("3.8s", formatDuration(3820))
        assertEquals("42s", formatDuration(42_000))
        assertEquals("1:05", formatDuration(65_000))
    }

    @Test fun parseExitCodeReadsClaudeAndQwenShapes() {
        assertEquals(2, parseExitCode("Error: Exit code 2\nboom"))
        assertEquals(1, parseExitCode("Exit Code: 1"))
        assertNull(parseExitCode("all good"))
    }

    @Test fun misc() {
        assertEquals("first …", firstLine("\n  first\nsecond"))
        assertEquals("developer.android.com/x", stripScheme("https://developer.android.com/x"))
        assertEquals("tsx", languageFromPath("/a/b.tsx"))
        assertEquals("dockerfile", languageFromPath("Dockerfile"))
        assertNull(languageFromPath("notes.unknownext"))
        assertEquals("12,345", groupDigits(12_345))
    }

    @Test fun diffMarksAddedAndRemovedLinesAndCountsThem() {
        val d = ToolDiff.compute("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(2, d.added)
        assertEquals(1, d.removed)
        assertEquals(listOf("Ctx:a", "Del:b", "Add:B", "Ctx:c", "Add:d"), d.lines.map { "${it.kind}:${it.text}" })
        assertEquals("+2 −1", d.stat)
    }

    @Test fun diffFoldsLongUnchangedRunsKeepingThreeLinesOfContext() {
        val before = (0 until 30).map { "l$it" }
        val after = before.toMutableList().also { it[15] = "changed" }
        val d = ToolDiff.compute(before.joinToString("\n") + "\n", after.joinToString("\n") + "\n")
        assertEquals(2, d.lines.count { it.kind == DiffLineKind.Fold })
        assertEquals(DiffLine(DiffLineKind.Fold, "12 unchanged lines", (0 until 12).map { "l$it" }), d.lines[0])
        assertEquals("changed", d.lines.first { it.kind == DiffLineKind.Add }.text)
        assertEquals(6, d.lines.count { it.kind == DiffLineKind.Ctx })
        // A fold keeps the lines it hides (the Android view expands it in place, spec 14 §3.5).
        assertEquals((19 until 30).map { "l$it" }, d.lines.last().hidden)
    }

    @Test fun diffOfIdenticalTextIsAllContext() {
        val d = ToolDiff.compute("a\nb\n", "a\nb\n")
        assertEquals(0, d.added + d.removed)
        // Like the web: an unchanged run with no change on either side folds entirely.
        assertEquals(listOf(DiffLine(DiffLineKind.Fold, "2 unchanged lines", listOf("a", "b"))), d.lines)
    }

    private fun live(status: ToolStatus, progress: Double? = null, origin: BlockOrigin = BlockOrigin.LIVE) =
        ToolBlock("b9", "t9", "Bash", JsonObject(emptyMap()), status, progress = progress?.let { ToolProgressInfo(it, null) }, origin = origin)

    @Test fun timingIsMeasuredFromFirstSightAndNeverFabricated() {
        var now = 1_000_000L
        ToolTiming.clock = { now }
        assertEquals(0.0, ToolTiming.of(live(ToolStatus.RUNNING)).runningSeconds)
        assertNull(ToolTiming.of(live(ToolStatus.RUNNING)).label) // under 1 s: no clock in the header
        now += 12_400
        assertEquals("0:12", ToolTiming.of(live(ToolStatus.RUNNING)).label)
        // tool_progress.elapsed_seconds wins when larger
        assertEquals("0:40", ToolTiming.of(live(ToolStatus.RUNNING, progress = 40.0)).label)
        now += 1_400
        assertEquals("14s", ToolTiming.of(live(ToolStatus.DONE)).label)
        assertEquals(13_800L, ToolTiming.of(live(ToolStatus.DONE)).durationMs)
        // First seen finished, or from history: no duration.
        val hist = ToolBlock("h1", "t", "Bash", JsonObject(emptyMap()), ToolStatus.DONE, origin = BlockOrigin.HISTORY)
        assertNull(ToolTiming.of(hist).label)
    }
}
