package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.testing.Parity
import com.assistant.core.testing.TuningPins

/** Owner of the implementation these tests turn green (spec 14 §7). */
internal const val OWNER = "A-05"

internal const val AUDIO_TUNING = "com.assistant.core.audio.AudioTuning"

internal val audioCore: AudioCore by lazy { Parity.load<AudioCore>(OWNER) }

internal fun audioPins() = TuningPins(AUDIO_TUNING, OWNER)

/** API levels every `sdkInt` policy is tested at (spec 14 §6.1). */
internal val SDK_LEVELS = listOf(21, 22, 23, 26, 30, 31, 34, 36)
