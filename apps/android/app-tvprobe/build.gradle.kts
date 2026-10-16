// :app-tvprobe — TV remote probe: logs every key event (incl. the Fire TV Alexa/mic button) and
// records from the remote's BLE mic while a key is held. com.assistant.tvprobe, Views only.
plugins {
    alias(libs.plugins.archie.android.app.tool)
}

android {
    namespace = "com.assistant.tvprobe"
    defaultConfig {
        applicationId = "com.assistant.tvprobe"
    }
}
