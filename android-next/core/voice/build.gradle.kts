// :core:voice — VoiceSessionMachine, transports, provider parsers (spec 14 §1.2). Owner: A-06.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.voice"
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:protocol"))
    api(project(":core:network"))
    implementation(libs.webrtc.android)
    // A-04: parity fakes + virtual-time helpers (spec 14 §6.1). Test classpath only.
    testImplementation(project(":core:testing"))
}
