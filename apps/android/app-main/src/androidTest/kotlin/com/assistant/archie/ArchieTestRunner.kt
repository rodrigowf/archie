package com.assistant.archie

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * B-09 instrumented runner: the app under test runs [TestArchieApplication] — in-memory settings
 * pointing at an on-device MockWebServer ([TestBackend]), never the live Jetson.
 */
class ArchieTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestArchieApplication::class.java.name, context)
}
