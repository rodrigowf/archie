// :core:protocol — ProtocolCodec, ServerFrame/ClientFrame, RestDto (spec 14 §1.2). Owner: A-02.
plugins {
    alias(libs.plugins.archie.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
}
