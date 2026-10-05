// :core:design — ArchieTheme from design/tokens, M3 components (spec 14 §1.2). Owner: B-01.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.core.design"
    sourceSets {
        // The generated tokens (design/tokens/dist/Tokens.kt, package com.assistant.design) are
        // compiled in place, never copied, so a token rebuild reaches the app without a manual step.
        // Only TokenAdapter.kt reads them (spec 14 §7 B-01, risk X14).
        getByName("main").kotlin.directories += rootProject.layout.projectDirectory
            .dir("../design/tokens/dist").asFile.path
    }
    testOptions.unitTests.all { test ->
        // Goldens: compare against src/test/screenshots in `check`; record with recordRoborazziDebug.
        test.maxHeapSize = "2g"
    }
}

roborazzi {
    // Spec 14 §6.4: goldens live in <module>/src/test/screenshots/.
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
