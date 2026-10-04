package com.assistant.core.voicehost

import com.assistant.core.voicehost.ports.TriggerDeps
import com.assistant.core.voicehost.ports.TriggerRouter
import com.assistant.core.voicehost.ports.VoiceHostCore
import com.assistant.core.voicehost.ports.WakeServiceController
import com.assistant.core.voicehost.ports.WakeServiceDeps
import com.assistant.core.voicehost.ports.WakeStartDeduper
import com.assistant.core.voicehost.trigger.DefaultTriggerRouter
import com.assistant.core.voicehost.wake.DefaultWakeServiceController
import com.assistant.core.voicehost.wake.DefaultWakeStartDeduper

/**
 * The `:core:voice-host` parity entry point (registered in `META-INF/services` for the A-04
 * harness). The runtime builds the same classes directly.
 */
class DefaultVoiceHostCore : VoiceHostCore {
    override fun wakeStartDeduper(): WakeStartDeduper = DefaultWakeStartDeduper()

    override fun wakeService(deps: WakeServiceDeps): WakeServiceController = DefaultWakeServiceController(deps)

    override fun triggerRouter(deps: TriggerDeps): TriggerRouter = DefaultTriggerRouter(deps)
}
