// :app-mictest — standalone mic test tool for the A300M (VU meter, level history, record/play,
// headset plug log). com.assistant.mictest, minSdk 21, framework Views only, no project deps.
plugins {
    alias(libs.plugins.archie.android.app.tool)
}

android {
    namespace = "com.assistant.mictest"
    defaultConfig {
        applicationId = "com.assistant.mictest"
    }
}
