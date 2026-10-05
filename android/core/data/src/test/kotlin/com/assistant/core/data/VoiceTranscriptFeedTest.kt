package com.assistant.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.BlockScope
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.UserEntry
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import com.assistant.core.network.ReconnectPolicy
import com.assistant.core.network.SocketClient
import com.assistant.core.session.ArchiePoolApi
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.OrchestratorIdStore
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-09 follow-up (spec 12 §4.7, VT-2): the OpenAI (WebRTC) voice owner's own transcripts reach the
 * Archie timeline through [ConversationRepository.voiceDataChannelEvent] — user transcript first,
 * the assistant's live text streaming (the caret), and the final transcript replacing the partial.
 */
class VoiceTranscriptFeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend().start()

    @After fun tearDown() {
        scope.cancel()
        backend.shutdown()
    }

    private fun repository(): ConversationRepository {
        backend.poolJson = """[{"local_id":"ORCH","sdk_session_id":"JSONL","status":"idle","cost":0.0,"turns":0,"title":"Living room","is_orchestrator":true}]"""
        val settings = SettingsStore(
            MemoryDataStore(
                mutablePreferencesOf().apply {
                    this[stringPreferencesKey("server_url")] = backend.url
                    this[booleanPreferencesKey("auto_connect")] = true
                },
            ),
            scope,
        )
        val url: () -> String = { settings.settings.value?.serverUrl ?: backend.url }
        val http = HttpStack()
        val api = ArchieApi(http, url)
        val orchestrator = OrchestratorChannel(SocketClient(http, scope, ReconnectPolicy { 200 }), ArchiePoolApi(api), Ids(), scope)
        val history = HistoryRepository(api, scope)
        val conversations = ConversationRepository(api, orchestrator, AgentSocketPool({ SocketClient(http, scope, ReconnectPolicy { 200 }) }), history, scope, { settings.settings.value?.serverUrl })
        val open = OpenSessionsRepository(conversations, history, orchestrator, scope)
        ConnectionRepository(settings, orchestrator, conversations, open, history, ServerScanner { emptyList() }, scope).start()
        eventually { conversations.current(ConversationKey.ARCHIE)?.connection == com.assistant.core.model.ConnectionState.SUBSCRIBED }
        return conversations
    }

    private class Ids : OrchestratorIdStore {
        var id: String? = null
        override suspend fun load() = id
        override suspend fun save(localId: String) { id = localId }
        override suspend fun clear() { id = null }
    }

    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun entries(c: ConversationRepository) = c.current(ConversationKey.ARCHIE)!!.entries

    @Test fun ownerTranscripts_streamInOrder_finalReplacesPartial() {
        val c = repository()
        c.voiceDataChannelEvent(ev("""{"type":"input_audio_buffer.speech_started"}"""))
        c.voiceDataChannelEvent(ev("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Turn on the lights"}"""))
        c.voiceDataChannelEvent(ev("""{"type":"response.output_audio_transcript.delta","delta":"Sure, "}"""))
        c.voiceDataChannelEvent(ev("""{"type":"response.output_audio_transcript.delta","delta":"turning"}"""))

        eventually(message = { "entries=${entries(c)}" }) {
            (entries(c).lastOrNull() as? AssistantEntry)?.blocks?.lastOrNull()?.let { (it as? TextBlock)?.text == "Sure, turning" } == true
        }
        val user = entries(c).filterIsInstance<UserEntry>().single()
        assertEquals("Turn on the lights", user.text)
        assertEquals(UserOrigin.VOICE, user.origin)
        val live = (entries(c).last() as AssistantEntry).blocks.last() as TextBlock
        assertTrue("the live transcript streams (caret)", live.streaming)
        assertEquals(BlockScope.VOICE, live.scope)
        assertTrue("user transcript before the reply", entries(c).indexOf(user) < entries(c).lastIndex)

        // The final transcript replaces the partial in place (one block, not two).
        c.voiceDataChannelEvent(ev("""{"type":"response.output_audio_transcript.done","transcript":"Sure, turning them on."}"""))
        eventually { ((entries(c).last() as AssistantEntry).blocks.last() as TextBlock).text == "Sure, turning them on." }
        val done = entries(c).last() as AssistantEntry
        assertEquals(1, done.blocks.count { it is TextBlock })
        assertFalse("final transcript no longer streaming", (done.blocks.last() as TextBlock).streaming)

        // The next exchange lands after it, in order (I-2: a new user transcript ends the run).
        c.voiceDataChannelEvent(ev("""{"type":"response.done"}"""))
        c.voiceDataChannelEvent(ev("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Thanks"}"""))
        c.voiceDataChannelEvent(ev("""{"type":"response.output_audio_transcript.delta","delta":"Any time"}"""))
        eventually { entries(c).size == 4 }
        val texts = entries(c).map {
            when (it) {
                is UserEntry -> "U:" + it.text
                is AssistantEntry -> "A:" + (it.blocks.last() as TextBlock).text
                else -> "?"
            }
        }
        assertEquals(listOf("U:Turn on the lights", "A:Sure, turning them on.", "U:Thanks", "A:Any time"), texts)
    }

    @Test fun nothingIsSentToTheServer() {
        val c = repository()
        val before = backend.frames.size
        c.voiceDataChannelEvent(ev("""{"type":"response.output_audio_transcript.delta","delta":"hi"}"""))
        eventually { entries(c).isNotEmpty() }
        Thread.sleep(200)
        assertEquals("a local timeline input only (the transport already mirrored the event)", before, backend.frames.size)
    }
}
