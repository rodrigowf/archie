// :core:conversation — spec-12 reducer, HistoryMerger, fixture conformance (spec 14 §1.2). Owner: A-02.
plugins {
    alias(libs.plugins.archie.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(libs.kotlinx.collections.immutable)
}

/**
 * Writes `fixtures-index.txt` (one fixture file name per line) so the conformance test enumerates
 * `apps/protocol-fixtures/` without scanning the classpath (spec 14 §6.1).
 */
abstract class GenerateFixtureIndex : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixturesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val names = fixturesDir.get().asFile
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name }
            .sorted()
        val out = outputDir.get().file("fixtures-index.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(names.joinToString(separator = "\n", postfix = "\n"))
    }
}

val protocolFixtures = rootProject.layout.projectDirectory.dir("../protocol-fixtures")

val generateFixtureIndex = tasks.register<GenerateFixtureIndex>("generateFixtureIndex") {
    fixturesDir.set(protocolFixtures)
    outputDir.set(layout.buildDirectory.dir("generated/fixture-index"))
}

sourceSets.named("test") {
    resources.srcDir(protocolFixtures)
    resources.srcDir(generateFixtureIndex)
}
