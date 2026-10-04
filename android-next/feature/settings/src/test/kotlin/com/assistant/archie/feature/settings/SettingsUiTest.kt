package com.assistant.archie.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.datastore.preferences.core.Preferences
import com.assistant.archie.feature.settings.ui.SessionSettingsSheet
import com.assistant.archie.feature.settings.ui.SettingsPageKey
import com.assistant.archie.feature.settings.ui.SettingsPageScreen
import com.assistant.archie.feature.settings.ui.SettingsScreen
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.theme.ArchieTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SettingsSaveUiTest` of spec 14 §6.3 and the B-08 DoD: every save shows "Saved" or the server's
 * error verbatim + Retry; MCP "empty = all" semantics (bug 1) with the last-on server locked;
 * connection rows switch and reconnect; permission rationale, denied and blocked states; the
 * session sheet PUTs only the changed keys and restarts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After fun tearDown() { harness?.close() }

    private fun h(
        prefs: Preferences = SettingsGoldenBase.DEVICE,
        platform: FakePlatform = FakePlatform(),
        sessions: SessionControl = SessionControl.None,
        configure: (SettingsBackend) -> Unit = {},
    ): Harness = Harness(SettingsBackend().start().also(configure), prefs, platform, sessions = sessions).also {
        harness = it
        runBlocking { it.feature.server.refreshNow(); it.feature.auth.checkNow() }
    }

    private fun show(h: Harness, page: SettingsPageKey) {
        compose.setContent { ArchieTheme { SettingsPageScreen(h.feature, page, onBack = {}) } }
        compose.waitForIdle()
    }

    // 30 s: the full `check` runs every module's Robolectric tests at once; 10 s flaked under that load.
    private fun waitText(text: String, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun serverSave_showsSavedSnackbar() {
        val h = h()
        show(h, SettingsPageKey.AGENT_SESSIONS)
        compose.onNodeWithTag("chrome").assertIsOn().performClick()
        waitText("Saved")
        eventually { h.backend.puts.singleOrNull() == """{"chrome_extension":false}""" }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("chrome").assertIsOff() }.isSuccess }
    }

    @Test fun serverSave_failure_showsDetailVerbatim_andRetrySaves() {
        val detail = "Unknown provider 'claude' (CLI not installed on the server)"
        val h = h { it.failNextPut = 400 to detail }
        show(h, SettingsPageKey.AGENT_SESSIONS)
        compose.onNodeWithTag("chrome").performClick()
        waitText(detail)
        compose.onNodeWithText("Retry").performClick()
        waitText("Saved")
        eventually { h.backend.puts.size == 2 && h.backend.puts[0] == h.backend.puts[1] }
    }

    @Test fun deviceSave_showsSaved_andWritesTheOldDataStoreKey() {
        val h = h()
        show(h, SettingsPageKey.APPEARANCE)
        compose.onNodeWithText("Light").performClick()
        waitText("Saved")
        eventually { h.settings.settings.value?.themeMode == com.assistant.core.model.ThemeMode.LIGHT }
    }

    /** Bug 1: `[]` shows every switch on; one off writes the others; Turn all on writes `[]`. */
    @Test fun mcp_emptyMeansAll_andSwitchingOneOffWritesTheOthers() {
        val h = h { b ->
            b.mcp = """{"servers":{"a":{"command":"x"},"b":{"command":"y"},"c":{"command":"z"}}}"""
            b.config = JsonObject(b.config + ("enabled_mcps" to JsonArray(emptyList())))
        }
        show(h, SettingsPageKey.MCP_SERVERS)
        listOf("a", "b", "c").forEach { compose.onNodeWithTag("mcp:$it").assertIsSelected() }
        compose.onNodeWithText("All servers are on, including any added to .claude.json later.").assertExists()
        compose.onNodeWithTag("mcp:b").performClick()
        eventually { h.backend.puts.lastOrNull() == """{"enabled_mcps":["a","c"]}""" }
        waitText("Turn all on")
        compose.onNodeWithTag("mcp-all-on").performClick()
        eventually { h.backend.puts.lastOrNull() == """{"enabled_mcps":[]}""" }
    }

    /** The live Jetson: one server, explicitly on — it is the last one on, so it can't go off. */
    @Test fun mcp_lastOnServer_isLocked() {
        val h = h()
        show(h, SettingsPageKey.MCP_SERVERS)
        compose.onNodeWithTag("mcp:chrome-devtools").assertIsNotEnabled()
        compose.onNodeWithText("Last one on (an empty list means all)").assertExists()
    }

    /** "No servers yet" while connected (inv03 §1.6) is gone; tapping another server switches to it. */
    @Test fun connection_listsCurrent_andTappingAServerSwitches() {
        val h = h()
        show(h, SettingsPageKey.CONNECTION)
        compose.onNodeWithText("Connected to jetson").assertExists()
        compose.onNodeWithTag("server:ws://192.168.0.200:80").assertExists()
        compose.onNodeWithTag("server:ws://192.168.0.28:8765").performClick()
        eventually { h.connection.changes == listOf("ws://192.168.0.28:8765") }
        waitText("Connecting to laptop…")
    }

    /** Wake word with the microphone denied: rationale first, nothing enabled behind the user's back. */
    @Test fun wakeWord_withoutMicrophone_showsRationale_andStaysOff() {
        val p = FakePlatform().apply { granted.remove(AppPermission.MICROPHONE) }
        val h = h(prefs = androidx.datastore.preferences.core.mutablePreferencesOf().apply {
            this[androidx.datastore.preferences.core.booleanPreferencesKey("enable_wake_word")] = false
        }, platform = p)
        show(h, SettingsPageKey.WAKE_WORD)
        compose.onNodeWithTag("wake-toggle").assertIsOff().performClick()
        compose.onNodeWithTag("rationale:MICROPHONE").assertExists()
        compose.onNodeWithText("Continue").assertExists()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithTag("rationale:MICROPHONE").assertDoesNotExist()
        Thread.sleep(200)
        assertEquals(false, h.settings.settings.value?.enableWakeWord)
    }

    /** Denials are not silent (inv03 §1.1): wake word on but no mic → a visible card with Allow. */
    @Test fun wakeWord_onButMicDenied_showsTheCard() {
        val p = FakePlatform().apply { granted.remove(AppPermission.MICROPHONE) }
        val h = h(platform = p)
        show(h, SettingsPageKey.WAKE_WORD)
        compose.onNodeWithText("Microphone permission needed").assertExists()
        compose.onNodeWithText("Needs the microphone permission.").assertExists()
        compose.onNodeWithTag("talk-phrases").assertIsNotEnabled()
    }

    @Test fun blockedPermission_offersAppSettings() {
        val p = FakePlatform().apply { granted.remove(AppPermission.NOTIFICATIONS); blocked += AppPermission.NOTIFICATIONS }
        val h = h(platform = p)
        show(h, SettingsPageKey.PERMISSIONS)
        compose.onNodeWithText("Blocked · open app settings", substring = true).assertExists()
        compose.onNodeWithTag("perm:NOTIFICATIONS").performClick()
        compose.onNodeWithTag("rationale:NOTIFICATIONS").assertExists()
        compose.onNodeWithText("Open app settings").assertExists()
        compose.onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test fun bluetoothOutput_asksForNearbyDevices() {
        val p = FakePlatform().apply {
            granted.remove(AppPermission.NEARBY_DEVICES)
            outputs.value = outputs.value + com.assistant.core.model.AudioOutput.BLUETOOTH
        }
        val h = h(platform = p)
        show(h, SettingsPageKey.AUDIO)
        compose.onNodeWithText("Bluetooth").performClick()
        compose.onNodeWithTag("rationale:NEARBY_DEVICES").assertExists()
        compose.onNodeWithText("Not now").performClick()
        Thread.sleep(200)
        assertEquals(com.assistant.core.model.AudioOutput.LOUDSPEAKER, h.settings.settings.value?.audioOutput)
    }

    @Test fun phrases_saveOnDone_andRefuseBlank() {
        val h = h()
        show(h, SettingsPageKey.WAKE_WORD)
        compose.onNodeWithTag("talk-phrases").performTextReplacement(" , ")
        compose.onNodeWithTag("talk-phrases").performImeAction()
        compose.onNodeWithText("Enter at least one phrase").assertExists()
        compose.onNodeWithTag("talk-phrases").performTextReplacement("hey buddy, my friend")
        compose.onNodeWithTag("talk-phrases").performImeAction()
        eventually { h.settings.settings.value?.talkWord == "hey buddy, my friend" }
        waitText("Saved")
    }

    @Test fun home_serverRowsDisabledWhenOffline() {
        val h = h()
        h.connection.state.value = h.connection.state.value.copy(phase = ConnectionStatus.Phase.OFFLINE)
        compose.setContent { ArchieTheme { SettingsScreen(h.feature, onBack = {}, onOpenPage = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-row:VOICE").assertIsNotEnabled()
        compose.onNodeWithTag("settings-row:AUDIO").assertIsEnabled()
        compose.onNodeWithText("jetson · offline").assertExists()
    }

    @Test fun account_pasteFlow_validatesThenSignsIn() {
        val h = h { it.auth = """{"authenticated":false,"auth_url":null,"headless":true}""" }
        show(h, SettingsPageKey.ACCOUNT)
        compose.onNodeWithTag("auth-credentials").performTextReplacement("{not json")
        compose.onNodeWithTag("auth-set-credentials").performClick()
        waitText("That isn't valid JSON. Copy the whole file, including the braces.")
        compose.onNodeWithTag("auth-credentials").performTextReplacement("""{"claudeAiOauth":{"accessToken":"sk-x"}}""")
        compose.onNodeWithTag("auth-set-credentials").performClick()
        waitText(AuthModel.SIGNED_IN)
        assertTrue(h.backend.requests.contains("POST /api/auth/credentials"))
    }

    @Test fun sessionSheet_putsOnlyChangedKeys_thenRestarts() {
        var restarted = 0
        val sessions = object : SessionControl {
            override fun session(localId: String) = flowOf(SessionInfo(localId, "SDK1", "Energy dashboard", busy = false))
            override suspend fun restart(localId: String): Boolean { restarted++; return true }
        }
        val h = h(sessions = sessions)
        var dismissed = false
        compose.setContent { ArchieTheme { SessionSettingsSheet(h.feature, "L1", onDismiss = { dismissed = true }) } }
        waitText("Restart")
        compose.onNodeWithTag("wd:/home/rodrigo/assistant").performScrollTo().performClick()
        waitText("Save and restart")
        compose.onNodeWithText("Changes apply after a restart.").assertExists()
        compose.onNodeWithTag("session-restart").performClick()
        eventually { restarted == 1 && dismissed }
        assertEquals("""{"working_directory":"/home/rodrigo/assistant"}""", h.backend.puts.single())
    }

    @Test fun sessionSheet_restartRefusedWhileBusy() {
        val sessions = object : SessionControl {
            override fun session(localId: String) = flowOf(SessionInfo(localId, "SDK1", "x", busy = true))
            override suspend fun restart(localId: String) = true
        }
        val h = h(sessions = sessions)
        compose.setContent { ArchieTheme { SessionSettingsSheet(h.feature, "L1", onDismiss = {}) } }
        waitText("A reply is running: stop it to restart.")
        compose.onNodeWithTag("session-restart").assertIsNotEnabled()
    }
}
