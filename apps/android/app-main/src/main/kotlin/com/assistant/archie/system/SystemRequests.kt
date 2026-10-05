package com.assistant.archie.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * RECORD_AUDIO in context (spec 14 §2.9): a voice start without the permission parks its action
 * here; the Activity's [SystemOverlays] shows the one-screen rationale, runs the system dialog and
 * [resolve]s. A denial is never silent: the Activity reports it to PermissionCenter and says so.
 */
class MicPermissionGate(private val context: Context) {
    data class Request(val id: Long, val onGranted: () -> Unit)

    private val ids = AtomicLong()
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Runs [action] now when the microphone is allowed; otherwise asks first and runs it on a grant. */
    fun withMicrophone(action: () -> Unit) {
        if (granted()) action() else _pending.value = Request(ids.incrementAndGet(), action)
    }

    fun resolve(granted: Boolean) {
        val r = _pending.value ?: return
        _pending.value = null
        if (granted) r.onGranted()
    }
}

/** Shell actions requested from outside the UI (launcher shortcuts, spec 14 §2.8). */
enum class ShellCommand { NEW_ARCHIE, NEW_AGENT }

/** Holds one pending [ShellCommand] so a cold-launch shortcut is not lost before the shell composes. */
class ShellCommands {
    private val _pending = MutableStateFlow<ShellCommand?>(null)
    val pending: StateFlow<ShellCommand?> = _pending.asStateFlow()

    fun offer(c: ShellCommand) { _pending.value = c }

    fun take(): ShellCommand? = _pending.value.also { _pending.value = null }
}
