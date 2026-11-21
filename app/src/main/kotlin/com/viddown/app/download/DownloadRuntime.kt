package com.viddown.app.download

import android.content.Context
import com.viddown.data.repo.DownloadRepository
import com.viddown.data.settings.SettingsRepository
import com.viddown.download.DownloadManager
import com.viddown.download.DownloadStore
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Lets the manager use the Room-backed repository. */
class RepositoryDownloadStore(private val repository: DownloadRepository) : DownloadStore {
    override suspend fun save(task: DownloadTask) {
        repository.save(task)
    }
    override suspend fun get(id: String): DownloadTask? = repository.get(id)
    override suspend fun tasksIn(states: Set<DownloadState>): List<DownloadTask> = repository.tasksIn(states)
    override suspend fun deleteRecord(id: String) {
        repository.deleteRecord(id)
    }
}

/**
 * Starts the download machinery once per process: recovers interrupted downloads, keeps the foreground service in step with
 * the work, and posts completed/failed notifications (when the user allows them).
 */
class DownloadRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val manager: () -> DownloadManager,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext

    fun start() {
        scope.launch(Dispatchers.IO) {
            val m = manager()
            // Subscribe before start(), so nothing that start() triggers can be missed.
            launch {
                m.work.map { it.hasWork }.distinctUntilChanged().filter { it }.collect { DownloadService.start(appContext) }
            }
            launch {
                m.outcomes.collect { outcome ->
                    if (settings.settings.first().notificationsEnabled) Notifications.notifyOutcome(appContext, outcome)
                }
            }
            m.start()
        }
    }
}
