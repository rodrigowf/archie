package com.assistant.archie.tv

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.graph.TvGraphOwner
import com.assistant.archie.tv.voice.LiveMicBridge
import com.assistant.archie.tv.voice.TvVoice
import com.assistant.archie.tv.voice.TvVoiceOwner
import com.assistant.core.audio.platform.ElapsedRealtimeClock
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostOwner

/**
 * Builds [TvAppGraph] and ties it to the process lifecycle (spec 15 §6.1, like `ArchieApplication`).
 * JVM tests run with a plain `Application` and render state-driven screens, so no unit test ever
 * dials the default server.
 *
 * It is also the [VoiceHostOwner] the merged `VoiceHostService` reaches the runtime through, and
 * the [TvVoiceOwner] of the TV's voice (T4: `TvVoice.hostFactory` builds the voice host).
 */
open class TvApplication : Application(), TvGraphOwner, VoiceHostOwner, TvVoiceOwner {
    override val graph: TvAppGraph by lazy { createGraph() }

    override val voiceHostRuntime: VoiceHostRuntime
        get() = graph.voiceHost ?: error("Archie TV was built without a voice host")

    // ── T4 voice (spec 15 §4) ──
    /** The live call's microphone: shared by the voice host's transports and [tvVoice]. */
    private val liveMic = LiveMicBridge(ElapsedRealtimeClock)

    override val tvVoice: TvVoice? by lazy { TvVoice(this, graph, liveMic) }

    protected open fun createGraph(): TvAppGraph = TvAppGraph(this, voiceHostFactory = TvVoice.hostFactory(liveMic))
    // ── end T4 ──

    override fun onCreate() {
        super.onCreate()
        graph.attachNetwork()
        tvVoice?.install() // T4: mic armed while started, graph.voice / graph.liveVoice
        // TV control commands bring the app forward from the background (spec 15 §5.2); Fire OS
        // allows the background start with the SYSTEM_ALERT_WINDOW app-op tv_setup.sh grants.
        graph.navHolder.bringToFront = {
            startActivity(
                android.content.Intent(this, com.assistant.archie.tv.shell.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.processLifecycle)
    }
}
