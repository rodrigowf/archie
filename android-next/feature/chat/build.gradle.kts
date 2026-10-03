// :feature:chat (spec 14 §1.2). Owner: B-04.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.archie.feature.chat"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:markdown"))
    implementation(project(":feature:toolcards"))
}
