package com.assistant.archie.feature.visuals

import com.assistant.core.network.ApiResult
import com.assistant.core.protocol.CastProbeDto
import com.assistant.core.protocol.CastResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether this server can show a visualization on the TV (BX-2, IA §9.4). "Show on TV" is shown
 * only when [Available]: hidden, never greyed out, so nothing hints at a feature the server lacks.
 */
sealed interface CastCapability {
    /** Not probed yet, or the probe failed on the network: try again on the next connect. */
    data object Unknown : CastCapability
    data object Available : CastCapability
    /** [reason] is the server's (e.g. "No Fire TV connected over adb") or why the probe says no. */
    data class Unavailable(val reason: String?) : CastCapability

    companion object {
        /**
         * The probe rule (web parity: frontend services/capabilitiesProbe.ts; shipped BX-2 is
         * `GET /api/visualizations/cast` → `{available, reason}`, plan/20 §A):
         * - `{available: true}` → [Available]; `{available: false, reason}` → [Unavailable];
         * - 404 / 405, or a `text/html` SPA-fallback 200 (a backend without BX-2, G-38: it fails to
         *   decode) → [Unavailable];
         * - a network error or an untrusted certificate → [Unknown] (retried on the next connect).
         */
        fun of(result: ApiResult<CastProbeDto>): CastCapability = when (result) {
            is ApiResult.Ok -> if (result.value.available) Available else Unavailable(result.value.reason ?: "Not available")
            is ApiResult.HttpError -> Unavailable(result.errorMessage())
            is ApiResult.DecodeError -> Unavailable("Not available on this server")
            is ApiResult.NetworkError, is ApiResult.Untrusted -> Unknown
        }
    }
}

/** The result of one "Show on TV" tap, for the snackbar. */
data class CastOutcome(val ok: Boolean, val message: String)

/**
 * Holds [capability] for the current server and performs the cast (spec 14 §4.2). Probed once per
 * server connection ([onConnected]) and whenever a visual screen opens while still [CastCapability.Unknown]
 * ([ensure]). The cast never navigates the app or blocks the WebView.
 */
class CastController(
    private val probe: suspend () -> ApiResult<CastProbeDto>,
    private val castCall: suspend (String) -> ApiResult<CastResponse>,
    private val scope: CoroutineScope,
) {
    private val _capability = MutableStateFlow<CastCapability>(CastCapability.Unknown)
    val capability: StateFlow<CastCapability> = _capability.asStateFlow()
    private var job: Job? = null

    /** A (re)connect to a server: forget the old answer and probe again. */
    fun onConnected() {
        _capability.value = CastCapability.Unknown
        job?.cancel()
        job = scope.launch { _capability.value = CastCapability.of(probe()) }
    }

    /** Probe if nothing is known yet (a visual screen opened); no-op while a probe runs or once resolved. */
    fun ensure() {
        if (_capability.value != CastCapability.Unknown || job?.isActive == true) return
        job = scope.launch { _capability.value = CastCapability.of(probe()) }
    }

    /** Suspends until the probe answers (tests). */
    suspend fun probeNow(): CastCapability = CastCapability.of(probe()).also { _capability.value = it }

    /**
     * `POST /api/visualizations/cast {path}` → `{ok, message}`. Success reads "Showing “title” on TV"
     * (the server's text carries the full URL; the title reads better); `{ok:false}` shows the
     * server's message verbatim; an HTTP error shows its `detail` verbatim. A 405, or a 404 that is
     * not the API's JSON "Visualization not found", means the endpoint is gone: the action hides.
     */
    suspend fun cast(path: String, title: String?): CastOutcome = when (val r = castCall(path)) {
        is ApiResult.Ok ->
            if (r.value.ok) CastOutcome(true, "Showing “${title ?: path}” on TV")
            else CastOutcome(false, r.value.message?.takeIf { it.isNotBlank() } ?: "Could not show it on the TV")
        is ApiResult.HttpError -> {
            if (r.code == 405 || (r.code == 404 && (r.html || r.detail == null))) {
                _capability.value = CastCapability.Unavailable(r.errorMessage())
            }
            CastOutcome(false, r.errorMessage() ?: "Could not show it on the TV")
        }
        else -> CastOutcome(false, r.errorMessage() ?: "Could not show it on the TV")
    }
}
