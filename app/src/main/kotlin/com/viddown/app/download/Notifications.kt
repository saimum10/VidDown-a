package com.viddown.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.viddown.app.MainActivity
import com.viddown.app.R
import com.viddown.core.model.DownloadUi
import com.viddown.download.BlockReason
import com.viddown.download.DownloadOutcome
import com.viddown.download.WorkState
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask

/** Everything about notifications in one place. No notification ever contains a URL, a header or a token. */
object Notifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_RESULT = "downloads_result"
    const val FOREGROUND_ID = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_progress_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_result_description)
            },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing notification of the foreground service. [tasks] are the records of everything not finished. */
    fun progress(context: Context, work: WorkState, tasks: List<DownloadTask>): Notification {
        val running = tasks.filter { it.state.isActive }
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_viddown)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
        when {
            running.isNotEmpty() -> {
                builder.setContentTitle(context.resources.getQuantityString(R.plurals.notification_downloading, running.size, running.size))
                builder.setContentText(DownloadUi.title(running.first()))
                val totals = running.map { it.totalBytes?.takeIf { t -> t > 0 } }
                if (totals.all { it != null }) {
                    val total = totals.sumOf { it!! }
                    val done = running.sumOf { it.bytesDownloaded }.coerceIn(0, total)
                    // Notification progress is an Int; scale to keep it exact for sizes beyond 2 GB.
                    val max = 1000
                    builder.setProgress(max, (done.toDouble() / total * max).toInt(), false)
                } else {
                    builder.setProgress(0, 0, true) // unknown total: an honest indeterminate bar, never a fake percentage
                }
            }
            work.blockedBy == BlockReason.WIFI_REQUIRED -> builder.setContentTitle(context.getString(R.string.notification_waiting_wifi))
            work.blockedBy == BlockReason.NO_NETWORK -> builder.setContentTitle(context.getString(R.string.notification_waiting_network))
            else -> {
                builder.setContentTitle(context.getString(R.string.notification_preparing))
                builder.setProgress(0, 0, true)
            }
        }
        if (work.queued > 0) builder.setSubText(context.resources.getQuantityString(R.plurals.notification_queued, work.queued, work.queued))
        return builder.build()
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Completed or failed. The caller checks the user's setting first. */
    fun notifyOutcome(context: Context, outcome: DownloadOutcome) {
        if (!canNotify(context)) return
        val task = outcome.task
        val title = DownloadUi.title(task)
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_viddown)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
        when (outcome) {
            is DownloadOutcome.Completed -> builder
                .setContentTitle(context.getString(R.string.notification_completed))
                .setContentText(title)
            is DownloadOutcome.Failed -> builder
                .setContentTitle(context.getString(R.string.notification_failed))
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n${outcome.error.userMessage}"))
        }
        try {
            NotificationManagerCompat.from(context).notify(task.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call: nothing to show, nothing to fix
        }
    }

    /** Whether [task] still needs the foreground notification. */
    fun isUnfinished(task: DownloadTask): Boolean = task.state == DownloadState.QUEUED || task.state.isActive
}
