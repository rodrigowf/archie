// :core:protocol — ProtocolCodec, ServerFrame/ClientFrame, RestDto (spec 14 §1.2). Owner: A-02.
plugins {
    alias(libs.plugins.archie.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
}

// The codec round-trip test decodes every frame of the shared fixtures (spec 14 §6.1).
val protocolFixtures = rootProject.layout.projectDirectory.dir("../protocol-fixtures")
// The harness catalogs the web mock server serves (`GET /api/config/harnesses`), shared with the web tests.
val harnessCatalogs = rootProject.layout.projectDirectory.file("../web/mock-server/data/harnesses.json")

tasks.withType<Test>().configureEach {
    inputs.dir(protocolFixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("archie.protocolFixtures", protocolFixtures.asFile.absolutePath)
    inputs.file(harnessCatalogs).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("archie.harnessCatalogs", harnessCatalogs.asFile.absolutePath)
}
