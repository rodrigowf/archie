package com.assistant.archie.tv.shell

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeyRouter (spec 15 §3.3): accelerators to overlay → screen → defaults; long-press OK; key-up swallowing. */
class KeyRouterTest {
    private val routed = mutableListOf<String>()
    private var overlayOn = false
    private var screenHandles = setOf<TvKey>()
    private var passOnResult = false
    private var passed = 0

    private val router = KeyRouter(
        overlay = { if (overlayOn) TvKeyHandler { k -> routed += "overlay:$k"; true } else null },
        screen = { TvKeyHandler { k -> (k in screenHandles).also { if (it) routed += "screen:$k" } } },
        defaults = TvKeyHandler { k ->
            when (k) {
                TvKey.MENU, TvKey.LONG_OK, TvKey.PLAY_PAUSE -> { routed += "default:$k"; true }
                else -> false
            }
        },
    )

    private fun send(code: Int, down: Boolean, repeat: Int = 0, longPress: Boolean = false): Boolean =
        router.dispatch(RawKey(code, down, repeat, longPress)) { passed++; passOnResult }

    @Test fun keyCodesMatchAndroid() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, TvKeyCodes.DPAD_CENTER)
        assertEquals(KeyEvent.KEYCODE_ENTER, TvKeyCodes.ENTER)
        assertEquals(KeyEvent.KEYCODE_NUMPAD_ENTER, TvKeyCodes.NUMPAD_ENTER)
        assertEquals(KeyEvent.KEYCODE_MENU, TvKeyCodes.MENU)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, TvKeyCodes.MEDIA_PLAY_PAUSE)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, TvKeyCodes.MEDIA_PLAY)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, TvKeyCodes.MEDIA_PAUSE)
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, TvKeyCodes.MEDIA_REWIND)
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, TvKeyCodes.MEDIA_FAST_FORWARD)
    }

    @Test fun menuGoesToTheScreenFirstAndSwallowsItsKeyUp() {
        screenHandles = setOf(TvKey.MENU)
        assertTrue(send(TvKeyCodes.MENU, down = true))
        assertTrue(send(TvKeyCodes.MENU, down = false))
        assertEquals(listOf("screen:MENU"), routed)
        assertEquals(0, passed)
    }

    @Test fun menuFallsBackToTheGlobalDefault() {
        assertTrue(send(TvKeyCodes.MENU, down = true))
        assertTrue(send(TvKeyCodes.MENU, down = false))
        assertEquals(listOf("default:MENU"), routed)
        assertEquals(0, passed)
    }

    @Test fun overlayWinsOverTheScreen() {
        overlayOn = true
        screenHandles = setOf(TvKey.MENU)
        send(TvKeyCodes.MENU, down = true)
        assertEquals(listOf("overlay:MENU"), routed)
    }

    @Test fun playPauseAndItsMediaVariantsReachLiveVoice() {
        for (code in listOf(TvKeyCodes.MEDIA_PLAY_PAUSE, TvKeyCodes.MEDIA_PLAY, TvKeyCodes.MEDIA_PAUSE)) {
            send(code, down = true)
            send(code, down = false)
        }
        assertEquals(List(3) { "default:PLAY_PAUSE" }, routed)
        assertEquals(0, passed)
    }

    @Test fun menuRepeatsDoNotRouteAgain() {
        send(TvKeyCodes.MENU, down = true)
        assertTrue(send(TvKeyCodes.MENU, down = true, repeat = 1))
        assertTrue(send(TvKeyCodes.MENU, down = true, repeat = 2))
        send(TvKeyCodes.MENU, down = false)
        assertEquals(listOf("default:MENU"), routed)
    }

    @Test fun unhandledRewindGoesToTheViewHierarchyDownAndUp() {
        passOnResult = true
        assertTrue(send(TvKeyCodes.MEDIA_REWIND, down = true))
        assertTrue(send(TvKeyCodes.MEDIA_REWIND, down = false))
        assertEquals(2, passed)
        assertTrue(routed.isEmpty())
    }

    @Test fun heldFastForwardPagesOnEveryRepeat() {
        screenHandles = setOf(TvKey.FAST_FORWARD)
        send(TvKeyCodes.MEDIA_FAST_FORWARD, down = true)
        send(TvKeyCodes.MEDIA_FAST_FORWARD, down = true, repeat = 1)
        send(TvKeyCodes.MEDIA_FAST_FORWARD, down = true, repeat = 2)
        send(TvKeyCodes.MEDIA_FAST_FORWARD, down = false)
        assertEquals(List(3) { "screen:FAST_FORWARD" }, routed)
        assertEquals(0, passed)
    }

    @Test fun shortOkIsAPlainClick() {
        passOnResult = true
        assertTrue(send(TvKeyCodes.DPAD_CENTER, down = true))
        assertTrue(send(TvKeyCodes.DPAD_CENTER, down = false))
        assertEquals(2, passed)
        assertTrue(routed.isEmpty())
    }

    @Test fun longPressOkNobodyConsumedRoutesOnceAndSwallowsTheRestOfThePress() {
        passOnResult = false
        send(TvKeyCodes.DPAD_CENTER, down = true)                       // passOn #1
        assertTrue(send(TvKeyCodes.DPAD_CENTER, down = true, repeat = 1, longPress = true)) // passOn #2, then route
        assertTrue(send(TvKeyCodes.DPAD_CENTER, down = true, repeat = 2))
        assertTrue(send(TvKeyCodes.DPAD_CENTER, down = false))          // no click
        assertEquals(listOf("default:LONG_OK"), routed)
        assertEquals(2, passed)
    }

    @Test fun longPressOnAConsumingSurfaceIsLeftToTheSurface() {
        passOnResult = true // a tv-material surface consumes every OK event (its onLongClick routes itself)
        send(TvKeyCodes.ENTER, down = true)
        send(TvKeyCodes.ENTER, down = true, repeat = 1, longPress = true)
        send(TvKeyCodes.ENTER, down = false)
        assertTrue(routed.isEmpty())
        assertEquals(3, passed)
    }

    @Test fun longPressGoesToTheScreenBeforeTheDefault() {
        screenHandles = setOf(TvKey.LONG_OK)
        send(TvKeyCodes.DPAD_CENTER, down = true)
        send(TvKeyCodes.DPAD_CENTER, down = true, repeat = 1)
        assertEquals(listOf("screen:LONG_OK"), routed)
    }

    @Test fun otherKeysPassThrough() {
        passOnResult = true
        assertTrue(send(KeyEvent.KEYCODE_DPAD_LEFT, down = true))
        assertTrue(send(KeyEvent.KEYCODE_BACK, down = true))
        assertEquals(2, passed)
        assertFalse(routed.isNotEmpty())
    }
}
