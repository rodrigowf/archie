import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

/** Shared constants (spec 14 §1.4). */
object ArchieSdk {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK_LEGACY = 21
    const val MIN_SDK_MODERN = 26
    /** Bytecode level and build JDK. */
    val JAVA = JavaVersion.VERSION_17
    /** JVM that runs Android unit tests: Robolectric needs Java 21 for SDK 35+ sandboxes. */
    const val TEST_JDK = 21
    val JVM_TARGET = JvmTarget.JVM_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).orElseThrow {
    IllegalStateException("Catalog alias '$alias' missing from gradle/libs.versions.toml")
}

internal fun Project.emulatorAbis(): Boolean =
    providers.gradleProperty("archie.emulatorAbis").map { it.toBoolean() }.getOrElse(false)

/** Reads a .properties file through the provider API so the configuration cache tracks it. */
internal fun Project.readProperties(relativeToRoot: Boolean, path: String): Properties? {
    val dir = if (relativeToRoot) rootProject.layout.projectDirectory else layout.projectDirectory
    val text = providers.fileContents(dir.file(path)).asText.orNull ?: return null
    return Properties().apply { load(text.reader()) }
}

/** compileSdk, Java 17, desugaring, test defaults, common to libraries and apps. */
internal fun Project.configureAndroidCommon(android: CommonExtension, minSdk: Int) {
    android.apply {
        compileSdk = ArchieSdk.COMPILE_SDK
        defaultConfig.minSdk = minSdk
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        compileOptions.sourceCompatibility = ArchieSdk.JAVA
        compileOptions.targetCompatibility = ArchieSdk.JAVA
        // java.time in timestamp parsing on API 21-25 (fixes the UTC skew, inv03 §1.5).
        compileOptions.isCoreLibraryDesugaringEnabled = true
        testOptions.unitTests.isIncludeAndroidResources = true
        lint.abortOnError = true
        lint.checkReleaseBuilds = true
        // API 21 safety: any call above minSdk without a Build.VERSION guard fails lint.
        lint.error += "NewApi"
    }
    configureKotlinJvmTarget()
    val testLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(ArchieSdk.TEST_JDK))
    }
    tasks.withType<Test>().configureEach {
        javaLauncher.set(testLauncher)
        // Robolectric's FileDescriptor interceptor (SDK 36 ApplicationSharedMemory) on JDK 21.
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
    }
    dependencies {
        add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
        add("testImplementation", libs.lib("junit4"))
    }
    registerCatalogTierCheck()
}

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(ArchieSdk.JVM_TARGET)
    }
}
