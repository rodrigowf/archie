package com.assistant.archie.feature.visuals

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** A bare Activity hosting pooled WebViews in the instrumented tests. */
class TestHostActivity : Activity() {
    lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
