// :core:data — repositories over the network (spec 14 §1.2). Owner: B-03.
plugins {
    alias(libs.plugins.archie.android.library)
}

android {
    namespace = "com.assistant.core.data"
}

dependencies {
    api(project(":core:conversation"))
    api(project(":core:network"))
    api(project(":core:session"))
    api(project(":core:settings"))
    api(project(":core:voice-host"))
}
