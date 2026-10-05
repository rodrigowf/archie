package com.assistant.core.voice.parity

import com.assistant.core.testing.Parity
import com.assistant.core.testing.TuningPins
import com.assistant.core.voice.ports.VoiceCore

internal const val OWNER = "A-06"

internal const val VOICE_TUNING = "com.assistant.core.voice.VoiceTuning"

internal val voiceCore: VoiceCore by lazy { Parity.load<VoiceCore>(OWNER) }

internal fun voicePins() = TuningPins(VOICE_TUNING, OWNER)
