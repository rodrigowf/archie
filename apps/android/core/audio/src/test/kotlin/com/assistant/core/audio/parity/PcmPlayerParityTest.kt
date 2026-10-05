package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.TrackConstructor
import com.assistant.core.testing.FakeAudioManagerPort
import com.assistant.core.testing.FakeAudioTrackFactory
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.RecordingLog
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speaker playback (inv04 §4.7; fixes B3; RS-25). */
@OptIn(ExperimentalCoroutinesApi::class)
class PcmPlayerParityTest {
    private val chunk = ByteArray(960) { 1 } // 20 ms at 24 kHz

    @Test
    fun startBuildsA72000ByteTrackAndLogsTheSpeakerMarker() = runTest {
        val log = RecordingLog()
        val tracks = FakeAudioTrackFactory(sdkInt = 26, minBuffer = 4000)
        val player = audioCore.pcmPlayer(tracks, 26, 24000, StandardTestDispatcher(testScheduler), log)
        player.start(SpeakerMode.CALL, null)
        runCurrent()
        assertEquals(72000, tracks.last.spec.bufferBytes)
        assertEquals(SpeakerMode.CALL, tracks.last.spec.speakerMode)
        assertEquals(TrackConstructor.BUILDER_WITH_ATTRIBUTES, tracks.last.spec.constructor)
        assertTrue(tracks.last.ops.contains("play"))
        assertTrue(log.dump(), log.contains("Speaker started: rate=24000Hz bufSize=72000 mode=CALL"))
        player.cleanup()
    }

    /** RS-25 (`20217b1`): API 21/22 never call the 4-arg write; M+ never use the blocking write. */
    @Test
    fun rs25_lollipopNeverCallsTheFourArgWrite() = runTest {
        for (sdk in SDK_LEVELS) {
            val tracks = FakeAudioTrackFactory(sdk)
            val player = audioCore.pcmPlayer(tracks, sdk, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
            player.start(SpeakerMode.CALL, null)
            repeat(5) { player.enqueue(chunk) }
            advanceUntilIdle()
            val t = tracks.last
            if (sdk < 23) {
                assertEquals("API $sdk 4-arg attempts", 0, t.nonBlockingWrites.get())
                assertTrue("API $sdk", t.blockingWrites.get() > 0)
                assertEquals(TrackConstructor.LEGACY_STREAM_TYPE, t.spec.constructor)
                assertEquals(AudioStream.VOICE_CALL, t.spec.legacyStream)
            } else {
                assertEquals("API $sdk blocking writes", 0, t.blockingWrites.get())
                assertTrue("API $sdk", t.nonBlockingWrites.get() > 0)
            }
            assertEquals("API $sdk", 5 * 960, t.bytesWritten.get())
            assertEquals("API $sdk frames", 5L * 480, player.totalFramesWritten())
            player.cleanup()
        }
    }

    @Test
    fun preferredDeviceIsOnlyPinnedFromApi23() = runTest {
        for (sdk in listOf(22, 26)) {
            val tracks = FakeAudioTrackFactory(sdk)
            val player = audioCore.pcmPlayer(tracks, sdk, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
            player.start(SpeakerMode.MEDIA, FakeAudioManagerPort.BT_A2DP)
            runCurrent()
            assertEquals("API $sdk", sdk >= 23, tracks.last.ops.any { it.startsWith("preferred=") })
            player.cleanup()
        }
    }

    /** Barge-in flush: drop the queue, pause → flush → play, reset the written-frame counter. */
    @Test
    @PinsConstant("audio.barge_in_flush")
    fun flushDropsQueueRunsPauseFlushPlayAndResetsCounter() = runTest {
        val tracks = FakeAudioTrackFactory(26)
        val player = audioCore.pcmPlayer(tracks, 26, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
        player.start(SpeakerMode.CALL, null)
        repeat(3) { player.enqueue(chunk) }
        advanceUntilIdle()
        assertEquals(3L * 480, player.totalFramesWritten())
        tracks.last.ops.clear()
        player.flush()
        assertEquals(listOf("pause", "flush", "play"), tracks.last.ops.filter { it in setOf("pause", "flush", "play") })
        assertEquals(0L, player.totalFramesWritten())
        player.cleanup()
    }

    @Test
    fun fullBufferParksTheRemainderAndRetriesAfter10ms() = runTest {
        val clock = FakeClock.boundTo(testScheduler)
        val tracks = FakeAudioTrackFactory(26, clock = clock)
        var calls = 0
        tracks.configure = { t -> t.acceptBytes = { req -> if (calls++ == 0) 0 else req } }
        val player = audioCore.pcmPlayer(tracks, 26, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
        player.start(SpeakerMode.CALL, null)
        player.enqueue(chunk)
        advanceTimeBy(100)
        runCurrent()
        val times = tracks.last.writeTimes
        assertTrue("expected a retry, got $times", times.size >= 2)
        assertEquals(10L, times[1] - times[0])
        assertEquals(960, tracks.last.bytesWritten.get())
        player.cleanup()
    }

    @Test
    fun aThrowingWriteNeverEscapes() = runTest {
        val tracks = FakeAudioTrackFactory(26)
        val player = audioCore.pcmPlayer(tracks, 26, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
        player.start(SpeakerMode.CALL, null)
        tracks.last.throwOnWrite = IllegalStateException("dead track")
        player.enqueue(chunk)
        advanceUntilIdle()
        tracks.last.throwOnWrite = null
        player.enqueue(chunk)
        advanceUntilIdle()
        assertEquals("the writer must survive a failed write", 960, tracks.last.bytesWritten.get())
        player.cleanup()
    }

    @Test
    fun setSpeakerModeRebuildsTheTrackOnlyWhenStarted() = runTest {
        val tracks = FakeAudioTrackFactory(26)
        val player = audioCore.pcmPlayer(tracks, 26, 24000, StandardTestDispatcher(testScheduler), RecordingLog())
        player.setSpeakerMode(SpeakerMode.MEDIA, null)
        assertEquals("no track before start", 0, tracks.tracks.size)
        player.start(SpeakerMode.CALL, null)
        player.setSpeakerMode(SpeakerMode.MEDIA, null)
        runCurrent()
        assertEquals(2, tracks.tracks.size)
        assertTrue(tracks.tracks[0].released)
        assertEquals(SpeakerMode.MEDIA, tracks.tracks[1].spec.speakerMode)
        player.cleanup()
        assertTrue(tracks.tracks[1].released)
        assertNull(player.headPositionFrames())
    }

    /**
     * B3: after a CALL↔MEDIA rebuild exactly one writer remains. Runs on real threads (4) so two
     * writers would overlap inside `write`; the old code launched a second writer without
     * cancelling the first.
     */
    @Test
    fun b3_exactlyOneWriterAfterModeRebuilds() {
        val pool = Executors.newFixedThreadPool(4)
        val dispatcher = pool.asCoroutineDispatcher()
        try {
            val tracks = FakeAudioTrackFactory(26)
            tracks.configure = { it.writeDelayMs = 1 }
            val player = audioCore.pcmPlayer(tracks, 26, 24000, dispatcher, RecordingLog())
            player.start(SpeakerMode.CALL, null)
            player.setSpeakerMode(SpeakerMode.MEDIA, null)
            player.setSpeakerMode(SpeakerMode.CALL, null)
            val n = 300
            repeat(n) { player.enqueue(chunk) }
            val deadline = System.currentTimeMillis() + 15_000
            while (tracks.tracks.sumOf { it.bytesWritten.get() } < n * 960 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val live = tracks.last
            assertEquals(n * 960, tracks.tracks.sumOf { it.bytesWritten.get() })
            assertEquals("concurrent writers on the live track", 1, live.maxConcurrentWrites)
            tracks.tracks.dropLast(1).forEach { assertEquals("writes to a released track", 0, it.writesAfterRelease.get()) }
            assertEquals(n * 480L, player.totalFramesWritten())
            player.cleanup()
        } finally {
            dispatcher.close()
            pool.shutdownNow()
        }
    }
}
