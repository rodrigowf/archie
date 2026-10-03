// Archie Android (spec 14 §1.1). Standalone build next to the old android/ (D5).
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions JDK 21 for the unit-test JVMs (Robolectric SDK 35+ needs Java 21; the laptop and
    // Jetson only have JDK 17). The settings plugins block cannot read the version catalog, so this
    // is the one version outside libs.versions.toml (VERSIONS.md).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "archie-android"

// Shared core. JVM: model, protocol, conversation. Legacy tier (minSdk 21): network ... testing.
// Modern tier (minSdk 26): data, design, markdown. The tier of every module is declared once in
// build-logic/convention/src/main/kotlin/ArchieTiers.kt.
include(":core:model")
include(":core:protocol")
include(":core:conversation")
include(":core:network")
include(":core:settings")
include(":core:session")
include(":core:audio")
include(":core:voice")
include(":core:wakeword")
include(":core:voice-host")
include(":core:testing")
include(":core:data")
include(":core:design")
include(":core:markdown")

include(":feature:chat")
include(":feature:toolcards")
include(":feature:sessions")
include(":feature:memory")
include(":feature:visuals")
include(":feature:settings")

include(":app-main")
include(":app-lite")
