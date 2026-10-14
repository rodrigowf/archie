package com.assistant.core.wakeword.platform

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import com.assistant.core.audio.platform.AndroidMicSourceFactory
import com.assistant.core.audio.platform.ElapsedRealtimeClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.network.VoiceApi
import com.assistant.core.wakeword.ports.OpenAiKeyProvider
import com.assistant.core.wakeword.ports.WakeEngineDeps
import com.assistant.core.wakeword.ports.WhisperTranscriber
import com.assistant.core.wakeword.sr.AndroidSpeechRecognizerRunner
import com.assistant.core.wakeword.vosk.AndroidVoskModelSource
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/**
 * The wake loop's own thread (R4): mic reads, the Vosk recognizer and their teardown all run on
 * this single thread. One per process (the host keeps it across engine rebuilds); [close] at
 * process / service end.
 */
class WakeLoopThread : Closeable {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "wakeword-loop").apply { isDaemon = true } }.asCoroutineDispatcher()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }
}

/** Production wiring of [WakeEngineDeps] (the host composes it; A-08). */
object AndroidWakeword {
    fun deps(
        context: Context,
        thread: WakeLoopThread,
        log: VoiceLog,
        whisper: WhisperTranscriber?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): WakeEngineDeps = WakeEngineDeps(
        scope = thread.scope,
        clock = ElapsedRealtimeClock,
        log = log,
        sdkInt = sdkInt,
        // Reads run on the caller (= the loop thread), so read and release share one thread.
        mics = AndroidMicSourceFactory(),
        vosk = AndroidVoskModelSource.get(context, log, sdkInt),
        speechRecognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) AndroidSpeechRecognizerRunner(context, log, sdkInt) else null,
        whisper = whisper,
    )

    /** The Whisper key from `GET /api/config/openai-key`; null (→ fail-closed) on any error or blank key. */
    fun keyProvider(api: VoiceApi): OpenAiKeyProvider = OpenAiKeyProvider { api.openAiKey().getOrNull()?.takeIf { it.isNotEmpty() } }
}
