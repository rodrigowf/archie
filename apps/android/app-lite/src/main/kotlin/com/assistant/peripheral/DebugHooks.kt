package com.assistant.peripheral

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * DEBUG BUILDS ONLY: every hook is behind `BuildConfig.DEBUG` (a compile-time constant, so R8
 * removes it from release builds). Not a `src/debug` source set: the repo's root `.gitignore`
 * ignores every `debug/` directory.
 *
 * The G-01 lite script (spec 14 §6.2) needs the Whisper confirm gate to answer without calling
 * OpenAI. `adb shell setprop debug.archie.whisper_url http://10.0.2.2:<port>/v1/audio/transcriptions`
 * redirects the real `WhisperClient`'s POST to a local mock; everything else (key fetch, upload,
 * verdict, the `Whisper CONFIRMED` log marker) runs unchanged. Empty property → no effect.
 */
object DebugHooks {
    private const val TAG = "ArchieDebug"
    private const val WHISPER_URL_PROP = "debug.archie.whisper_url"
    private const val WHISPER_HOST = "api.openai.com"

    fun okHttpBase(): OkHttpClient? = if (!BuildConfig.DEBUG) null else OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val override = if (req.url.host == WHISPER_HOST) systemProperty(WHISPER_URL_PROP).toHttpUrlOrNull() else null
            if (override == null) chain.proceed(req) else {
                Log.w(TAG, "Whisper request redirected to $override ($WHISPER_URL_PROP)")
                chain.proceed(req.newBuilder().url(override).build())
            }
        })
        .build()

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    } catch (_: Exception) {
        ""
    }
}
