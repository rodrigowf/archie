// :core:wakeword — WakeLoopMachine, VoskEngine, WhisperClient (spec 14 §1.2). Owner: A-07.
// A-07 adds the CMake vosk-stderr-shim (NDK 26.1.10909125, CMake 3.22.1) and the model asset.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.wakeword"
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:network"))
    implementation(libs.vosk.android)
}
