package com.assistant.core.voicehost.runtime

import android.app.Activity
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.testing.FakeWakeConfigStore
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.HostAudio
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.WakeHealth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 15 TV-8 / TV-9: `HostConfig.tv` is additive. The phone configs keep the wake word and no
 * audio overrides; a TV host never builds a wake engine, whatever the settings say, and its service
 * runs only while voice does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeTvConfigTest {
    private val tv = HostConfig.tv(Activity::class.java, HostAudio(MicSourceType.VOICE_RECOGNITION, mediaPlayback = true))

    @Test
    fun phoneConfigsAreUnchanged() {
        val main = HostConfig.main(Activity::class.java, Activity::class.java)
        val lite = HostConfig.lite(Activity::class.java)
        for (c in listOf(main, lite)) {
            assertTrue(c.wakeWord)
            assertNull(c.audio)
        }
        assertFalse(tv.wakeWord)
        assertFalse(tv.bringToFrontOnTrigger)
        assertFalse(tv.serviceRunsAlways)
        assertEquals(MicSourceType.VOICE_RECOGNITION, tv.audio!!.micSource)
    }

    @Test
    fun phoneHostStillBuildsTheWakeEngine() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS // wake word on
        r.settle()
        assertEquals(1, r.engines.size)
        assertTrue(r.store.saves.last().enabled)
    }

    @Test
    fun tvHostNeverBuildsAWakeEngine() = runTest {
        val r = HostRig(this, config = tv)
        r.store.stored = FakeWakeConfigStore.DEFAULTS.copy(enabled = true) // a stale "enabled" in the prefs file
        r.connectAndAdopt(HostRig.SETTINGS) // settings say wake word on
        r.advance(10_000)
        r.runtime.onStickyRestart()
        r.runtime.onScreenOnOrUserPresent()
        r.advance(10_000)
        assertTrue("no wake engine on a TV host", r.engines.isEmpty())
        assertFalse(r.store.saves.any { it.enabled })
        assertEquals(WakeHealth.DISABLED, r.state.wake)
        assertFalse("nothing keeps the service up while voice is off", r.runtime.serviceWanted())
    }

    @Test
    fun tvHostStillRunsLiveVoice() = runTest {
        val r = HostRig(this, config = tv)
        r.connectAndAdopt(HostRig.SETTINGS)
        r.startActiveVoice()
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertTrue(r.runtime.serviceWanted())
        assertTrue(r.engines.isEmpty())
    }
}
