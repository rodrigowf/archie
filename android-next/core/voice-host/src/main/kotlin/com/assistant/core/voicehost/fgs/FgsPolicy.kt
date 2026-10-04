package com.assistant.core.voicehost.fgs

/** `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` values (literal, so the policy is API-independent and JVM-testable). */
object FgsTypes {
    const val NONE = 0

    /** `FOREGROUND_SERVICE_TYPE_MICROPHONE` (API 30). */
    const val MICROPHONE = 0x80

    /** `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API 34). */
    const val SPECIAL_USE = 0x40000000
}

/** Who asked the service to (re)start. */
enum class StartOrigin {
    /**
     * A foreground context: Activity ON_START, a trampoline Activity (tile, notification "Talk" /
     * "Resume listening", shortcut), the VIS session `onShow`. Allowed to start a microphone FGS.
     */
    FOREGROUND,

    /** Anything else: a notification action handled by the running service, a process-start from the background. */
    BACKGROUND,

    /** `onStartCommand(null)`: START_STICKY restart after the process was killed. */
    STICKY_RESTART,
}

/**
 * The decision for one `onStartCommand`.
 *
 * @property callStartForeground whether to call `ServiceCompat.startForeground` now.
 * @property typeMask the FGS types to pass (only meaningful when [callStartForeground]).
 * @property micAllowed whether the host may open the mic in the background after this start; when
 *   false the wake word stays paused (`WakeHealth.PAUSED_NEEDS_FOREGROUND`) and the notification
 *   offers "Resume listening".
 */
data class FgsDecision(val callStartForeground: Boolean, val typeMask: Int, val micAllowed: Boolean) {
    val degraded: Boolean get() = !micAllowed
}

/**
 * FGS type policy (spec 14 §2.6; inv04 R5; risk X9). Pure: the API level and the permission state
 * are inputs, so every branch is tested on the JVM at 21…36.
 *
 *  - API < 30: no while-in-use restriction on the mic; `startForeground(id, n)` (types are ignored
 *    below 29 and the microphone type does not exist on 29). The mic is allowed iff RECORD_AUDIO is granted.
 *  - API 30–33: MICROPHONE when started from the foreground with RECORD_AUDIO; otherwise no type
 *    (a background-started FGS records silence there, so the host degrades instead).
 *  - API 34+: MICROPHONE | SPECIAL_USE when eligible; SPECIAL_USE only otherwise (a microphone type
 *    from the background throws `SecurityException`).
 *  - The first start of a service instance must always call `startForeground` (the
 *    `startForegroundService` contract). Afterwards it is called again **only** from a foreground
 *    origin that adds a type (the "Resume listening" promotion) — never from a background intent (R5).
 */
object FgsPolicy {
    fun decide(
        sdkInt: Int,
        origin: StartOrigin,
        recordAudioGranted: Boolean,
        alreadyForeground: Boolean,
        currentMask: Int,
    ): FgsDecision {
        val eligibleMic = recordAudioGranted && (sdkInt < 30 || origin == StartOrigin.FOREGROUND)
        val wanted = maskFor(sdkInt, eligibleMic)
        val call = when {
            !alreadyForeground -> true
            origin != StartOrigin.FOREGROUND -> false
            else -> wanted and currentMask.inv() != 0
        }
        val effective = if (call) wanted else currentMask
        val mic = if (sdkInt < 30) recordAudioGranted else effective and FgsTypes.MICROPHONE != 0
        return FgsDecision(call, effective, mic)
    }

    /** The type mask for [sdkInt] when the mic is (not) eligible. */
    fun maskFor(sdkInt: Int, micEligible: Boolean): Int = when {
        sdkInt < 30 -> FgsTypes.NONE
        sdkInt < 34 -> if (micEligible) FgsTypes.MICROPHONE else FgsTypes.NONE
        else -> if (micEligible) FgsTypes.MICROPHONE or FgsTypes.SPECIAL_USE else FgsTypes.SPECIAL_USE
    }
}
