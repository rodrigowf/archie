// :feature:toolcards (spec 14 §1.2, §3.4–§3.5). Owner: B-05.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.archie.feature.toolcards"
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
    implementation(project(":core:design"))
    implementation(project(":core:markdown"))
    implementation(project(":core:conversation"))
    implementation(libs.java.diff.utils)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
