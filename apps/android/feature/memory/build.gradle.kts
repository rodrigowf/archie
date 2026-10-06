// :feature:memory (spec 14 §1.2, §4.1, §7 B-07): the memory tree, the memory document and the link
// resolver. Read-only (MEM-5). Owner: B-07.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.archie.feature.memory"
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
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:markdown"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)

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
