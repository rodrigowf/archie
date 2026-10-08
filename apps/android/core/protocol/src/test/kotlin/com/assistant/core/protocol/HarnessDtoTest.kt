package com.assistant.core.protocol

import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue
import com.assistant.core.model.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `GET /api/config/harnesses` and the `harness_options` maps (spec 12 §6.14, §8.1), decoded from the
 * web mock server's catalogs (`apps/web/mock-server/data/harnesses.json`, the same data the web
 * tests use), plus the fallback for older servers.
 */
class HarnessDtoTest {
    private val harnesses by lazy {
        val path = requireNotNull(System.getProperty("archie.harnessCatalogs")) { "archie.harnessCatalogs not set" }
        RestJson.decodeFromString<HarnessesDto>(File(path).readText()).toModel()
    }

    private fun catalog(id: String) = requireNotNull(harnesses.first { it.id == id }.catalog)

    @Test
    fun harnesses_decodeEveryCatalog() {
        assertEquals(listOf("claude", "qwen", "gemini", "codex"), harnesses.map { it.id })
        assertEquals("Claude Code", harnesses[0].label)
        val claude = catalog("claude")
        assertEquals("claude", claude.provider)
        assertEquals(11, claude.models.size)
        assertEquals("claude-sonnet-5-5", claude.defaultModel)
        assertTrue(claude.allowCustomModel)
        assertEquals(listOf("effort", "thinking", "thinking_budget", "fallback_model", "todo_tools"), claude.options.map { it.key })

        val opus = claude.models.first { it.id == "opus" }
        assertEquals("Opus", opus.label)
        assertEquals("builtin", opus.source)
        assertEquals(1_000_000L, opus.contextWindow)
        assertEquals(true, opus.supportsThinking)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), opus.efforts)
        assertEquals(emptyList<String>(), claude.models.first { it.id == "haiku" }.efforts)

        val budget = claude.options.first { it.key == "thinking_budget" }
        assertEquals(HarnessOptionKind.NUMBER, budget.kind)
        assertEquals(HarnessValue.Num(16000.0), budget.default)
        assertEquals(1024.0, budget.min)
        assertEquals(128000.0, budget.max)
        assertEquals(1024.0, budget.step)
        assertTrue(budget.models!!.contains("claude-opus-4-6"))
        assertEquals(HarnessValue.Flag(true), claude.options.first { it.key == "todo_tools" }.default)
        val thinking = claude.options.first { it.key == "thinking" }
        assertEquals("Fixed budget", thinking.choices.first { it.value == "enabled" }.label)
        assertTrue(thinking.choices.first { it.value == "enabled" }.models!!.contains("haiku"))
        assertNull(thinking.choices.first { it.value == "adaptive" }.models)
        assertTrue(thinking.help!!.startsWith("--thinking"))

        val codex = catalog("codex")
        assertEquals("low", codex.models.first { it.id == "codex-mini" }.defaultEffort)
        assertEquals(HarnessValue.Text("medium"), codex.options.first { it.key == "effort" }.default)
        assertEquals(1, codex.warnings.size)
        assertEquals(-1.0, catalog("gemini").options.first { it.key == "thinking_budget" }.min)
    }

    @Test
    fun harnesses_lenient() {
        val list = RestJson.decodeFromString<HarnessesDto>(
            """{"harnesses":[{"id":"x","catalog":null,"extra":1},{"id":""},{"id":"y","label":"Y","catalog":{"models":[{"id":"m"},{"id":""}],
               "options":[{"key":"a","kind":"slider"},{"key":"b","kind":"toggle","default":false},{"key":"c","kind":"select","default":{"x":1}}],
               "default_model":"","warnings":["", "w"]}}]}""",
        ).toModel()
        assertEquals(listOf("x", "y"), list.map { it.id })
        assertEquals("x", list[0].label)
        assertNull(list[0].catalog)
        val c = list[1].catalog!!
        assertEquals("y", c.provider)
        assertEquals(listOf("m"), c.models.map { it.id })
        assertEquals("m", c.models[0].label)
        assertEquals("unknown kinds are skipped", listOf("b", "c"), c.options.map { it.key })
        assertEquals(HarnessValue.Flag(false), c.options[0].default)
        assertNull("a non-value default reads as none", c.options[1].default)
        assertNull(c.defaultModel)
        assertEquals(listOf("w"), c.warnings)
    }

    @Test
    fun optionValues_readAndWrite() {
        val global = harnessOptionsByProvider(Json.parseToJsonElement("""{"claude":{"effort":"max","thinking_budget":32000,"todo_tools":false,"gone":null,"bad":{"x":1}},"codex":{},"junk":3}"""))
        assertEquals(
            mapOf(
                "claude" to mapOf("effort" to HarnessValue.Text("max"), "thinking_budget" to HarnessValue.Num(32000.0), "todo_tools" to HarnessValue.Flag(false)),
                "codex" to emptyMap(),
            ),
            global,
        )
        assertEquals(emptyMap<String, Any>(), harnessOptionsByProvider(JsonNull))

        assertEquals(mapOf("effort" to null, "thinking" to HarnessValue.Text("off")), harnessOptionsOverlay(Json.parseToJsonElement("""{"effort":null,"thinking":"off","bad":[1]}""")))
        assertNull("{} = inherit every key", harnessOptionsOverlay(Json.parseToJsonElement("{}")))
        assertNull(harnessOptionsOverlay(JsonNull))
        assertNull(harnessOptionsOverlay(null))

        assertEquals(JsonPrimitive(32000L), HarnessValue.Num(32000.0).toJson())
        assertEquals(JsonPrimitive(0.5), HarnessValue.Num(0.5).toJson())
        assertEquals("32000", HarnessValue.Num(32000.0).display)
        assertEquals("-1", HarnessValue.Num(-1.0).display)
    }

    @Test
    fun config_harnessOptions() {
        val cfg = RestJson.decodeFromString<ConfigDto>("""{"provider":"claude","harness_model":{"claude":"opus"},"harness_options":{"claude":{"effort":"high"}}}""").toModel()
        assertEquals(mapOf("claude" to mapOf("effort" to HarnessValue.Text("high"))), cfg.harnessOptions)
        assertEquals(emptyMap<String, Any>(), RestJson.decodeFromString<ConfigDto>("""{"harness_options":null}""").toModel().harnessOptions)

        // PUT merges per key on the server; null deletes the key (= CLI default).
        val patch = ConfigPatch(harnessOptions = mapOf("claude" to mapOf("effort" to null, "thinking_budget" to HarnessValue.Num(2048.0))))
        assertEquals(
            """{"harness_options":{"claude":{"effort":null,"thinking_budget":2048}}}""",
            RestJson.encodeToJsonElement(patch.toDto()).toString(),
        )
        assertEquals("""{"harness_model":{"codex":""}}""", RestJson.encodeToJsonElement(ConfigPatch(harnessModel = mapOf("codex" to "")).toDto()).toString())
    }

    @Test
    fun sessionConfig_harnessOptionsIsAWholeMap() {
        val s = RestJson.decodeFromString<SessionConfigDto>(
            """{"working_directory":null,"enabled_mcps":null,"chrome_extension":null,"provider":"codex","harness_model":"gpt-6-luna","harness_options":{"effort":"low","web_search":null}}""",
        ).toModel()
        assertEquals(mapOf("effort" to HarnessValue.Text("low"), "web_search" to null), s.harnessOptions)
        assertNull(RestJson.decodeFromString<SessionConfigDto>("""{"harness_options":{}}""").toModel().harnessOptions)

        assertEquals(
            """{"harness_options":{"effort":"max","thinking":null}}""",
            SessionConfig(harnessOptions = mapOf("effort" to HarnessValue.Text("max"), "thinking" to null)).toPutBody().toString(),
        )
        assertEquals(
            """{"provider":"codex","harness_model":null,"harness_options":null}""",
            SessionConfig(provider = "codex").toPutBody(setOf("harness_model", "harness_options")).toString(),
        )
    }

    @Test
    fun fallback_qwenCatalogFromModels() {
        val rows = (Json.parseToJsonElement(
            """["a","","a",{"id":"b","display_name":"B","context_window":1000000,"supports_thinking":true,"supports_vision":false},{"x":1},null]""",
        ) as JsonArray).toList()
        val c = HarnessFallback.qwenCatalogFromModels(rows)
        assertEquals("qwen", c.provider)
        assertEquals(listOf("a" to "a", "b" to "B"), c.models.map { it.id to it.label })
        assertEquals("settings", c.models[0].source)
        assertEquals(1_000_000L, c.models[1].contextWindow)
        assertEquals(true, c.models[1].supportsThinking)
        assertEquals(false, c.models[1].supportsVision)
        assertEquals(emptyList<Any>(), c.options)
        assertEquals(emptyList<String>(), c.warnings)
        assertTrue(c.allowCustomModel)
        assertTrue(HarnessFallback.qwenCatalogFromModels(emptyList()).warnings.single().startsWith("No models listed"))
    }

    @Test
    fun fallback_providersToHarnessRows() {
        val rows = HarnessFallback.harnessesFromProviders(
            listOf(HarnessProviderDto("claude", "Claude Code", "d"), HarnessProviderDto("qwen", "")),
            listOf(JsonPrimitive("q1")),
        )
        assertEquals(listOf(Triple("claude", "Claude Code", null), Triple("qwen", "qwen", 1)), rows.map { Triple(it.id, it.label, it.catalog?.models?.size) })
        assertNull(HarnessFallback.harnessesFromProviders(listOf(HarnessProviderDto("qwen", "Qwen")), null)[0].catalog)
        assertEquals(
            listOf(HarnessProviderDto("claude", "Claude Code", "d"), HarnessProviderDto("qwen", "qwen", "")),
            HarnessFallback.providersFromHarnesses(rows),
        )
        // The live Jetson's providers body decodes into the same rows.
        val live = RestJson.decodeFromString<HarnessProvidersDto>("""{"providers":[{"id":"claude","label":"Claude Code","description":"x"}]}""")
        assertEquals("Claude Code", HarnessFallback.harnessesFromProviders(live.providers, null).single().label)
    }
}
