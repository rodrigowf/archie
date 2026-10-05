import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `archie.android.library`: main-app-only library, minSdk 26 (spec 14 §1.3). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        check(archieTier == ArchieTier.MODERN) {
            "$path applies archie.android.library (minSdk 26) but is not in ArchieTiers.modernModules"
        }
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this, ArchieSdk.MIN_SDK_MODERN)
            if (file("consumer-rules.pro").exists()) defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}
