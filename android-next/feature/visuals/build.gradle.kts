// :feature:visuals (spec 14 §1.2). Owner: B-07.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.archie.feature.visuals"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
}
