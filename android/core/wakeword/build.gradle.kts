// :core:wakeword — WakeLoop, Vosk/SpeechRecognizer adapters, WhisperClient (spec 14 §1.2). Owner: A-07.
// Native: the Lollipop vosk-stderr-shim (NDK 26.1.10909125, CMake 3.22.1), built for every ABI (the
// apps filter ABIs). The patched libvosk.so lives in :app-lite, not here (spec 14 §1.9).
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.wakeword"
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // `Model(path)` needs the model's raw files (old `A/build.gradle.kts:46`). The apps packaging the
    // 68 MB model asset must declare the same rule.
    androidResources {
        noCompress += "vosk-model-small-en-us-0.15"
    }
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:network"))
    implementation(libs.vosk.android)
    // A-04: parity fakes + virtual-time helpers (spec 14 §6.1). Test classpath only.
    testImplementation(project(":core:testing"))
}
