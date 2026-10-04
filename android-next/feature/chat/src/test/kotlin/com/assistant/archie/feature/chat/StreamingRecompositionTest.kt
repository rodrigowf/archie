package com.assistant.archie.feature.chat

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.support.FakeChatBackend
import com.assistant.archie.feature.chat.support.FakeChatVoice
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.ui.ConversationScreen
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.ui.LocalMarkdownRenderProbe
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

/**
 * Spec 14 §3.6 budget item 3: while a turn streams, **zero** recompositions of non-tail `MdBlock`
 * items. End to end: frames → reducer → ViewModel (flattener + MarkdownStore, 33 ms sampling) →
 * LazyColumn. A committed node is the same instance on every delta, so the probe in
 * [com.assistant.core.markdown.ui.MarkdownBlock] must run for it exactly once; only tail nodes (new
 * instances per delta) render again. Tool cards and text interleave, as in a real turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h6000dp", application = Application::class)
class StreamingRecompositionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun committedMarkdownNeverRecomposesWhileStreaming() {
        val backend = FakeChatBackend(Frames.agent())
        val vm = ConversationViewModel(backend, FakeChatVoice(), DefaultToolCardRenderer::describe, flattenDispatcher = Dispatchers.Unconfined)
        val renders = IdentityHashMap<MdNode, Int>()
        compose.setContent {
            CompositionLocalProvider(LocalMarkdownRenderProbe provides { n -> renders[n] = (renders[n] ?: 0) + 1 }) {
                ArchieTheme(reduceMotion = true) { ConversationScreen(vm) }
            }
        }
        backend.apply(com.assistant.core.conversation.ConversationInput.LocalSend("Plan the refactor"))
        backend.frames("""{"type":"status","status":"processing"}""")

        val answer = buildString {
            for (p in 1..6) {
                append("## Step $p\n\n")
                append("Paragraph $p explains **why** the change matters, with `code` and a [link](https://example.com/$p).\n\n")
                append("- first point of $p\n- second point of $p\n\n")
            }
        }
        var deltas = 0
        var tools = 0
        for (chunk in answer.chunked(9)) {
            backend.frames("""{"type":"text_delta","text":${kotlinx.serialization.json.JsonPrimitive(chunk)}}""")
            deltas++
            if (deltas % 25 == 0) {
                // Interleave a tool call: the open text block closes, a later delta opens a new block (I-4).
                tools++
                backend.frames("""{"type":"tool_use","tool_use_id":"t$tools","tool_name":"Read","tool_input":{"file_path":"/src/a$tools.kt"}}""")
                backend.frames("""{"type":"tool_result","tool_use_id":"t$tools","output":"ok $tools","is_error":false}""")
            }
            compose.mainClock.advanceTimeBy(ConversationViewModel.SAMPLE_MS + 1)
            compose.waitForIdle()
        }
        backend.frames("""{"type":"turn_complete"}""")
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()

        val committed = vm.state.value.items.filterIsInstance<ChatItem.MdBlock>()
        assertTrue("streamed enough nodes", committed.size > 20)
        assertTrue("interleaved tools", tools >= 3)
        // Not vacuous: every final node rendered, plus the transient tail instances along the way.
        assertTrue("probe saw the final nodes", committed.all { renders.containsKey(it.node) })
        assertTrue("probe saw streaming tails", renders.size > committed.size)
        val again = renders.filterValues { it > 1 }
        assertEquals("node instances rendered more than once: ${again.keys.take(3)}", 0, again.size)
    }
}
