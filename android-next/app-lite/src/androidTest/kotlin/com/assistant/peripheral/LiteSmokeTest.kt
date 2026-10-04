package com.assistant.peripheral

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.voicehost.HostConnection
import com.assistant.core.voicehost.service.VoiceHostService
import com.assistant.peripheral.face.FaceAction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke of the lite app on the `A300M_API21` AVD (spec 14 §7 C-01), against an
 * on-device MockWebServer backend (never the live Jetson):
 *  - process start runs the FGS the companion watchdog looks for (inv04 §6.4 contract 3);
 *  - the face connects and shows Ready; Settings opens and Back returns to the face;
 *  - Start → voice CONNECTING → the scripted `voice/session` failure → Error face, wake re-armed.
 */
@RunWith(AndroidJUnit4::class)
class LiteSmokeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = inst.targetContext
    private val app: LiteApplication get() = ctx.applicationContext as LiteApplication

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!cond()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            SystemClock.sleep(100)
        }
    }

    @Test fun processStart_runsTheHostService_companionContract3() {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        waitFor("VoiceHostService running") {
            @Suppress("DEPRECATION")
            am.getRunningServices(100).any { it.service.packageName == ctx.packageName && it.service.className == VoiceHostService::class.java.name }
        }
    }

    @Test fun face_settings_andTheVoiceErrorPath() {
        MockBackend().use { backend ->
            runBlocking { withTimeout(10_000) { app.graph.settings.awaitLoaded() } }
            app.graph.liteSettings.setServer(backend.serverUrl)
            val activity = inst.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = app.graph.voiceHost

            waitFor("connected + adopted") { host.state.value.connection == HostConnection.CONNECTED && backend.frames.any { it.contains("\"start\"") } }
            waitFor("face shows Ready (was ${activity.faceModel?.word})") { activity.faceModel?.word == "Ready" }
            assertEquals("127.0.0.1", activity.faceModel!!.connLabel)

            // Settings round trip: tap the gear, Back returns to the face.
            inst.runOnMainSync { findByDescription(activity, "Settings").performClick() }
            assertTrue(activity.showingSettings)
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            inst.waitForIdleSync()
            assertFalse(activity.showingSettings)

            // Start (the big shape) → voice_start + POST voice/session (503) → Error face.
            assertEquals(FaceAction.START, activity.faceModel!!.action)
            inst.runOnMainSync { findByDescription(activity, "Tap to talk").performClick() }
            waitFor("voice/session requested") { backend.paths.any { it.startsWith("POST /api/orchestrator/voice/session") } }
            waitFor("Error face (was ${activity.faceModel?.word})") { activity.faceModel?.word == "Error" }
            assertTrue(backend.frames.any { it.contains("\"voice_start\"") })
            // Finalize → the wake word is re-armed (RS-09 / RS-30: 1,500 ms after the stop).
            waitFor("wake re-armed (${host.state.value.wake})", 10_000) { host.state.value.wake != com.assistant.core.voicehost.WakeHealth.PAUSED_FOR_VOICE }

            // RAM snapshot for the report (dumpsys meminfo is the budget measure, spec 14 §5.6).
            val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            Log.i("LiteSmoke", "PSS total ${mem.totalPss} kB, dalvik ${mem.dalvikPss} kB, native ${mem.nativePss} kB")
            activity.finish()
        }
    }

    private fun findByDescription(activity: MainActivity, text: String): View {
        val out = ArrayList<View>()
        activity.window.decorView.findViewsWithText(out, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return out.firstOrNull { it.isShown } ?: throw AssertionError("no view described \"$text\"")
    }
}
