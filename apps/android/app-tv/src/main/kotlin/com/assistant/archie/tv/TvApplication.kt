package com.assistant.archie.tv

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.graph.TvGraphOwner
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostOwner

/**
 * Builds [TvAppGraph] and ties it to the process lifecycle (spec 15 §6.1, like `ArchieApplication`).
 * JVM tests run with a plain `Application` and render state-driven screens, so no unit test ever
 * dials the default server.
 *
 * It is also the [VoiceHostOwner] the merged `VoiceHostService` reaches the runtime through; T4
 * supplies the factory (`TvAppGraph.voiceHostFactory`), until then nothing starts the service.
 */
open class TvApplication : Application(), TvGraphOwner, VoiceHostOwner {
    override val graph: TvAppGraph by lazy { createGraph() }

    override val voiceHostRuntime: VoiceHostRuntime
        get() = graph.voiceHost ?: error("Archie TV was built without a voice host (T4 wires it)")

    protected open fun createGraph(): TvAppGraph = TvAppGraph(this)

    override fun onCreate() {
        super.onCreate()
        graph.attachNetwork()
        // TV control commands bring the app forward from the background (spec 15 §5.2); Fire OS
        // allows the background start with the SYSTEM_ALERT_WINDOW app-op tv_setup.sh grants.
        graph.navHolder.bringToFront = {
            startActivity(
                android.content.Intent(this, com.assistant.archie.tv.shell.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.processLifecycle)
        // ── T3: the TV control socket (/api/tv/ws), spec 15 §5 ──
        graph.tvControl.start()
        // ── end T3 ──
    }
}
