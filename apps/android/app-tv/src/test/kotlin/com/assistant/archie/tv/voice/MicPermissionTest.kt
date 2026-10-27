package com.assistant.archie.tv.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The RECORD_AUDIO result → what the app can still do. The case that matters is BLOCKED: Fire TV
 * answers a mic request denied *without ever showing the dialog*, leaving
 * `shouldShowRequestPermissionRationale` false, and before this the app only toasted "Archie needs
 * the microphone to hear the remote" — a dead end that needed adb to clear (2026-10-27).
 */
class MicPermissionTest {
    @Test
    fun `granted arms the mic`() {
        assertEquals(MicPermission.GRANTED, MicPermission.of(granted = true, canAskAgain = true))
        // The flag is meaningless once it is granted.
        assertEquals(MicPermission.GRANTED, MicPermission.of(granted = true, canAskAgain = false))
    }

    @Test
    fun `a denial the platform will ask about again is only a toast`() {
        assertEquals(MicPermission.RETRY, MicPermission.of(granted = false, canAskAgain = true))
    }

    @Test
    fun `a denial with no way to ask again sends the user to app settings`() {
        assertEquals(MicPermission.BLOCKED, MicPermission.of(granted = false, canAskAgain = false))
    }
}
