package com.assistant.archie.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** PermissionCenter (spec 14 §2.9): denials are recorded and shown, never silent (inv03 §1.1). */
class PermissionCenterTest {
    @Test fun deniedOnce_canAskAgain_staysDenied() {
        val p = FakePlatform().apply { granted.clear() }
        val c = PermissionCenter(p)
        c.onResult(AppPermission.MICROPHONE, granted = false, canAskAgain = true)
        assertEquals(PermissionStatus.DENIED, c.state.value.status(AppPermission.MICROPHONE))
    }

    @Test fun deniedForGood_isBlocked_untilGrantedInSettings() {
        val p = FakePlatform().apply { granted.clear() }
        val c = PermissionCenter(p)
        c.onResult(AppPermission.MICROPHONE, granted = false, canAskAgain = false)
        assertEquals(PermissionStatus.BLOCKED, c.state.value.status(AppPermission.MICROPHONE))
        // The user allows it in app settings and comes back.
        p.granted += AppPermission.MICROPHONE
        c.refresh()
        assertEquals(PermissionStatus.GRANTED, c.state.value.status(AppPermission.MICROPHONE))
        assertEquals(false, AppPermission.MICROPHONE in p.blocked)
    }

    @Test fun apiLevelGates() {
        val p = FakePlatform(sdkInt = 30).apply { granted.clear() }
        val s = PermissionCenter(p).state.value
        assertEquals(PermissionStatus.NOT_REQUIRED, s.status(AppPermission.NOTIFICATIONS))   // 33+
        assertEquals(PermissionStatus.NOT_REQUIRED, s.status(AppPermission.NEARBY_DEVICES))  // 31+
        assertEquals(PermissionStatus.DENIED, s.status(AppPermission.MICROPHONE))
    }

    @Test fun checklistReflectsBatteryAndNotifications() {
        val p = FakePlatform().apply { batteryUnrestricted = true; notificationsOn = false; isXiaomiFamily = false }
        val s = PermissionCenter(p).state.value
        assertEquals(listOf(ReliabilityItem.MICROPHONE, ReliabilityItem.BATTERY, ReliabilityItem.NOTIFICATIONS, ReliabilityItem.RECENTS_LOCK), s.checklist.map { it.first })
        assertEquals("2 of 3 set", s.checklistSummary)
    }
}
