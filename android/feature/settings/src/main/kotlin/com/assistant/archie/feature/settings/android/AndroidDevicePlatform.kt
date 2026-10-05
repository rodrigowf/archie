package com.assistant.archie.feature.settings.android

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.assistant.archie.feature.settings.AppPermission
import com.assistant.archie.feature.settings.AppVersion
import com.assistant.archie.feature.settings.Appearance
import com.assistant.archie.feature.settings.AppearanceStore
import com.assistant.archie.feature.settings.DevicePlatform
import com.assistant.archie.feature.settings.TextSize
import com.assistant.core.model.AudioOutput
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** [DevicePlatform] over the Android framework (main app, minSdk 26). */
class AndroidDevicePlatform(context: Context) : DevicePlatform {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val packageName: String get() = app.packageName

    override val deviceName: String by lazy {
        runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    override val appVersion: AppVersion by lazy {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        AppVersion(info.versionName ?: "?", code, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    override fun isGranted(permission: AppPermission): Boolean =
        sdkInt < permission.minSdk || app.checkSelfPermission(permission.manifest) == PackageManager.PERMISSION_GRANTED

    override fun isBlocked(permission: AppPermission) = prefs.getBoolean("blocked:${permission.name}", false)
    override fun setBlocked(permission: AppPermission, blocked: Boolean) {
        prefs.edit().putBoolean("blocked:${permission.name}", blocked).apply()
    }

    override fun availableOutputs(): Flow<Set<AudioOutput>> = callbackFlow {
        fun emit() { trySend(outputsNow()) }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = emit()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = emit()
        }
        emit()
        audio.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { audio.unregisterAudioDeviceCallback(cb) }
    }.distinctUntilChanged()

    private fun outputsNow(): Set<AudioOutput> {
        val types = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        return buildSet {
            add(AudioOutput.AUTO)
            add(AudioOutput.LOUDSPEAKER)
            if (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types) add(AudioOutput.EARPIECE)
            if (types.any { it in BLUETOOTH_TYPES }) add(AudioOutput.BLUETOOTH)
            if (types.any { it in WIRED_TYPES }) add(AudioOutput.WIRED)
        }
    }

    /** Archie's voice plays on the call stream (inv04 §4.10: AudioTrack tagged CALL for every route but BT media). */
    override fun speakerLevel(): Float? = runCatching {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (max <= 0) null else audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toFloat() / max
    }.getOrNull()

    override fun setSpeakerLevel(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) else 1
        val index = (level * max).roundToInt().coerceIn(min, max)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, index, 0)
    }

    override fun isIgnoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

    override fun notificationsEnabled(): Boolean = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override val isXiaomiFamily: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    override fun isDefaultAssistant(): Boolean? = if (Build.VERSION.SDK_INT >= 29) {
        runCatching { app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrNull()
    } else {
        null
    }

    override fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))

    /** The direct request needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (declared in this module's manifest). */
    override fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))

    /** HyperOS / MIUI Autostart, falling back to the app details page (spec 14 §2.6). */
    override fun autostartIntents(): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetailsIntent(),
    )

    override fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)

    /** "Default digital assistant app" lives under voice input settings on most builds. */
    override fun assistantSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    companion object {
        private const val PREFS = "archie_settings_feature"

        private val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }
        private val WIRED_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }
    }
}

/**
 * Text size and reduce motion (IA §7 Appearance). The old DataStore has no keys for them and
 * `:core:settings` is not this module's, so they live in a small SharedPreferences file; the theme
 * itself stays in [com.assistant.core.settings.SettingsStore] (`theme_mode`).
 */
class PrefsAppearanceStore(context: Context) : AppearanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("archie_appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val appearance: StateFlow<Appearance> = state

    private fun read() = Appearance(
        textSize = TextSize.entries.firstOrNull { it.name == prefs.getString(KEY_TEXT, null) } ?: TextSize.DEFAULT,
        reduceMotion = prefs.getBoolean(KEY_MOTION, false),
    )

    override fun setTextSize(v: TextSize) {
        prefs.edit().putString(KEY_TEXT, v.name).apply()
        state.value = read()
    }

    override fun setReduceMotion(v: Boolean) {
        prefs.edit().putBoolean(KEY_MOTION, v).apply()
        state.value = read()
    }

    private companion object {
        const val KEY_TEXT = "text_size"
        const val KEY_MOTION = "reduce_motion"
    }
}
