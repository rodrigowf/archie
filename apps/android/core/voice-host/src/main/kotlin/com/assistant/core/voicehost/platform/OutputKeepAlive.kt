package com.assistant.core.voicehost.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.assistant.core.audio.ports.VoiceLog

/**
 * Settings → Audio → "Keep audio output awake" (lite): while a wired plug is in the jack, loops a
 * buffer of digital silence so the output never goes to standby.
 *
 * Why: about 3 s after the last sound Android puts the output in standby and the codec stops driving
 * the headset jack (A300M HAL: `out_standby` → `disable_snd_device(voip-headset-others)`); powered
 * speakers on the jack then hiss until the next sound plays. An active track keeps the path enabled.
 *
 * Kept out of the voice path on purpose:
 *  - it plays zeros: nothing audible, nothing for the mic, the VAD or the echo ducker to hear;
 *  - it never requests audio focus, so the voice session's focus is untouched (and focus loss is moot);
 *  - USAGE_MEDIA: Archie's voice (call plane) and the cues are mixed on top by the system mixer;
 *  - a static buffer looped by the mixer: no thread and no CPU in this process;
 *  - only with a wired plug present (on the loudspeaker it would just keep the amp powered).
 * Side effects while it runs: media counts as "playing", so the volume keys change media volume,
 * and audioserver keeps its wakelock (the CPU does not deep-sleep) — fine for a terminal on a charger.
 */
class OutputKeepAlive(context: Context, private val log: VoiceLog) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Main thread only.
    private var enabled = false
    private var plugged = false
    private var receiver: BroadcastReceiver? = null
    private var track: AudioTrack? = null

    private val evaluate = Runnable { if (enabled && plugged) start() else stop() }

    /** From any thread; the settings flow calls it on every change of the key. */
    fun setEnabled(value: Boolean) {
        main.post {
            if (enabled == value) return@post
            enabled = value
            if (value) register() else unregister()
            evaluate.run()
        }
    }

    private fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                plugged = intent?.getIntExtra("state", 0) == 1
                // AudioService re-routes around this broadcast: start/stop a moment later.
                main.removeCallbacks(evaluate)
                main.postDelayed(evaluate, SETTLE_MS)
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        // Sticky: the returned intent is the current plug state.
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
        plugged = sticky?.getIntExtra("state", 0) == 1
        receiver = r
    }

    private fun unregister() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        main.removeCallbacks(evaluate)
    }

    private fun start() {
        if (track != null) return
        try {
            val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: FALLBACK_RATE
            val frames = rate / 2 // 0.5 s, looped forever
            val t = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(rate) // the output's own rate: no resampling in the mixer
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                frames * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            t.write(ShortArray(frames), 0, frames)
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                log.w(TAG, "could not create the silent track (state=${t.state})")
                return
            }
            t.setLoopPoints(0, frames, -1)
            t.play()
            track = t
            log.i(TAG, "started: looping silence at $rate Hz on the media stream (wired plug present)")
        } catch (e: Exception) {
            log.e(TAG, "could not start the silent track", e)
        }
    }

    private fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        t.release()
        log.i(TAG, "stopped (${if (!enabled) "setting off" else "no wired plug"})")
    }

    private companion object {
        const val TAG = "OutputKeepAlive"
        const val SETTLE_MS = 1_000L
        const val FALLBACK_RATE = 48_000
    }
}
