package com.assistant.archie.tv.voice

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.assistant.archie.tv.graph.TvGraphOwner
import com.assistant.archie.tv.shell.Conversation
import com.assistant.core.data.ConversationKey
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

/**
 * Debug-build voice hooks (spec 15 §4, WP T4). Every action logs to `ArchieTv/Debug`.
 *
 * ```
 * adb shell am broadcast -n com.assistant.archie.tv/.voice.DebugMicReceiver -a com.assistant.archie.tv.DEBUG_MIC_BURST --es wav hello.wav
 * ```
 * - `DEBUG_MIC_BURST` `--es wav <file>`: plays a WAV (any rate, mono/stereo PCM16; a bare name is
 *   looked up in the app's files dir, then external files dir) through `RemoteMicSource.inject` —
 *   exactly the path of a real held mic button, routed by what is on screen.
 * - `DEBUG_NEW_ARCHIE`: starts a new Archie conversation (never inject into a real one).
 * - `DEBUG_OPEN_AGENT` [`--es local_id <id>`]: a new agent session (or the given one) on screen.
 * - `DEBUG_CLOSE_AGENT` `--es local_id <id>`: closes that agent session on the server.
 * - `DEBUG_LIVE_VOICE`: the Play/Pause toggle.
 * - `DEBUG_SET_SERVER` `--es url <url>`: switch servers (Settings → Connection's path).
 * - `DEBUG_MIC_SPIKE`: the T4 transport spike — opens the WebRTC ADM's clock source
 *   (VOICE_COMMUNICATION, 48 kHz) next to the armed remote recorder for 3 s and logs whether it
 *   streams in real time and whether the platform silences either client.
 */
class DebugMicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val voice = (app as? TvVoiceOwner)?.tvVoice ?: return log("no TvVoice")
        val graph = (app as TvGraphOwner).graph
        when (intent.action?.substringAfterLast('.')) {
            "DEBUG_MIC_BURST" -> {
                val name = intent.getStringExtra("wav") ?: return log("missing --es wav")
                val file = listOfNotNull(File(name).takeIf { it.isAbsolute }, File(app.filesDir, name), app.getExternalFilesDir(null)?.let { File(it, name) })
                    .firstOrNull { it.isFile } ?: return log("no such WAV: $name")
                val parsed = Wav.parse(file.readBytes()) ?: return log("not a PCM16 WAV: $file")
                val pcm = Wav.resample(parsed.first, parsed.second, TvVoiceTuning.SAMPLE_RATE_HZ)
                log("injecting ${file.name}: ${pcm.size * 1000L / TvVoiceTuning.SAMPLE_RATE_HZ} ms, screen=${graph.navHolder.current.value}")
                voice.mic.inject(pcm)
            }
            "DEBUG_NEW_ARCHIE" -> {
                graph.conversations.newArchie()
                log("new Archie conversation armed")
            }
            "DEBUG_OPEN_AGENT" -> {
                val given = intent.getStringExtra("local_id")
                val nav = graph.navHolder.navigator ?: return log("no shell composed (open the app first)")
                val key = if (given != null) ConversationKey.agent(given) else graph.conversations.newAgent()
                val localId = key.value.removePrefix("agent:")
                graph.navHolder.bringToFront?.invoke()
                android.os.Handler(android.os.Looper.getMainLooper()).post { nav.push(Conversation(localId = localId, archie = false)) }
                log("agent on screen: local_id=$localId")
            }
            "DEBUG_CLOSE_AGENT" -> {
                val id = intent.getStringExtra("local_id") ?: return log("missing --es local_id")
                val pending = goAsync()
                thread {
                    val ok = runBlocking { graph.conversations.close(ConversationKey.agent(id)) }
                    log("closed agent $id: $ok")
                    pending.finish()
                }
            }
            "DEBUG_SET_SERVER" -> {
                val url = intent.getStringExtra("url") ?: return log("missing --es url")
                val pending = goAsync()
                thread {
                    runBlocking { graph.connectionModel.switchTo(url) }
                    log("server → $url (now ${graph.currentServerUrl()})")
                    pending.finish()
                }
            }
            "DEBUG_LIVE_VOICE" -> android.os.Handler(android.os.Looper.getMainLooper()).post { log("live voice toggle: ${graph.liveVoice.toggle()}") }
            "DEBUG_MIC_SPIKE" -> thread(name = "tv-mic-spike") { spike(app) }
            else -> log("unknown action ${intent.action}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun spike(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val voice = (context.applicationContext as TvVoiceOwner).tvVoice
        log("SPIKE remote mic state=${voice?.mic?.state?.value}")
        fun configs(tag: String) = am.activeRecordingConfigurations.forEach {
            log("SPIKE $tag recCfg src=${it.clientAudioSource} silenced=${if (android.os.Build.VERSION.SDK_INT >= 29) it.isClientSilenced else "?"} dev=${it.audioDevice?.type}/${it.audioDevice?.productName} fmt=${it.format.sampleRate}")
        }
        configs("before")
        for ((source, name) in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VC", MediaRecorder.AudioSource.MIC to "MIC", MediaRecorder.AudioSource.DEFAULT to "DEFAULT")) {
            for (rate in listOf(48_000, 16_000)) {
                try {
                    probe(am, source, name, rate, ::configs)
                } catch (e: Exception) {
                    log("SPIKE $name@$rate failed: ${e.message}")
                }
            }
        }
        configs("after")
    }

    @SuppressLint("MissingPermission")
    private fun probe(am: AudioManager, source: Int, name: String, rate: Int, configs: (String) -> Unit) {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("SPIKE $name@$rate: not initialized (min=$min)")
            rec.release()
            return
        }
        rec.startRecording()
        val buf = ShortArray(rate / 100) // 10 ms, like the ADM
        val t0 = SystemClock.elapsedRealtime()
        var samples = 0L
        var sum = 0.0
        var reads = 0
        var maxGap = 0L
        var last = t0
        val watchdog = thread { Thread.sleep(2_500); if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        while (SystemClock.elapsedRealtime() - t0 < 2_000) {
            val n = rec.read(buf, 0, buf.size)
            val now = SystemClock.elapsedRealtime()
            maxGap = maxOf(maxGap, now - last)
            last = now
            if (n <= 0) break
            samples += n
            reads++
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            if (reads == 50) configs("$name@$rate during")
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
        val rms = if (samples > 0) sqrt(sum / samples) else -1.0
        log("SPIKE $name@$rate: $samples samples in $elapsed ms = ${samples * 1000 / elapsed} Hz, reads=$reads, max gap $maxGap ms, rms=${"%.1f".format(rms)}")
        runCatching { rec.stop() }
        rec.release()
        watchdog.join()
    }

    private fun log(s: String) {
        Log.i("ArchieTv/Debug", s)
    }
}
