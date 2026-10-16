package com.assistant.archie.tv.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/*
 * Navigation 3 keys (spec 15 §3.4, mirroring :app-main's Routes.kt). The back stack is plain
 * observable state; these @Serializable keys survive process death. Drawer destinations replace the
 * stack root ([TvNavigator.selectRoot]); pushed content (Conversation, Viewer, MemoryDoc,
 * SettingsPage, Connect) is pushed on top ([TvNavigator.push]).
 */

@Serializable data object Home : NavKey

@Serializable data object Conversations : NavKey

/**
 * One conversation, full screen (T2). [archie] = the current Archie (orchestrator) conversation;
 * otherwise an agent session by pool key [localId] and/or history id [sdkId].
 */
@Serializable data class Conversation(val localId: String? = null, val sdkId: String? = null, val archie: Boolean = false) : NavKey

@Serializable data object Visuals : NavKey

/**
 * The full-screen web viewer (T3). [target]: a root-relative server path (`visualizations/x.html`)
 * or an absolute URL. [pushed] = opened by a TV control command ("Opened by Archie").
 */
@Serializable data class Viewer(val target: String, val title: String? = null, val pushed: Boolean = false) : NavKey

@Serializable data object Memory : NavKey

@Serializable data class MemoryDoc(val path: String) : NavKey

@Serializable data object Settings : NavKey

/** Settings → detail pages (spec 15 §3.5). */
@Serializable
enum class TvSettingsPageId(val title: String) {
    /** T5: the Connection pane (status, server, certificate); changing the server pushes [Connect]. */
    CONNECTION("Connection"),
    REMOTE_MIC("Remote microphone"),
    VOICE("Voice"),
    DISPLAY("Display"),
    TV_CONTROL("TV control"),
    SERVER("Server"),
    ABOUT("About"),
}

@Serializable data class SettingsPage(val id: TvSettingsPageId) : NavKey

/** Server discovery, manual address and certificate trust (spec 15 §3.6); the first-run root when [firstRun]. */
@Serializable data class Connect(val firstRun: Boolean = false) : NavKey
