import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** `archie.jvm.library`: Kotlin JVM, JVM 17 toolchain, JUnit 4, Android lint (spec 14 §1.3). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.android.lint")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions.jvmTarget.set(ArchieSdk.JVM_TARGET)
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = ArchieSdk.JAVA
            targetCompatibility = ArchieSdk.JAVA
        }
        dependencies {
            add("testImplementation", libs.lib("junit4"))
        }
        registerCatalogTierCheck()
    }
}
