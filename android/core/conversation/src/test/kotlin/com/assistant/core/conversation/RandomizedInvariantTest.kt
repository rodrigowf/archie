package com.assistant.core.conversation

import com.assistant.core.model.HarnessProvider
import com.assistant.core.protocol.ServerFrame
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests over random interleavings (spec 12 §4.1: "each invariant SHOULD be a property test
 * on both platforms"). Seeded, so a failure is reproducible from the printed seed.
 */
class RandomizedInvariantTest {

    private val transcripts = listOf(
        """{"type":"input_audio_buffer.speech_started"}""",
        """{"type":"conversation.item.input_audio_transcription.completed","transcript":"user says"}""",
        """{"type":"response.audio_transcript.delta","delta":"ok "}""",
        """{"type":"response.audio_transcript.done","transcript":"ok done"}""",
        """{"type":"response.done"}""",
        """{"serverContent":{"inputTranscription":{"text":"frag"}}}""",
        """{"serverContent":{"outputTranscription":{"text":"out"}}}""",
        """{"serverContent":{"turnComplete":true}}""",
        """{"serverContent":{"interrupted":true}}""",
    )

    private fun randomInput(r: Random, orchestrator: Boolean, seq: () -> Long): ConversationInput {
        val id = "t${r.nextInt(6)}"
        val stamp: (ServerFrame) -> ServerFrame = { f -> if (!orchestrator && r.nextInt(3) > 0) withSeq(f, seq()) else f }
        val frame: ServerFrame? = when (r.nextInt(30)) {
            0, 1 -> ServerFrame.TextDelta("d${r.nextInt(3)}")
            2 -> ServerFrame.TextComplete("c${r.nextInt(3)}")
            3 -> ServerFrame.ThinkingDelta("k")
            4 -> ServerFrame.ThinkingComplete("k")
            5, 6 -> ServerFrame.ToolUse(id, "Bash", obj("""{"n":${r.nextInt(3)}}"""))
            7, 8 -> ServerFrame.ToolResult(id, JsonPrimitive(if (r.nextBoolean()) "out$id" else ""), r.nextInt(5) == 0)
            9 -> ServerFrame.ToolResult("", JsonPrimitive("anon"), false)
            10 -> ServerFrame.ToolExecuting(id)
            11 -> ServerFrame.PermissionRequest("r${r.nextInt(3)}", "ExitPlanMode", obj("{}"))
            12 -> ServerFrame.PermissionResolved("r${r.nextInt(3)}", if (r.nextBoolean()) "allow" else "deny", "user")
            13 -> ServerFrame.UserMessage("u${r.nextInt(3)}", queued = r.nextInt(3) == 0)
            14 -> if (orchestrator) ServerFrame.Status(if (r.nextBoolean()) "streaming" else "idle") else processing()
            15 -> if (orchestrator) ServerFrame.TurnComplete(inputTokens = 5) else turnComplete()
            16 -> ServerFrame.Status("interrupted")
            17 -> ServerFrame.Error(listOf("send_failed", "api_error", "interrupted", "compact_failed", "unknown_type").random(r), "x")
            18 -> ServerFrame.CompactComplete("auto", "s")
            19 -> ServerFrame.SessionStalled(1.0)
            20 -> if (r.nextInt(4) == 0) ServerFrame.SessionStopped() else null
            21, 22 -> ServerFrame.VoiceEvent(obj(transcripts.random(r)))
            23 -> if (r.nextInt(3) == 0) ServerFrame.VoiceEnded("user_stop") else ServerFrame.VoiceOwnerActive(true)
            24 -> ServerFrame.UserMessage("[shared text]\ns", source = "shared_inject")
            else -> null
        }
        if (frame != null) return ConversationInput.Frame(stamp(frame))
        return when (r.nextInt(10)) {
            0 -> ConversationInput.LocalSend("p${r.nextInt(3)}")
            1 -> ConversationInput.LocalInterrupt
            2 -> ConversationInput.LocalCompact
            3 -> ConversationInput.LocalInject("[shared text]\ns")
            4 -> ConversationInput.SocketClosed
            5 -> ConversationInput.SocketOpened
            6 -> ConversationInput.Frame(ServerFrame.SessionStarted("L1", resumeState = null))
            7 -> ConversationInput.VoiceLocalEnd
            8 -> ConversationInput.DataChannelEvent(obj(transcripts.random(r)))
            else -> ConversationInput.LocalSendAudio
        }
    }

    private fun withSeq(f: ServerFrame, seq: Long): ServerFrame = when (f) {
        is ServerFrame.TextDelta -> f.copy(seq = seq, streamId = "S")
        is ServerFrame.TextComplete -> f.copy(seq = seq, streamId = "S")
        is ServerFrame.ToolUse -> f.copy(seq = seq, streamId = "S")
        is ServerFrame.ToolResult -> f.copy(seq = seq, streamId = "S")
        is ServerFrame.PermissionRequest -> f.copy(seq = seq, streamId = "S")
        else -> f
    }

    @Test
    fun invariantsHoldOnRandomSequences() {
        for (seed in 1..400) {
            val r = Random(seed)
            val orch = seed % 2 == 0
            var counter = 0L
            val initial = if (orch) orchestrator(voiceActive = r.nextBoolean()) else agent(provider = if (seed % 3 == 0) HarnessProvider.QWEN else HarnessProvider.CLAUDE)
            var s = initial
            val inputs = ArrayList<ConversationInput>()
            repeat(120) {
                val i = randomInput(r, orch) { ++counter }
                inputs += i
                val before = s
                s = ConversationReducer.reduce(s, i)
                val v = InvariantChecker.check(s) + InvariantChecker.checkStep(before, i, s)
                if (v.isNotEmpty()) fail("seed $seed step ${inputs.size} ($i): $v")
            }
            // I-13 determinism: replaying the same inputs from the same state gives the same state
            assertEquals("seed $seed", s, ConversationReducer.reduceAll(initial, inputs))
        }
    }

    /**
     * RESULT-DELIVERY as a property: for every id that received results, the last non-empty output
     * (or "" if all were empty) is shown on its card, or held as an orphan when no card exists. Never lost.
     */
    @Test
    fun resultsAreNeverLost() {
        for (seed in 1..400) {
            val r = Random(seed)
            var s = if (seed % 2 == 0) orchestrator(voiceActive = true) else agent().send("go").on(processing())
            val expected = HashMap<String, String>()
            repeat(80) {
                val id = "t${r.nextInt(8)}"
                val f: ServerFrame = when (r.nextInt(8)) {
                    0, 1 -> ServerFrame.ToolUse(id, "Bash", obj("{}"))
                    2, 3 -> {
                        val out = if (r.nextInt(3) == 0) "" else "o${r.nextInt(100)}"
                        if (out.isNotEmpty() || id !in expected) expected[id] = out
                        ServerFrame.ToolResult(id, JsonPrimitive(out), false)
                    }
                    4 -> voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"v"}""")
                    5 -> ServerFrame.UserMessage("x")
                    6 -> if (seed % 2 == 0) ServerFrame.Status(if (r.nextBoolean()) "streaming" else "idle") else turnComplete()
                    else -> ServerFrame.TextDelta("t")
                }
                s = s.on(f)
            }
            for ((id, out) in expected) {
                val card = s.entries.filterIsInstance<AssistantEntry>().flatMap { it.blocks }.filterIsInstance<ToolBlock>().singleOrNull { it.toolUseId == id }
                val shown = card?.output ?: s.orphanResults[id]?.output
                assertEquals("seed $seed, $id (card=$card, orphan=${s.orphanResults[id]})", out, shown)
            }
        }
    }
}
