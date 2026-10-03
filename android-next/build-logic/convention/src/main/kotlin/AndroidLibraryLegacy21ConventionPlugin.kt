import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `archie.android.library.legacy21`: shared core consumed by the A300M lite app.
 * minSdk 21, compileSdk 37, desugaring, lint NewApi = error, consumer ProGuard file (spec 14 §1.3).
 * Uses only legacy/pinned/shared catalog aliases (enforced by catalogTierCheck).
 */
class AndroidLibraryLegacy21ConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        check(archieTier == ArchieTier.LEGACY) {
            "$path applies archie.android.library.legacy21 but is listed in ArchieTiers.modernModules"
        }
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this, ArchieSdk.MIN_SDK_LEGACY)
            if (file("consumer-rules.pro").exists()) defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}
