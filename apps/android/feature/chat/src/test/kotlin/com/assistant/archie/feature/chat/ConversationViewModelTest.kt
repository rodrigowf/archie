package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.support.FakeChatBackend
import com.assistant.archie.feature.chat.support.FakeChatVoice
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.conversation.HistoryState
import com.assistant.core.design.components.ComposerPrimary
import com.assistant.core.model.UploadResult
import com.assistant.core.network.UploadSource
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private fun TestScope.vm(backend: FakeChatBackend, voice: FakeChatVoice = FakeChatVoice()): ConversationViewModel {
        val d = StandardTestDispatcher(testScheduler)
        return ConversationViewModel(backend, voice, DefaultToolCardRenderer::describe, flattenDispatcher = d, externalScope = backgroundScope, clock = { testScheduler.currentTime })
    }

    @Test
    fun loadOlderAsksOncePerOldestEntry() = runTest {
        val s = Frames.reduce(Frames.agent(), """{"type":"user_message","text":"hi"}""").let { it.copy(history = HistoryState(loaded = true, hasMore = true, startIndex = 50)) }
        val backend = FakeChatBackend(s)
        val vm = vm(backend)
        runCurrent()
        repeat(3) { vm.onAction(ChatAction.LoadOlder) }
        assertEquals(1, backend.olderRequests)   // guard keyed on the oldest id (5c029d6)
        // A page arrived (a new oldest entry): the next request goes through.
        val cur = backend.state.value!!
        backend.set(
            cur.copy(
                entries = cur.entries.add(0, com.assistant.core.conversation.UserEntry("h:sdk-1:0", "older", com.assistant.core.conversation.UserOrigin.HISTORY)),
                history = HistoryState(loaded = true, hasMore = true, startIndex = 0),
            ),
        )
        runCurrent()
        vm.onAction(ChatAction.LoadOlder)
        assertEquals(2, backend.olderRequests)
    }

    @Test
    fun streamingPublishesAtMostEverySampleInterval() = runTest {
        val backend = FakeChatBackend(Frames.reduce(Frames.agent(), """{"type":"status","status":"processing"}"""))
        val vm = vm(backend)
        runCurrent()
        val seen = mutableListOf<Int>()
        backgroundScope.launchCollect(vm) { seen += it }
        runCurrent()
        val before = seen.size
        repeat(30) { backend.frames("""{"type":"text_delta","text":"word "}"""); advanceTimeBy(1) }
        runCurrent()
        val during = seen.size - before
        assertTrue("≤ 2 publications in 30 ms, got $during", during <= 2)
        advanceTimeBy(100); runCurrent()
        val text = vm.state.value.items.filterIsInstance<com.assistant.archie.feature.chat.model.ChatItem.MdBlock>().last().node.toString()
        assertTrue("the latest text arrives after the interval: $text", text.contains("word word word"))
    }

    @Test
    fun voiceReconnectTimeline() = runTest {
        val voice = FakeChatVoice(VoiceSessionState(phase = SessionPhase.ACTIVE, isOwner = true))
        val vm = vm(FakeChatBackend(Frames.archie()), voice)
        runCurrent()
        assertTrue(vm.state.value.voice is VoiceUi.Active)
        voice.state.value = voice.state.value.copy(reconnectBanner = "Reconnecting…")
        runCurrent()
        assertTrue(vm.state.value.voice is VoiceUi.Reconnecting)
        advanceTimeBy(9_000)
        voice.state.value = voice.state.value.copy(reconnectBanner = null)
        runCurrent()
        assertEquals(VoiceUi.Reconnected(9), vm.state.value.voice)
        advanceTimeBy(ConversationViewModel.OUTCOME_MS + 1); runCurrent()
        assertTrue(vm.state.value.voice is VoiceUi.Active)

        voice.state.value = voice.state.value.copy(reconnectBanner = "Reconnecting…")
        runCurrent()
        voice.state.value = VoiceSessionState(phase = SessionPhase.ERROR, errorMessage = "5 tries over 60 s")
        runCurrent()
        assertEquals(VoiceUi.ReconnectFailed("5 tries over 60 s"), vm.state.value.voice)
        assertEquals(ComposerPrimary.Voice, vm.state.value.composer.primary)
    }

    @Test
    fun voiceElsewhereKeepsTextInput() = runTest {
        val voice = FakeChatVoice(VoiceSessionState(phase = SessionPhase.OFF, remoteVoiceActive = true, isOwner = false))
        voice.remoteDevice.value = "Pixel 8"
        val vm = vm(FakeChatBackend(Frames.archie()), voice)
        runCurrent()
        assertEquals(VoiceUi.Elsewhere("Pixel 8", true), vm.state.value.voice)
        assertTrue(vm.state.value.composer.enabled)
        assertEquals(ComposerPrimary.SendDisabled, vm.state.value.composer.primary)
    }

    @Test
    fun uploadInjectsTheShareLine() = runTest {
        val backend = FakeChatBackend(Frames.archie())
        val vm = vm(backend)
        runCurrent()
        vm.onAction(ChatAction.Upload(UploadSource("notes.txt", "text/plain", 2048) { Buffer() }, subject = "for Friday"))
        runCurrent()
        assertEquals(
            listOf("[shared file] notes.txt (2.0 KB, text/plain) — /uploads/notes.txt\nNote: for Friday\nLocal path: /srv/uploads/notes.txt"),
            backend.injected,
        )
        assertEquals(null, vm.state.value.busyOverlay)
    }

    @Test
    fun shareLineSizes() {
        fun line(size: Long) = ConversationViewModel.shareLine(UploadResult("f", "/p", "/u", size, "x/y"), null)
        assertTrue(line(10).contains("(10 B, x/y)"))
        assertTrue(line(3 * 1024 * 1024).contains("(3.0 MB, x/y)"))
    }

    @Test
    fun slashCommandsAreSingleWords() {
        assertTrue(ConversationViewModel.SLASH.matches("/help"))
        assertTrue(ConversationViewModel.SLASH.matches("/model sonnet"))
        assertFalse(ConversationViewModel.SLASH.matches("/home/rodrigo/x"))
        assertFalse(ConversationViewModel.SLASH.matches("hello /help"))
    }

    @Test
    fun stallTitles() {
        assertEquals("Bash silent for 2 min", ConversationUiMapper.stallTitle("Bash", 120.4))
        assertEquals("Bash silent for 45s", ConversationUiMapper.stallTitle("Bash", 45.0))
        assertEquals("No response for 2m10s", ConversationUiMapper.stallTitle(null, 130.0))
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCollect(vm: ConversationViewModel, onItems: (Int) -> Unit) =
    launch { vm.state.collect { onItems(it.items.size) } }
