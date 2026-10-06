package com.assistant.archie.system

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.archie.R
import com.assistant.archie.graph.GraphOwner
import com.assistant.archie.shell.MainActivity
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.service.VoiceHostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Default digital assistant app" support (spec 14 §2.8; fixes inv04 B6). The system binds this
 * service once Archie is the selected assistant; the assist gesture then shows an
 * [ArchieVoiceInteractionSession].
 */
class ArchieVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService ready")
    }

    /** Assist from the lock screen behaves like the assist gesture (API 26+). */
    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "voice assist from keyguard")
        showSession(Bundle(), 0)
    }

    private companion object {
        const val TAG = "ArchieVIS"
    }
}

class ArchieVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ArchieVoiceInteractionSession(this)
}

/**
 * The assist overlay: [onShow] is a foreground context, so it may start the microphone FGS. It
 * hands [Trigger.ASSIST] to the single ingress and shows a compact panel — live status, **End**,
 * **Open Archie** — that closes itself when voice ends. Without RECORD_AUDIO it opens the app,
 * which asks with the rationale first (spec 14 §2.9).
 */
class ArchieVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var status: TextView? = null

    private val graph get() = (context.applicationContext as? GraphOwner)?.graph

    override fun onCreateContentView(): View {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = context.getColor(R.color.archie_window_background)
        val fg = if (night) Color.WHITE else Color.BLACK
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        status = TextView(context).apply {
            text = "Archie · Connecting…"
            setTextColor(fg)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        panel.addView(status)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Button(context).apply {
            text = "End"
            setOnClickListener {
                graph?.voiceHost?.stopVoice()
                hide()
            }
        })
        row.addView(Button(context).apply {
            text = "Open Archie"
            setOnClickListener { openApp(null) }
        })
        panel.addView(row)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { })
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { hide() } // tap outside the panel closes the overlay; voice keeps running
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val g = graph ?: return hide()
        val host = g.voiceHost ?: return hide()
        g.connection.start()
        if (!g.mic.granted()) {
            openApp(Trigger.ASSIST)
            return
        }
        if (!VoiceHostService.start(context, fromForeground = true)) host.onServiceStartRefused()
        host.startVoice(Trigger.ASSIST)
        watch?.cancel()
        watch = scope.launch {
            var wasLive = false
            host.state.collectLatest { s ->
                status?.text = statusText(s)
                val live = s.session.phase != SessionPhase.OFF && s.session.phase != SessionPhase.ERROR
                if (wasLive && !live) hide()
                wasLive = wasLive || live
            }
        }
    }

    override fun onHide() {
        watch?.cancel()
        super.onHide()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(startVoice: Trigger?) {
        val intent = if (startVoice != null) SystemIntents.startVoiceInApp(context, startVoice)
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(intent) else context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("ArchieVIS", "open app failed: ${e.message}")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }

    companion object {
        fun statusText(s: VoiceUiState): String {
            val p = s.session
            if (p.remoteVoiceActive && !p.isOwner) return "Voice is active on another device"
            return "Archie · " + when (p.phase) {
                SessionPhase.OFF -> "Voice ended"
                SessionPhase.CONNECTING, SessionPhase.SUMMARIZING -> "Connecting…"
                SessionPhase.ACTIVE -> "Listening"
                SessionPhase.SPEAKING -> "Speaking"
                SessionPhase.THINKING -> "Thinking"
                SessionPhase.TOOL_USE -> "Using tools"
                SessionPhase.ENDING -> "Ending…"
                SessionPhase.ERROR -> p.errorMessage ?: "Voice error"
            }
        }
    }
}

/**
 * Stub recognizer: `VoiceInteractionServiceInfo` rejects a VIS without a `recognitionService`
 * (spec 14 §2.8). Archie does not offer speech recognition to other apps; every request errors.
 */
class ArchieRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
