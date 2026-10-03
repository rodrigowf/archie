package com.assistant.core.conversation

import com.assistant.core.conversation.fixtures.ProtocolFixtures
import com.assistant.core.protocol.ServerFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Gate G-P: every fixture file in `shared/protocol-fixtures/` through [ConversationReducer], compared with its
 * `expected` (spec 12 §11, spec 14 §6.1). The same files run in `frontend-next`; a fixture that fails
 * here is a spec bug or a platform bug, never an accepted platform difference.
 */
@RunWith(Parameterized::class)
class ProtocolFixtureConformanceTest(private val fileName: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> = ProtocolFixtures.names().map { arrayOf<Any>(it) }
    }

    private val fixture: JsonObject by lazy { ProtocolFixtures.load(fileName) }

    @Test
    fun matchesExpected() {
        assertEquals("fixture name must equal its file name", fileName.removeSuffix(".json"), fixture["name"]!!.jsonPrimitive.content)
        val actual = ProtocolFixtures.run(fixture)
        val problems = ProtocolFixtures.compare(fixture, actual)
        if (problems.isNotEmpty()) fail("$fileName:\n  " + problems.joinToString("\n  "))
    }

    /** §4.1 invariants after every step (and I-7 / I-14 / I-15 at the steps they apply to). */
    @Test
    fun invariantsHoldAfterEveryStep() {
        val problems = ArrayList<String>()
        var previous: ConversationState? = null
        ProtocolFixtures.run(fixture) { input, s ->
            InvariantChecker.check(s).forEach { problems += "after $input: $it" }
            previous?.let { p -> InvariantChecker.checkStep(p, input, s).forEach { problems += "after $input: $it" } }
            previous = s
        }
        if (problems.isNotEmpty()) fail("$fileName:\n  " + problems.joinToString("\n  "))
    }

    /** I-13: same initial state + same inputs ⇒ same state. */
    @Test
    fun deterministic() {
        assertEquals(ProtocolFixtures.run(fixture), ProtocolFixtures.run(fixture))
    }

    /** I-8: delivering every seq-stamped frame twice is the same as once (session_stalled exempt but idempotent). */
    @Test
    fun seqDuplicatesAreIdempotent() {
        val once = ProtocolFixtures.run(fixture)
        val doubled = runDoubled(fixture)
        assertEquals(ProtocolFixtures.normaliseEntries(once), ProtocolFixtures.normaliseEntries(doubled))
        assertEquals(once.checkpoint, doubled.checkpoint)
        assertEquals(once.orphanResults, doubled.orphanResults)
        assertEquals(once.unattributed, doubled.unattributed)
        assertEquals(once.status, doubled.status)
    }

    private fun runDoubled(fixture: JsonObject): ConversationState {
        val initial = ProtocolFixtures.run(JsonObject(fixture + ("events" to kotlinx.serialization.json.JsonArray(emptyList())) - "client_actions"))
        var s = initial
        for (input in ProtocolFixtures.inputs(fixture)) {
            if (input == null) continue
            s = ConversationReducer.reduce(s, input)
            val f = (input as? ConversationInput.Frame)?.frame
            if (f != null && f.seq != null && !f.streamId.isNullOrEmpty() && f !is ServerFrame.SessionStalled) {
                s = ConversationReducer.reduce(s, input)
            }
        }
        return s
    }
}

class FixtureIndexTest {
    /** Guards against a broken resources path (spec 14 §6.1: fail if zero fixtures are found). */
    @Test
    fun fixturesArePresent() {
        val names = ProtocolFixtures.names()
        assertTrue("no protocol fixtures found", names.isNotEmpty())
        assertTrue("expected the 33 spec-12 fixtures, found ${names.size}", names.size >= 33)
        for (n in names) ProtocolFixtures.load(n)
    }
}
