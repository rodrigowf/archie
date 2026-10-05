package com.assistant.archie.system

import android.app.Application
import android.util.Log
import com.assistant.archie.R
import com.assistant.archie.graph.MainAppGraph
import com.assistant.archie.shell.MainActivity
import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voicehost.AndroidVoiceHost
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.runtime.VoiceHostRuntime

/**
 * The main app's process-scoped voice host (spec 14 §2.5): the A-08 runtime with `HostConfig.main`,
 * riding on the graph's single orchestrator channel. `ConnectionRepository` keeps owning connect and
 * server switches (`manageConnection = false`); the runtime only adds voice, wake word and triggers.
 *
 * Built lazily on first access (the UI's ON_START, `VoiceHostService` after a sticky restart, the
 * tile, the trampoline or the assist session), then the "can't listen in the background" notice
 * attaches.
 */
object MainVoiceHost {
    fun create(app: Application, graph: MainAppGraph): VoiceHostRuntime {
        val runtime = AndroidVoiceHost.create(
            app = app,
            settings = graph.settings,
            http = graph.http,
            config = HostConfig.main(
                launchActivity = MainActivity::class.java,
                trampolineActivity = VoiceTrampolineActivity::class.java,
                smallIcon = R.drawable.ic_stat_archie,
            ),
            transcripts = MainTranscriptSink(onVoiceMessageSent = graph.conversations::voiceMessageSent),
            channel = graph.orchestrator,
            scope = graph.scope,
            // Spec 12 §4.7 / VT-2: the OpenAI owner's own transcripts (no server mirror) → Archie timeline.
            dataChannelTap = graph.conversations::voiceDataChannelEvent,
        )
        BackgroundNotice.attach(app, runtime, graph.scope)
        return runtime
    }
}

/**
 * The text side of [TranscriptSink] in the main app is only logged: the Archie timeline gets the
 * transcripts from the reducer — WS providers via the server's `voice_event` frames, the OpenAI
 * (WebRTC) owner via `dataChannelTap` → `ConversationRepository.voiceDataChannelEvent` (live deltas
 * streaming, the final transcript replacing them). Feeding them here too would duplicate them.
 */
internal class MainTranscriptSink(private val onVoiceMessageSent: () -> Unit) : TranscriptSink {
    override fun userTranscript(text: String, final: Boolean) { if (final) Log.d(TAG, "user transcript (${text.length} chars)") }
    override fun assistantTranscript(text: String, final: Boolean) { if (final) Log.d(TAG, "assistant transcript (${text.length} chars)") }
    override fun system(text: String) = Unit.also { Log.i(TAG, text) }
    /** A talk-phrase capture went out: the Archie timeline shows it as this device's voice message. */
    override fun voiceMessageSent() = onVoiceMessageSent()
    override fun turnComplete() = Unit
    override fun voiceEnded() = Unit

    private companion object {
        const val TAG = "ArchieVoice"
    }
}
