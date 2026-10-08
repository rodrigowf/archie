package com.assistant.archie.feature.settings

import com.assistant.core.model.ConfigPatch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.assistant.core.model.HarnessValue

/**
 * Server saves (CFG-1/2/5/6) against a MockWebServer backend serving the live Jetson's GET bodies:
 * "Saved" on success, the backend `detail` **verbatim** + Retry on failure, the PUT answer replacing
 * the local copy, the Google auto-correct, and MCP saves with the fixed "empty = all" semantics.
 */
class ServerSettingsModelTest {
    private val h = Harness()

    @After fun tearDown() = h.close()

    private fun collectOne(block: suspend () -> Unit): SettingsMessage = runBlocking {
        val next = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { h.feature.messages.messages.first() } }
        block()
        next.await()
    }

    @Test fun refresh_loadsConfigAndEveryCatalog_fromTheLiveShapes() = runBlocking {
        h.feature.server.refreshNow()
        val s = h.feature.server.current
        val cfg = s.config.value!!
        assertEquals("gpt-4o", cfg.defaultModel)
        assertEquals("", cfg.defaultAudioModel)
        assertEquals(listOf("chrome-devtools"), cfg.enabledMcps)
        assertEquals(2, cfg.workingDirectoryHistory.size)
        assertEquals(74, s.catalogs.orchestratorModels?.models?.size)
        assertEquals(listOf("claude", "qwen", "gemini", "codex"), s.catalogs.harnesses?.map { it.id })
        assertEquals(listOf("claude", "qwen", "gemini", "codex"), s.catalogs.providers?.map { it.id })
        assertEquals(5, s.catalogs.harnesses!![0].catalog?.options?.size)
        assertEquals(listOf("chrome-devtools"), s.catalogs.mcpNames)
        assertTrue(s.catalogs.voiceModels!!.providers.containsKey("openai"))
        assertTrue("Google list discovered for the saved endpoint", s.catalogs.googleVoiceModels["aistudio"]!!.isNotEmpty())
        assertTrue(s.catalogs.skills!!.isNotEmpty())
    }

    @Test fun save_success_showsSaved_andTheAnswerReplacesTheLocalCopy() {
        runBlocking { h.feature.server.refreshNow() }
        val m = collectOne { h.feature.server.save(ConfigPatch(chromeExtension = false), "chrome_extension") }
        assertEquals(SettingsMessage.SAVED, m.text)
        assertFalse(m.error)
        assertEquals(false, h.feature.server.current.config.value?.chromeExtension)
        assertEquals("""{"chrome_extension":false}""", h.backend.puts.single())
    }

    @Test fun audioModel_isSavedSeparately_andEmptyMeansServerDefault() {
        runBlocking { h.feature.server.refreshNow() }
        assertEquals("gpt-audio", h.feature.server.current.catalogs.orchestratorModels?.defaultAudioModel)
        collectOne { h.feature.server.save(ConfigPatch(defaultAudioModel = "gpt-audio-mini"), "default_audio_model") }
        collectOne { h.feature.server.save(ConfigPatch(defaultAudioModel = ""), "default_audio_model") }
        assertEquals(listOf("""{"default_audio_model":"gpt-audio-mini"}""", """{"default_audio_model":""}"""), h.backend.puts)
    }

    @Test fun save_failure_showsTheServerDetailVerbatim_withRetry_andRetrySaves() {
        runBlocking { h.feature.server.refreshNow() }
        val detail = "Directory does not exist: /home/rodrigo/nope"
        h.backend.failNextPut = 400 to detail
        val m = collectOne { assertNull(h.feature.server.save(ConfigPatch(workingDirectory = "/home/rodrigo/nope"), "working_directory")) }
        assertEquals(detail, m.text)
        assertTrue(m.error)
        assertEquals("Retry", m.actionLabel)
        assertEquals(detail, h.feature.server.current.saveError)
        assertEquals("192.168.0.28:/home/rodrigo/assistant", h.feature.server.current.config.value?.workingDirectory)

        val again = collectOne { m.retry!!.invoke() }
        assertEquals(SettingsMessage.SAVED, again.text)
        assertEquals(2, h.backend.puts.size)
        assertEquals(h.backend.puts[0], h.backend.puts[1])
        assertEquals("/home/rodrigo/nope", h.feature.server.current.config.value?.workingDirectory)
    }

    @Test fun save_networkFailure_isAnErrorWithRetry_too() {
        runBlocking { h.feature.server.refreshNow() }
        h.backend.shutdown()
        val m = collectOne { h.feature.server.save(ConfigPatch(voiceRecordingEnabled = true), "voice_recording_enabled") }
        assertTrue(m.error)
        assertEquals("Retry", m.actionLabel)
    }

    /** CFG-6 [LOAD-BEARING]: a stale Gemini model is switched once, and the notice says so. */
    @Test fun googleAutoCorrect_putsTheDiscoveredDefault_once() = runBlocking {
        h.backend.config = kotlinx.serialization.json.JsonObject(
            h.backend.config + mapOf(
                "default_voice_provider" to kotlinx.serialization.json.JsonPrimitive("google"),
                "default_voice_model" to kotlinx.serialization.json.JsonPrimitive("gemini-2.0-flash-live-001"),
                "default_voice_name" to kotlinx.serialization.json.JsonPrimitive("Kore"),
                "default_voice_endpoint" to kotlinx.serialization.json.JsonPrimitive("vertex"),
            ),
        )
        h.feature.server.refreshNow()
        val s = h.feature.server.current
        assertEquals("gemini-live-2.5-flash-native-audio", s.config.value?.voice?.model)
        assertEquals(AutoCorrect("gemini-2.0-flash-live-001", "gemini-live-2.5-flash-native-audio", "Kore"), s.autoCorrected)
        assertEquals(1, h.backend.puts.size)
        h.feature.server.refreshNow()
        assertEquals("no second PUT for a model already corrected", 1, h.backend.puts.size)
    }

    /** Bug 1 end to end: switching one MCP off from "all" PUTs the others. */
    @Test fun mcpSave_writesTheOthers() = runBlocking {
        h.backend.mcp = """{"servers":{"a":{"command":"x"},"b":{"command":"y"},"c":{"command":"z"}}}"""
        h.backend.config = kotlinx.serialization.json.JsonObject(h.backend.config + ("enabled_mcps" to kotlinx.serialization.json.JsonArray(emptyList())))
        h.feature.server.refreshNow()
        val s = h.feature.server.current
        val next = McpLogic.toggle(s.config.value!!.enabledMcps, s.catalogs.mcpNames, "b", on = false)
        h.feature.server.save(ConfigPatch(enabledMcps = next), "enabled_mcps")
        assertEquals("""{"enabled_mcps":["a","c"]}""", h.backend.puts.last())
        h.feature.server.save(ConfigPatch(enabledMcps = McpLogic.toggle(listOf("a", "c"), s.catalogs.mcpNames, "b", on = true)), "enabled_mcps")
        assertEquals("""{"enabled_mcps":[]}""", h.backend.puts.last())
    }

    @Test fun deviceSave_confirmsWithSaved_andWritesTheOldKey() {
        val m = collectOne { h.feature.device.setMicGain(1.23f) }
        assertEquals(SettingsMessage.SAVED, m.text)
        eventually { h.settings.settings.value?.micGainLevel == 1.2f }
    }

    @Test fun messagesStreamIsOrdered() = runBlocking {
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { h.feature.messages.messages.take(2).toList() } }
        h.feature.device.setTheme(com.assistant.core.model.ThemeMode.LIGHT)
        eventually { h.settings.settings.value?.themeMode == com.assistant.core.model.ThemeMode.LIGHT }
        h.feature.device.setAutoConnect(false)
        assertEquals(listOf("Saved", "Saved"), got.await().map { it.text })
    }

    // ───────── harness catalogs + defaults (spec 12 §6.14, §8.1) ─────────

    /** An older server (no `/api/config/harnesses`): providers + the Qwen model list. */
    @Test fun harnesses_fallBackToProvidersAndQwenModels() = runBlocking {
        h.backend.harnesses = null
        h.feature.server.refreshNow()
        val list = h.feature.server.current.catalogs.harnesses!!
        assertEquals(listOf("claude", "qwen", "gemini"), list.map { it.id })
        assertNull(list[0].catalog)
        assertEquals(listOf("qwen3.6-plus", "glm-5.1", "deepseek-v4-pro", "deepseek-v4-flash"), list[1].catalog?.models?.map { it.id })
        assertEquals("[ModelStudio Standard] qwen3.6-plus", list[1].catalog?.models?.first()?.label)
        assertTrue(h.backend.requests.contains("GET /api/config/providers"))
    }

    @Test fun harnesses_refreshAsksTheServerToRebuild() {
        runBlocking { h.feature.server.refreshNow() }
        h.feature.server.refreshHarnesses()
        eventually { h.backend.requests.contains("GET /api/config/harnesses?refresh=true") && !h.feature.server.current.refreshingHarnesses }
        assertEquals(4, h.feature.server.current.catalogs.harnesses?.size)
    }

    /** Partial PUTs per key; `null` = CLI default (the server deletes the key). */
    @Test fun globalHarnessDefaults_putOneKeyAtATime() = runBlocking {
        h.feature.server.refreshNow()
        h.feature.server.save(HarnessLogic.globalOptionPatch("claude", "effort", OptionState.Value(HarnessValue.Text("max"))), "harness_options")
        h.feature.server.save(HarnessLogic.globalOptionPatch("claude", "thinking_budget", OptionState.Value(HarnessValue.Num(32000.0))), "harness_options")
        assertEquals(
            mapOf("effort" to HarnessValue.Text("max"), "thinking_budget" to HarnessValue.Num(32000.0)),
            h.feature.server.current.config.value?.harnessOptions?.get("claude"),
        )
        h.feature.server.save(HarnessLogic.globalOptionPatch("claude", "effort", OptionState.Cli), "harness_options")
        h.feature.server.save(HarnessLogic.globalModelPatch("codex", "gpt-6-luna"), "harness_model")
        assertEquals(
            listOf(
                """{"harness_options":{"claude":{"effort":"max"}}}""",
                """{"harness_options":{"claude":{"thinking_budget":32000}}}""",
                """{"harness_options":{"claude":{"effort":null}}}""",
                """{"harness_model":{"codex":"gpt-6-luna"}}""",
            ),
            h.backend.puts,
        )
        val cfg = h.feature.server.current.config.value!!
        assertEquals(mapOf("thinking_budget" to HarnessValue.Num(32000.0)), cfg.harnessOptions["claude"])
        assertEquals("gpt-6-luna", cfg.harnessModel["codex"])
    }

    /** The session sheet sends the whole `harness_options` map; a harness change resets model + options. */
    @Test fun sessionController_sendsTheWholeOptionsMap_andResetsOnHarnessChange() = runBlocking {
        h.backend.sessionConfig = """{"working_directory":null,"enabled_mcps":null,"chrome_extension":null,"provider":"claude","harness_model":"opus","harness_options":{"effort":"max"}}"""
        val sessions = object : SessionControl {
            override fun session(localId: String) = kotlinx.coroutines.flow.flowOf(SessionInfo(localId, "SDK1", "t", busy = false))
            override suspend fun restart(localId: String) = true
        }
        val c = SessionSettingsController("L1", h.api, sessions, h.feature.messages, h.scope)
        eventually { c.state.value.phase == SessionSheetPhase.READY }
        assertEquals(mapOf("effort" to HarnessValue.Text("max")), c.state.value.harnessOptions)

        c.setOption("thinking", OptionState.Cli)
        assertTrue(c.state.value.dirty)
        c.setOption("thinking", OptionState.Inherit)
        assertFalse("back to the saved map", c.state.value.dirty)

        c.setOption("effort", OptionState.Value(HarnessValue.Text("low")))
        c.setOption("thinking_budget", OptionState.Value(HarnessValue.Num(2048.0)))
        c.run(restart = false)
        assertEquals("""{"harness_options":{"effort":"low","thinking_budget":2048}}""", h.backend.puts.last())

        c.setProvider("codex", "claude")
        assertNull(c.state.value.harnessModel)
        assertNull(c.state.value.harnessOptions)
        c.setProvider(null, "claude")
        assertEquals("the saved harness restores the saved values", "opus", c.state.value.harnessModel)
        assertEquals(setOf(SessionKey.PROVIDER), c.state.value.changes.keys)
        c.setProvider("codex", "claude")
        c.run(restart = false)
        assertEquals("""{"provider":"codex","harness_model":null,"harness_options":null}""", h.backend.puts.last())
    }
}
