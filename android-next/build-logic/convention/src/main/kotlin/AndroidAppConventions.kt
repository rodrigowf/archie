import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** `archie.android.app.main`: com.assistant.archie, minSdk 26 (spec 14 §1.6-§1.8). */
class AndroidAppMainConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureArchieApp(
            minSdk = ArchieSdk.MIN_SDK_MODERN,
            deviceAbis = listOf("arm64-v8a"),        // the POCO X7 is 64-bit only
            emulatorAbis = listOf("x86_64"),         // POCO_X7 AVD
            signingPrefix = "archie",                // decision Q2
        )
    }
}

/** `archie.android.app.lite`: com.assistant.peripheral, minSdk 21, Views only (spec 14 §1.6-§1.9, §5). */
class AndroidAppLiteConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureArchieApp(
            minSdk = ArchieSdk.MIN_SDK_LEGACY,
            deviceAbis = listOf("armeabi-v7a"),      // the A300M runs 32-bit userspace
            // x86: the A300M_API21 AVD. x86_64: the POCO_X7 AVD (API 36, abilist x86_64,arm64-v8a), used
            // to prove the lite APK installs over the old ARM-only APK, which the x86 API 21 image cannot run.
            emulatorAbis = listOf("x86", "x86_64"),
            signingPrefix = "peripheral",            // MUST be the key the A300M build was signed with
        )
        val emulator = emulatorAbis()
        extensions.configure<ApplicationExtension> {
            // The patched libvosk.so in src/main/jniLibs must win over the AAR's stock copy (§1.9, X4).
            packaging.jniLibs.pickFirsts += "lib/*/libvosk.so"
            // API 21 needs v1 (JAR) signing; AGP turns it on for minSdk < 24, keep it explicit.
            signingConfigs.findByName("peripheral")?.enableV1Signing = true
        }
        val components = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            if (emulator) variant.sources.jniLibs?.addStaticSourceDirectory("src/emulator/jniLibs")
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val verify = tasks.register<VerifyPatchedVoskTask>("verifyPatchedVosk$cap") {
                group = "verification"
                description = "Fails unless stderr/stdin/stdout are STB_WEAK in every libvosk.so of the ${variant.name} APK."
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                script.set(rootProject.layout.projectDirectory.file("tools/native/verify_vosk_patch.py"))
                report.set(layout.buildDirectory.file("reports/verifyPatchedVosk/${variant.name}.txt"))
            }
            // An unpatched lib must never reach a device: verify right after every assemble.
            tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(verify) }
        }
        tasks.register("verifyPatchedVosk") {
            group = "verification"
            description = "verifyPatchedVoskDebug (spec 14 §1.9)."
            dependsOn("verifyPatchedVoskDebug")
        }

        val noCompose = tasks.register<VerifyNoComposeTask>("verifyNoCompose") {
            group = "verification"
            description = "Fails if any androidx.compose artifact resolves in releaseRuntimeClasspath (D8)."
            classpathName.set("releaseRuntimeClasspath")
            resolvedModules.set(
                configurations.named("releaseRuntimeClasspath")
                    .flatMap { c: Configuration -> c.incoming.resolutionResult.rootComponent }
                    .map { VerifyNoComposeTask.collect(it) },
            )
            report.set(layout.buildDirectory.file("reports/verifyNoCompose.txt"))
        }
        tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach { dependsOn(noCompose) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyPatchedVosk") }
    }
}

private fun Project.configureArchieApp(
    minSdk: Int,
    deviceAbis: List<String>,
    emulatorAbis: List<String>,
    signingPrefix: String,
) {
    pluginManager.apply("com.android.application")
    val version = readProperties(relativeToRoot = false, path = "version.properties")
        ?: throw GradleException("$path: missing version.properties (versionCode, versionName)")
    val keystore = readProperties(relativeToRoot = true, path = "keystore.properties")
    val storeFile = keystore?.getProperty("$signingPrefix.storeFile")
    val useEmulatorAbis = emulatorAbis()

    extensions.configure<ApplicationExtension> {
        configureAndroidCommon(this, minSdk)
        defaultConfig.targetSdk = ArchieSdk.TARGET_SDK
        defaultConfig.versionCode = version.getProperty("versionCode").toInt()
        defaultConfig.versionName = version.getProperty("versionName")
        defaultConfig.ndk.abiFilters.clear()
        defaultConfig.ndk.abiFilters += deviceAbis
        if (useEmulatorAbis) defaultConfig.ndk.abiFilters += emulatorAbis

        if (storeFile != null) {
            val config = signingConfigs.create(signingPrefix) {
                this.storeFile = file(storeFile)
                storePassword = keystore.getProperty("$signingPrefix.storePassword")
                keyAlias = keystore.getProperty("$signingPrefix.keyAlias")
                keyPassword = keystore.getProperty("$signingPrefix.keyPassword")
            }
            buildTypes.getByName("debug").signingConfig = config
            buildTypes.getByName("release").signingConfig = config
        }
        // No applicationIdSuffix on debug: two installs would run two wake-word services (§1.6).
        buildTypes.getByName("debug").isDebuggable = true
        buildTypes.getByName("release").apply {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (file("proguard-rules.pro").exists()) proguardFiles("proguard-rules.pro")
        }
    }

    // §1.8: a release build without the real key fails; debug falls back to the SDK debug key.
    val hasKey = storeFile != null
    val checkSigning = tasks.register("checkReleaseSigning") {
        group = "verification"
        description = "Fails when keystore.properties has no '$signingPrefix.*' signing entry (spec 14 §1.8)."
        val prefix = signingPrefix
        doLast {
            if (!hasKey) throw GradleException(
                "Release signing for '$prefix' is not configured: add $prefix.storeFile/storePassword/" +
                    "keyAlias/keyPassword to android-next/keystore.properties (spec 14 §1.8).",
            )
        }
    }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkSigning) }
}
