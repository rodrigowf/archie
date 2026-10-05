plugins {
    `kotlin-dsl`
}

group = "com.assistant.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: the root build.gradle.kts loads the real plugins (apply false), so there is
    // exactly one copy of AGP / KGP on the build classpath.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = libs.plugins.archie.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibraryLegacy21") {
            id = libs.plugins.archie.android.library.legacy21.get().pluginId
            implementationClass = "AndroidLibraryLegacy21ConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.archie.android.library.asProvider().get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.archie.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidAppMain") {
            id = libs.plugins.archie.android.app.main.get().pluginId
            implementationClass = "AndroidAppMainConventionPlugin"
        }
        register("androidAppLite") {
            id = libs.plugins.archie.android.app.lite.get().pluginId
            implementationClass = "AndroidAppLiteConventionPlugin"
        }
    }
}
