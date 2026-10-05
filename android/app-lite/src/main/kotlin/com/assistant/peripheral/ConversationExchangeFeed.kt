package com.assistant.peripheral

import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.voicehost.LastExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Feeds the face's last exchange (spec 14 §5.2) from the Archie conversation itself, not only
 * from this device's voice transcripts (2026-10-05, A300M: a conversation opened or typed into on
 * another device left the face empty).
 *
 * - **Live** (orchestrator frames): `user_message` echoes (O-3; `source:"voice_message"` = another
 *   device's voice message, VM-1) start an exchange; the assistant line is set at each
 *   `text_complete` of a text turn (12 §4.4.2), never per `text_delta` (no caption rebind per
 *   token on the A300M). Deltas are kept, bounded, only as a fallback when a turn ends without
 *   `text_complete`.
 * - **Voice dedupe:** while this device has its own voice session ([voiceLive]), the voice core
 *   feeds the sink with transcripts and this feed ignores conversation content (and does not
 *   load history), so nothing is shown twice. The backend sends no `text_delta` for voice turns
 *   anyway (transcripts travel as `voice_event`); the skip only guards the overlap.
 * - **Attach:** every `session_started` (attach, re-attach after a reconnect, foreground resync)
 *   loads the last exchange from history (`GET …/messages`, a few lines). A live write that lands
 *   while the page is in flight wins ([LastExchangeSink.applyHistory]).
 * - **Voice elsewhere:** a passive device gets no transcripts (OpenAI has no server mirror, VT-2),
 *   so history is reloaded when the other device's voice ends.
 * - **Conversation change:** `session_started` for another conversation, or ours closed
 *   (`agent_session_closed`, or the channel's ref going null on a close / server change, [onDetached]):
 *   the exchange is cleared, matching "No conversation open on the server".
 *
 * The lite channel always sends `start` after adopting a conversation (autoStart), so
 * `session_started` is the one attach signal; the channel state is only watched for "none" (a new
 * ref there could race the frame collector on another thread and clear a fresh page).
 * Calls come from two collectors (frames, channel state): every entry point is synchronized.
 */
class ConversationExchangeFeed(
    private val scope: CoroutineScope,
    private val sink: LastExchangeSink,
    /** `GET /api/sessions/{sdkId}/messages?limit=[HISTORY_LIMIT]`; null on any failure. */
    private val history: suspend (sdkId: String) -> List<MessagePreviewDto>?,
    /** This device's own voice session is up (any phase but OFF / ERROR). */
    private val voiceLive: () -> Boolean,
    /** The channel's adopted `sdkId`, used when a `session_started` lacks `jsonl_id` (pre-O-3). */
    private val channelSdkId: () -> String? = { null },
) {
    private var localId: String? = null
    private var sdkId: String? = null
    private val pending = StringBuilder()
    private var committedInTurn = false
    private var remoteVoice = false
    private var load: Job? = null

    /** The channel has no conversation any more (`ChannelState.orchestrator` became null). */
    @Synchronized fun onDetached() = switchTo(null)

    @Synchronized fun onFrame(f: ServerFrame) {
        when (f) {
            is ServerFrame.SessionStarted -> {
                f.sessionId?.let { switchTo(it) }
                (f.jsonlId?.takeIf { it.isNotEmpty() } ?: channelSdkId()?.takeIf { it.isNotEmpty() })?.let { sdkId = it }
                if (f.voice == true && f.voiceInitiator != true && !voiceLive()) remoteVoice = true
                resetTurn()
                loadHistory()
            }
            is ServerFrame.AgentSessionClosed -> if (f.isOrchestrator && f.sessionId != null && f.sessionId == localId) switchTo(null)
            is ServerFrame.VoiceOwnerActive -> if (f.active) {
                if (!voiceLive()) remoteVoice = true
            } else {
                onRemoteVoiceEnded()
            }
            is ServerFrame.VoiceEnded, is ServerFrame.VoiceStopped -> onRemoteVoiceEnded()
            else -> if (!voiceLive()) onContent(f)
        }
    }

    private fun onContent(f: ServerFrame) {
        when (f) {
            is ServerFrame.UserMessage -> {
                if (f.queued) return
                resetTurn()
                if (f.source == "voice_message") sink.showUser(voiceMessage(f.text)) else sink.showUser(f.text)
            }
            is ServerFrame.Status -> when (f.status) {
                "streaming" -> resetTurn()
                "idle", "interrupted" -> { flushPending(); resetTurn() }
            }
            is ServerFrame.TextDelta -> if (pending.length < PENDING_CAP) pending.append(f.text, 0, minOf(f.text.length, PENDING_CAP - pending.length))
            is ServerFrame.TextComplete -> {
                val text = f.text.ifEmpty { pending.toString() }
                pending.setLength(0)
                commit(text)
            }
            is ServerFrame.TurnComplete -> { flushPending(); resetTurn() }
            else -> Unit
        }
    }

    private fun commit(text: String) {
        if (text.isBlank()) return
        sink.showAssistant(text, firstOfTurn = !committedInTurn)
        committedInTurn = true
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.setLength(0)
        commit(text)
    }

    private fun resetTurn() {
        pending.setLength(0)
        committedInTurn = false
    }

    private fun onRemoteVoiceEnded() {
        if (!remoteVoice) return
        remoteVoice = false
        loadHistory()
    }

    private fun switchTo(id: String?) {
        if (id == localId) return
        localId = id
        sdkId = null
        remoteVoice = false
        load?.cancel()
        load = null
        resetTurn()
        sink.clear()
    }

    private fun loadHistory() {
        val id = sdkId ?: return
        if (voiceLive()) return
        val conversation = localId
        val since = sink.version()
        load?.cancel()
        load = scope.launch {
            val page = history(id) ?: return@launch
            val e = lastExchangeOf(page) ?: return@launch
            synchronized(this@ConversationExchangeFeed) {
                if (localId == conversation && sdkId == id && !voiceLive()) sink.applyHistory(e, since)
            }
        }
    }

    companion object {
        /** History lines fetched on attach: enough to reach the last prompt past a few tool lines. */
        const val HISTORY_LIMIT = 12

        /** Bound of the `text_delta` fallback buffer (the face shows at most 5 lines anyway). */
        private const val PENDING_CAP = LastExchange.MAX_CHARS * 2

        private val VOICE_RECORDING = Regex("^\\[voice, recording: [^\\]]*] ?")
        private val AUDIO = Regex("^\\[audio:[A-Za-z0-9]+] ?")
        private val COMMAND = Regex("^<(command-name|command-message|command-args|local-command-stdout|local-command-stderr|local-command-caveat)>")

        /** "(voice message)", or "(voice message) <prompt>" when the clip came with one (VM-1). */
        fun voiceMessage(prompt: String): String =
            if (prompt.isBlank() || prompt == "(audio message)") LastExchangeSink.REMOTE_VOICE_MESSAGE
            else "${LastExchangeSink.REMOTE_VOICE_MESSAGE} ${prompt.trim()}"

        /**
         * The last exchange of a history page (spec 12 §5.1, reduced to what the face needs): the
         * last user prompt and the last assistant text after it. Tool-result wrappers, notices and
         * empty lines are skipped; `[voice] ` is stripped; `[audio:<fmt>] …` is a voice message. An
         * assistant reply after a background notice, or with no prompt in the page, has no user line.
         * Null when the page holds neither (raw text: the sink strips and caps it).
         */
        fun lastExchangeOf(page: List<MessagePreviewDto>): LastExchange? {
            var assistant: String? = null
            for (i in page.indices.reversed()) {
                val p = page[i]
                if (p.role == "assistant") {
                    if (assistant == null) assistant = lastText(p)
                    continue
                }
                if (p.role != "user") continue
                if (p.blocks.isNotEmpty() && p.blocks.all { it.type == "tool_result" }) continue
                val text = p.text
                if (text.isBlank()) continue
                when (val user = classifyUser(text)) {
                    null -> continue
                    "" -> continue
                    BACKGROUND -> if (assistant != null) return LastExchange(null, assistant)
                    else -> return LastExchange(user, assistant)
                }
            }
            return assistant?.let { LastExchange(null, it) }
        }

        private const val BACKGROUND = "\u0000background"

        private fun lastText(p: MessagePreviewDto): String? {
            if (p.blocks.isEmpty()) return p.text.takeIf { it.isNotBlank() }
            return p.blocks.lastOrNull { it.type == "text" && !it.text.isNullOrBlank() }?.text
        }

        /** The user line as shown, [BACKGROUND] for a background notice, null for other notices. */
        private fun classifyUser(text: String): String? {
            if (text.startsWith("[voice] ")) return text.substring(8).trim()
            VOICE_RECORDING.find(text)?.let { return text.substring(it.value.length).trim() }
            AUDIO.find(text)?.let { return voiceMessage(text.substring(it.value.length)) }
            if (text.startsWith("<task-notification>")) return BACKGROUND
            if (text.startsWith("[Request interrupted by user")) return null
            if (text.startsWith("This session is being continued from a previous conversation")) return null
            if (COMMAND.containsMatchIn(text)) return null
            return text
        }
    }
}
