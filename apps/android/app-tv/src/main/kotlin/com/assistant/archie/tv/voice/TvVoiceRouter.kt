package com.assistant.archie.tv.voice

import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.shell.Conversation

/** Where one burst of the remote's microphone goes (spec 15 §4.1). */
sealed interface VoiceDestination {
    /** Live voice is running and this device owns it: the burst is a walkie-talkie turn. */
    data object Live : VoiceDestination

    /** An agent conversation is on screen: transcribe → the dictation chip → send to that agent. */
    data class Dictation(val localId: String?, val sdkId: String?, val title: String?) : VoiceDestination

    /** Anything else: a voice message (`send_audio`) to the current Archie conversation. */
    data object VoiceMessage : VoiceDestination

    /** Settings → Remote microphone or the first-run mic check is showing: the burst is a mic test. */
    data object Test : VoiceDestination
}

/** What the router looks at when a burst starts. */
data class RouteInputs(
    /** This device owns a live voice session (connecting or running). */
    val liveOwned: Boolean,
    /** The screen on top of the back stack (null: no shell composed). */
    val screen: NavKey?,
    /** A mic test page is showing (it registers itself while composed). */
    val testing: Boolean = false,
    /** The agent conversation's title, for the overlay ("To Claude · Fix wake word drift"). */
    val agentTitle: String? = null,
)

/** Pure routing table of spec 15 §4.1 (first match wins). */
object TvVoiceRouter {
    fun route(i: RouteInputs): VoiceDestination {
        if (i.liveOwned) return VoiceDestination.Live // never break a running call
        if (i.testing) return VoiceDestination.Test
        val s = i.screen
        if (s is Conversation && !s.archie && (s.localId != null || s.sdkId != null)) {
            return VoiceDestination.Dictation(s.localId, s.sdkId, i.agentTitle)
        }
        return VoiceDestination.VoiceMessage
    }
}
