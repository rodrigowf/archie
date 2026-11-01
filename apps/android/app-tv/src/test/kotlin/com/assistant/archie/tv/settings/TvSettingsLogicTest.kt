package com.assistant.archie.tv.settings

import com.assistant.archie.feature.settings.HarnessLogic
import com.assistant.archie.tv.control.TvControlState
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.model.ConfigPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings on the TV (spec 15 §3.5): the choices it renders and the PUTs it saves. */
class TvSettingsLogicTest {
    private val cfg = SettingsFixtures.config
    private val st = SettingsFixtures.state

    @Test fun pagesInMockupOrder() {
        assertEquals(TvSettingsPageId.entries.toSet(), TvSettingsPages.ORDER.toSet())
        assertEquals(TvSettingsPageId.CONNECTION, TvSettingsPages.ORDER.first())
        assertEquals(TvSettingsPageId.ABOUT, TvSettingsPages.ORDER.last())
    }

    @Test fun voiceChoicesFollowTheProviderCatalog() {
        val choices = TvVoiceSettings.choices(cfg, st).associateBy { it.key }
        assertEquals(listOf("openai", "qwen"), choices.getValue(TvVoiceSettings.PROVIDER).options.map { it.id })
        assertEquals("OpenAI", choices.getValue(TvVoiceSettings.PROVIDER).valueLabel)
        assertEquals(listOf("gpt-realtime", "gpt-realtime-mini"), choices.getValue(TvVoiceSettings.MODEL).options.map { it.id })
        assertEquals("Cedar", choices.getValue(TvVoiceSettings.VOICE).valueLabel)
        assertEquals("Auto-detect", choices.getValue(TvVoiceSettings.LANGUAGE).valueLabel)
        assertNull("no Google backend row for OpenAI", choices[TvVoiceSettings.ENDPOINT])
    }

    @Test fun voiceKeepsAnUnlistedSavedModel() {
        val odd = cfg.copy(voice = cfg.voice.copy(model = "gpt-realtime-2099"))
        val model = TvVoiceSettings.choices(odd, st).first { it.key == TvVoiceSettings.MODEL }
        assertEquals("gpt-realtime-2099", model.options.first().id)
        assertEquals("gpt-realtime-2099 (not listed)", model.valueLabel)
    }

    @Test fun voicePatchesAreTheAppsPartialPuts() {
        assertEquals(ConfigPatch(defaultVoiceProvider = "qwen"), TvVoiceSettings.patch(TvVoiceSettings.PROVIDER, "qwen"))
        assertEquals(ConfigPatch(defaultVoiceName = "marin"), TvVoiceSettings.patch(TvVoiceSettings.VOICE, "marin"))
        assertEquals(ConfigPatch(defaultVoiceTranscriptionLanguage = ""), TvVoiceSettings.patch(TvVoiceSettings.LANGUAGE, ""))
        assertNull(TvVoiceSettings.patch("nope", "x"))
    }

    @Test fun savingDisablesEveryChoice() {
        val busy = st.copy(saving = "voice")
        assertTrue(TvVoiceSettings.choices(cfg, busy).none { it.enabled })
        assertTrue(TvServerSettings.sections(cfg, busy, null).flatMap { it.choices }.none { it.enabled })
    }

    @Test fun serverSectionsInOrder() {
        val sections = TvServerSettings.sections(cfg, st, SettingsFixtures.accounts)
        assertEquals(listOf("Conversation model", "Agent sessions", "Working directory", "MCP servers", "Accounts"), sections.map { it.title })
    }

    @Test fun textModelsExcludeAudioModels() {
        val text = TvServerSettings.sections(cfg, st, null)[0].choices.first { it.key == TvServerSettings.TEXT_MODEL }
        assertEquals(listOf("claude-sonnet-5", "claude-opus-5-5", "gpt-4.1"), text.options.map { it.id })
        assertEquals("Anthropic · Claude Sonnet 5", text.valueLabel)
        val audio = TvServerSettings.sections(cfg, st, null)[0].choices.first { it.key == TvServerSettings.AUDIO_MODEL }
        assertEquals(listOf("", "gpt-audio"), audio.options.map { it.id })
        assertEquals("Server default", audio.valueLabel)
    }

    @Test fun audioModelRowOnlyWhenTheServerHasTheSetting() {
        val old = cfg.copy(defaultAudioModel = null)
        assertTrue(TvServerSettings.sections(old, st, null)[0].choices.none { it.key == TvServerSettings.AUDIO_MODEL })
    }

    @Test fun harnessModelChoiceHasNoCustomEntry() {
        val agents = TvServerSettings.sections(cfg, st, null)[1]
        val model = agents.choices.first { it.key == "${TvServerSettings.HARNESS_MODEL}:claude" }
        assertEquals("Claude Code model", model.title)
        assertEquals(listOf(HarnessLogic.CLI_DEFAULT, "opus", "sonnet"), model.options.map { it.id })
        assertEquals("Opus", model.valueLabel)
        assertEquals(ConfigPatch(harnessModel = mapOf("claude" to "sonnet")), TvServerSettings.patch(cfg, model.key, "sonnet"))
        assertEquals(ConfigPatch(harnessModel = mapOf("claude" to "")), TvServerSettings.patch(cfg, model.key, HarnessLogic.CLI_DEFAULT))
    }

    @Test fun savedCustomHarnessModelStaysListed() {
        val custom = cfg.copy(harnessModel = mapOf("claude" to "claude-opus-5-5[1m]"))
        val model = TvServerSettings.sections(custom, st, null)[1].choices.first { it.key == "${TvServerSettings.HARNESS_MODEL}:claude" }
        assertEquals("claude-opus-5-5[1m] (custom)", model.valueLabel)
    }

    @Test fun workingDirectoryPicksFromHistory() {
        val dir = TvServerSettings.sections(cfg, st, null)[2].choices.single()
        assertEquals(3, dir.options.size)
        assertEquals("Archie", dir.valueLabel)
        assertEquals(ConfigPatch(workingDirectory = "laptop:/home/rodrigo/assistant"), TvServerSettings.patch(cfg, dir.key, "laptop:/home/rodrigo/assistant"))
    }

    @Test fun mcpTogglesFollowTheAllEnabledRule() {
        val names = st.catalogs.mcpNames
        val toggles = TvServerSettings.sections(cfg, st, null)[3].toggles
        assertTrue(toggles.all { it.on && it.enabled })
        // Turning one off writes the explicit list of the others (CFG-4).
        assertEquals(
            ConfigPatch(enabledMcps = listOf("atlassian", "posthog")),
            TvServerSettings.togglePatch(cfg, names, "${TvServerSettings.MCP_PREFIX}chrome-devtools", false),
        )
        // The last one on can't be turned off (an empty list means all).
        val one = cfg.copy(enabledMcps = listOf("posthog"))
        val last = TvServerSettings.sections(one, st, null)[3].toggles.first { it.key.endsWith("posthog") }
        assertFalse(last.enabled)
        assertNull(TvServerSettings.togglePatch(one, names, last.key, false))
        // Turning every server back on writes [].
        val two = cfg.copy(enabledMcps = listOf("chrome-devtools", "atlassian"))
        assertEquals(ConfigPatch(enabledMcps = emptyList()), TvServerSettings.togglePatch(two, names, "${TvServerSettings.MCP_PREFIX}posthog", true))
    }

    @Test fun chromeToggle() {
        assertEquals(ConfigPatch(chromeExtension = true), TvServerSettings.togglePatch(cfg, emptyList(), TvServerSettings.CHROME, true))
    }

    @Test fun accountsAreReadOnlyLines() {
        val info = TvServerSettings.sections(cfg, st, SettingsFixtures.accounts)[4].info
        assertEquals(listOf("Claude Code", "Codex", "OpenAI API"), info.map { it.title })
        assertEquals("Signed in · rodrigo@… · Max", info[0].value)
        assertTrue(info[1].warn)
        assertEquals("Loading…", TvServerSettings.sections(cfg, st, null)[4].info.single().value)
    }

    @Test fun liveIdleChoices() {
        val c = LiveIdle.choice(120)
        assertEquals("2 min", c.valueLabel)
        assertEquals("Never", LiveIdle.label(0))
        assertEquals("45 s", LiveIdle.choice(45).valueLabel) // a value set elsewhere stays listed
    }

    @Test fun hostPortAndCertificateLines() {
        assertEquals("192.168.0.200:443", TvSettingsStatus.hostPortOf("wss://192.168.0.200"))
        assertEquals("192.168.0.200:80", TvSettingsStatus.hostPortOf("ws://192.168.0.200"))
        assertEquals("192.168.0.200:8765", TvSettingsStatus.hostPortOf("https://192.168.0.200:8765/x"))
        val pinned = connectionUi(ConnectionStatus(serverUrl = "wss://192.168.0.200", phase = ConnectionStatus.Phase.CONNECTED), mapOf("192.168.0.200:443" to "abc"))
        assertEquals("Pinned on this TV", pinned.certificate)
        assertEquals(TvSettingsStatus.Tone.OK, pinned.tone)
        val lan = connectionUi(ConnectionStatus(serverUrl = "ws://192.168.0.200:80", phase = ConnectionStatus.Phase.OFFLINE), emptyMap())
        assertEquals("Not encrypted (LAN)", lan.certificate)
        assertEquals(TvSettingsStatus.Tone.BAD, lan.tone)
        assertEquals("192.168.0.200", lan.serverLabel)
    }

    @Test fun controlStatusLines() {
        assertEquals(TvSettingsStatus.Tone.OK, TvSettingsStatus.control(TvControlState.Ready).tone)
        val rejected = TvSettingsStatus.control(TvControlState.Rejected("bad token"))
        assertEquals(TvSettingsStatus.Tone.BAD, rejected.tone)
        assertEquals("bad token", rejected.body)
        assertEquals("Reconnecting in 4 s", TvSettingsStatus.control(TvControlState.Waiting(2, 3_500, null)).title)
    }

    @Test fun hintsDependOnThePane() {
        assertEquals("Open", settingsHints(TvSettingsPageId.VOICE, inPane = false).first().label)
        assertEquals("Change", settingsHints(TvSettingsPageId.VOICE, inPane = true).first().label)
        assertEquals("Hold to test", settingsHints(TvSettingsPageId.REMOTE_MIC, inPane = true)[1].label)
    }
}
