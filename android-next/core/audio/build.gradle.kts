// :core:audio — EchoDucker, RouteDecider, MicSource, PcmSink, ... (spec 14 §1.2). Owner: A-05.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.audio"
}

dependencies {
    api(project(":core:model"))
    // A-04: parity fakes + virtual-time helpers (spec 14 §6.1). Test classpath only.
    testImplementation(project(":core:testing"))
}
