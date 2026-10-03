package com.assistant.archie.graph

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Builds [MainAppGraph] (spec 14 §2.2) and ties it to the process lifecycle. JVM tests run with their
 * own Application (a test [GraphOwner] built against a local server), so no unit test ever dials the
 * default server.
 */
class ArchieApplication : Application(), GraphOwner {
    override val graph: MainAppGraph by lazy { MainAppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.attachNetwork()
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.processLifecycle)
    }
}
