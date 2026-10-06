package com.assistant.archie.feature.settings

import com.assistant.core.model.AuthStatus
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class AuthPhase { IDLE, CHECKING, SIGNING_IN, SAVING }

data class AuthState(
    val status: AuthStatus? = null,
    val phase: AuthPhase = AuthPhase.IDLE,
    /** Verbatim message of the last failed status check (status stays as it was). */
    val checkError: String? = null,
    /** Message of the last failed sign-in / credentials attempt. */
    val actionError: String? = null,
    /** "Not now" on the gate, for this app process. */
    val gateDismissed: Boolean = false,
) {
    /** The AuthGate covers the app only for a known "not signed in" (a failed check never blocks). */
    val gateBlocked: Boolean get() = status != null && !status.authenticated && !gateDismissed

    /** One line for Settings → Account ("Claude · signed in"). */
    val summary: String
        get() = when {
            phase == AuthPhase.CHECKING && status == null -> "Claude · checking…"
            status == null -> if (checkError != null) "Claude · couldn't check" else "Claude"
            status.authenticated -> "Claude · signed in"
            else -> "Claude · not signed in"
        }
}

sealed interface CredentialsCheck {
    data class Ok(val json: String) : CredentialsCheck
    data class Bad(val error: String) : CredentialsCheck
}

/**
 * Claude CLI sign-in state of the backend (inv01 §3.1, inv02 §1.11, spec 12 §8.1), a port of the
 * web `authStore`. Used by the AuthGate and Settings → Account. A failed status check is "unknown",
 * not "signed out"; server errors are shown verbatim; credentials can be replaced while signed in
 * (the headless check does not look at expiry, so an expired token still reads "signed in").
 */
class AuthModel(
    private val api: ArchieApi,
    private val messages: SettingsMessages,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var inFlight: Job? = null

    /** `GET /api/auth/status`. On failure the previous status is kept. */
    fun check() {
        if (inFlight?.isActive == true) return
        _state.update { it.copy(phase = AuthPhase.CHECKING, checkError = null) }
        inFlight = scope.launch { checkNow() }
    }

    suspend fun checkNow() {
        when (val r = api.authStatus()) {
            is ApiResult.Ok -> _state.update { it.copy(status = r.value, phase = AuthPhase.IDLE) }
            else -> _state.update { it.copy(phase = AuthPhase.IDLE, checkError = r.errorMessage()) }
        }
    }

    /**
     * `POST /api/auth/login` (non-headless only): runs `claude setup-token` on the server and blocks
     * until it exits, so a sign-in window opens **on the server machine**.
     */
    suspend fun signIn(): Boolean {
        _state.update { it.copy(phase = AuthPhase.SIGNING_IN, actionError = null) }
        return when (val r = api.authLogin()) {
            is ApiResult.Ok -> {
                val ok = r.value.authenticated
                _state.update {
                    it.copy(
                        status = r.value,
                        phase = AuthPhase.IDLE,
                        actionError = if (ok) null else "Sign-in didn't finish on the server. Try again, or paste credentials.",
                    )
                }
                if (ok) messages.post(SettingsMessage(SIGNED_IN))
                ok
            }
            else -> { _state.update { it.copy(phase = AuthPhase.IDLE, actionError = r.errorMessage()) }; false }
        }
    }

    /** `POST /api/auth/credentials`. */
    suspend fun submitCredentials(text: String): Boolean {
        when (val c = checkCredentialsText(text)) {
            is CredentialsCheck.Bad -> { _state.update { it.copy(actionError = c.error) }; return false }
            is CredentialsCheck.Ok -> {
                _state.update { it.copy(phase = AuthPhase.SAVING, actionError = null) }
                return when (val r = api.authCredentials(c.json)) {
                    is ApiResult.Ok -> {
                        val ok = r.value.authenticated
                        _state.update {
                            it.copy(
                                status = r.value,
                                phase = AuthPhase.IDLE,
                                actionError = if (ok) null else "Invalid credentials: the server didn't accept them.",
                            )
                        }
                        if (ok) messages.post(SettingsMessage(SIGNED_IN))
                        ok
                    }
                    else -> {
                        _state.update { it.copy(phase = AuthPhase.IDLE, actionError = "Failed to set credentials: ${r.errorMessage()}") }
                        false
                    }
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(actionError = null) }
    fun dismissGate() = _state.update { it.copy(gateDismissed = true) }

    /** A new server: forget the old server's status. */
    fun reset() { _state.update { AuthState(gateDismissed = it.gateDismissed) } }

    companion object {
        const val SIGNED_IN = "Signed in to Claude"
        private val json = Json { ignoreUnknownKeys = true }

        /** Client-side check before sending (mirrors `manager/auth.py`: `claudeAiOauth.accessToken`). */
        fun checkCredentialsText(text: String): CredentialsCheck {
            val t = text.trim()
            if (t.isEmpty()) return CredentialsCheck.Bad("Paste the contents of .credentials.json")
            val parsed = try { json.parseToJsonElement(t) } catch (_: Exception) {
                return CredentialsCheck.Bad("That isn't valid JSON. Copy the whole file, including the braces.")
            }
            val token = ((parsed as? JsonObject)?.get("claudeAiOauth") as? JsonObject)?.get("accessToken") as? JsonPrimitive
            if (token == null || !token.isString || token.content.isEmpty()) {
                return CredentialsCheck.Bad("Invalid credentials: the file has no claudeAiOauth.accessToken.")
            }
            return CredentialsCheck.Ok(t)
        }
    }
}
