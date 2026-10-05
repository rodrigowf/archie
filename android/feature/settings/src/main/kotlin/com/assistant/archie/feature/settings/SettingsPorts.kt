package com.assistant.archie.feature.settings

import android.content.Intent
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.model.AudioOutput
import com.assistant.core.network.ApiResult
import com.assistant.core.network.CertificateInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/*
 * Seams between the settings feature and the rest of the app. The shell (app-main) adapts the
 * process-scoped repositories and Android services to these; tests use fakes. Nothing here owns
 * domain state.
 */

/** The device's link to a backend (adapts `ConnectionRepository` + `OrchestratorChannel`). */
interface ConnectionControl {
    val status: StateFlow<ConnectionStatus>

    /** T-15: store [url], tear down, connect to the new server (fixes inv03 §8 bug 3). */
    suspend fun changeServer(url: String)
    fun connect()
    fun disconnect()
    fun scan()

    /**
     * A REST probe of [url] (`GET /api/auth/status`) before switching, so an untrusted TLS
     * certificate becomes a [ApiResult.Untrusted] the trust dialog can show (TOFU, spec 14 §4.3).
     */
    suspend fun probe(url: String): ApiResult<*>

    /** Pins the certificate for [hostPort] (the user tapped "Trust"). */
    suspend fun trust(hostPort: String, certificate: CertificateInfo)
}

/** What the session sheet needs to know about one open session (adapts `OpenSessionsRepository`). */
data class SessionInfo(
    val localId: String,
    val sdkId: String?,
    val title: String,
    /** A reply is running: restart is refused (web parity). */
    val busy: Boolean,
    /** A view of a session that is not open on this device. */
    val readOnly: Boolean = false,
)

interface SessionControl {
    fun session(localId: String): Flow<SessionInfo?>

    /** Close → reopen with the same pool key + `resume_sdk_id` (the backend applies config only to a new pool entry). */
    suspend fun restart(localId: String): Boolean

    object None : SessionControl {
        override fun session(localId: String): Flow<SessionInfo?> = flowOf(null)
        override suspend fun restart(localId: String) = false
    }
}

/** Wake-word health line for the Wake word page; B-09 adapts `VoiceHost.state` (A-08). */
interface VoiceStatusSource {
    /** e.g. "Listening for “wake up”", "Paused while a voice conversation runs"; null = unknown. */
    val wakeStatus: StateFlow<String?>

    object None : VoiceStatusSource {
        override val wakeStatus: StateFlow<String?> = MutableStateFlow(null)
    }
}

/** Runtime permissions PermissionCenter tracks (spec 14 §2.9). */
enum class AppPermission(val manifest: String, val minSdk: Int, val title: String) {
    MICROPHONE("android.permission.RECORD_AUDIO", 23, "Microphone"),
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", 33, "Notifications"),
    NEARBY_DEVICES("android.permission.BLUETOOTH_CONNECT", 31, "Nearby devices"),
}

enum class PermissionStatus {
    GRANTED,

    /** Never asked, or denied once: asking again shows the system dialog. */
    DENIED,

    /** Denied with "Don't ask again" (or twice): only app settings can grant it. */
    BLOCKED,

    /** Not a runtime permission on this API level. */
    NOT_REQUIRED,
}

data class AppVersion(val name: String, val code: Long, val debuggable: Boolean)

/** Everything Android the settings pages read or launch; [AndroidDevicePlatform] in the app. */
interface DevicePlatform {
    val sdkInt: Int

    /** "POCO X7" style, for the scope chip ("This device · POCO X7"). */
    val deviceName: String
    val appVersion: AppVersion
    val packageName: String

    fun isGranted(permission: AppPermission): Boolean

    /** Remembered "denied with don't ask again" (the system can't be asked without an Activity). */
    fun isBlocked(permission: AppPermission): Boolean
    fun setBlocked(permission: AppPermission, blocked: Boolean)

    /** Output routes that can be picked now (live; fixes inv03 §8 bug 12). AUTO, LOUDSPEAKER, EARPIECE always. */
    fun availableOutputs(): Flow<Set<AudioOutput>>

    /** Archie's voice volume (the call stream it plays on), 0–1; null = unknown. */
    fun speakerLevel(): Float?
    fun setSpeakerLevel(level: Float)

    fun isIgnoringBatteryOptimizations(): Boolean
    fun notificationsEnabled(): Boolean

    /** Xiaomi / Redmi / POCO (HyperOS, MIUI): show the Autostart step. */
    val isXiaomiFamily: Boolean

    /** Is Archie the default digital assistant? null when it can't be read. */
    fun isDefaultAssistant(): Boolean?

    fun appDetailsIntent(): Intent
    fun batteryOptimizationIntent(): Intent
    fun autostartIntents(): List<Intent>
    fun notificationSettingsIntent(): Intent
    fun assistantSettingsIntent(): Intent
}

/** Device-local appearance prefs the old DataStore has no keys for (text size, reduce motion). */
enum class TextSize(val scale: Float, val label: String, val summary: String) {
    DEFAULT(1f, "Default", "default text size"),
    LARGE(1.15f, "Large", "large text"),
    LARGER(1.3f, "Larger", "larger text"),
}

data class Appearance(val textSize: TextSize = TextSize.DEFAULT, val reduceMotion: Boolean = false)

interface AppearanceStore {
    val appearance: StateFlow<Appearance>
    fun setTextSize(v: TextSize)
    fun setReduceMotion(v: Boolean)
}

/** In-memory [AppearanceStore] (tests, previews). */
class MemoryAppearanceStore(initial: Appearance = Appearance()) : AppearanceStore {
    private val state = MutableStateFlow(initial)
    override val appearance: StateFlow<Appearance> = state
    override fun setTextSize(v: TextSize) { state.value = state.value.copy(textSize = v) }
    override fun setReduceMotion(v: Boolean) { state.value = state.value.copy(reduceMotion = v) }
}
