// :app-main — Archie, com.assistant.archie, minSdk 26, Compose (spec 14 §1.2, §2). Owners: B-03 / B-09.
plugins {
    alias(libs.plugins.archie.android.app.main)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.kotlin.serialization)   // Navigation 3 keys are @Serializable (spec 14 §2.1)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.archie"
    defaultConfig {
        applicationId = "com.assistant.archie"
    }
    testOptions.unitTests.all { test ->
        // Shell goldens + Robolectric smoke (spec 14 §6.3, §6.4).
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
    implementation(project(":core:voice-host"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:toolcards"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:visuals"))
    implementation(project(":feature:settings"))

    // Shell (B-03): single Activity, Navigation 3, adaptive layout, splash, lifecycle.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
