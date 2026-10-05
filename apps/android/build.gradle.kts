// Plugin aliases only (spec 14 §1.1). Loading them here puts one copy of AGP and the Kotlin
// Gradle plugin on the build classpath; modules apply the archie.* convention plugins.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}
