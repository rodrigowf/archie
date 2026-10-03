package com.assistant.archie.shell

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.graph.GraphOwner
import com.assistant.archie.graph.MainAppGraph
import com.assistant.core.data.ItemKey
import com.assistant.core.data.SharePayload
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A GraphOwner whose graph is built on first access, after the test pointed it at [FakeBackend]. */
class TestArchieApp : Application(), GraphOwner {
    override val graph: MainAppGraph by lazy { factory!!(this) }

    companion object {
        @Volatile var factory: ((Application) -> MainAppGraph)? = null
    }
}

/**
 * Robolectric smoke of the real app (spec 14 §7 B-03 DoD): `MainActivity` + the real graph against a
 * MockWebServer backend. It connects, adopts the orchestrator, shows the Archie conversation and the
 * history, and — decision P-1 — destroying the Activity closes and stops nothing on the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = TestArchieApp::class)
class ShellSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val backend = FakeBackend()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        backend.start()
        backend.poolJson = """[{"local_id":"ORCH","sdk_session_id":"JSONL","status":"idle","cost":0.0,"turns":0,"title":"Living-room TV","is_orchestrator":true}]"""
        backend.sessionsJson = """[
          {"session_id":"JSONL","started_at":"2026-10-03T10:00:00+00:00","last_activity":"2026-10-03T11:00:00+00:00","title":"Living-room TV","message_count":4,"is_orchestrator":true,"provider":"claude","local_id":"ORCH"},
          {"session_id":"S9","started_at":"2026-08-28T21:00:00+00:00","last_activity":"2026-08-28T21:49:03.550000+00:00","title":"Jetson thermal check","message_count":9,"is_orchestrator":false,"provider":"qwen","local_id":null}
        ]"""
        backend.messagesJson = """{"messages":[{"role":"user","text":"Plan movie night","blocks":[]},{"role":"assistant","text":"On it.","blocks":[{"type":"text","text":"On it."}]}],"total_count":2,"has_more":false,"start_index":0}"""
        val url = backend.url
        TestArchieApp.factory = { app ->
            MainAppGraph(
                app,
                settings = SettingsStore(
                    MemoryDataStore(mutablePreferencesOf().apply {
                        this[stringPreferencesKey("server_url")] = url
                        this[booleanPreferencesKey("auto_connect")] = true
                    }),
                    scope,
                ),
                scanner = { emptyList() },
                scope = scope,
            )
        }
    }

    @After fun tearDown() {
        scope.cancel()
        backend.shutdown()
    }

    @Test fun launchConnectsToBackend_showsArchieAndHistory_destroyClosesNothing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val graph = (ApplicationProvider.getApplicationContext<Application>() as GraphOwner).graph

        eventually(message = { "frames=${backend.frames}" }) {
            backend.frames.any { it.first == "orch" && it.second.contains("\"type\":\"start\"") && it.second.contains("\"local_id\":\"ORCH\"") }
        }
        eventually { graph.openSessions.active.value == ItemKey.Archie }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("On it.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(titleInTopBar("Living-room TV")).assertIsDisplayed()
        compose.onNodeWithText("Plan movie night").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Connected to ${backend.server.hostName}").assertIsDisplayed()
        compose.onNodeWithText("Jetson thermal check").assertIsDisplayed()
        compose.onNodeWithText("Earlier").assertIsDisplayed()

        // P-1: the Activity going away (finish, rotation, swipe from Recents) never ends a session.
        scenario.recreate()
        scenario.close()
        Thread.sleep(500)
        assertTrue("no close: ${backend.closeRequests()}", backend.closeRequests().isEmpty())
        assertTrue("no stop: ${backend.stopFrames()}", backend.stopFrames().isEmpty())
        assertEquals("process-scoped state survives the Activity", ItemKey.Archie, graph.openSessions.active.value)
    }

    @Test fun shareIntentLandsInShareRepository() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "hello from another app").putExtra(Intent.EXTRA_SUBJECT, "subj")
        ActivityScenario.launch<MainActivity>(intent).use {
            val graph = (ApplicationProvider.getApplicationContext<Application>() as GraphOwner).graph
            assertEquals(SharePayload.Text("hello from another app", "subj"), graph.share.pending.value)
        }
    }
}
