// :core:network — HttpStack, SocketClient, ArchieApi, VoiceApi, discovery (spec 14 §1.2). Owner: A-03.
plugins {
    alias(libs.plugins.archie.android.library.legacy21)
}

android {
    namespace = "com.assistant.core.network"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
