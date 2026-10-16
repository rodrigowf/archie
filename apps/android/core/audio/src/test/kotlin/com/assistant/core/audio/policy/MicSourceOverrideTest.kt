package com.assistant.core.audio.policy

import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.audio.ports.MicSourceType
import java.util.ServiceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 15 TV-9: the mic-source override is additive. Phones (no argument / ServiceLoader) keep the
 * API-level policy bit for bit; only an explicit [FixedMicSourcePolicy] changes the source.
 */
class MicSourceOverrideTest {
    private val levels = listOf(21, 22, 23, 24, 26, 30, 31, 34, 36)

    @Test
    fun defaultCoreKeepsTheApiLevelPolicy() {
        val core = DefaultAudioCore()
        assertSame(DefaultMicSourcePolicy, core.micSourcePolicy)
        for (sdk in levels) {
            val expected = if (sdk < 24) MicSourceType.VOICE_RECOGNITION else MicSourceType.VOICE_COMMUNICATION
            assertEquals("sdk $sdk", expected, core.micSourcePolicy.sourceFor(sdk))
        }
    }

    @Test
    fun serviceLoaderStillBuildsTheDefaultCore() {
        val loaded = ServiceLoader.load(AudioCore::class.java).single()
        assertEquals(MicSourceType.VOICE_COMMUNICATION, loaded.micSourcePolicy.sourceFor(30))
    }

    @Test
    fun fixedPolicyForcesOneSourceOnEveryLevel() {
        val core = DefaultAudioCore(FixedMicSourcePolicy(MicSourceType.VOICE_RECOGNITION))
        for (sdk in levels) assertEquals("sdk $sdk", MicSourceType.VOICE_RECOGNITION, core.micSourcePolicy.sourceFor(sdk))
        // Everything else about the core is unchanged.
        assertSame(DefaultBufferSizing, core.bufferSizing)
    }
}
