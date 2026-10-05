package com.assistant.core.voicehost.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.notify.NotificationModel

/**
 * Builds the single ongoing host notification ([com.assistant.core.voicehost.HostTuning.NOTIFICATION_ID])
 * from a pure [NotificationModel]. Actions that only change state (Mute, End, Pause listening) go
 * straight to the running service (`PendingIntent.getService`: allowed, the service is already in
 * the foreground). Actions that need the microphone from the background (Talk, Resume listening,
 * Reconnect) go through the trampoline Activity, a foreground context (spec 14 §2.6).
 */
class HostNotifications(private val context: Context, private val config: HostConfig) {
    private val spec = config.notification
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var voiceSinceWallMs = 0L

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val host = NotificationChannel(spec.hostChannelId, spec.hostChannelName, NotificationManager.IMPORTANCE_LOW).apply {
            description = spec.hostChannelDescription
            setShowBadge(false)
        }
        manager.createNotificationChannel(host)
        if (spec.voiceChannelId != spec.hostChannelId) {
            val voice = NotificationChannel(spec.voiceChannelId, spec.voiceChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(voice)
        }
    }

    fun build(model: NotificationModel): Notification {
        if (model.voice && voiceSinceWallMs == 0L) voiceSinceWallMs = System.currentTimeMillis()
        if (!model.voice) voiceSinceWallMs = 0L
        val open = PendingIntent.getActivity(context, 0, Intent(context, config.launchActivity), FLAGS)
        val b = NotificationCompat.Builder(context, model.channelId)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setSmallIcon(spec.smallIcon)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(if (model.voice) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
        if (model.voice) b.setUsesChronometer(true).setWhen(voiceSinceWallMs).setShowWhen(true)
        model.actions.forEachIndexed { i, a -> b.addAction(0, a.label, pendingFor(a, i + 1)) }
        return b.build()
    }

    fun notify(n: Notification, id: Int) = manager.notify(id, n)

    private fun pendingFor(action: NotificationAction, requestCode: Int): PendingIntent = when (action) {
        NotificationAction.TALK, NotificationAction.RESUME_LISTENING, NotificationAction.RECONNECT ->
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, config.trampolineActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(VoiceHostService.EXTRA_HOST_ACTION, action.name),
                FLAGS,
            )
        else -> PendingIntent.getService(
            context,
            requestCode,
            Intent(context, VoiceHostService::class.java).setAction(VoiceHostService.ACTION_PREFIX + action.name),
            FLAGS,
        )
    }

    private companion object {
        /** FLAG_IMMUTABLE is an API 23 constant; older platforms ignore the bit (the old app did the same). */
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
