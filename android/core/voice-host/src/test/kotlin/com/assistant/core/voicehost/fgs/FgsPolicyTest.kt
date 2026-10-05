package com.assistant.core.voicehost.fgs

import com.assistant.core.voicehost.fgs.FgsTypes.MICROPHONE
import com.assistant.core.voicehost.fgs.FgsTypes.NONE
import com.assistant.core.voicehost.fgs.FgsTypes.SPECIAL_USE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS type policy (spec 14 §2.6; inv04 R5; risk X9) with a fake SDK level and permission state.
 * The lite app runs on API 21/22, the main app on 26…36.
 */
class FgsPolicyTest {
    private val sdks = listOf(21, 22, 23, 26, 29, 30, 31, 33, 34, 35, 36)

    private fun first(sdk: Int, origin: StartOrigin, granted: Boolean = true) =
        FgsPolicy.decide(sdk, origin, granted, alreadyForeground = false, currentMask = NONE)

    @Test
    fun theFirstStartAlwaysCallsStartForeground() {
        for (sdk in sdks) for (o in StartOrigin.entries) for (g in listOf(true, false)) {
            assertTrue("sdk=$sdk $o granted=$g", first(sdk, o, g).callStartForeground)
        }
    }

    @Test
    fun belowApi30NoTypeIsPassedAndTheMicFollowsThePermission() {
        for (sdk in listOf(21, 22, 23, 26, 29)) for (o in StartOrigin.entries) {
            assertEquals("sdk=$sdk $o", FgsDecision(true, NONE, micAllowed = true), first(sdk, o, granted = true))
            assertEquals("sdk=$sdk $o", FgsDecision(true, NONE, micAllowed = false), first(sdk, o, granted = false))
        }
    }

    /** The lite app (A300M, API 22): a sticky restart or a boot launch keeps listening (no restriction). */
    @Test
    fun liteOnLollipopKeepsTheMicAfterAStickyRestart() {
        val d = first(22, StartOrigin.STICKY_RESTART)
        assertTrue(d.micAllowed)
        assertFalse(d.degraded)
    }

    @Test
    fun api30To33UseTheMicrophoneTypeOnlyFromTheForeground() {
        for (sdk in 30..33) {
            assertEquals(FgsDecision(true, MICROPHONE, true), first(sdk, StartOrigin.FOREGROUND))
            assertEquals(FgsDecision(true, NONE, false), first(sdk, StartOrigin.BACKGROUND))
            assertEquals(FgsDecision(true, NONE, false), first(sdk, StartOrigin.STICKY_RESTART))
            assertEquals("no permission → no mic type", FgsDecision(true, NONE, false), first(sdk, StartOrigin.FOREGROUND, granted = false))
        }
    }

    @Test
    fun api34PlusUsesMicrophoneAndSpecialUseFromTheForeground() {
        for (sdk in 34..36) {
            assertEquals(FgsDecision(true, MICROPHONE or SPECIAL_USE, true), first(sdk, StartOrigin.FOREGROUND))
        }
    }

    /** spec 14 §2.6: `onStartCommand(null)` → SPECIAL_USE only; the mic is not re-acquired in the background. */
    @Test
    fun api34PlusStickyRestartDegradesToSpecialUse() {
        for (sdk in 34..36) {
            val d = first(sdk, StartOrigin.STICKY_RESTART)
            assertEquals(SPECIAL_USE, d.typeMask)
            assertTrue(d.degraded)
            assertEquals(FgsDecision(true, SPECIAL_USE, false), first(sdk, StartOrigin.BACKGROUND))
            assertEquals("denied permission", FgsDecision(true, SPECIAL_USE, false), first(sdk, StartOrigin.FOREGROUND, granted = false))
        }
    }

    /** "Resume listening" via the trampoline (a foreground context) promotes the degraded service. */
    @Test
    fun aForegroundStartPromotesADegradedService() {
        val promoted = FgsPolicy.decide(34, StartOrigin.FOREGROUND, true, alreadyForeground = true, currentMask = SPECIAL_USE)
        assertEquals(FgsDecision(true, MICROPHONE or SPECIAL_USE, true), promoted)
        val r = FgsPolicy.decide(31, StartOrigin.FOREGROUND, true, alreadyForeground = true, currentMask = NONE)
        assertEquals(FgsDecision(true, MICROPHONE, true), r)
    }

    /** R5: never call startForeground again from a background intent (e.g. a notification Mute/End). */
    @Test
    fun aBackgroundIntentNeverCallsStartForegroundAgain() {
        for (sdk in sdks) for (mask in listOf(NONE, MICROPHONE, SPECIAL_USE, MICROPHONE or SPECIAL_USE)) {
            for (o in listOf(StartOrigin.BACKGROUND, StartOrigin.STICKY_RESTART)) {
                val d = FgsPolicy.decide(sdk, o, true, alreadyForeground = true, currentMask = mask)
                assertFalse("sdk=$sdk mask=$mask $o", d.callStartForeground)
                assertEquals("keeps the current types", mask, d.typeMask)
            }
        }
    }

    @Test
    fun aForegroundRepeatThatAddsNothingDoesNotRestart() {
        val d = FgsPolicy.decide(34, StartOrigin.FOREGROUND, true, alreadyForeground = true, currentMask = MICROPHONE or SPECIAL_USE)
        assertFalse(d.callStartForeground)
        assertTrue(d.micAllowed)
    }

    @Test
    fun theTypeConstantsAreThePlatformValues() {
        assertEquals(0x80, MICROPHONE) // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(0x40000000, SPECIAL_USE) // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    }
}
