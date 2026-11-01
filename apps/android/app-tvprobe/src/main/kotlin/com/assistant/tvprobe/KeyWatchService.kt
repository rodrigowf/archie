package com.assistant.tvprobe

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Logs every key the system lets an accessibility service see, in any app (never consumes). */
class KeyWatchService : AccessibilityService() {
    override fun onServiceConnected() { Log.i("TVPROBE", "a11y connected") }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i("TVPROBE", "a11y key ${if (event.action == 0) "DOWN" else "UP"} code=${event.keyCode} " +
            "${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode}")
        return false
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) Log.i("TVPROBE", "a11y window ${event.packageName}")
    }
    override fun onInterrupt() {}
}
