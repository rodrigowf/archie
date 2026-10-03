// :app-lite — voice-first A300M app, com.assistant.peripheral, minSdk 21, Views only (spec 14 §5). Owner: C-01.
// Must stay signed with the key in keystore.properties `peripheral.*` and keep versionCode >= 11 (§1.7-§1.8).
plugins {
    alias(libs.plugins.archie.android.app.lite)
}

android {
    namespace = "com.assistant.peripheral"
    defaultConfig {
        applicationId = "com.assistant.peripheral"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:settings"))
    implementation(project(":core:session"))
    implementation(project(":core:audio"))
    implementation(project(":core:voice"))
    implementation(project(":core:wakeword"))
    implementation(project(":core:voice-host"))
    testImplementation(project(":core:testing"))
}
