import org.gradle.api.Project

/**
 * Dependency tiers (spec 14 §1.2, §1.5).
 *
 * LEGACY: compiled for the A300M (minSdk 21). The pure-JVM core modules count as LEGACY too,
 * because :app-lite consumes them. A LEGACY module may only use legacy/pinned/shared/test
 * catalog aliases and may only depend on LEGACY modules.
 *
 * MODERN: minSdk 26 (main app only). May depend on anything except :app-lite and may not use
 * a legacy-tier alias (it must take the modern AndroidX line).
 */
enum class ArchieTier { LEGACY, MODERN }

object ArchieTiers {
    /** Every minSdk-26 module. Anything not listed is LEGACY. */
    val modernModules: Set<String> = setOf(
        ":core:data",
        ":core:design",
        ":core:markdown",
        ":feature:chat",
        ":feature:toolcards",
        ":feature:sessions",
        ":feature:memory",
        ":feature:visuals",
        ":feature:settings",
        ":app-main",
    )

    fun tierOf(path: String): ArchieTier =
        if (path in modernModules) ArchieTier.MODERN else ArchieTier.LEGACY
}

val Project.archieTier: ArchieTier get() = ArchieTiers.tierOf(path)
