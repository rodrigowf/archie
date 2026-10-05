package com.assistant.core.conversation

import com.assistant.core.conversation.fixtures.ProtocolFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runner must be able to fail: a perturbed `expected` is reported, numbers compare numerically. */
class FixtureRunnerSelfTest {
    private val fixture = ProtocolFixtures.load("plain_text_turn.json")
    private val actual = ProtocolFixtures.run(fixture)

    private fun withExpected(f: (JsonObject) -> JsonObject): JsonObject {
        val expected = fixture["expected"]!!.jsonObject
        return JsonObject(fixture + ("expected" to f(expected)))
    }

    @Test
    fun unmodifiedFixturePasses() {
        assertEquals(emptyList<String>(), ProtocolFixtures.compare(fixture, actual))
    }

    @Test
    fun wrongTextIsReported() {
        val bad = withExpected { e ->
            val entries = e["entries"]!!.jsonArray
            val user = JsonObject(entries[0].jsonObject + ("text" to JsonPrimitive("bye")))
            JsonObject(e + ("entries" to JsonArray(listOf(user) + entries.drop(1))))
        }
        assertTrue(ProtocolFixtures.compare(bad, actual).any { it.contains("entries[0].text") })
    }

    @Test
    fun missingEntryIsReported() {
        val bad = withExpected { e -> JsonObject(e + ("entries" to JsonArray(e["entries"]!!.jsonArray.take(1)))) }
        assertTrue(ProtocolFixtures.compare(bad, actual).isNotEmpty())
    }

    @Test
    fun wrongStateIsReported() {
        val bad = withExpected { e ->
            JsonObject(e + ("state" to JsonObject(e["state"]!!.jsonObject + ("status" to JsonPrimitive("streaming")))))
        }
        assertTrue(ProtocolFixtures.compare(bad, actual).any { it.contains("state.status") })
    }

    @Test
    fun unexpectedOrphanIsReported() {
        val bad = withExpected { e ->
            JsonObject(e + ("orphan_results" to JsonArray(listOf(JsonObject(mapOf("tool_use_id" to JsonPrimitive("x")))))))
        }
        assertTrue(ProtocolFixtures.compare(bad, actual).any { it.startsWith("orphan_results") })
    }

    @Test
    fun numbersCompareNumerically() {
        val out = ArrayList<String>()
        ProtocolFixtures.diff("n", JsonPrimitive(0.0123), JsonPrimitive("0.01230".toBigDecimal()), out)
        ProtocolFixtures.diff("n", JsonPrimitive(1010), JsonPrimitive(1010L), out)
        assertEquals(emptyList<String>(), out)
        ProtocolFixtures.diff("n", JsonPrimitive(1), JsonPrimitive(2), out)
        assertEquals(1, out.size)
    }
}
