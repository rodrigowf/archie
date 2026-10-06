package com.assistant.archie.system

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.assistant.archie.R
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.notify.NotificationAction
import com.assistant.core.voicehost.service.VoiceHostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The `background` channel (spec 14 §2.7): when Android refuses the foreground service (12+
 * `ForegroundServiceStartNotAllowedException`, `WakeHealth.BACKGROUND_RESTRICTED`) there is no
 * host notification to carry "Resume listening", so a regular one says so. Tapping it goes through
 * the trampoline (a foreground context) and resumes. It is removed once the host recovers.
 */
internal object BackgroundNotice {
    const val CHANNEL_ID = "background"
    const val NOTIFICATION_ID = 1002
    private const val TAG = "ArchieBackground"

    fun attach(app: Application, host: VoiceHost, scope: CoroutineScope) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        scope.launch {
            host.state.map { it.wake == WakeHealth.BACKGROUND_RESTRICTED }.distinctUntilChanged().collect { restricted ->
                if (!restricted) {
                    nm.cancel(NOTIFICATION_ID)
                    return@collect
                }
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Background listening", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown when Android stops Archie from listening in the background"
                        setShowBadge(false)
                    },
                )
                val resume = PendingIntent.getActivity(
                    app, 1,
                    Intent(app, VoiceTrampolineActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(VoiceHostService.EXTRA_HOST_ACTION, NotificationAction.RESUME_LISTENING.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_archie)
                    .setContentTitle("Archie can't listen in the background")
                    .setContentText("Tap to resume listening")
                    .setContentIntent(resume)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                try {
                    nm.notify(NOTIFICATION_ID, n)
                } catch (e: SecurityException) {
                    Log.w(TAG, "notification not allowed: ${e.message}") // POST_NOTIFICATIONS denied
                }
            }
        }
    }
}
