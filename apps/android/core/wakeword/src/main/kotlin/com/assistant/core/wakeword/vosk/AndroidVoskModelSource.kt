package com.assistant.core.wakeword.vosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.policy.voskResultText
import com.assistant.core.wakeword.ports.RecognizerText
import com.assistant.core.wakeword.ports.StreamingRecognizer
import com.assistant.core.wakeword.ports.StreamingRecognizerFactory
import com.assistant.core.wakeword.ports.VoskModelSource
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Loads the bundled `vosk-model-small-en-us-0.15` (old `VoskModelLoader.getModel`): extract the
 * asset tree to `filesDir/vosk-model/` when the `.stamp` differs, load the Lollipop stderr shim
 * below API 23, then `Model(path)`. One process-wide instance ([get]); the model is cached and a
 * failure is sticky for the process (every retry costs 0.5–2 s and the environment won't fix itself).
 *
 * Extraction and `Model()` run on `Dispatchers.IO` (a few seconds, once). The `Model` is
 * thread-safe; the [StreamingRecognizer]s it creates are not and stay on the wake loop (R4).
 */
class AndroidVoskModelSource private constructor(
    private val context: Context,
    private val log: VoiceLog,
    private val sdkInt: Int,
) : VoskModelSource {
    private val mutex = Mutex()

    @Volatile private var cached: StreamingRecognizerFactory? = null

    @Volatile private var loadFailed = false
    private var shimAttempted = false

    override suspend fun load(): StreamingRecognizerFactory? {
        cached?.let { return it }
        if (loadFailed) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cached?.let { return@withLock it }
                if (loadFailed) return@withLock null
                val started = SystemClock.elapsedRealtime()
                val target = File(context.filesDir, VoskModelFiles.extractDirName)
                try {
                    if (VoskModelFiles.shouldExtract(target, VoskModelFiles.stamp)) {
                        log.d(TAG, "Extracting Vosk model to ${target.absolutePath}")
                        val sources = assetSources(context.assets)
                        VoskModelFiles.extractTree(sources, target, VoskModelFiles.stamp)
                        log.d(TAG, "Vosk model extracted (${sources.size} files) in ${SystemClock.elapsedRealtime() - started} ms")
                    } else {
                        log.d(TAG, "Vosk model already extracted at ${target.absolutePath}")
                    }
                    maybeLoadStderrShim() // must precede the first libvosk dlopen (Model())
                    val model = Model(target.absolutePath)
                    log.d(TAG, "Vosk model loaded (total ${SystemClock.elapsedRealtime() - started} ms)")
                    VoskRecognizerFactory(model, log).also { cached = it }
                } catch (t: Throwable) {
                    log.e(TAG, "Vosk model load failed", t)
                    loadFailed = true
                    null
                }
            }
        }
    }

    private fun assetSources(assets: AssetManager): Map<String, () -> InputStream> =
        VoskModelFiles.assetFileList { path -> assets.list(path)?.toList() ?: emptyList() }
            .associateWith { rel -> { assets.open("${VoskModelFiles.assetRoot}/$rel") } }

    /**
     * Lollipop polyfill (inv04 §5.1): Bionic < 23 has no exported `stderr`, which libvosk.so
     * references. Load the shim, re-publish it RTLD_GLOBAL, then pre-load libvosk.so RTLD_GLOBAL
     * so JNA's later `loadLibrary("vosk")` reuses it. Never on M+ (the shim cannot load there).
     */
    private fun maybeLoadStderrShim() {
        if (shimAttempted) return
        shimAttempted = true
        if (sdkInt >= WakeTuning.VOSK_SHIM_MAX_SDK_EXCLUSIVE) {
            log.d(TAG, "Stderr shim skipped — SDK_INT=$sdkInt >= ${WakeTuning.VOSK_SHIM_MAX_SDK_EXCLUSIVE}")
            return
        }
        try {
            System.loadLibrary(VoskStderrShim.LIBRARY)
            val publishRc = VoskStderrShim.publishStderrShimGlobally()
            val voskSo = "${context.applicationInfo.nativeLibraryDir}/libvosk.so"
            val preloadRc = VoskStderrShim.preloadVoskGlobally(voskSo)
            log.d(TAG, "Stderr shim loaded (SDK_INT=$sdkInt, publishGlobal rc=$publishRc, preloadVosk[$voskSo] rc=$preloadRc)")
        } catch (t: Throwable) {
            log.e(TAG, "Stderr shim failed to load — Vosk will likely fail next", t)
        }
    }

    companion object {
        private const val TAG = "VoskModelLoader"

        // Holds only the application context (see [get]), so the static reference cannot leak an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AndroidVoskModelSource? = null

        /** The process-wide loader (the old `VoskModelLoader` object). */
        fun get(context: Context, log: VoiceLog, sdkInt: Int = Build.VERSION.SDK_INT): AndroidVoskModelSource =
            instance ?: synchronized(this) {
                instance ?: AndroidVoskModelSource(context.applicationContext, log, sdkInt).also { instance = it }
            }
    }
}

/** Creates one constrained-grammar `Recognizer` per recognition cycle over the shared `Model`. */
class VoskRecognizerFactory(private val model: Model, private val log: VoiceLog) : StreamingRecognizerFactory {
    override fun create(grammarJson: String, sampleRateHz: Int): StreamingRecognizer =
        VoskStreamingRecognizer(Recognizer(model, sampleRateHz.toFloat(), grammarJson), log)
}

/** Thin `org.vosk.Recognizer` adapter (old `VoskWakeWordEngine.feed` / `peekText` / `close`). NOT thread-safe. */
private class VoskStreamingRecognizer(private val recognizer: Recognizer, private val log: VoiceLog) : StreamingRecognizer {
    private var closed = false

    override fun accept(samples: ShortArray, count: Int): RecognizerText {
        if (closed || count <= 0) return RecognizerText("", isFinal = false)
        val isFinal = recognizer.acceptWaveForm(samples, count)
        val json = if (isFinal) recognizer.finalResult else recognizer.partialResult
        return RecognizerText(voskResultText(json), isFinal)
    }

    override fun partial(): String = if (closed) "" else voskResultText(recognizer.partialResult)

    override fun reset() {
        if (!closed) recognizer.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            recognizer.close()
        } catch (t: Throwable) {
            log.w("VoskWakeWordEngine", "Recognizer close failed: ${t.message}")
        }
    }
}

/** JNI entry points of `libvosk-stderr-shim.so` (`src/main/cpp/vosk_stderr_shim.c`). */
internal object VoskStderrShim {
    const val LIBRARY = "vosk-stderr-shim"

    /** Re-dlopens the shim `RTLD_NOW | RTLD_GLOBAL`; 0 on success, -1 on failure. */
    external fun publishStderrShimGlobally(): Int

    /** dlopens libvosk.so `RTLD_NOW | RTLD_GLOBAL` once the shim is global; 0 on success, -1 on failure. */
    external fun preloadVoskGlobally(path: String): Int
}
