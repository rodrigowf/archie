// :core:settings — SettingsStore, CheckpointStore, ServicePrefs (spec 14 §1.2). Owner: A-03.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.settings"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.datastore.preferences.legacy)
}
