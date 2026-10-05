package com.assistant.archie.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.assistant.archie.graph.GraphOwner
import com.assistant.archie.shell.MainActivity
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.service.VoiceHostService

/**
 * The foreground-context entry for everything that must open the microphone from outside the app
 * (spec 14 §2.6, §2.8): the QS tile, the "Talk to Archie" shortcut, `ACTION_ASSIST`, and the host
 * notification's Talk / Resume listening / Reconnect actions. Android 12+ refuses a microphone FGS
 * started from the background, so these go through this transparent Activity: it promotes
 * `VoiceHostService` from the foreground, hands the trigger to the single ingress and finishes.
 * It never shows UI. Without RECORD_AUDIO it opens Archie, which asks with the rationale first.
 */
class VoiceTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        // Stay a moment: the service's startForeground (MICROPHONE) runs after this onCreate, and the
        // while-in-use grant is evaluated while the app is still the top activity.
        window.decorView.postDelayed({ finish() }, LINGER_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val graph = (application as? GraphOwner)?.graph ?: return
        val host = graph.voiceHost ?: return Unit.also { Log.w(TAG, "no voice host in this process") }
        // Headless start after a sticky restart or a cold process: the socket comes up too (idempotent).
        graph.connection.start()
        val hostAction = intent?.getStringExtra(VoiceHostService.EXTRA_HOST_ACTION)
        val trigger = SystemIntents.triggerOf(intent)
        Log.i(TAG, "trampoline: action=${intent?.action} hostAction=$hostAction trigger=$trigger")
        when {
            hostAction != null && hostAction != NotificationAction.TALK.name ->
                VoiceHostService.handleActivityIntent(this, intent) // Resume listening / Reconnect
            trigger != null -> {
                if (!graph.mic.granted()) {
                    // Ask in the app (rationale + system dialog), then start (spec 14 §2.9).
                    startActivity(SystemIntents.startVoiceInApp(this, trigger))
                    return
                }
                if (!VoiceHostService.start(this, fromForeground = true)) graph.voiceHost?.onServiceStartRefused()
                host.startVoice(trigger)
            }
        }
    }

    companion object {
        private const val TAG = "ArchieTrampoline"
        private const val LINGER_MS = 600L

        /** Tile / shortcut / notification "Talk": start voice through the trampoline. */
        fun talkIntent(context: Context, trigger: Trigger): Intent =
            Intent(context, VoiceTrampolineActivity::class.java)
                .setAction(SystemIntents.ACTION_TALK)
                .putExtra(SystemIntents.EXTRA_TRIGGER, trigger.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/** Intent actions and extras of the system entry points (spec 14 §2.8). */
object SystemIntents {
    const val ACTION_TALK = "com.assistant.archie.action.TALK"
    const val ACTION_NEW_ARCHIE = "com.assistant.archie.action.NEW_ARCHIE"
    const val ACTION_NEW_AGENT = "com.assistant.archie.action.NEW_AGENT"

    /** A [Trigger] name. */
    const val EXTRA_TRIGGER = "com.assistant.archie.extra.TRIGGER"

    /** MainActivity: ask for the microphone, then start voice with this [Trigger] name. */
    const val EXTRA_START_VOICE = "com.assistant.archie.extra.START_VOICE"

    /** The trigger a trampoline intent carries, or null when it is not a voice start. */
    fun triggerOf(intent: Intent?): Trigger? {
        intent ?: return null
        if (intent.getStringExtra(VoiceHostService.EXTRA_HOST_ACTION) == NotificationAction.TALK.name) return Trigger.NOTIFICATION
        return when (intent.action) {
            ACTION_TALK -> intent.getStringExtra(EXTRA_TRIGGER)?.let { n -> Trigger.entries.firstOrNull { it.name == n } } ?: Trigger.ASSIST
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> Trigger.ASSIST
            else -> null
        }
    }

    fun startVoiceInApp(context: Context, trigger: Trigger): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_START_VOICE, trigger.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** MainActivity-side handling: shortcuts and the deferred voice start. Returns true when consumed. */
    fun handleInMain(intent: Intent?, commands: ShellCommands, startVoice: (Trigger) -> Unit): Boolean {
        intent ?: return false
        when (intent.action) {
            ACTION_NEW_ARCHIE -> { commands.offer(ShellCommand.NEW_ARCHIE); return true }
            ACTION_NEW_AGENT -> { commands.offer(ShellCommand.NEW_AGENT); return true }
        }
        val name = intent.getStringExtra(EXTRA_START_VOICE) ?: return false
        intent.removeExtra(EXTRA_START_VOICE)
        startVoice(Trigger.entries.firstOrNull { it.name == name } ?: Trigger.ASSIST)
        return true
    }
}
