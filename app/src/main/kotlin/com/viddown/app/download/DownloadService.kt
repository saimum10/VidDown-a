package com.viddown.app.download

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.viddown.app.VidDownApplication
import com.viddown.download.WorkState
import com.viddown.engine.api.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible (ongoing notification) while downloads run. It does no downloading itself: the
 * DownloadManager does. The service stops as soon as there is no work, and it is deliberately NOT sticky: after the system
 * killed the process, downloads are recovered the next time the user opens the app (a background restart could not legally
 * start a foreground service on recent Android versions anyway).
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val container = (application as VidDownApplication).container
        Notifications.ensureChannels(this)
        // Must happen within seconds of startForegroundService(): show something before any other work.
        val first = Notifications.progress(this, container.downloadManager.work.value, emptyList())
        if (!enterForeground(first)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                combine(container.downloads.downloads, container.downloadManager.work) { downloads, work -> downloads to work }
                    .collect { (downloads, work) ->
                        if (!work.hasWork) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf(lastStartId) // ignored when a newer start command arrived in the meantime
                            return@collect
                        }
                        val unfinished = downloads.filter { Notifications.isUnfinished(it) }
                        val notification = Notifications.progress(this@DownloadService, work, unfinished)
                        try {
                            NotificationManagerCompat.from(this@DownloadService).notify(Notifications.FOREGROUND_ID, notification)
                        } catch (e: SecurityException) {
                            // notifications not allowed: the service keeps running with the notification it already has
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, Notifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.FOREGROUND_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        // The system refused (background start restrictions, missing permission). Downloads continue while the process lives.
        false
    }

    /** Android 15 limits "data sync" services to a few hours: stop in time, the app recovers the downloads when opened. */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        /** Asks for the service. Failure to start it (not allowed in the background) is not an error. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException and friends
            }
        }
    }
}
