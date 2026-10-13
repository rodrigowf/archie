package com.assistant.core.voicehost.service

import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.annotation.DrawableRes
import com.assistant.core.voicehost.R
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.notify.NotificationModel

/**
 * The platform MediaSession behind the media-style voice notification
 * ([com.assistant.core.voicehost.NotificationSpec.mediaControls]): it is what makes a live voice
 * conversation show up like a music player — in the shade's media controls and on the lock screen.
 *
 * Android 13+ draws that card from the session, not from the notification: the title and subtitle
 * come from the metadata, the buttons from the PlaybackState's custom actions (no play/pause or skip
 * actions, so Mute/Unmute and End take the compact slots). Older versions use the notification's
 * own actions ([HostNotifications]). The state stays PLAYING for the whole conversation, muted or
 * not: the system times paused cards out of the shade.
 *
 * Active only while voice is live; [update] with a non-voice model deactivates it. The platform
 * APIs used here exist from API 21; the service creates it only where the media-style notification
 * can be built (API 26+).
 */
internal class VoiceMediaSession(
    context: Context,
    private val title: String,
    launch: PendingIntent,
    onAction: (NotificationAction) -> Unit,
) {
    private val session = MediaSession(context, "ArchieVoice").apply {
        setSessionActivity(launch)
        setCallback(object : MediaSession.Callback() {
            override fun onCustomAction(action: String, extras: Bundle?) {
                NotificationAction.entries.firstOrNull { it.name == action }?.let(onAction)
            }
        })
    }

    val token: MediaSession.Token get() = session.sessionToken

    private var shown: NotificationModel? = null

    /** What was last published to the session (tests: Robolectric's MediaController does not relay it). */
    internal var published: Pair<MediaMetadata?, PlaybackState?> = null to null
        private set

    @Synchronized
    fun update(model: NotificationModel) {
        if (!model.voice) {
            if (session.isActive) {
                val stopped = PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build()
                session.setPlaybackState(stopped)
                session.isActive = false
                published = null to stopped
            }
            shown = null
            return
        }
        if (model == shown && session.isActive) return
        shown = model
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, model.status)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, model.status)
            .build()
        session.setMetadata(metadata)
        val state = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(0L)
        model.actions.forEach { a -> state.addCustomAction(PlaybackState.CustomAction.Builder(a.name, a.label, iconFor(a)).build()) }
        val built = state.build()
        session.setPlaybackState(built)
        published = metadata to built
        if (!session.isActive) session.isActive = true
    }

    @Synchronized
    fun release() {
        shown = null
        session.isActive = false
        session.release()
    }

    companion object {
        /** Call-app convention: the mic icon shows the mic's state (on → mic, muted → mic off). */
        @DrawableRes
        fun iconFor(action: NotificationAction): Int = when (action) {
            NotificationAction.MUTE -> R.drawable.ic_voice_mic
            NotificationAction.UNMUTE -> R.drawable.ic_voice_mic_off
            else -> R.drawable.ic_voice_end
        }
    }
}
