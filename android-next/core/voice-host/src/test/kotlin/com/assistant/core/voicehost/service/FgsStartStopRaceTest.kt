package com.assistant.core.voicehost.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.HostTuning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B-09 crash (found on the POCO AVD): the runtime asks for the service on every owner voice-phase
 * change, so a voice that ends quickly issued `startForegroundService()` (ENDING) and then
 * `stopService()` (OFF) before the start reached `onStartCommand`. Android then kills the app with
 * `ForegroundServiceDidNotStartInTimeException` ("did not then call Service.startForeground()").
 * Robolectric does not raise that exception, so these tests pin the conditions that cause it:
 * never stop with a start in flight, and never send a start that changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HostTestApp::class)
class FgsStartStopRaceTest {
    private val app get() = RuntimeEnvironment.getApplication() as HostTestApp

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        VoiceHostService.resetForTest()
    }

    private fun HostRig.attach() {
        settings.value = HostRig.SETTINGS
        app.runtime = runtime
    }

    /** Delivers a start intent to a fresh service instance (what the platform does after startForegroundService). */
    private fun deliver(intent: Intent): VoiceHostService {
        val sc = Robolectric.buildService(VoiceHostService::class.java, intent).create()
        sc.get().onStartCommand(intent, 0, 1)
        return sc.get()
    }

    @Test
    fun aStopWhileAForegroundStartIsInFlight_isDeferredUntilStartForegroundRan() = runTest {
        HostRig(this).attach()
        assertTrue(VoiceHostService.start(app, fromForeground = true))
        val pending = shadowOf(app).nextStartedService
        assertNotNull(pending)

        VoiceHostService.stop(app) // voice ended (OFF) before the start was delivered
        assertNull("stopService with a start in flight crashes the app", shadowOf(app).nextStoppedService)

        val svc = deliver(pending!!)
        assertEquals("startForeground ran first", HostTuning.NOTIFICATION_ID, shadowOf(svc).lastForegroundNotificationId)
        assertTrue("then the deferred stop", shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun repeatedStartsWithNothingToChange_sendNoNewForegroundStart() = runTest {
        HostRig(this).attach()
        assertTrue(VoiceHostService.start(app, fromForeground = true))
        val first = shadowOf(app).nextStartedService!!
        // In flight: further requests (each owner phase change) are absorbed.
        repeat(3) { VoiceHostService.start(app, fromForeground = true) }
        assertNull(shadowOf(app).nextStartedService)

        deliver(first) // foreground with MICROPHONE|SPECIAL_USE
        repeat(5) { assertTrue(VoiceHostService.start(app, fromForeground = true)) }
        assertNull("already foreground with every wanted type", shadowOf(app).nextStartedService)

        // Nothing in flight any more: a stop goes straight through.
        VoiceHostService.stop(app)
        assertEquals(ComponentName(app, VoiceHostService::class.java), shadowOf(app).nextStoppedService?.component)
    }

    @Test
    fun aDegradedServiceIsStillPromoted() = runTest {
        HostRig(this).attach()
        val sc = Robolectric.buildService(VoiceHostService::class.java).create()
        sc.get().onStartCommand(null, 0, 1) // sticky restart → SPECIAL_USE only
        assertTrue(VoiceHostService.start(app, fromForeground = true)) // "Resume listening"
        assertNotNull("the MICROPHONE type is missing, so a start is sent", shadowOf(app).nextStartedService)
    }

    @Test
    fun aNewStartCancelsADeferredStop() = runTest {
        HostRig(this).attach()
        VoiceHostService.start(app, fromForeground = true)
        val pending = shadowOf(app).nextStartedService!!
        VoiceHostService.stop(app)
        VoiceHostService.start(app, fromForeground = true) // wanted again before delivery
        val svc = deliver(pending)
        assertFalse(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun aRefusedStart_isLoggedAndDoesNotBlockTheNextOne() = runTest {
        HostRig(this).attach()
        var refuse = true
        var stops = 0
        val ctx = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                if (refuse) throw ForegroundServiceStartNotAllowedException("background")
                return super.startForegroundService(service)
            }

            override fun stopService(name: Intent): Boolean {
                stops++
                return true
            }
        }
        assertFalse(VoiceHostService.start(ctx, fromForeground = true))
        VoiceHostService.stop(ctx)
        assertEquals("nothing in flight after a refusal: the stop is not deferred", 1, stops)
        refuse = false
        assertTrue(VoiceHostService.start(ctx, fromForeground = true))
        assertNotNull(shadowOf(app).nextStartedService)
    }
}
