// :core:markdown — incremental markdown on commonmark-java (spec 14 §1.2, §3.2). Owner: B-02.
plugins {
    alias(libs.plugins.archie.android.library)
    alias(libs.plugins.archie.android.compose)
}

android {
    namespace = "com.assistant.core.markdown"
}

dependencies {
    api(project(":core:design"))
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.yaml.front.matter)
    implementation(libs.highlights)
}
