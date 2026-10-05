package com.assistant.core.wakeword.parity

import com.assistant.core.testing.FakeKeyProvider
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.RecordingLog
import com.assistant.core.wakeword.ports.WhisperOutcome
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * whisper-1 HTTP client against a local server (inv04 §2.1 external call, §4.4). Request shape,
 * key caching and the 401 rule (fail-closed; a 401 clears the cached key).
 */
class WhisperClientParityTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val keys = FakeKeyProvider("sk-test")
    private val client = wakeCore.whisperClient(http, server.url("/v1/audio/transcriptions").toString(), keys, RecordingLog())
    private val wav = ByteArray(44 + 3200) { 1 }

    @After fun tearDown() = server.shutdown()

    /** Multipart fields of a recorded request: name → (filename, content). */
    private fun parts(r: RecordedRequest): Map<String, Pair<String?, String>> {
        val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=").trim()
        val body = r.body.readByteArray().toString(Charsets.ISO_8859_1)
        return body.split("--$boundary").drop(1).filter { !it.startsWith("--") }.associate { part ->
            val (headers, content) = part.trimStart('\r', '\n').split("\r\n\r\n", limit = 2)
            val disposition = headers.lines().first { it.startsWith("Content-Disposition", ignoreCase = true) }
            val name = Regex("name=\"([^\"]+)\"").find(disposition)!!.groupValues[1]
            val filename = Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
            name to (filename to content.removeSuffix("\r\n"))
        }
    }

    /** RS-37 (`70283e3`): temperature 0 + English + json; the WAV goes up as `wake.wav`. */
    @Test
    @PinsConstant("whisper.fail_closed")
    fun rs37_requestIsTheTunedMultipartWithTemperatureZero() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"  Wake up. "}"""))
        val outcome = client.transcribe(wav)
        assertEquals(WhisperOutcome.Transcript("Wake up."), outcome)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/audio/transcriptions", r.path)
        assertEquals("Bearer sk-test", r.getHeader("Authorization"))
        val p = parts(r)
        assertEquals("wake.wav", p["file"]!!.first)
        assertEquals(wav.size, p["file"]!!.second.length)
        assertEquals("whisper-1", p["model"]!!.second)
        assertEquals("json", p["response_format"]!!.second)
        assertEquals("en", p["language"]!!.second)
        assertEquals("0", p["temperature"]!!.second)
    }

    @Test
    fun theKeyIsFetchedOnceAndCached() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"text":"x"}""")) }
        client.transcribe(wav)
        client.transcribe(wav)
        assertEquals(1, keys.fetches.get())
    }

    @Test
    fun a401ClearsTheCachedKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"text":"x"}"""))
        assertEquals(WhisperOutcome.Unauthorized, client.transcribe(wav))
        client.transcribe(wav)
        assertEquals("the key is re-fetched after a 401", 2, keys.fetches.get())
    }

    @Test
    fun httpErrorsAreFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(WhisperOutcome.HttpError(500), client.transcribe(wav))
    }

    @Test
    fun noKeyMeansNoRequest() = runBlocking {
        keys.key = null
        assertEquals(WhisperOutcome.NoKey, client.transcribe(wav))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMalformedBodyIsAFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>"))
        assertTrue(client.transcribe(wav) is WhisperOutcome.Failure)
    }
}
