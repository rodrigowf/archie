package com.assistant.mictest

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mic test: live VU meter + level history, record/play back what the mic heard, and a log of
 * every wired-headset plug event (a bad contact shows up as plug/unplug flicker or dropouts).
 *
 * On the A300M (Samsung, Android 5): VOICE_RECOGNITION uses the wired headset mic when a 4-pole
 * plug is detected (what Archie lite records with); CAMCORDER always uses the phone's own mic.
 * Only one app can record at a time on Android 5, so Archie lite's listening must be off.
 */
class MainActivity : Activity() {

    private lateinit var audio: AudioManager
    private lateinit var headsetText: TextView
    private lateinit var statusText: TextView
    private lateinit var readout: TextView
    private lateinit var counters: TextView
    private lateinit var meter: MeterView
    private lateinit var monitorButton: Button
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var recInfo: TextView
    private lateinit var logText: TextView
    private lateinit var sourceGroup: RadioGroup
    private lateinit var boostGroup: RadioGroup
    private lateinit var outputGroup: RadioGroup

    private var capture: Capture? = null
    private var playback: Playback? = null

    private val recBuffer = ShortArray(SAMPLE_RATE * MAX_RECORD_S)
    @Volatile private var recLen = 0
    @Volatile private var recording = false

    private var plugEvents = 0
    private var lastPlugState: Int? = null
    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Per-run counters, reset on every monitor start.
    private var blocks = 0
    private var dropouts = 0
    private var clippedBlocks = 0
    private var clicks = 0
    private var maxPeakDb = MeterView.FLOOR_DB

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onHeadsetIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        log("app started")
        // Sticky broadcast: the returned intent is the current headset state.
        registerReceiver(headsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))?.let { onHeadsetIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        if (hasMicPermission()) startMonitor() else requestMicPermission()
    }

    override fun onPause() {
        super.onPause()
        // Release the mic so Archie lite can use it again.
        stopPlayback()
        stopMonitor()
    }

    override fun onDestroy() {
        unregisterReceiver(headsetReceiver)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMonitor()
        else setStatus("Microphone permission denied", Color.rgb(229, 57, 53))
    }

    private fun hasMicPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    // ---------------------------------------------------------------- headset

    private fun onHeadsetIntent(intent: Intent) {
        val state = intent.getIntExtra("state", -1)
        val mic = intent.getIntExtra("microphone", -1)
        val name = intent.getStringExtra("name").orEmpty()
        val plugged = state == 1
        val desc = when {
            !plugged -> "NOT plugged — phone's own mic"
            mic == 1 -> "PLUGGED, with mic ($name)"
            else -> "PLUGGED, but NO mic detected ($name) — 3-pole or bad contact"
        }
        headsetText.text = "Wired headset: $desc\nPlug changes since start: $plugEvents"
        headsetText.setTextColor(
            when {
                !plugged -> Color.rgb(200, 200, 200)
                mic == 1 -> Color.rgb(76, 175, 80)
                else -> Color.rgb(255, 152, 0)
            },
        )
        val key = state * 10 + mic
        if (lastPlugState != null && lastPlugState != key) {
            plugEvents++
            headsetText.text = "Wired headset: $desc\nPlug changes since start: $plugEvents"
            log("HEADSET ${if (plugged) "plugged" else "UNPLUGGED"} mic=${mic == 1} name=$name")
            // The input device changes with the plug: reopen so the meter follows it.
            if (capture != null) restartMonitor()
        } else if (lastPlugState == null) {
            log("headset at start: ${if (plugged) "plugged" else "not plugged"} mic=${mic == 1} name=$name")
        }
        lastPlugState = key
    }

    // ---------------------------------------------------------------- monitor

    private fun selectedSource(): Int = when (sourceGroup.checkedRadioButtonId) {
        ID_SRC_CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        ID_SRC_MIC -> MediaRecorder.AudioSource.MIC
        ID_SRC_VOICE_COMM -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun startMonitor() {
        if (capture != null) return
        blocks = 0; dropouts = 0; clippedBlocks = 0; clicks = 0
        maxPeakDb = MeterView.FLOOR_DB
        meter.reset()
        updateCounters()
        val c = Capture(selectedSource())
        capture = c
        monitorButton.text = "Stop meter"
        setStatus("Opening mic (${sourceName(c.source)})…", Color.rgb(200, 200, 200))
        c.start()
    }

    private fun stopMonitor() {
        if (recording) stopRecording()
        val c = capture ?: return
        capture = null
        c.running = false
        c.join(500)
        monitorButton.text = "Start meter"
        setStatus("Meter stopped (mic released)", Color.rgb(200, 200, 200))
    }

    private fun restartMonitor() {
        stopMonitor()
        startMonitor()
    }

    /** UI thread, once per block. */
    private fun onBlock(c: Capture, rmsRaw: Double, peakRaw: Int, dropout: Boolean, clipped: Boolean, newClicks: Int) {
        if (capture !== c) return
        blocks++
        // Ignore the first blocks: some HALs deliver zeros while the input warms up.
        val counted = blocks > WARMUP_BLOCKS
        if (counted && dropout) dropouts++
        if (counted && clipped) clippedBlocks++
        if (counted) clicks += newClicks
        val rmsDb = toDb(rmsRaw)
        val peakDb = toDb(peakRaw.toDouble())
        if (peakDb > maxPeakDb) maxPeakDb = peakDb
        meter.push(rmsDb, peakDb, counted && dropout, clipped)
        readout.text = String.format(
            Locale.US, "RMS %6.1f dBFS   peak %6.1f dBFS\nraw: rms %5.0f   peak %5d  (of 32767)",
            rmsDb, peakDb, rmsRaw, peakRaw,
        )
        if (blocks % 8 == 0) updateCounters()
        if (recording) recInfo.text = recordingInfo()
    }

    private fun updateCounters() {
        val seconds = blocks * BLOCK / SAMPLE_RATE.toFloat()
        counters.text = String.format(
            Locale.US,
            "%.0f s monitored · max peak %.1f dBFS\ndropouts (digital silence): %d · clipped blocks: %d · clicks: %d",
            seconds, maxPeakDb, dropouts, clippedBlocks, clicks,
        )
        counters.setTextColor(if (dropouts + clicks > 0) Color.rgb(255, 152, 0) else Color.rgb(170, 170, 180))
    }

    private fun onCaptureFailed(c: Capture, message: String) {
        if (capture === c) {
            capture = null
            monitorButton.text = "Start meter"
        }
        if (recording) stopRecording()
        setStatus(message, Color.rgb(229, 57, 53))
        log(message)
    }

    private inner class Capture(val source: Int) : Thread("mic-capture") {
        @Volatile var running = true

        override fun run() {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, BLOCK * 2 * 8))
            } catch (e: Exception) {
                fail("Could not open mic: ${e.message}")
                return
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                fail("Could not open mic (AudioRecord not initialized)")
                return
            }
            try {
                rec.startRecording()
            } catch (e: IllegalStateException) {
                rec.release()
                fail("Could not start mic: ${e.message}")
                return
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                fail("Mic busy — another app is recording. Turn off Archie lite's listening (wake word / voice) and press Start meter.")
                return
            }
            runOnUiThread {
                if (capture === this) {
                    setStatus("Listening: ${sourceName(source)} · ${SAMPLE_RATE / 1000} kHz", Color.rgb(76, 175, 80))
                    log("meter started: ${sourceName(source)}")
                }
            }
            val buf = ShortArray(BLOCK)
            var prev = 0
            try {
                while (running) {
                    val n = rec.read(buf, 0, BLOCK)
                    if (n < 0) {
                        fail("Mic read error $n")
                        break
                    }
                    if (n == 0) continue
                    var sumSq = 0.0
                    var peak = 0
                    var nonZero = false
                    var clipped = false
                    var newClicks = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        val a = abs(s)
                        sumSq += s.toDouble() * s
                        if (a > peak) peak = a
                        if (s != 0) nonZero = true
                        if (a >= CLIP_LEVEL) clipped = true
                        if (abs(s - prev) > CLICK_JUMP) newClicks++
                        prev = s
                    }
                    if (recording) appendRecording(buf, n)
                    val rms = sqrt(sumSq / n)
                    val dropout = !nonZero
                    runOnUiThread { onBlock(this, rms, peak, dropout, clipped, newClicks) }
                }
            } finally {
                try { rec.stop() } catch (_: IllegalStateException) { }
                rec.release()
            }
        }

        private fun fail(message: String) {
            running = false
            runOnUiThread { onCaptureFailed(this, message) }
        }
    }

    // ---------------------------------------------------------------- record / play

    private fun appendRecording(buf: ShortArray, n: Int) {
        val room = recBuffer.size - recLen
        val take = minOf(room, n)
        System.arraycopy(buf, 0, recBuffer, recLen, take)
        recLen += take
        if (take < n || recLen >= recBuffer.size) {
            recording = false
            runOnUiThread { stopRecording() }
        }
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        stopPlayback()
        if (capture == null) startMonitor()
        recLen = 0
        recording = true
        recordButton.text = "■ Stop recording"
        playButton.isEnabled = false
        recInfo.text = recordingInfo()
        log("recording started (${sourceName(selectedSource())})")
    }

    private fun stopRecording() {
        recording = false
        recordButton.text = "● Record"
        playButton.isEnabled = recLen > 0
        recInfo.text = recordingInfo()
        if (recLen > 0) log(String.format(Locale.US, "recorded %.1f s", recLen / SAMPLE_RATE.toFloat()))
    }

    private fun recordingInfo(): String {
        val secs = recLen / SAMPLE_RATE.toFloat()
        return if (recording) String.format(Locale.US, "Recording… %.1f s (max %d s)", secs, MAX_RECORD_S)
        else if (recLen == 0) "Nothing recorded yet"
        else String.format(Locale.US, "Recording: %.1f s — press Play", secs)
    }

    private fun selectedBoost(): Int = when (boostGroup.checkedRadioButtonId) {
        ID_BOOST_4 -> 4
        ID_BOOST_16 -> 16
        ID_BOOST_32 -> 32
        else -> 1
    }

    private fun togglePlayback() {
        if (playback != null) {
            stopPlayback()
            return
        }
        if (recLen == 0) return
        val gain = selectedBoost()
        val data = ShortArray(recLen)
        for (i in 0 until recLen) {
            data[i] = (recBuffer[i] * gain).coerceIn(-32768, 32767).toShort()
        }
        val loudspeaker = outputGroup.checkedRadioButtonId == ID_OUT_SPEAKER
        val p = Playback(data, loudspeaker)
        playback = p
        playButton.text = "■ Stop playing"
        recordButton.isEnabled = false
        log("playing ${String.format(Locale.US, "%.1f", recLen / SAMPLE_RATE.toFloat())} s, boost x$gain, " +
            if (loudspeaker) "phone loudspeaker" else "media route")
        p.start()
    }

    private fun stopPlayback() {
        val p = playback ?: return
        p.running = false
        p.join(500)
        onPlaybackEnded(p)
    }

    private fun onPlaybackEnded(p: Playback) {
        if (playback !== p) return
        playback = null
        playButton.text = "▶ Play"
        recordButton.isEnabled = true
    }

    private inner class Playback(val data: ShortArray, val loudspeaker: Boolean) : Thread("mic-playback") {
        @Volatile var running = true

        override fun run() {
            val oldMode = audio.mode
            val oldSpeaker = audio.isSpeakerphoneOn
            // STREAM_MUSIC follows the media route (headset jack if plugged, else speaker / Bluetooth).
            // Loudspeaker: in-call mode + speakerphone forces the phone's own speaker.
            val stream = if (loudspeaker) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            if (loudspeaker) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.isSpeakerphoneOn = true
            }
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                stream, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, BLOCK * 2 * 8), AudioTrack.MODE_STREAM,
            )
            try {
                track.play()
                var off = 0
                while (running && off < data.size) {
                    val n = track.write(data, off, minOf(BLOCK * 4, data.size - off))
                    if (n <= 0) break
                    off += n
                }
                // Let the tail drain.
                while (running && track.playbackHeadPosition < data.size) sleep(20)
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: IllegalStateException) { }
                track.release()
                if (loudspeaker) {
                    audio.isSpeakerphoneOn = oldSpeaker
                    audio.mode = oldMode
                }
                runOnUiThread { onPlaybackEnded(this) }
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(16, 16, 20))
        }
        fun text(size: Float, color: Int = Color.rgb(230, 230, 235), bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        fun section(title: String) = text(13f, Color.rgb(130, 170, 255), bold = true).apply { this.text = title }

        root.addView(text(22f, bold = true).apply { this.text = "Mic Test" })
        headsetText = text(16f, bold = true)
        root.addView(headsetText)
        statusText = text(14f)
        root.addView(statusText)

        root.addView(section("MIC SOURCE"))
        sourceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun radio(group: RadioGroup, id: Int, label: String) = group.addView(RadioButton(this).apply {
            this.id = id
            this.text = label
            setTextColor(Color.rgb(230, 230, 235))
        })
        radio(sourceGroup, ID_SRC_VOICE_REC, "Voice recognition — headset mic if plugged (what Archie uses)")
        radio(sourceGroup, ID_SRC_CAMCORDER, "Camcorder — phone's own mic")
        radio(sourceGroup, ID_SRC_MIC, "Mic (default)")
        radio(sourceGroup, ID_SRC_VOICE_COMM, "Voice communication (echo cancel / noise suppression)")
        sourceGroup.check(ID_SRC_VOICE_REC)
        sourceGroup.setOnCheckedChangeListener { _, _ -> if (capture != null) restartMonitor() }
        root.addView(sourceGroup)

        root.addView(section("LEVEL"))
        monitorButton = Button(this).apply {
            text = "Start meter"
            setOnClickListener { if (capture != null) stopMonitor() else if (hasMicPermission()) startMonitor() else requestMicPermission() }
        }
        root.addView(monitorButton)
        readout = text(16f).apply { typeface = Typeface.MONOSPACE }
        root.addView(readout)
        meter = MeterView(this)
        root.addView(meter)
        counters = text(13f)
        root.addView(counters)

        root.addView(section("RECORD / PLAY"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordButton = Button(this).apply { text = "● Record"; setOnClickListener { toggleRecording() } }
        playButton = Button(this).apply { text = "▶ Play"; isEnabled = false; setOnClickListener { togglePlayback() } }
        val half = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(recordButton, half)
        row.addView(playButton, LinearLayout.LayoutParams(half))
        root.addView(row)
        recInfo = text(14f).apply { text = "Nothing recorded yet" }
        root.addView(recInfo)

        root.addView(text(13f, Color.rgb(170, 170, 180)).apply { text = "Playback boost (quiet mics are hard to hear at x1):" })
        boostGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        radio(boostGroup, ID_BOOST_1, "x1")
        radio(boostGroup, ID_BOOST_4, "x4")
        radio(boostGroup, ID_BOOST_16, "x16")
        radio(boostGroup, ID_BOOST_32, "x32")
        boostGroup.check(ID_BOOST_4)
        root.addView(boostGroup)

        root.addView(text(13f, Color.rgb(170, 170, 180)).apply { text = "Play through:" })
        outputGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        radio(outputGroup, ID_OUT_SPEAKER, "Phone loudspeaker")
        radio(outputGroup, ID_OUT_MEDIA, "Media route (headset jack / Bluetooth)")
        outputGroup.check(ID_OUT_SPEAKER)
        root.addView(outputGroup)

        root.addView(section("LOG  (wiggle the plug and watch for plug changes / dropouts)"))
        logText = text(12f, Color.rgb(170, 170, 180)).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.START }
        root.addView(logText)

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 16, 20))
            addView(root)
        }
    }

    private fun setStatus(message: String, color: Int) {
        statusText.text = message
        statusText.setTextColor(color)
    }

    private fun log(message: String) {
        logLines.addFirst("${clock.format(Date())}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
        logText.text = logLines.joinToString("\n")
    }

    private fun sourceName(source: Int) = when (source) {
        MediaRecorder.AudioSource.CAMCORDER -> "camcorder (phone mic)"
        MediaRecorder.AudioSource.MIC -> "mic"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice communication"
        else -> "voice recognition"
    }

    private fun toDb(raw: Double): Float =
        if (raw < 1.0) MeterView.FLOOR_DB else max(MeterView.FLOOR_DB, (20 * log10(raw / 32768.0)).toFloat())

    companion object {
        private const val SAMPLE_RATE = 16000 // same rate Archie's voice capture uses
        private const val BLOCK = 512 // 32 ms
        private const val MAX_RECORD_S = 30
        private const val WARMUP_BLOCKS = 8
        private const val CLIP_LEVEL = 32000
        private const val CLICK_JUMP = 20000 // sample-to-sample jump no real voice makes at 16 kHz
        private const val MAX_LOG_LINES = 40
        private const val REQ_MIC = 1

        private const val ID_SRC_VOICE_REC = 101
        private const val ID_SRC_CAMCORDER = 102
        private const val ID_SRC_MIC = 103
        private const val ID_SRC_VOICE_COMM = 104
        private const val ID_BOOST_1 = 201
        private const val ID_BOOST_4 = 202
        private const val ID_BOOST_16 = 203
        private const val ID_BOOST_32 = 204
        private const val ID_OUT_SPEAKER = 301
        private const val ID_OUT_MEDIA = 302
    }
}
