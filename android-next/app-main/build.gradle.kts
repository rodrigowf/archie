// :app-main — Archie, com.assistant.archie, minSdk 26, Compose (spec 14 §1.2, §2). Owners: B-03 / B-09.
plugins {
    alias(libs.plugins.archie.android.app.main)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.archie"
    defaultConfig {
        applicationId = "com.assistant.archie"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:markdown"))
    implementation(project(":core:voice-host"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:toolcards"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:visuals"))
    implementation(project(":feature:settings"))
    testImplementation(project(":core:testing"))
}
