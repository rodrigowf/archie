// :core:voice-host — VoiceHostRuntime, VoiceHostService, VoiceHost API (spec 14 §1.2). Owner: A-08.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.voicehost"
}

dependencies {
    api(project(":core:session"))
    api(project(":core:voice"))
    api(project(":core:wakeword"))
    api(project(":core:settings"))
    implementation(libs.androidx.core.ktx.legacy)
}
