package com.assistant.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `assistant_service_prefs`: same keys and absent-key defaults as `AssistantService.kt:99-105, 376-381`. */
class ServicePrefsTest {
    @Test fun defaultsForAbsentKeys() {
        assertEquals(WakeServicePrefs(false, "my friend", "wake up", 1.0f, 2.0f, ""), ServicePrefs(MemoryKeyValue()).loadWake())
        assertEquals(false, ServicePrefs(MemoryKeyValue()).buttonTriggerEnabled)
    }

    @Test fun writesTheOldKeys() {
        val kv = MemoryKeyValue()
        val p = ServicePrefs(kv)
        p.saveWake(WakeServicePrefs(true, "t", "w", 0.5f, 3f, "ws://h:80"))
        p.buttonTriggerEnabled = true
        assertEquals(
            mapOf(
                "wake_word_enabled" to true, "turn_talk_word" to "t", "realtime_wake_word" to "w",
                "wake_word_mic_gain" to 0.5f, "talk_silence_sensitivity" to 3f, "server_url" to "ws://h:80",
                "button_trigger_enabled" to true,
            ),
            kv.values,
        )
        assertEquals("assistant_service_prefs", ServicePrefs.FILE_NAME)
        assertTrue(p.loadWake().enabled)
    }
}
