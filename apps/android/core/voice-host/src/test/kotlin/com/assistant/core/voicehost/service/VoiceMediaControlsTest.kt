package com.assistant.core.voicehost.service

import android.app.Activity
import android.app.Notification
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.notify.NotificationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The media-style voice notification: shade media controls + lock screen, like a music player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceMediaControlsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun voice(actions: List<NotificationAction>, status: String, muted: Boolean = false) =
        NotificationModel("voice", "Archie", "Archie · $status", actions, voice = true, status = status, muted = muted)

    private val idle = NotificationModel("host", "Archie", "Connected", listOf(NotificationAction.TALK), voice = false)

    @Test
    fun liveVoiceIsAMediaCardOnTheMainApp() {
        val config = HostConfig.main(Activity::class.java, Activity::class.java)
        val received = mutableListOf<NotificationAction>()
        lateinit var media: VoiceMediaSession
        val notes = HostNotifications(app, config) { media.token }
        media = VoiceMediaSession(app, "Archie", notes.openIntent()) { received += it }

        val live = voice(listOf(NotificationAction.MUTE, NotificationAction.END), "Listening")
        media.update(live)
        val n = notes.build(live)
        assertEquals(Notification.MediaStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNotNull(n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(listOf("Mute", "End"), n.actions.map { it.title.toString() })
        assertEquals("Listening", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())

        val (metadata, state) = media.published
        assertEquals(PlaybackState.STATE_PLAYING, state?.state)
        assertEquals(0L, state?.actions) // no play/pause or skip: the custom actions take the compact slots
        assertEquals(listOf("MUTE", "END"), state?.customActions?.map { it.action })
        assertEquals("Listening", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Archie", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))

        // Muted: Unmute replaces Mute; the card says so and keeps PLAYING (paused cards time out).
        media.update(voice(listOf(NotificationAction.UNMUTE, NotificationAction.END), "Muted · Listening", muted = true))
        assertEquals(listOf("UNMUTE", "END"), media.published.second?.customActions?.map { it.action })
        assertEquals(PlaybackState.STATE_PLAYING, media.published.second?.state)

        // Voice over: back to the plain host notification, session inactive.
        media.update(idle)
        assertEquals(PlaybackState.STATE_STOPPED, media.published.second?.state)
        assertNull(notes.build(idle).extras.getString(Notification.EXTRA_TEMPLATE)?.takeIf { "MediaStyle" in it })
        media.release()
    }

    @Test
    fun liteKeepsThePlainVoiceNotification() {
        val notes = HostNotifications(app, HostConfig.lite(Activity::class.java))
        val n = notes.build(voice(listOf(NotificationAction.END), "Listening"))
        assertFalse(n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
    }
}
