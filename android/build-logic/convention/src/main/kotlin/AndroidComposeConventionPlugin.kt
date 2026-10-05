import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * `archie.android.compose`: Compose compiler plugin + BOM (spec 14 §1.3 rule 3). Apply after
 * archie.android.library or archie.android.app.main. Only minSdk-26 modules may use it.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        check(archieTier == ArchieTier.MODERN) {
            "$path: Compose is allowed only in minSdk-26 modules (D8; :app-lite and the shared core are Views/pure)"
        }
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByType(CommonExtension::class.java)
        android.buildFeatures.compose = true
        dependencies {
            val bom = libs.lib("androidx-compose-bom")
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        }
    }
}
