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
        assertEquals("gpt-audio-mini", cfg.defaultModel)
        assertEquals(listOf("chrome-devtools"), cfg.enabledMcps)
        assertEquals(2, cfg.workingDirectoryHistory.size)
        assertEquals(74, s.catalogs.orchestratorModels?.models?.size)
        assertEquals(listOf("claude", "qwen", "gemini"), s.catalogs.providers?.map { it.id })
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
}
