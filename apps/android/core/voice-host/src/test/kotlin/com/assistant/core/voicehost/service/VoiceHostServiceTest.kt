package com.assistant.core.voicehost.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.Intent
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.HostTuning
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** An Application that hosts the runtime, as `LiteApplication` / `ArchieApplication` do. */
class HostTestApp : Application(), VoiceHostOwner {
    lateinit var runtime: VoiceHostRuntime
    override val voiceHostRuntime: VoiceHostRuntime get() = runtime
}

/**
 * The real [VoiceHostService] under Robolectric (spec 14 §2.6 FGS rules end to end: the service's
 * start paths drive the runtime's mic gate and the notification). The pure type matrix is
 * `FgsPolicyTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HostTestApp::class)
class VoiceHostServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as HostTestApp

    @Before
    fun grantMic() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun HostRig.service(): ServiceController<VoiceHostService> {
        app.runtime = runtime
        return Robolectric.buildService(VoiceHostService::class.java).create()
    }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Android 14 START_STICKY restart: SPECIAL_USE only → the wake mic is held, health says why. */
    @Test
    fun stickyRestartOnApi34DegradesAndAForegroundStartPromotes() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        val sc = r.service()
        sc.get().onStartCommand(null, 0, 1)
        r.settle()

        val shadow = shadowOf(sc.get())
        assertEquals(HostTuning.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(WakeHealth.PAUSED_NEEDS_FOREGROUND, r.state.wake)
        assertFalse("no mic opened in the background", r.engines.any { "start" in it.calls })

        // "Resume listening" through the trampoline: a foreground-origin start promotes the service.
        sc.get().onStartCommand(Intent().putExtra(VoiceHostService.EXTRA_FROM_FOREGROUND, true), 0, 2)
        r.settle()
        assertEquals(WakeHealth.ARMED, r.state.wake)
        assertTrue(r.engines.last().calls.contains("start"))
    }

    @Test
    fun aForegroundStartWithoutTheMicPermissionStaysDegraded() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        val sc = r.service()
        sc.get().onStartCommand(Intent().putExtra(VoiceHostService.EXTRA_FROM_FOREGROUND, true), 0, 1)
        r.settle()
        assertEquals(WakeHealth.PAUSED_NEEDS_FOREGROUND, r.state.wake)
    }

    /** The lite notification: old id, channel, title and text (inv04 §4.5; the companion watchdog sees a running service). */
    @Test
    @Config(sdk = [28])
    fun liteNotificationOnAnOlderApiKeepsListeningAfterAStickyRestart() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        val sc = r.service()
        sc.get().onStartCommand(null, 0, 1)
        r.settle()
        val n = shadowOf(sc.get()).lastForegroundNotification
        assertEquals(HostTuning.NOTIFICATION_ID, shadowOf(sc.get()).lastForegroundNotificationId)
        assertEquals(HostTuning.NOTIFICATION_CHANNEL_ID, n.channelId)
        assertEquals("Listening for commands", n.text())
        assertEquals("Assistant Active", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }

    /** Android 12+: when the platform refuses the FGS, the host shows BACKGROUND_RESTRICTED and the service stops. */
    @Test
    fun aRefusedStartForegroundIsSurfaced() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        val sc = r.service()
        shadowOf(sc.get()).setThrowInStartForeground(IllegalStateException("ForegroundServiceStartNotAllowedException"))
        sc.get().onStartCommand(Intent().putExtra(VoiceHostService.EXTRA_FROM_FOREGROUND, true), 0, 1)
        r.settle()
        assertEquals(WakeHealth.BACKGROUND_RESTRICTED, r.state.wake)
        assertTrue(shadowOf(sc.get()).isStoppedBySelf)
    }

    @Test
    fun theLocalBinderServesTheProcessRuntime() = runTest {
        val r = HostRig(this)
        val sc = r.service()
        val binder = sc.get().onBind(Intent()) as VoiceHostService.LocalBinder
        assertSame(r.runtime, binder.host)
    }

    /** Notification actions reach the runtime (End = a user stop; nothing is sent without live voice). */
    @Test
    fun notificationActionsReachTheRuntime() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        val sc = r.service()
        sc.get().onStartCommand(Intent().putExtra(VoiceHostService.EXTRA_FROM_FOREGROUND, true), 0, 1)
        sc.get().onStartCommand(Intent().setAction(VoiceHostService.ACTION_PREFIX + "PAUSE_LISTENING"), 0, 2)
        r.settle()
        assertEquals(WakeHealth.PAUSED_BY_USER, r.state.wake)
        sc.get().onStartCommand(Intent().setAction(VoiceHostService.ACTION_PREFIX + "RESUME_LISTENING"), 0, 3)
        r.settle()
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }
}
