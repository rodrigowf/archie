package com.assistant.core.voicehost.parity

import com.assistant.core.testing.Parity
import com.assistant.core.testing.TuningPins
import com.assistant.core.voicehost.ports.VoiceHostCore

internal const val OWNER = "A-08"

internal const val HOST_TUNING = "com.assistant.core.voicehost.HostTuning"

internal val hostCore: VoiceHostCore by lazy { Parity.load<VoiceHostCore>(OWNER) }

internal fun hostPins() = TuningPins(HOST_TUNING, OWNER)
