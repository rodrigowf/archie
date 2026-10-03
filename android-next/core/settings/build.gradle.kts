// :core:settings — SettingsStore, CheckpointStore, ServicePrefs (spec 14 §1.2). Owner: A-03.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.settings"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    // DataStore types (DataStore<Preferences>) appear in public constructors for testability.
    api(libs.androidx.datastore.preferences.legacy)
    // `saved_servers_v2` JSON list (spec 14 §2.10).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
