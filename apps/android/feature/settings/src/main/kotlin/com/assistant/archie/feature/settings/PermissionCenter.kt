package com.assistant.archie.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One item of the background reliability checklist (spec 14 §2.6). */
enum class ReliabilityItem { MICROPHONE, BATTERY, AUTOSTART, NOTIFICATIONS, RECENTS_LOCK }

data class PermissionsState(
    val statuses: Map<AppPermission, PermissionStatus> = emptyMap(),
    val ignoringBatteryOptimizations: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val isXiaomiFamily: Boolean = false,
    val defaultAssistant: Boolean? = null,
) {
    fun status(p: AppPermission): PermissionStatus = statuses[p] ?: PermissionStatus.NOT_REQUIRED
    fun granted(p: AppPermission): Boolean = status(p).let { it == PermissionStatus.GRANTED || it == PermissionStatus.NOT_REQUIRED }

    /** "All granted" / "Notifications needed" for the Settings home row. */
    val summary: String
        get() {
            val missing = AppPermission.entries.filter { !granted(it) }
            return when {
                missing.isEmpty() -> "All granted"
                missing.size == 1 -> "${missing[0].title} not allowed"
                else -> "${missing.size} not allowed: ${missing.joinToString(", ") { it.title.lowercase() }}"
            }
        }

    /** The checklist items that apply on this device, and which are done. Autostart can't be read (null). */
    val checklist: List<Pair<ReliabilityItem, Boolean?>>
        get() = buildList {
            add(ReliabilityItem.MICROPHONE to granted(AppPermission.MICROPHONE))
            add(ReliabilityItem.BATTERY to ignoringBatteryOptimizations)
            if (isXiaomiFamily) add(ReliabilityItem.AUTOSTART to null)
            add(ReliabilityItem.NOTIFICATIONS to (granted(AppPermission.NOTIFICATIONS) && notificationsEnabled))
            add(ReliabilityItem.RECENTS_LOCK to null)
        }

    val checklistSummary: String
        get() {
            val known = checklist.filter { it.second != null }
            val done = known.count { it.second == true }
            return if (done == known.size) "All set" else "$done of ${known.size} set"
        }
}

/**
 * PermissionCenter (spec 14 §2.9, fixes "denials are silent", inv03 §1.1): the live status of every
 * runtime permission and the background-reliability checks. Requests happen in context (the UI asks
 * with a one-screen rationale first); a denial is recorded here so the rows show "Denied · Allow"
 * or, after "Don't ask again", "Blocked · Open app settings".
 */
class PermissionCenter(private val platform: DevicePlatform) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    /** Re-read everything (the page resumes after the user visited system settings). */
    fun refresh() { _state.value = read() }

    fun status(p: AppPermission): PermissionStatus = statusOf(p)

    /**
     * The system dialog answered. [canAskAgain] = `shouldShowRequestPermissionRationale` after the
     * denial: false means the system will not show the dialog again.
     */
    fun onResult(p: AppPermission, granted: Boolean, canAskAgain: Boolean) {
        platform.setBlocked(p, !granted && !canAskAgain)
        refresh()
    }

    private fun statusOf(p: AppPermission): PermissionStatus = when {
        platform.sdkInt < p.minSdk -> PermissionStatus.NOT_REQUIRED
        platform.isGranted(p) -> PermissionStatus.GRANTED.also { if (platform.isBlocked(p)) platform.setBlocked(p, false) }
        platform.isBlocked(p) -> PermissionStatus.BLOCKED
        else -> PermissionStatus.DENIED
    }

    private fun read() = PermissionsState(
        statuses = AppPermission.entries.associateWith { statusOf(it) },
        ignoringBatteryOptimizations = platform.isIgnoringBatteryOptimizations(),
        notificationsEnabled = platform.notificationsEnabled(),
        isXiaomiFamily = platform.isXiaomiFamily,
        defaultAssistant = platform.isDefaultAssistant(),
    )
}
