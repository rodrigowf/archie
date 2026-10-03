// :core:session — OrchestratorChannel (spec 14 §1.2). Owner: A-03.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.session"
}

dependencies {
    api(project(":core:network"))
    api(project(":core:settings"))
}
