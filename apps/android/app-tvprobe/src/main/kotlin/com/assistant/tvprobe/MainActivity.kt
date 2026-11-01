package com.assistant.tvprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.media.AudioManager.AudioRecordingCallback
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Probe for TV remotes. Every key event is logged (logcat tag TVPROBE + on screen). While any key
 * is held (except BACK) it records from the remote's BLE mic (or the default mic) and logs the
 * level every 200 ms, so one hold of the Alexa button tells us whether (1) the app receives the key
 * and (2) the remote streams audio to an AudioRecord.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    @Volatile private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 13f
            setPadding(48, 27, 48, 27)
        }
        scroll = ScrollView(this).apply { addView(log); isFocusable = false }
        val edit = EditText(this).apply {
            hint = "Dictation test: focus here, press OK for keyboard, hold the mic button and speak"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 18f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { line("TEXT=\"$e\"") }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(48, 27, 48, 0)
            addView(TextView(this@MainActivity).apply { setTextColor(Color.YELLOW); textSize = 16f
                text = "PLAY/PAUSE = arm/disarm normal capture.  FAST-FORWARD = arm/disarm privacy capture.  While armed, hold the mic button and speak." })
            addView(edit); addView(scroll)
        }
        setContentView(root)
        (getSystemService(AUDIO_SERVICE) as AudioManager).registerAudioRecordingCallback(object : AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.forEach { line("recCfg src=${it.clientAudioSource} silenced=${it.isClientSilenced} dev=${it.audioDevice?.productName}/${it.audioDevice?.type}") }
                if (configs.isEmpty()) line("recCfg: none active")
            }
        }, Handler(Looper.getMainLooper()))
        getExternalFilesDir(null)?.listFiles()?.forEach { it.copyTo(java.io.File(filesDir, it.name), overwrite = true) }
        line("intent action=${intent?.action}")
        line("SpeechRecognizer available=${SpeechRecognizer.isRecognitionAvailable(this)}")
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            line("input id=${it.id} type=${it.type} name=${it.productName} addr=${it.address}")
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        line("onNewIntent action=${intent.action} extras=${intent.extras?.keySet()}")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) { KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP"; else -> "MULTI" }
        line("key $action code=${event.keyCode} ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} " +
            "repeat=${event.repeatCount} long=${event.isLongPress} src=0x${Integer.toHexString(event.source)} dev=${event.deviceId}")
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (recording) recording = false else startRecording(privacy = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() { super.onPause(); line("onPause") }
    override fun onResume() { super.onResume(); line("onResume") }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); line("focus=$hasFocus") }

    private fun startRecording(privacy: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { line("no RECORD_AUDIO"); return }
        recording = true
        thread(name = "probe-rec") {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val src = if (privacy) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rec = try {
                if (privacy) AudioRecord.Builder().setAudioSource(src)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, 3200) * 2).setPrivacySensitive(true).build()
                else AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
            } catch (t: Throwable) { line("AudioRecord failed: $t"); recording = false; return@thread }
            rec.startRecording()
            line("ARMED src=$src privacy=$privacy state=${rec.state} recordingState=${rec.recordingState}")
            val buf = ShortArray(1600)
            var burst: java.io.ByteArrayOutputStream? = null; var burstNo = 0; var last = 0L; var peak = -120.0
            fun close() {
                val b = burst ?: return
                val f = java.io.File(getExternalFilesDir(null), "burst_${System.currentTimeMillis()}_${if (privacy) "priv" else "std"}.wav")
                writeWav(f, b.toByteArray(), rate)
                line("BURST #$burstNo ${b.size() / 32} ms peak=%.1f dBFS -> ${f.name}".format(peak))
                burst = null
            }
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { line("read=$n"); Thread.sleep(50); continue }
                val now = android.os.SystemClock.elapsedRealtime()
                if (burst != null && now - last > 500) close()
                if (burst == null) { burst = java.io.ByteArrayOutputStream(); burstNo++; peak = -120.0; line("burst #$burstNo start (gap ${now - last} ms)") }
                last = now
                var sum = 0.0; for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val db = 20 * log10(sqrt(sum / n) / 32768.0 + 1e-9); if (db > peak) peak = db
                val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putShort(buf[i])
                burst!!.write(bb.array())
            }
            close(); rec.stop(); rec.release(); line("DISARMED")
        }
    }

    private fun writeWav(f: java.io.File, pcm: ByteArray, rate: Int) {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVEfmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(pcm.size)
        f.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    private fun line(s: String) {
        Log.i("TVPROBE", s)
        runOnUiThread {
            log.append(s + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
