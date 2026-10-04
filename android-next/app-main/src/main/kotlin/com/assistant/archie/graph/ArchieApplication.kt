package com.assistant.archie.graph

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.assistant.archie.system.MainVoiceHost
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostOwner

/**
 * Builds [MainAppGraph] (spec 14 §2.2) and ties it to the process lifecycle. JVM tests run with their
 * own Application (a test [GraphOwner] built against a local server), so no unit test ever dials the
 * default server. Instrumented tests subclass this and override [createGraph] (their own settings,
 * a MockWebServer backend).
 *
 * B-09: it is also the [VoiceHostOwner] the voice host's service, the tile, the trampoline and the
 * assist session reach the process-scoped runtime through.
 */
open class ArchieApplication : Application(), GraphOwner, VoiceHostOwner {
    override val graph: MainAppGraph by lazy { createGraph() }

    override val voiceHostRuntime: VoiceHostRuntime
        get() = graph.voiceHost ?: error("this graph was built without a voice host")

    protected open fun createGraph(): MainAppGraph = MainAppGraph(this, voiceHostFactory = MainVoiceHost::create)

    override fun onCreate() {
        super.onCreate()
        graph.attachNetwork()
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.processLifecycle)
    }
}
