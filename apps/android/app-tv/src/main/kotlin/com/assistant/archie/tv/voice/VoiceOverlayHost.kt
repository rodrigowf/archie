package com.assistant.archie.tv.voice

import androidx.compose.runtime.Composable
import com.assistant.archie.tv.graph.TvAppGraph

/**
 * The voice layer drawn above every screen (spec 15 §3.4 "Voice overlay", §4.4): the hold-to-talk
 * level meter, "Sending…", live-voice orb + transcript, and the agent dictation chip. Owned by T4;
 * T0 draws nothing. Never takes focus except the dictation chip (the user just spoke).
 */
@Composable
fun VoiceOverlayHost(graph: TvAppGraph) {
    // T4.
}
