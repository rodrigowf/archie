package com.assistant.archie.tv.voice

import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Home
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The burst → destination flows of spec 15 §4.1 / §4.4 with fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvVoiceControllerTest {
    private class Rig(ts: TestScope) {
        var inputs = RouteInputs(liveOwned = false, screen = Home)
        val sent = mutableListOf<ByteArray>()
        var sendResult: VoiceSendResult = VoiceSendResult.Sent
        var transcript: TranscribeResult = TranscribeResult.Text("run the tests again")
        val gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val toasts = mutableListOf<String>()
        val cues = mutableListOf<Boolean>()
        val live = mutableListOf<String>()
        val dictated = mutableListOf<Pair<VoiceDestination.Dictation, String>>()
        val controller = TvVoiceController(
            scope = ts.backgroundScope.let { kotlinx.coroutines.CoroutineScope(it.coroutineContext + UnconfinedTestDispatcher(ts.testScheduler)) },
            inputs = { inputs },
            messages = { wav -> gate.await(); sent += wav; sendResult },
            transcriber = { transcript },
            live = object : LiveMicSink {
                override fun onLiveBurstStart() { live += "start" }
                override fun onLiveFrame(pcm: ShortArray) { live += "frame" }
                override fun onLiveBurstEnd() { live += "end" }
            },
            toast = { t, _ -> toasts += t },
            cue = { cues += it },
            sendDictation = { d, t -> dictated += d to t; true },
        )

        fun burst(ms: Long, tooShort: Boolean = ms < 250, capped: Boolean = false) {
            controller.onBurst(MicBurst.Start(5_000))
            var t = 0L
            while (t < ms) {
                controller.onBurst(MicBurst.Frame(ShortArray(320) { 1_000 }, 0.5f))
                t += 20
            }
            controller.onBurst(MicBurst.End(ms, tooShort, capped))
        }
    }

    @Test
    fun voiceMessageOverlayCuesAndWav() = runTest {
        val r = Rig(this)
        r.controller.onBurst(MicBurst.Start(5_000))
        val hold = r.controller.overlay.value as TvVoiceOverlay.Holding
        assertEquals(VoiceDestination.VoiceMessage, hold.destination)
        assertEquals("To Archie", hold.label)
        assertEquals(5_000, hold.startedAtMs)
        repeat(50) { r.controller.onBurst(MicBurst.Frame(ShortArray(320) { 1_000 }, 0.5f)) }
        assertEquals(0.5f, r.controller.level.value, 0f)
        r.controller.onBurst(MicBurst.End(1_000, tooShort = false))
        assertEquals(listOf(true, false), r.cues)
        val wav = r.sent.single()
        assertEquals("44-byte header + 1 s of 16 kHz PCM16", 44 + 32_000, wav.size)
        val h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(16_000, h.getInt(24))
        assertEquals(16, h.getShort(34).toInt())
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)
        assertTrue(r.toasts.isEmpty())
    }

    @Test
    fun sendingIsShownUntilTheMessageIsOut() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        val c = TvVoiceController(
            scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            inputs = { r.inputs }, messages = { gate.await(); VoiceSendResult.Sent }, transcriber = { r.transcript },
            live = object : LiveMicSink { override fun onLiveBurstStart() {}; override fun onLiveFrame(pcm: ShortArray) {}; override fun onLiveBurstEnd() {} },
            toast = { _, _ -> },
        )
        c.onBurst(MicBurst.Start(0)); c.onBurst(MicBurst.Frame(ShortArray(8_000), 0f)); c.onBurst(MicBurst.End(500, false))
        assertEquals(TvVoiceOverlay.Working("Sending…"), c.overlay.value)
        gate.complete(Unit)
        assertEquals(TvVoiceOverlay.Hidden, c.overlay.value)
    }

    @Test
    fun failedMessageAndTooShortBurstToast() = runTest {
        val r = Rig(this)
        r.burst(100)
        assertTrue(r.sent.isEmpty())
        assertEquals("Too short — hold the mic button while you speak", r.toasts.single())
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)

        r.sendResult = VoiceSendResult.Failed("Not connected — voice message not sent")
        r.burst(800)
        assertEquals("Not connected — voice message not sent", r.toasts.last())
    }

    @Test
    fun cappedBurstIsSentWithANote() = runTest {
        val r = Rig(this)
        r.burst(60_000, capped = true)
        assertEquals(1, r.sent.size)
        assertTrue(r.toasts.single().startsWith("Stopped at 60 s"))
    }

    @Test
    fun agentScreenDictatesThenSendsTheText() = runTest {
        val r = Rig(this)
        r.inputs = RouteInputs(liveOwned = false, screen = Conversation(localId = "a1"), agentTitle = "Claude")
        r.burst(1_200)
        val chip = r.controller.overlay.value as TvVoiceOverlay.Dictation
        assertEquals("run the tests again", chip.text)
        assertEquals("a1", chip.target.localId)
        assertTrue("no voice message to Archie", r.sent.isEmpty())
        r.controller.sendDictation("run the tests again and show me the failures")
        assertEquals("run the tests again and show me the failures", r.dictated.single().second)
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)
    }

    @Test
    fun dictationFailureAndCancel() = runTest {
        val r = Rig(this)
        r.inputs = RouteInputs(liveOwned = false, screen = Conversation(localId = "a1"))
        r.transcript = TranscribeResult.Failed("This server can't transcribe yet — update the backend")
        r.burst(900)
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)
        assertEquals("This server can't transcribe yet — update the backend", r.toasts.last())

        r.transcript = TranscribeResult.Text("hello")
        r.burst(900)
        r.controller.cancelDictation()
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)
        assertTrue(r.dictated.isEmpty())
    }

    @Test
    fun liveBurstsGoToTheCallWithoutOverlayOrCue() = runTest {
        val r = Rig(this)
        r.inputs = RouteInputs(liveOwned = true, screen = Home)
        r.controller.onBurst(MicBurst.Start(0))
        assertTrue(r.controller.liveHolding.value)
        r.controller.onBurst(MicBurst.Frame(ShortArray(320), 0.3f))
        r.controller.onBurst(MicBurst.End(300, false))
        assertEquals(listOf("start", "frame", "end"), r.live)
        assertEquals(TvVoiceOverlay.Hidden, r.controller.overlay.value)
        assertTrue(r.cues.isEmpty())
        assertTrue(r.sent.isEmpty())
        assertEquals(false, r.controller.liveHolding.value)
    }

    @Test
    fun micTestKeepsTheLastBurstForPlayBack() = runTest {
        val r = Rig(this)
        r.controller.testing = true
        assertNull(r.controller.test.value.lastWav)
        r.controller.onBurst(MicBurst.Start(0))
        assertTrue(r.controller.test.value.holding)
        r.controller.onBurst(MicBurst.Frame(ShortArray(1_600) { 7 }, 0.2f))
        r.controller.onBurst(MicBurst.Frame(ShortArray(1_600) { 7 }, 0.6f))
        r.controller.onBurst(MicBurst.End(200, tooShort = true))
        val t = r.controller.test.value
        assertEquals(false, t.holding)
        assertEquals(0.6f, t.peak, 0f)
        assertTrue(t.heard)
        val parsed = Wav.parse(t.lastWav!!)!!
        assertEquals(16_000, parsed.second)
        assertArrayEquals(ShortArray(3_200) { 7 }, parsed.first)
        assertTrue("a test burst is never sent", r.sent.isEmpty())
    }
}
