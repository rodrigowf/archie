package com.assistant.core.wakeword.parity

import com.assistant.core.testing.Parity
import com.assistant.core.testing.TuningPins
import com.assistant.core.wakeword.ports.WakewordCore

internal const val OWNER = "A-07"

internal const val WAKE_TUNING = "com.assistant.core.wakeword.WakeTuning"

internal val wakeCore: WakewordCore by lazy { Parity.load<WakewordCore>(OWNER) }

internal fun wakePins() = TuningPins(WAKE_TUNING, OWNER)

internal val TALK = listOf("my friend")
internal val WAKE = listOf("wake up")
