import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * `catalogTierCheck` (spec 14 §1.3 rule 2, §1.4 rules).
 *
 * Fails when:
 *  - a LEGACY module (minSdk 21 + JVM core) declares a modern-tier library;
 *  - a MODERN module declares a legacy-tier library;
 *  - a LEGACY module depends on a MODERN module (or anything depends on an app module);
 *  - a module declares an external dependency that is not in the catalog (versions live only
 *    in gradle/libs.versions.toml).
 *
 * Tiers come from the "# ---- <tier> tier" comment headers in libs.versions.toml. A library
 * with `version.ref` takes the tier of its version; a BOM-managed library without a version
 * takes the tier of the header it sits under in [libraries].
 */
@CacheableTask
abstract class CatalogTierCheckTask : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>
    @get:Input abstract val moduleTier: Property<String>

    /** "configuration|group:name:version" for every declared external dependency. */
    @get:Input abstract val externalDependencies: ListProperty<String>

    /** "configuration|:project:path|TIER" for every declared project dependency. */
    @get:Input abstract val projectDependencies: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalog: RegularFileProperty

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val tiers = CatalogTiers.parse(catalog.get().asFile.readLines())
        val me = moduleTier.get()
        val errors = mutableListOf<String>()

        for (entry in externalDependencies.get()) {
            val (conf, coord) = entry.split('|', limit = 2)
            val tier = tiers.tierOf(coord)
            when {
                tier == null -> errors += "$conf $coord is not in gradle/libs.versions.toml " +
                    "(versions live only in the catalog; request additions in gradle/catalog-requests.md)"
                me == "LEGACY" && tier == "modern" ->
                    errors += "$conf $coord is a MODERN-tier alias; minSdk-21 modules must use the legacy tier"
                me == "MODERN" && tier == "legacy" ->
                    errors += "$conf $coord is a LEGACY-tier alias; minSdk-26 modules must use the modern tier"
            }
        }
        for (entry in projectDependencies.get()) {
            val (conf, path, tier) = entry.split('|')
            if (path == ":app-main" || path == ":app-lite") {
                errors += "$conf $path: nothing may depend on an app module"
            } else if (me == "LEGACY" && tier == "MODERN") {
                errors += "$conf $path is a minSdk-26 module; a legacy-tier module may not depend on it"
            }
        }

        val text = if (errors.isEmpty()) "OK ${modulePath.get()} ($me)\n"
        else errors.joinToString("\n", postfix = "\n") { "${modulePath.get()}: $it" }
        report.get().asFile.writeText(text)
        if (errors.isNotEmpty()) {
            throw GradleException("catalogTierCheck failed for ${modulePath.get()} ($me tier):\n  " +
                errors.joinToString("\n  "))
        }
    }
}

/** Minimal parser for our own libs.versions.toml layout (one entry per line). */
internal class CatalogTiers(
    private val versioned: Map<String, String>,   // "group:name:version" -> tier
    private val unversioned: Map<String, String>, // "group:name" -> tier
) {
    fun tierOf(coordinate: String): String? {
        versioned[coordinate]?.let { return it }
        val parts = coordinate.split(':')
        val ga = "${parts[0]}:${parts[1]}"
        val version = parts.getOrNull(2).orEmpty()
        // A versionless (BOM-managed) declaration, or a BOM-managed alias.
        if (version.isEmpty()) {
            unversioned[ga]?.let { return it }
            // Versionless request for a catalog library that has versions: tier of those.
            versioned.entries.firstOrNull { it.key.startsWith("$ga:") }?.let { return it.value }
        }
        return null
    }

    companion object {
        private val header = Regex("""^#\s*-+\s*([a-z]+)\s+tier""")
        private val versionLine = Regex("""^([A-Za-z0-9_-]+)\s*=\s*"([^"]+)"""")
        private val group = Regex("""group\s*=\s*"([^"]+)"""")
        private val name = Regex("""name\s*=\s*"([^"]+)"""")
        private val versionRef = Regex("""version\.ref\s*=\s*"([^"]+)"""")
        private val versionLit = Regex("""[{,]\s*version\s*=\s*"([^"]+)"""")

        fun parse(lines: List<String>): CatalogTiers {
            val versionValue = mutableMapOf<String, String>()
            val versionTier = mutableMapOf<String, String>()
            val versioned = mutableMapOf<String, String>()
            val unversioned = mutableMapOf<String, String>()
            var section = ""
            var tier = "shared"
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("[")) { section = line; tier = "shared"; continue }
                val h = header.find(line)
                if (h != null) { tier = h.groupValues[1].let { if (it == "toolchain") "shared" else it }; continue }
                if (line.isEmpty() || line.startsWith("#")) continue
                when (section) {
                    "[versions]" -> versionLine.find(line)?.let {
                        versionValue[it.groupValues[1]] = it.groupValues[2]
                        versionTier[it.groupValues[1]] = tier
                    }
                    "[libraries]" -> {
                        val g = group.find(line)?.groupValues?.get(1) ?: continue
                        val n = name.find(line)?.groupValues?.get(1) ?: continue
                        val ref = versionRef.find(line)?.groupValues?.get(1)
                        val lit = versionLit.find(line)?.groupValues?.get(1)
                        when {
                            ref != null -> versioned["$g:$n:${versionValue[ref]}"] = versionTier[ref] ?: tier
                            lit != null -> versioned["$g:$n:$lit"] = tier
                            else -> unversioned["$g:$n"] = tier
                        }
                    }
                }
            }
            // Tiers that are always allowed everywhere.
            fun norm(t: String) = when (t) { "pinned", "shared", "test" -> "shared"; else -> t }
            return CatalogTiers(versioned.mapValues { norm(it.value) }, unversioned.mapValues { norm(it.value) })
        }
    }
}

/** Configurations whose declared dependencies are checked. */
private fun isCheckedConfiguration(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith("implementation") || n.endsWith("api") || n.endsWith("compileonly") ||
        n.endsWith("runtimeonly") || n == "corelibrarydesugaring"
}

/** Groups added implicitly by the Kotlin and Android plugins, not by module authors. */
private val implicitGroups = setOf("org.jetbrains.kotlin")

internal fun Project.registerCatalogTierCheck() {
    val task = tasks.register<CatalogTierCheckTask>("catalogTierCheck") {
        group = "verification"
        description = "Fails if this module crosses the minSdk 21 / 26 dependency tiers (spec 14 §1.3-§1.4)."
        modulePath.set(project.path)
        moduleTier.set(project.archieTier.name)
        catalog.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
        report.set(layout.buildDirectory.file("reports/catalogTierCheck.txt"))
        externalDependencies.set(provider {
            configurations.filter { isCheckedConfiguration(it.name) }.flatMap { conf ->
                conf.dependencies.withType(ExternalModuleDependency::class.java)
                    .filter { it.group !in implicitGroups }
                    .map { "${conf.name}|${it.group}:${it.name}:${it.version.orEmpty()}" }
            }.distinct().sorted()
        })
        projectDependencies.set(provider {
            configurations.filter { isCheckedConfiguration(it.name) }.flatMap { conf ->
                conf.dependencies.withType(ProjectDependency::class.java)
                    .filter { it.path != project.path }
                    .map { "${conf.name}|${it.path}|${ArchieTiers.tierOf(it.path).name}" }
            }.distinct().sorted()
        })
    }
    // Fail the build, not just `check`: Android modules run it before compiling anything.
    for (id in listOf("com.android.application", "com.android.library")) {
        pluginManager.withPlugin(id) {
            tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(task) }
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        tasks.matching { it.name == "compileKotlin" }.configureEach { dependsOn(task) }
    }
    pluginManager.withPlugin("base") {
        tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
    }
}
