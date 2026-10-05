package com.assistant.peripheral.face

import com.assistant.core.network.SocketState
import com.assistant.core.voicehost.LastExchange
import com.assistant.peripheral.LastExchangeSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainTextAndRetryTest {
    @Test fun stripsMarkdownToPlainText() {
        val md = """
            # Tomorrow in **Rio**
            - *Sunny*, 29°
            - see [the forecast](https://example.com) and `wttr.in`
            > ~~rain~~ none
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        assertEquals("Tomorrow in Rio\nSunny, 29°\nsee the forecast and wttr.in\nrain none\nval x = 1", PlainText.strip(md))
        assertEquals("snake_case_name stays", PlainText.strip("snake_case_name stays"))
        assertEquals("2 * 3 = 6", PlainText.strip("2 * 3 = 6"))
    }

    @Test fun capsEachSide() {
        val s = PlainText.strip("a".repeat(700), LastExchange.MAX_CHARS)
        assertEquals(600, s.length)
        assertEquals('…', s.last())
    }

    @Test fun lastExchangeSink_keepsOnlyTheLastExchange_plain() {
        val sink = LastExchangeSink(clock = { 0L })
        sink.userTranscript("partial", final = false)
        assertEquals(LastExchange(), sink.exchange.value)
        sink.userTranscript("What's **up**?", final = true)
        sink.assistantTranscript("Not much — `ok`.", final = true)
        assertEquals(LastExchange("What's up?", "Not much — ok."), sink.exchange.value)
        sink.userTranscript("next", final = true)
        assertEquals(LastExchange("next", null), sink.exchange.value)
        sink.voiceMessageSent()
        assertEquals(LastExchangeSink.VOICE_MESSAGE, sink.exchange.value.user)
    }

    @Test fun voiceErrorSystemLine_isHeld() {
        val sink = LastExchangeSink(clock = { 42L })
        sink.system("Voice error: Failed to start voice session (no connection info)")
        assertEquals(LastExchangeSink.VoiceError("Failed to start voice session (no connection info)", 42L), sink.error.value)
        sink.system("goAway in 30 s")
        assertEquals(42L, sink.error.value!!.atMs)
        sink.clearError()
        assertNull(sink.error.value)
    }

    @Test fun retryEstimate_mirrorsTheSocketBackoff() {
        val r = RetryEstimator(baseMs = 1_000, maxMs = 15_000)
        r.onSocket(SocketState.Connecting(0), 0)
        r.onSocket(SocketState.Disconnected(true, "x"), 100)
        assertEquals(1_000L, r.retryInMs(100))
        r.onSocket(SocketState.Connecting(3), 1_100)
        assertNull(r.retryInMs(1_100))
        r.onSocket(SocketState.Disconnected(true, "x"), 2_000)
        assertEquals(8_000L, r.retryInMs(2_000))
        assertEquals(0L, r.retryInMs(20_000))
        r.onSocket(SocketState.Connecting(10), 0)
        r.onSocket(SocketState.Disconnected(true, "x"), 0)
        assertEquals(15_000L, r.retryInMs(0))
        r.onSocket(SocketState.Disconnected(false, "client"), 0)
        assertNull(r.retryInMs(0))
    }
}
