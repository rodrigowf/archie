package com.assistant.archie

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.assistant.archie.shell.MainActivity
import com.assistant.archie.system.ArchieTalkTileService
import com.assistant.archie.system.VoiceTrampolineActivity
import com.assistant.core.model.ThemeMode
import com.assistant.core.settings.SettingsStore
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.fgs.FgsTypes
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.runtime.GateReason
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * B-09 DoD on the `POCO_X7` AVD (spec 14 §7): FGS start types, the sticky-restart degrade to
 * SPECIAL_USE and its "Resume listening" promotion, notification actions, the QS tile toggle, share
 * of text and of a 3 MB file (plus SEND_MULTIPLE and nginx's 413), and system-bar icon contrast in
 * dark and light. Backend: [TestBackend] (MockWebServer on the device), never the live Jetson.
 */
@RunWith(AndroidJUnit4::class)
class SystemIntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = inst.targetContext
    private val app: TestArchieApplication get() = ctx.applicationContext as TestArchieApplication
    private val graph get() = app.graph
    private val host: VoiceHostRuntime get() = graph.voiceHost!!
    private val device: UiDevice = UiDevice.getInstance(inst)
    private val created = mutableListOf<Uri>()

    @Before fun setUp() {
        val ua = inst.uiAutomation
        ua.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        ua.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        TestBackend.reset()
        settings {
            awaitLoaded()
            setEnableWakeWord(false)
            setStayConnectedInBackground(false)
            setThemeMode(ThemeMode.DARK)
        }
        device.wakeUp()
        shell("wm dismiss-keyguard")
    }

    @After fun tearDown() {
        // Let voice end and the host settle before anything stops the service: stopping it while
        // a startForegroundService() is pending crashes the app (see the open issue in the B-09 report).
        runCatching { host.stopVoice() }
        runCatching { waitFor("voice off", 10_000) { host.state.value.session.phase.let { it == SessionPhase.OFF || it == SessionPhase.ERROR } } }
        SystemClock.sleep(1_500)
        settings {
            setEnableWakeWord(false)
            setStayConnectedInBackground(false)
        }
        created.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        SystemClock.sleep(1_000)
        VoiceHostService.stop(ctx)
    }

    // ───────────────────────────── FGS types ─────────────────────────────

    @Test fun fgs_foregroundStart_isMicrophonePlusSpecialUse() {
        launchMainConnected()
        settings { setStayConnectedInBackground(true) } // the service is wanted → started from the foreground
        withService { svc ->
            waitFor("FGS types MICROPHONE|SPECIAL_USE (were 0x${svc.foregroundServiceType.toString(16)})") {
                svc.foregroundServiceType == FgsTypes.MICROPHONE or FgsTypes.SPECIAL_USE
            }
        }
        Log.i(TAG, "dumpsys: " + shell("dumpsys activity services ${ctx.packageName}").lines().filter { "isForeground" in it || "types=" in it }.joinToString(" | "))
        settings { setStayConnectedInBackground(false) }
        waitFor("service stops when nothing needs it") { !serviceRunning() }
    }

    @Test fun fgs_stickyRestart_degradesToSpecialUse_resumeListeningPromotes() {
        launchMainConnected()
        waitFor("no service running") { !serviceRunning() }
        withService { svc ->
            assertEquals("bound only: not foreground yet", 0, svc.foregroundServiceType)
            // What the platform calls after a START_STICKY restart (A-08 FgsPolicy: STICKY_RESTART).
            inst.runOnMainSync { svc.onStartCommand(null, 0, 1) }
            waitFor("degraded to SPECIAL_USE (0x${svc.foregroundServiceType.toString(16)})") { svc.foregroundServiceType == FgsTypes.SPECIAL_USE }
            assertTrue("wake mic paused until foreground", GateReason.NEEDS_FOREGROUND in host.micGate.reasons.value)

            // The notification's "Resume listening" → trampoline (a foreground context) → MICROPHONE.
            ctx.startActivity(
                Intent(ctx, VoiceTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(VoiceHostService.EXTRA_HOST_ACTION, NotificationAction.RESUME_LISTENING.name),
            )
            waitFor("promoted to MICROPHONE|SPECIAL_USE (0x${svc.foregroundServiceType.toString(16)})") {
                svc.foregroundServiceType == FgsTypes.MICROPHONE or FgsTypes.SPECIAL_USE
            }
            waitFor("mic gate reopened") { GateReason.NEEDS_FOREGROUND !in host.micGate.reasons.value }
        }
    }

    // ───────────────────────────── notification actions ─────────────────────────────

    @Test fun notification_pauseResume_talk_end() {
        launchMainConnected()
        settings { setEnableWakeWord(true) }
        waitFor("host notification with Pause listening (${actionTitles()})", 30_000) { "Pause listening" in actionTitles() }

        sendAction("Pause listening")
        waitFor("wake paused by the user (${host.state.value.wake})") { host.state.value.wake == WakeHealth.PAUSED_BY_USER }
        waitFor("Resume listening offered (${actionTitles()})") { "Resume listening" in actionTitles() }

        sendAction("Resume listening") // trampoline
        waitFor("wake resumed (${host.state.value.wake})") { host.state.value.wake != WakeHealth.PAUSED_BY_USER }

        sendAction("Talk") // trampoline → Trigger.NOTIFICATION
        waitFor("voice_start sent") { "voice_start" in TestBackend.frameTypes() }
        waitFor("voice notification Mute/End (${actionTitles()})") { actionTitles().containsAll(listOf("Mute", "End")) }
        assertTrue(notificationText().startsWith("Archie · "))

        sendAction("End")
        waitFor("voice_stop sent") { "voice_stop" in TestBackend.frameTypes() }
        waitFor("voice off (${host.state.value.session.phase})") { host.state.value.session.phase == SessionPhase.OFF }
        waitFor("back to the host actions (${actionTitles()})") { "Pause listening" in actionTitles() }
    }

    // ───────────────────────────── QS tile ─────────────────────────────

    @Test fun tile_togglesVoice() {
        launchMainConnected()
        // Service wanted throughout, so the host never stops it mid-start (A-08 race, see tearDown).
        settings { setStayConnectedInBackground(true) }
        val tile = ComponentName(ctx, ArchieTalkTileService::class.java).flattenToString()
        shell("cmd statusbar add-tile $tile")
        try {
            // SystemUI may still be loading the new tile (fresh boot): retry the click until it lands.
            var clicks = 0
            waitFor("tile → trampoline → voice_start after $clicks clicks (phase ${host.state.value.session.phase})", 40_000) {
                if ("voice_start" in TestBackend.frameTypes()) return@waitFor true
                if (host.state.value.session.phase == SessionPhase.OFF) {
                    shell("cmd statusbar click-tile $tile")
                    clicks++
                    SystemClock.sleep(4_000)
                }
                "voice_start" in TestBackend.frameTypes()
            }
            assertTrue(ArchieTalkTileService.voiceLive(host))
            shell("cmd statusbar expand-settings") // the tile listens while the panel shows it
            waitFor("tile shows active (${ArchieTalkTileService.lastRenderedState})") { ArchieTalkTileService.lastRenderedState == Tile.STATE_ACTIVE }

            shell("cmd statusbar click-tile $tile")
            waitFor("tile tap while on → voice_stop") { "voice_stop" in TestBackend.frameTypes() }
            waitFor("voice off") { host.state.value.session.phase == SessionPhase.OFF }
            waitFor("tile shows inactive (${ArchieTalkTileService.lastRenderedState})") { ArchieTalkTileService.lastRenderedState == Tile.STATE_INACTIVE }
        } finally {
            shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar collapse")
        }
    }

    // ───────────────────────────── share target ─────────────────────────────

    @Test fun share_text_isInjectedIntoArchie() {
        launchMainConnected()
        ctx.startActivity(
            Intent(Intent.ACTION_SEND).setClass(ctx, MainActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "hello from another app").putExtra(Intent.EXTRA_SUBJECT, "Article")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        tapShare()
        waitFor("inject_text with the share line (${TestBackend.frames})") {
            TestBackend.frames.any { it.contains("\"inject_text\"") && it.contains("[shared text] Article\\nhello from another app") }
        }
    }

    @Test fun share_3MbFile_streamsToUploads_thenInjectsTheLine() {
        launchMainConnected()
        val size = 3 * 1024 * 1024
        val uri = download("b09-share-3mb.bin", size)
        ctx.startActivity(
            Intent(Intent.ACTION_SEND).setClass(ctx, MainActivity::class.java).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        tapShare()
        waitFor("3 MB multipart upload (${TestBackend.uploads})", 30_000) { TestBackend.uploads.any { it >= size } }
        waitFor("inject_text with the [shared file] line") {
            TestBackend.frames.any { it.contains("\"inject_text\"") && it.contains("[shared file] shared-1.bin") && it.contains("Local path: /home/rodrigo/uploads/shared-1.bin") }
        }
    }

    @Test fun share_multipleFiles_andTheNginx413Message() {
        launchMainConnected()
        val a = download("b09-multi-a.txt", 64 * 1024)
        val b = download("b09-multi-b.txt", 96 * 1024)
        ctx.startActivity(
            Intent(Intent.ACTION_SEND_MULTIPLE).setClass(ctx, MainActivity::class.java).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        tapShare()
        waitFor("two uploads (${TestBackend.uploads})", 20_000) { TestBackend.uploads.size == 2 }
        waitFor("two injected lines") { TestBackend.frames.count { it.contains("\"inject_text\"") && it.contains("[shared file]") } == 2 }

        TestBackend.uploadCode = 413
        val big = download("b09-too-big.bin", 2 * 1024 * 1024)
        ctx.startActivity(
            Intent(Intent.ACTION_SEND).setClass(ctx, MainActivity::class.java).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, big).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        tapShare()
        assertTrue("413 message shown", device.wait(Until.hasObject(By.text("File is larger than the server's 1 MB upload limit")), 20_000))
        device.findObject(By.text("Close"))?.click()
    }

    // ───────────────────────────── system bars (OI-1) ─────────────────────────────

    @Test fun systemBars_iconContrast_followsTheAppTheme() {
        shell("cmd uimode night no") // the OI-1 case: a light system with Archie's dark theme
        try {
            settings { setThemeMode(ThemeMode.DARK) }
            launchMainConnected()
            barsCase("dark", expectLightIcons = true)

            settings { setThemeMode(ThemeMode.LIGHT) }
            SystemClock.sleep(2_000) // setApplicationNightMode → recreate
            barsCase("light", expectLightIcons = false)

            shell("cmd uimode night yes") // and the reverse: a dark system with Archie's light theme
            SystemClock.sleep(2_000)
            barsCase("light-on-dark-system", expectLightIcons = false)
        } finally {
            settings { setThemeMode(ThemeMode.DARK) }
            shell("cmd uimode night no")
        }
    }

    @Test fun splashAndWindowTheme_barIconsFollowTheNightQualifier() {
        fun lightBars(night: Boolean, style: Int): Pair<Boolean, Boolean> {
            val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            }
            val c = ctx.createConfigurationContext(cfg)
            val a = c.theme.obtainStyledAttributes(style, intArrayOf(android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar))
            return try { a.getBoolean(0, false) to a.getBoolean(1, false) } finally { a.recycle() }
        }
        for (style in listOf(R.style.Theme_Archie_Starting, R.style.Theme_Archie)) {
            assertEquals("dark window → light icons", false to false, lightBars(night = true, style))
            assertEquals("light window → dark icons", true to true, lightBars(night = false, style))
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun barsCase(name: String, expectLightIcons: Boolean) {
        device.waitForIdle()
        SystemClock.sleep(1_200)
        val activity = resumedMain()
        var lightAppearance = false
        inst.runOnMainSync {
            lightAppearance = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
        }
        assertEquals("$name: isAppearanceLightStatusBars", !expectLightIcons, lightAppearance)
        val shot = inst.uiAutomation.takeScreenshot()
        val out = File(ctx.getExternalFilesDir(null), "b09-system-bars-$name.png")
        out.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot ${out.absolutePath}")
        // The status-bar strip: the clock/battery glyphs must contrast with the background.
        val statusBarPx = (24 * ctx.resources.displayMetrics.density).toInt()
        var bright = 0
        var dark = 0
        for (y in 0 until statusBarPx) for (x in 0 until shot.width step 2) {
            val p = shot.getPixel(x, y)
            val l = (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
            if (l > 0.6) bright++ else if (l < 0.45) dark++
        }
        Log.i(TAG, "$name: status bar bright=$bright dark=$dark")
        if (expectLightIcons) {
            assertTrue("$name: background dark", dark > bright)
            assertTrue("$name: light glyphs present ($bright)", bright > 40)
        } else {
            assertTrue("$name: background light", bright > dark)
            assertTrue("$name: dark glyphs present ($dark)", dark > 40)
        }
    }

    private fun resumedMain(): Activity {
        var found: Activity? = null
        waitFor("MainActivity resumed") {
            inst.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity }
            }
            found != null
        }
        return found!!
    }

    private fun tapShare() {
        assertTrue("share sheet shown", device.wait(Until.hasObject(By.text("Share with")), 15_000))
        device.findObject(By.text("Share")).click()
    }

    /** A real content:// URI (MediaStore Downloads, owned by the app) with DISPLAY_NAME and SIZE. */
    private fun download(name: String, size: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        created += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            val chunk = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                Random.nextBytes(chunk)
                val n = minOf(left, chunk.size)
                out.write(chunk, 0, n)
                left -= n
            }
        }
        return uri
    }

    private fun launchMainConnected() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("orchestrator adopted (${graph.orchestrator.state.value})", 20_000) { graph.orchestrator.state.value.orchestrator != null }
        resumedMain()
    }

    private fun hostNotification() =
        ctx.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 1001 }?.notification

    private fun actionTitles(): List<String> = hostNotification()?.actions?.map { it.title.toString() }.orEmpty()

    private fun notificationText(): String = hostNotification()?.extras?.getCharSequence("android.text")?.toString().orEmpty()

    private fun sendAction(title: String) {
        val a = hostNotification()?.actions?.firstOrNull { it.title.toString() == title } ?: throw AssertionError("no action \"$title\" in ${actionTitles()}")
        a.actionIntent.send()
    }

    private fun serviceRunning(): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.getRunningServices(100).any { it.service.packageName == ctx.packageName && it.service.className == VoiceHostService::class.java.name }
    }

    /** Binds the real service and hands its instance over (the LocalBinder's outer instance). */
    private fun withService(block: (VoiceHostService) -> Unit) {
        val latch = CountDownLatch(1)
        var svc: VoiceHostService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                svc = binder?.javaClass?.declaredFields?.firstOrNull { it.type == VoiceHostService::class.java }
                    ?.apply { isAccessible = true }?.get(binder) as? VoiceHostService
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(ctx.bindService(Intent(ctx, VoiceHostService::class.java), conn, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("bound", latch.await(10, TimeUnit.SECONDS))
            block(svc ?: throw AssertionError("no service instance behind the binder"))
        } finally {
            ctx.unbindService(conn)
        }
    }

    private fun settings(block: suspend SettingsStore.() -> Unit) = runBlocking { graph.settings.block() }

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!cond()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val TAG = "B09Test"
    }
}
