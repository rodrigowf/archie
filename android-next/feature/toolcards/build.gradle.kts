// :feature:toolcards (spec 14 §1.2). Owner: B-05.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.archie.feature.toolcards"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:markdown"))
    implementation(project(":core:conversation"))
    implementation(libs.java.diff.utils)
}
