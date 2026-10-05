// :feature:visuals (spec 14 §1.2, §4.2, §7 B-07): the visuals list, the in-app WebView viewer
// (ArchieWebView + WebViewPool), Show on TV (BX-2) and the inline visual card. Owner: B-07.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.archie.feature.visuals"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)

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

    // VisualWebViewSecurityTest + WebViewPoolLeakTest (spec 14 §6.3), run on POCO_X7.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
