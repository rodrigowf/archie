// :core:testing — fakes + fixture loaders, consumed only via testImplementation (spec 14 §1.2). Owner: A-04.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit4)
}
