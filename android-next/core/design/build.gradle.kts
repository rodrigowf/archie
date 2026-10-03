// :core:design — ArchieTheme from design/tokens, M3 components (spec 14 §1.2). Owner: B-01.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.core.design"
}

dependencies {
    api(libs.androidx.compose.material3)
}
