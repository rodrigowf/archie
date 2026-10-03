// :core:conversation — spec-12 reducer, HistoryMerger, fixture conformance (spec 14 §1.2). Owner: A-02.
plugins {
    alias(libs.plugins.archie.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(libs.kotlinx.collections.immutable)
}
