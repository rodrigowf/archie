package com.assistant.archie.tv.shell

/*
 * Remote keys beyond D-pad / OK / Back (spec 15 §3.3), routed from the Activity's dispatchKeyEvent
 * before Compose sees them. Pure Kotlin (no android.view.KeyEvent) so routing is JVM-tested.
 */

/** The accelerator keys a screen may handle. */
enum class TvKey { MENU, PLAY_PAUSE, REWIND, FAST_FORWARD, LONG_OK }

/** A screen's (or overlay's) handler: true = handled, the key goes no further. */
fun interface TvKeyHandler {
    fun onKey(key: TvKey): Boolean
}

/** The fields of `android.view.KeyEvent` the router needs. */
data class RawKey(val code: Int, val down: Boolean, val repeat: Int = 0, val longPress: Boolean = false)

/** Android key codes (values of `android.view.KeyEvent.KEYCODE_*`, inlined so this file stays pure). */
object TvKeyCodes {
    const val DPAD_CENTER = 23
    const val ENTER = 66
    const val NUMPAD_ENTER = 160
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90

    val OK = setOf(DPAD_CENTER, ENTER, NUMPAD_ENTER)

    fun accelerator(code: Int): TvKey? = when (code) {
        MENU -> TvKey.MENU
        MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE -> TvKey.PLAY_PAUSE
        MEDIA_REWIND -> TvKey.REWIND
        MEDIA_FAST_FORWARD -> TvKey.FAST_FORWARD
        else -> null
    }

    /** Held Rewind / FF keep paging; Menu and Play/Pause act once per press. */
    fun repeats(key: TvKey): Boolean = key == TvKey.REWIND || key == TvKey.FAST_FORWARD
}

/**
 * Routes accelerator keys: [overlay] (options sheet, a screen's dialog or sheet) first, then the
 * current [screen], then [defaults] (Menu / long-press OK → the options sheet; Play/Pause → live
 * voice). While an overlay shows, the screen behind it never sees a key: what the overlay does not
 * handle goes to [defaults] (B6).
 *
 * - An accelerator handled on key-down also swallows its key-up (and repeats); one nobody handles
 *   goes to the view hierarchy unchanged (e.g. Rewind/FF reach a WebView).
 * - **Long-press OK**: the focused tv-material surface gets the repeat first (our `FocusCard` /
 *   `TvButton` pass `onLongClick` = [route] of [TvKey.LONG_OK], so tv-material releases its press
 *   and skips the click). Only when nothing consumed the repeat does the router route
 *   [TvKey.LONG_OK] itself, and then it swallows the rest of the press so no click follows.
 * - Back is not routed here: Compose `BackHandler`s handle it (see [BackPolicy]).
 */
class KeyRouter(
    private val overlay: () -> TvKeyHandler?,
    private val screen: () -> TvKeyHandler?,
    private val defaults: TvKeyHandler,
) {
    private val swallowed = mutableSetOf<Int>()
    private var longFired = false

    /** Routes [key] through overlay → defaults, or (no overlay) screen → defaults. */
    fun route(key: TvKey): Boolean {
        val o = overlay()
        if (o != null) return o.onKey(key) || defaults.onKey(key)
        return screen()?.onKey(key) == true || defaults.onKey(key)
    }

    /**
     * @param passOn delivers the event to the view hierarchy (Compose focus); returns whether it consumed it.
     * @return whether the event was consumed.
     */
    fun dispatch(key: RawKey, passOn: () -> Boolean): Boolean {
        val accel = TvKeyCodes.accelerator(key.code)
        if (accel != null) return dispatchAccelerator(accel, key, passOn)
        if (key.code in TvKeyCodes.OK) return dispatchOk(key, passOn)
        return passOn()
    }

    private fun dispatchAccelerator(accel: TvKey, key: RawKey, passOn: () -> Boolean): Boolean {
        if (!key.down) return if (swallowed.remove(key.code)) true else passOn()
        if (key.repeat > 0 && !TvKeyCodes.repeats(accel)) return if (key.code in swallowed) true else passOn()
        return if (route(accel)) {
            swallowed += key.code
            true
        } else {
            swallowed -= key.code
            passOn()
        }
    }

    private fun dispatchOk(key: RawKey, passOn: () -> Boolean): Boolean {
        if (key.down && key.repeat == 0) {
            longFired = false
            return passOn()
        }
        if (key.down) {
            if (longFired) return true
            if (passOn()) return true
            if ((key.repeat == 1 || key.longPress) && route(TvKey.LONG_OK)) {
                longFired = true
                return true
            }
            return false
        }
        if (longFired) {
            longFired = false
            return true
        }
        return passOn()
    }
}
