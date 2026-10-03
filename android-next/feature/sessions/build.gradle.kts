// :feature:sessions (spec 14 §1.2). Owner: B-06.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.archie.feature.sessions"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:design"))
}
