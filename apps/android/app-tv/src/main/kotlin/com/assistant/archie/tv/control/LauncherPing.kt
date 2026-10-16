package com.assistant.archie.tv.control

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Tells the TvServerHub launcher/screensaver that the TV is in use (spec 15 §7.3, TV-10), so it
 * neither returns to the launcher nor blanks the screen while Archie shows pushed content or runs
 * live voice. Contract: broadcast `com.example.tvserverhub.action.KEEP_AWAKE` to that package, with
 * an optional int extra `seconds` = how long to hold off idling from now. Harmless when the
 * launcher is not installed. Pings are throttled to one per [MIN_INTERVAL_MS] unless forced.
 */
class LauncherPing(private val context: Context, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    @Volatile private var lastAt = Long.MIN_VALUE / 2

    fun ping(seconds: Int? = null, force: Boolean = false) {
        val now = clock()
        if (!force && now - lastAt < MIN_INTERVAL_MS) return
        lastAt = now
        val intent = Intent(ACTION).setPackage(LAUNCHER_PACKAGE)
        if (seconds != null) intent.putExtra(EXTRA_SECONDS, seconds)
        runCatching { context.sendBroadcast(intent) }
    }

    companion object {
        const val LAUNCHER_PACKAGE = "com.example.tvserverhub"
        const val ACTION = "com.example.tvserverhub.action.KEEP_AWAKE"
        const val EXTRA_SECONDS = "seconds"
        const val MIN_INTERVAL_MS = 30_000L
    }
}
