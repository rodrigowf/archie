package com.assistant.core.audio.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import com.assistant.core.audio.ports.MicFailure
import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MicSpec
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Opens mono PCM16 AudioRecords (old `MicCapture.start`, `WakeWordDetector.startSilenceMonitor`).
 * The source comes from [com.assistant.core.audio.policy.DefaultMicSourcePolicy] via [MicSpec];
 * the buffer from [com.assistant.core.audio.policy.DefaultBufferSizing] (callers compute both).
 *
 * [readContext] is where the blocking `AudioRecord.read` runs. Pass the capture loop's own
 * single-thread dispatcher so read and [MicSource.release] stay on one thread (R4); the default
 * runs the read on the caller's thread.
 */
class AndroidMicSourceFactory(
    private val readContext: CoroutineContext = EmptyCoroutineContext,
) : MicSourceFactory {

    override fun minBufferBytes(sampleRateHz: Int): Int =
        AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is the app's; a denial maps to PERMISSION_DENIED.
    override fun open(spec: MicSpec): MicOpenResult {
        val record = try {
            AudioRecord(spec.source.androidValue, spec.sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, spec.bufferBytes)
        } catch (_: SecurityException) {
            return MicOpenResult.Failed(MicFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            return MicOpenResult.Failed(MicFailure.CONSTRUCTOR_THREW)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return MicOpenResult.Failed(MicFailure.NOT_INITIALIZED)
        }
        val started = try {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: Exception) {
            false
        }
        if (!started) {
            record.release()
            return MicOpenResult.Failed(MicFailure.START_RECORDING_FAILED)
        }
        return MicOpenResult.Opened(AndroidMicSource(spec, record, readContext))
    }
}

private class AndroidMicSource(
    override val spec: MicSpec,
    private val record: AudioRecord,
    private val readContext: CoroutineContext,
) : MicSource {
    @Volatile private var released = false

    override suspend fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        if (released) return MicSource.ERROR_INVALID_OPERATION
        return if (readContext === EmptyCoroutineContext) {
            record.read(buffer, offset, length)
        } else {
            withContext(readContext) { record.read(buffer, offset, length) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (_: Exception) {}
        record.release()
    }
}
