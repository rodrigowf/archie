package com.assistant.core.voice.ports

import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live audio levels of a voice session, for the dock's level orb only: pure observation, nothing
 * here feeds back into gain, ducking, VAD or the session. Both are RMS normalised to full scale
 * (PCM16 32768 = 1): [mic] what the microphone picks up (before any gain; 0 while muted), [speaker]
 * what Archie is playing out now. Transports publish them every [PERIOD_MS] and only while someone
 * collects, so an unobserved session pays nothing beyond a counter read per buffer.
 */
data class VoiceLevels(val mic: Float, val speaker: Float) {
    companion object {
        /** ~15 Hz, like the web clients' meters (inv02 F-26). */
        const val PERIOD_MS = 66L

        val Silent = VoiceLevels(0f, 0f)

        /** "No live session": the default of the ports that have no levels. */
        val None: StateFlow<VoiceLevels?> = MutableStateFlow<VoiceLevels?>(null).asStateFlow()

        /** RMS (0..1) → a 0..1 visual level; speech sits around 0.02–0.3 (web `visualLevel`). */
        fun visual(rms: Float): Float = if (rms > 0f) min(1f, sqrt(rms * 4f)) else 0f
    }
}
