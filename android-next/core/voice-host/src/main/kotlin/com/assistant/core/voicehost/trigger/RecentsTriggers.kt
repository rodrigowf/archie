package com.assistant.core.voicehost.trigger

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.assistant.core.audio.platform.LogcatVoiceLog
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.service.VoiceHostOwner
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base class of the recents-key accessibility trigger. The lite app declares it at the old FQCN
 * `com.assistant.peripheral.service.ButtonAccessibilityService` (spec 14 §1.7: the user enabled
 * that component by name in Accessibility settings), as an empty subclass.
 *
 * Behaviour of the old service (`ButtonAccessibilityService.kt:32-60`): a KEYCODE_APP_SWITCH held
 * ≥ 600 ms fires on release and is consumed; a short press passes through. Changed on purpose:
 * the trigger goes to the runtime's single [TriggerIngress] (no Activity broadcast, R2), the
 * `button_trigger_enabled` check is the ingress's (one source of truth), and a release without a
 * press never fires (the old code measured from 0).
 */
open class RecentsKeyAccessibilityService : AccessibilityService() {
    private val detector = LongPressDetector()

    override fun onServiceConnected() {
        LogcatVoiceLog.d(TAG, "Accessibility service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_APP_SWITCH) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(SystemClock.elapsedRealtime())
            KeyEvent.ACTION_UP -> if (detector.onUp(SystemClock.elapsedRealtime())) {
                val rt = (application as? VoiceHostOwner)?.voiceHostRuntime ?: return false
                if (!rt.buttonTriggerEnabled()) return false // disabled: let the press through (old behaviour)
                LogcatVoiceLog.d(TAG, "Recents long-press → starting realtime voice session")
                rt.ingress.trigger(Trigger.RECENTS)
                return true // consume: suppress the recents drawer
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "ButtonAccessibility"
    }
}

/**
 * Raw recents-key monitor (old `AssistantService.startRecentsMonitor`, `:613-675`): reads
 * `input_event` structs from `/dev/input/event2` (the A300M `sec_touchkey`) and fires on a
 * KEY_APPSWITCH long press. Kept for parity on the lite app (decision E-7) pending field evidence
 * (Q8); now behind the `button_trigger_enabled` toggle (fixes B5, the ingress checks it).
 *
 * Without root an app usually cannot open the node: it is `root:input 0660` and SELinux labels it
 * `input_device`, which `untrusted_app` may not read. That failure is logged once with the marker
 * [LOG_MARKER] ("Recents monitor error: … EACCES") so a field logcat answers Q8.
 */
class DevInputRecentsMonitor(
    private val log: VoiceLog,
    private val path: String = DEFAULT_PATH,
    private val onLongPress: () -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        stop()
        running = true
        thread = Thread({ run() }, "recents-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        log.d(TAG, "Recents monitor started")
        val detector = LongPressDetector()
        try {
            DataInputStream(FileInputStream(path)).use { dis ->
                val buf = ByteArray(STRUCT_SIZE)
                while (running) {
                    var off = 0
                    while (off < STRUCT_SIZE) {
                        val n = dis.read(buf, off, STRUCT_SIZE - off)
                        if (n < 0) { running = false; break }
                        off += n
                    }
                    if (!running) break
                    val e = parse(buf) ?: continue
                    if (e.type != EV_KEY || e.code != KEY_APPSWITCH) continue
                    when (e.value) {
                        1 -> detector.onDown(SystemClock.elapsedRealtime())
                        0 -> if (detector.onUp(SystemClock.elapsedRealtime())) {
                            log.d(TAG, "Recents long-press → starting realtime voice session")
                            onLongPress()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.w(TAG, "$LOG_MARKER ${e.message}")
        }
        log.d(TAG, "Recents monitor stopped")
    }

    data class InputEvent(val type: Int, val code: Int, val value: Int)

    companion object {
        private const val TAG = "AssistantService"
        const val DEFAULT_PATH = "/dev/input/event2"
        const val LOG_MARKER = "Recents monitor error:"

        /** 32-bit kernel `input_event`: timeval (8) + type (2) + code (2) + value (4). */
        const val STRUCT_SIZE = 16
        const val EV_KEY = 0x01
        const val KEY_APPSWITCH = 0x00fe

        /** Parses one 16-byte little-endian `input_event` (the A300M is 32-bit ARM). */
        fun parse(buf: ByteArray): InputEvent? {
            if (buf.size < STRUCT_SIZE) return null
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            bb.long // timeval
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val value = bb.int
            return InputEvent(type, code, value)
        }
    }
}
