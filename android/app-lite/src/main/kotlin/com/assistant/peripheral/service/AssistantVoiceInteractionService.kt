package com.assistant.peripheral.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.service.VoiceHostOwner

/**
 * Kept for parity at the old FQCN (decision E-7; spec 14 §5.1). B6 fixed: the system shows a
 * session from [AssistantVoiceSessionService] (the old XML pointed `sessionService` at this class
 * and overrode a non-callback `showSession`). Field test L-F5 decides whether it stays.
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService ready")
    }

    private companion object {
        const val TAG = "AssistantVIS"
    }
}

/** Hosts [AssistSession]. */
class AssistantVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * Assist gesture → the single trigger ingress, then the session closes at once (no overlay UI on
 * the lite app: the runtime brings the face to the front itself, `HostConfig.lite`).
 */
class AssistSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    private var fired = false

    /** API 23+ callback. */
    override fun onCreate() {
        super.onCreate()
        fire()
    }

    /**
     * API 21/22 callback (`onCreate(Bundle)`, removed from the current SDK stubs): declared without
     * `override` so it still overrides the Lollipop framework method at the JVM level.
     */
    @Suppress("unused")
    fun onCreate(args: Bundle?) {
        fire()
    }

    private fun fire() {
        if (fired) return
        fired = true
        Log.d(TAG, "Assist session → realtime voice")
        (service.application as? VoiceHostOwner)?.voiceHostRuntime?.startVoice(Trigger.ASSIST)
        finish()
    }

    private companion object {
        const val TAG = "AssistantVIS"
    }
}
