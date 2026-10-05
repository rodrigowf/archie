// :core:testing — fakes + fixture loaders, consumed only via testImplementation (spec 14 §1.2). Owner: A-04.
//
// The fakes implement the voice-stack port interfaces (`…/ports/*.kt`, owned by A-04), so this
// module depends on the four voice modules' MAIN source sets. The voice modules consume it from
// their unit tests only (`testImplementation(project(":core:testing"))`), which Gradle resolves
// without a cycle: `:core:voice:test` → `:core:testing` → `:core:voice:main`.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(project(":core:audio"))
    api(project(":core:voice"))
    api(project(":core:wakeword"))
    api(project(":core:voice-host"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit4)
    // Whisper / SDP adapter tests run against a scripted local HTTP server.
    api(libs.okhttp.mockwebserver)
}
