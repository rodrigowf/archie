package com.assistant.core.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Loader for `tools/parity/old_constants.json` (spec 14 §6.2), generated from the OLD app at commit
 * `e871d05` by `tools/parity/extract_old_constants.py`.
 */
object OldConstants {
    data class Entry(
        val id: String,
        val module: String,
        val label: String,
        val file: String,
        val lines: List<Int>,
        val type: String,
        val value: JsonElement,
        /** `fqcn#NAME` of the new Tuning value, or null for behavioural rows. */
        val newRef: String?,
    ) {
        val newClass: String? get() = newRef?.substringBefore('#')
        val newName: String? get() = newRef?.substringAfter('#')
        val isPinned: Boolean get() = label == "LB" || label == "wire"
    }

    val sourceCommit: String by lazy { root["source_commit"]!!.jsonPrimitive.content }

    private val root: JsonObject by lazy {
        val f = ArchieRoot.file("tools/parity/old_constants.json")
        assertTrue("missing ${f.path} — run tools/parity/extract_old_constants.py", f.isFile)
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    val all: List<Entry> by lazy {
        root["constants"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Entry(
                id = o["id"]!!.jsonPrimitive.content,
                module = o["module"]!!.jsonPrimitive.content,
                label = o["label"]!!.jsonPrimitive.content,
                file = o["file"]!!.jsonPrimitive.content,
                lines = o["lines"]!!.jsonArray.map { it.jsonPrimitive.int },
                type = o["type"]!!.jsonPrimitive.content,
                value = o["value"] ?: JsonNull,
                newRef = (o["new"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }

    operator fun get(id: String): Entry =
        all.firstOrNull { it.id == id } ?: throw AssertionError("old_constants.json has no row '$id'")

    fun forModule(module: String): List<Entry> = all.filter { it.module == module }

    /** Asserts the NEW Tuning value equals the OLD extracted value for [entry]. */
    fun assertNewMatchesOld(entry: Entry, owner: String) {
        assertNotNull("row ${entry.id} has no new ref", entry.newRef)
        val probe = TuningProbe(entry.newClass!!, owner)
        val actual = probe.raw(entry.newName!!)
        assertValue("${entry.id} (${entry.newRef})", entry.type, entry.value, actual)
    }

    fun assertValue(what: String, type: String, old: JsonElement, actual: Any?) {
        when (type) {
            "int", "long" -> assertEquals(what, (old as JsonPrimitive).longOrNull, (actual as Number).toLong())
            "double", "float" -> assertEquals(what, (old as JsonPrimitive).doubleOrNull!!, (actual as Number).toDouble(), 1e-6)
            "string" -> assertEquals(what, (old as JsonPrimitive).content, actual.toString())
            "bool" -> assertEquals(what, (old as JsonPrimitive).booleanOrNull, actual)
            "long_list" -> assertEquals(what, (old as JsonArray).map { it.jsonPrimitive.longOrNull }, toList(actual).map { (it as Number).toLong() })
            "string_set" -> assertEquals(what, (old as JsonArray).map { it.jsonPrimitive.content }.toSet(), toList(actual).map { it.toString() }.toSet())
            "string_list" -> assertEquals(what, (old as JsonArray).map { it.jsonPrimitive.content }, toList(actual).map { it.toString() })
            else -> throw AssertionError("$what: type '$type' has no scalar comparison")
        }
    }

    private fun toList(v: Any?): List<Any?> = when (v) {
        is Collection<*> -> v.toList()
        is LongArray -> v.toList()
        is IntArray -> v.toList()
        is Array<*> -> v.toList()
        else -> throw AssertionError("not a list: $v")
    }
}

/**
 * Pins Tuning constants of one module: each call asserts the NEW value (reflection) equals the
 * literal from inv04 §4, and records the `old_constants.json` row it covers (by [id], scanned by
 * `ConstantCoverageTest`).
 */
class TuningPins(fqcn: String, owner: String) {
    val probe = TuningProbe(fqcn, owner)

    fun long(id: String, name: String, expected: Long) {
        assertEquals("$id → $name", expected, probe.long(name))
    }

    fun int(id: String, name: String, expected: Int) {
        assertEquals("$id → $name", expected, probe.int(name))
    }

    fun double(id: String, name: String, expected: Double) {
        assertEquals("$id → $name", expected, probe.double(name), 0.0)
    }

    fun float(id: String, name: String, expected: Float) {
        assertEquals("$id → $name", expected, probe.float(name), 0.0f)
    }

    fun string(id: String, name: String, expected: String) {
        assertEquals("$id → $name", expected, probe.string(name))
    }

    fun longList(id: String, name: String, expected: List<Long>) {
        assertEquals("$id → $name", expected, probe.list(name).map { (it as Number).toLong() })
    }

    fun stringSet(id: String, name: String, expected: Set<String>) {
        assertEquals("$id → $name", expected, probe.list(name).map { it.toString() }.toSet())
    }
}
