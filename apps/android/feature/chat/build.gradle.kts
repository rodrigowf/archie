// :feature:chat — the conversation screen (spec 14 §3.1, §3.6; IA §6). Owner: B-04.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.assistant.archie.feature.chat"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // Spec 14 §6.3: the UI tests replay the same apps/protocol-fixtures as the reducer tests.
        getByName("test").resources.directories += rootProject.layout.projectDirectory
            .dir("../protocol-fixtures").asFile.path
        // The streaming benchmark (§3.6) replays a recorded markdown answer from the corpus.
        getByName("androidTest").assets.directories += rootProject.layout.projectDirectory
            .dir("core/markdown/src/test/resources/corpus").asFile.path
    }
    // `-PchatBenchmark` runs the instrumented tests (StreamingChatBenchmark) non-debuggable, like the
    // spec 14 §3.6 `benchmark` build: `./gradlew :feature:chat:connectedReleaseAndroidTest -PchatBenchmark`.
    if (providers.gradleProperty("chatBenchmark").isPresent) testBuildType = "release"
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
    implementation(project(":feature:toolcards"))

    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
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
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)   // ComponentActivity host in any build type
}
