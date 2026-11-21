package com.viddown.download

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FileNames
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.selector.EngineSelector
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns every download from "queued" to "saved":
 *
 *  * Records live in the [store]; the engine writes into `<tempRoot>/<id>/`; only a verified result is handed to [output].
 *    A download is COMPLETED only after the engine finished, the file was checked and it was saved at its final place.
 *  * At most [ManagerSettings.maxConcurrent] downloads run at a time, oldest first. Each keeps the engine it was created with.
 *  * When the network is gone, or only mobile data is available while "Wi-Fi only" is on, running downloads go back to QUEUED
 *    (partial data is kept, so they resume) and nothing starts until the network is allowed again.
 *  * [start] recovers downloads that were running when the process died: they go back to QUEUED and run again.
 *  * Cancelling deletes the temporary data. A failure keeps it only when the failure is the kind a retry can resume.
 *
 * Every state change goes through the state machine in [DownloadState]; every mutation happens under one lock.
 * Nothing here throws into the caller's scope: unexpected errors become FAILED records.
 */
class DownloadManager(
    private val store: DownloadStore,
    private val engines: EngineSelector,
    private val output: OutputStore,
    private val tempRoot: File,
    private val settings: Flow<ManagerSettings>,
    private val network: Flow<NetworkState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val persistIntervalMs: Long = 500,
    private val onUnexpectedError: (Throwable) -> Unit = {},
) {
    private val lock = Mutex()
    private val running = HashMap<String, Job>()
    /** Thread-safe on purpose: the engine selector reads it (inside its own lock) to learn which engines must stay open. */
    private val runningEngine = ConcurrentHashMap<String, EngineId>()
    private val cancelRequested = HashSet<String>()
    private val requeueRequested = HashSet<String>()
    private val finalizing = HashSet<String>()

    /** Request headers (cookies, referer) are kept in memory only: they are never written to the database. */
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    private var currentSettings = ManagerSettings()
    private var currentNetwork = NetworkState.OFFLINE
    private var gate: BlockReason? = BlockReason.NO_NETWORK
    private var started = false

    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live

    private val _outcomes = MutableSharedFlow<DownloadOutcome>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Completed and failed downloads, for notifications. Nobody listening means nothing is kept. */
    val outcomes: SharedFlow<DownloadOutcome> = _outcomes

    private val _work = MutableStateFlow(WorkState(0, 0, BlockReason.NO_NETWORK))
    val work: StateFlow<WorkState> = _work

    private val _engineCommitments = MutableStateFlow<Set<EngineId>>(emptySet())

    /**
     * Engines of the downloads that are queued or running. They keep these engines whatever is selected later; only downloads
     * created after a change use the new choice. Settings uses this to say so when the user switches.
     */
    val engineCommitments: StateFlow<Set<EngineId>> = _engineCommitments

    // ------------------------------------------------------------------------------------------ lifecycle

    /** Recovers interrupted downloads, removes leftovers and begins following settings and network. Safe to call twice. */
    suspend fun start() {
        lock.withLock {
            if (started) return
            started = true
            tempRoot.mkdirs()
            for (t in store.tasksIn(ACTIVE_STATES)) {
                if (t.id !in running) store.save(t.transitionTo(DownloadState.QUEUED).copy(error = null))
            }
            removeOrphanTempDirs()
        }
        // Every change of the selected engine closes the engine that was selected before, unless a download still uses it.
        // Without this the previous engine (and for yt-dlp its Python runtime) would stay loaded until some download finished.
        scope.launch {
            try {
                engines.selection.collect { engines.releaseUnusedBy { runningEngine.values.toSet() } }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onUnexpectedError(t)
            }
        }
        scope.launch {
            try {
                combine(settings.distinctUntilChanged(), network.distinctUntilChanged()) { s, n -> s to n }
                    .collect { (s, n) ->
                        lock.withLock {
                            currentSettings = s.sanitized()
                            currentNetwork = n
                            applyGate()
                            pump()
                            publish()
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onUnexpectedError(t)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ commands

    /**
     * Queues a download with the engine selected right now (in Auto mode: the one that suits the link, the same one that inspected it). The engine is stored with the record and never changed.
     * [listedWith] is the engine that inspected the request and produced [format] (null when the caller did not inspect first): when
     * the selection moved away from it in the meantime the request is rejected with [DownloadError.engineChanged].
     */
    suspend fun enqueue(
        request: MediaRequest,
        format: FormatOption?,
        title: String? = null,
        durationSeconds: Long? = null,
        listedWith: EngineId? = null,
        /** Language code of a subtitle file to save next to the video; null for none. */
        subtitleLang: String? = null,
    ): EnqueueResult {
        val engine = when (val r = engines.engineForUrl(request.url)) {
            is EngineResult.Failure -> return EnqueueResult.Rejected(r.error)
            is EngineResult.Success -> r.value
        }
        // The formats on screen belong to the engine that listed them. If the user switched engines in between, they are not
        // handed to the new engine (format ids differ per engine) and nothing is queued.
        if (listedWith != null && listedWith != engine.id) return EnqueueResult.Rejected(DownloadError.engineChanged(listedWith, engine.id))
        val id = newId()
        val task = DownloadTask(
            id = id,
            engineId = engine.id,
            request = request.copy(headers = emptyMap()), // headers are not part of the record
            format = format,
            title = title?.takeIf { it.isNotBlank() },
            durationSeconds = durationSeconds,
            state = DownloadState.QUEUED,
            totalBytes = format?.takeIf { !it.sizeIsEstimate }?.sizeBytes?.takeIf { it > 0 },
            createdAtMillis = clock(),
            subtitleLang = subtitleLang?.takeIf { it.isNotBlank() },
        )
        if (request.headers.isNotEmpty()) headers[id] = request.headers
        lock.withLock {
            store.save(task)
            pump()
            publish()
        }
        return EnqueueResult.Queued(id)
    }

    /** Stops a running download or cancels a queued one. Temporary data is deleted. A finished download is left alone. */
    suspend fun cancel(id: String) {
        lock.withLock {
            val job = running[id]
            if (job != null) {
                cancelRequested += id
                job.cancel()
                return
            }
            val t = store.get(id) ?: return
            if (!t.state.isTerminal) {
                deleteTemp(id)
                store.save(t.transitionTo(DownloadState.CANCELLED).copy(error = null, bytesDownloaded = 0))
                headers.remove(id)
                clearLive(id)
                publish()
            }
        }
    }

    /** Puts a failed or cancelled download back in the queue. It keeps its engine and resumes from kept data where it can. */
    suspend fun retry(id: String): Boolean = lock.withLock {
        val t = store.get(id) ?: return@withLock false
        if (t.state != DownloadState.FAILED && t.state != DownloadState.CANCELLED) return@withLock false
        store.save(t.transitionTo(DownloadState.QUEUED).copy(error = null))
        pump()
        publish()
        true
    }

    /** Removes the record and its temporary data. The saved file is untouched. Refused (false) while the download runs. */
    suspend fun deleteRecord(id: String): Boolean = lock.withLock {
        // A download that has just finished may still be on its way out of the running set: its record is already final.
        if (id in running && store.get(id)?.state?.isTerminal != true) return@withLock false
        removeRecord(id)
        publish()
        true
    }

    /** Removes failed and cancelled records (never completed ones: those are the library). Returns how many. */
    suspend fun clearUnsuccessful(): Int = lock.withLock {
        val victims = store.tasksIn(setOf(DownloadState.FAILED, DownloadState.CANCELLED))
        victims.forEach { removeRecord(it.id) }
        publish()
        victims.size
    }

    /** Clears the whole download history (records only, never files). Running and queued downloads stay. */
    suspend fun clearHistory(): Int = lock.withLock {
        val victims = store.tasksIn(DownloadState.entries.filter { it.isTerminal }.toSet())
        victims.forEach { removeRecord(it.id) }
        publish()
        victims.size
    }

    /** Deletes the saved file, then the record. The record is kept when the file could not be deleted. */
    suspend fun deleteFile(id: String): DeleteFileResult = lock.withLock {
        val t = store.get(id) ?: return@withLock DeleteFileResult.NoRecord
        val location = t.outputPath
        if (location == null) {
            removeRecord(id)
            publish()
            return@withLock DeleteFileResult.AlreadyGone
        }
        when (val r = output.delete(location)) {
            DeleteResult.Deleted -> {
                removeRecord(id)
                publish()
                DeleteFileResult.Deleted
            }
            DeleteResult.NotFound -> {
                removeRecord(id)
                publish()
                DeleteFileResult.AlreadyGone
            }
            is DeleteResult.Failed -> DeleteFileResult.Failed(r.reason)
        }
    }

    /** Whether the saved file of a completed download still exists (it may have been deleted by another app). */
    suspend fun fileExists(id: String): Boolean {
        val location = store.get(id)?.outputPath ?: return false
        return output.exists(location)
    }

    /** Size of everything in the temporary folder. */
    fun temporaryBytes(): Long = sizeOf(tempRoot)

    /**
     * Deletes temporary data of downloads that are not queued or running. A failed download loses its kept partial data
     * (a retry then starts from zero). Returns the bytes freed.
     */
    suspend fun cleanupTemporaryFiles(): Long = lock.withLock {
        var freed = 0L
        for (d in tempRoot.listFiles { f -> f.isDirectory }.orEmpty()) {
            val t = store.get(d.name)
            val keep = d.name in running || (t != null && (t.state == DownloadState.QUEUED || t.state.isActive || t.state == DownloadState.PAUSED))
            if (keep) continue
            freed += sizeOf(d)
            d.deleteRecursively()
            if (t != null && t.state == DownloadState.FAILED && t.bytesDownloaded != 0L) store.save(t.copy(bytesDownloaded = 0))
        }
        freed
    }

    // ------------------------------------------------------------------------------------------ scheduling (lock held)

    private fun blockReason(): BlockReason? = when {
        currentNetwork == NetworkState.OFFLINE -> BlockReason.NO_NETWORK
        currentSettings.wifiOnly && currentNetwork != NetworkState.WIFI -> BlockReason.WIFI_REQUIRED
        else -> null
    }

    /** Sends running downloads back to the queue when the network is not allowed. Downloads that are already saving are left to finish. */
    private fun applyGate() {
        gate = blockReason()
        if (gate == null) return
        for ((id, job) in running) {
            if (id in cancelRequested || id in finalizing) continue
            requeueRequested += id
            job.cancel()
        }
    }

    private suspend fun pump() {
        if (gate != null) return
        var slots = currentSettings.maxConcurrent - running.size
        if (slots <= 0) return
        for (t in store.tasksIn(setOf(DownloadState.QUEUED))) {
            if (slots <= 0) break
            if (t.id in running) continue
            launchTask(t)
            slots--
        }
    }

    private fun launchTask(t: DownloadTask) {
        runningEngine[t.id] = t.engineId
        val job = scope.launch(start = CoroutineStart.LAZY) { runGuarded(t.id) }
        running[t.id] = job
        job.start()
    }

    private suspend fun publish() {
        val queuedTasks = store.tasksIn(setOf(DownloadState.QUEUED))
        val queued = queuedTasks.count { it.id !in running }
        _work.value = WorkState(running = running.size, queued = queued, blockedBy = gate)
        _engineCommitments.value = queuedTasks.mapTo(HashSet<EngineId>()) { it.engineId } + runningEngine.values
    }

    // ------------------------------------------------------------------------------------------ running one download

    private suspend fun runGuarded(id: String) {
        try {
            runTask(id)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { safely { onCancelled(id) } }
            throw e
        } catch (t: Throwable) {
            onUnexpectedError(t)
            withContext(NonCancellable) { safely { failUnexpected(id, t) } }
        } finally {
            withContext(NonCancellable) { safely { finish(id) } }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onUnexpectedError(t)
        }
    }

    private class AbortDownload(val error: DownloadError) : RuntimeException("download aborted", null, false, false)

    private suspend fun runTask(id: String) {
        var task = store.get(id) ?: return
        if (task.state != DownloadState.QUEUED) return
        task = task.copy(request = task.request.copy(headers = headers[id].orEmpty()))
        task = move(task, DownloadState.PREPARING) { it.copy(error = null) }

        val engine = when (val r = engines.engineFor(task.engineId)) {
            // Changing the selected engine cannot help this record (it keeps its engine), so the message says what can. The partial
            // data stays: the engine may work again later (for example after the runtime was repaired) and Retry resumes.
            is EngineResult.Failure -> return fail(
                task,
                DownloadError.engineUnavailableForDownload(task.engineId, r.error.technicalDetail.ifBlank { r.error.userMessage }),
                keepTemp = true,
            )
            is EngineResult.Success -> r.value
        }
        val dir = File(tempRoot, id)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return fail(task, DownloadError.invalidDestination("The temporary folder could not be created"))
        }

        var lastPersist: Long? = null
        var spaceChecked = false
        var finished = false
        try {
            engine.download(task, dir.absolutePath).collect { event ->
                when (event) {
                    DownloadEvent.Started -> {
                        if (task.state == DownloadState.PREPARING) task = move(task, DownloadState.DOWNLOADING)
                    }
                    is DownloadEvent.Progress -> {
                        if (task.state == DownloadState.PREPARING) task = move(task, DownloadState.DOWNLOADING)
                        val total = event.totalBytes ?: task.totalBytes
                        task = task.copy(bytesDownloaded = event.bytesDownloaded, totalBytes = total)
                        setLive(id, LiveProgress(event.speedBytesPerSecond, event.etaSeconds, null))
                        if (!spaceChecked && total != null) {
                            spaceChecked = true
                            checkSpace(total, event.bytesDownloaded)?.let { throw AbortDownload(it) }
                        }
                        val now = clock()
                        if (lastPersist == null || now - lastPersist!! >= persistIntervalMs) {
                            lastPersist = now
                            store.save(task)
                        }
                    }
                    is DownloadEvent.Processing -> {
                        setLive(id, LiveProgress(null, null, event.stage)) // before the state change, so the stage is there when PROCESSING shows
                        if (task.state != DownloadState.PROCESSING) task = move(task, DownloadState.PROCESSING)
                    }
                    is DownloadEvent.Completed -> {
                        finished = true
                        finalizeDownload(task, event)
                    }
                    is DownloadEvent.Failed -> {
                        finished = true
                        fail(task, event.error)
                    }
                }
            }
        } catch (e: AbortDownload) {
            finished = true
            fail(task, e.error)
        }
        if (!finished) {
            fail(task, DownloadError.extractionFailed(task.engineId, "The engine ended without reporting a result"))
        }
    }

    /**
     * While downloading the temporary file grows to the full size, and saving then writes a second copy: both exist for a moment.
     * Checking this up front avoids a download that fails at 100%.
     */
    private fun checkSpace(total: Long, alreadyHave: Long): DownloadError? {
        val needed = (total - alreadyHave).coerceAtLeast(0) + total
        val free = tempRoot.usableSpace
        return if (needed > free) {
            DownloadError.insufficientStorage("About $needed bytes are needed (download plus the saved copy), $free are free")
        } else {
            null
        }
    }

    private suspend fun finalizeDownload(start: DownloadTask, event: DownloadEvent.Completed) {
        var task = start
        lock.withLock { finalizing += task.id }
        val dir = File(tempRoot, task.id)
        val file = File(event.outputPath)
        val insideOwnFolder = try {
            file.canonicalFile.parentFile == dir.canonicalFile
        } catch (e: java.io.IOException) {
            false
        }
        if (!insideOwnFolder) {
            return fail(task, DownloadError.invalidDestination("The engine reported a file outside its own folder"), keepTemp = false)
        }
        if (!file.isFile || event.sizeBytes <= 0 || file.length() != event.sizeBytes) {
            return fail(
                task,
                DownloadError.extractionFailed(task.engineId, "The result file is missing or its size differs (reported ${event.sizeBytes} bytes, found ${if (file.isFile) file.length() else -1})"),
                keepTemp = false,
            )
        }
        setLive(task.id, LiveProgress(null, null, "Saving"))
        if (task.state != DownloadState.PROCESSING) task = move(task, DownloadState.PROCESSING)

        val name = FileNames.sanitize(file.name)
        val mime = MediaMimeTypes.forExtension(FileNames.extensionOf(name))
        when (val result = output.publish(file, name, mime)) {
            is PublishResult.Failed -> fail(task, result.error, keepTemp = false)
            is PublishResult.Published -> withContext(NonCancellable) {
                publishExtras(dir, event.extraFiles)
                // The file is saved: record it even if a cancel arrives right now.
                val done = task.transitionTo(DownloadState.COMPLETED).copy(
                    outputPath = result.location,
                    bytesDownloaded = event.sizeBytes,
                    totalBytes = event.sizeBytes,
                    title = task.title?.takeIf { it.isNotBlank() } ?: result.displayName,
                    error = null,
                )
                store.save(done)
                deleteTemp(task.id)
                clearLive(task.id)
                _outcomes.tryEmit(DownloadOutcome.Completed(done))
            }
        }
    }

    /**
     * Saves the files that belong to the result (a subtitle) next to it. They must be inside the download's own folder; a file that
     * cannot be saved is skipped: the video itself is already saved and stays a success.
     */
    private suspend fun publishExtras(dir: File, extras: List<String>) {
        for (path in extras) {
            val extra = File(path)
            val inside = try {
                extra.canonicalFile.parentFile == dir.canonicalFile
            } catch (e: java.io.IOException) {
                false
            }
            if (!inside || !extra.isFile || extra.length() <= 0) continue
            val ext = FileNames.extensionOf(extra.name)
            val mime = MediaMimeTypes.forExtension(ext) ?: when (ext) {
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                else -> "text/plain"
            }
            output.publish(extra, FileNames.sanitize(extra.name), mime)
        }
    }

    private suspend fun fail(task: DownloadTask, error: DownloadError, keepTemp: Boolean = isResumable(error)) {
        withContext(NonCancellable) {
            val failed = task.transitionTo(DownloadState.FAILED).copy(
                error = error,
                bytesDownloaded = if (keepTemp) task.bytesDownloaded else 0,
            )
            // Data first, record second: whoever sees FAILED can rely on the files being gone, and a crash in between only
            // leaves a record that start() recovers.
            if (!keepTemp) deleteTemp(task.id)
            store.save(failed)
            clearLive(task.id)
            _outcomes.tryEmit(DownloadOutcome.Failed(failed, error))
        }
    }

    private suspend fun failUnexpected(id: String, t: Throwable) {
        val task = store.get(id) ?: return
        if (task.state.isTerminal) return
        fail(task, DownloadError.extractionFailed(task.engineId, "Unexpected error: ${t.javaClass.simpleName}: ${t.message}"), keepTemp = false)
    }

    private fun isResumable(e: DownloadError): Boolean = when (e.kind) {
        ErrorKind.CONNECTION_INTERRUPTED, ErrorKind.NETWORK_UNAVAILABLE, ErrorKind.INSUFFICIENT_STORAGE -> true
        ErrorKind.SERVER_REJECTED -> e.httpStatus?.let { it in 500..599 || it == 429 || it == 408 } ?: false
        else -> false
    }

    /** Runs when the download's coroutine was cancelled: by the user, by the network gate, or because the app is shutting down. */
    private suspend fun onCancelled(id: String) {
        val (byUser, byGate) = lock.withLock { (id in cancelRequested) to (id in requeueRequested) }
        val task = store.get(id) ?: return
        when {
            byUser -> if (!task.state.isTerminal) {
                deleteTemp(id)
                store.save(task.transitionTo(DownloadState.CANCELLED).copy(error = null, bytesDownloaded = 0))
                headers.remove(id)
            }
            byGate -> if (task.state.isActive) store.save(task.transitionTo(DownloadState.QUEUED))
            // Shutdown: the record stays as it is and start() recovers it next time.
        }
        clearLive(id)
    }

    private suspend fun finish(id: String) {
        lock.withLock {
            running.remove(id)
            runningEngine.remove(id)
            cancelRequested.remove(id)
            requeueRequested.remove(id)
            finalizing.remove(id)
            if (store.get(id)?.state?.isTerminal == true) headers.remove(id)
            pump()
            publish()
        }
        engines.releaseUnusedBy { runningEngine.values.toSet() }
    }

    // ------------------------------------------------------------------------------------------ small helpers

    private suspend fun move(task: DownloadTask, next: DownloadState, change: (DownloadTask) -> DownloadTask = { it }): DownloadTask {
        val moved = change(task.transitionTo(next))
        store.save(moved)
        return moved
    }

    private suspend fun removeRecord(id: String) {
        store.deleteRecord(id)
        deleteTemp(id)
        headers.remove(id)
        clearLive(id)
    }

    private fun deleteTemp(id: String) {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id == "." || id == "..") return
        File(tempRoot, id).deleteRecursively()
    }

    private suspend fun removeOrphanTempDirs() {
        for (d in tempRoot.listFiles { f -> f.isDirectory }.orEmpty()) {
            val t = store.get(d.name)
            if (t == null || t.state == DownloadState.COMPLETED || t.state == DownloadState.CANCELLED) d.deleteRecursively()
        }
    }

    private fun setLive(id: String, p: LiveProgress) = _live.update { it + (id to p) }

    private fun clearLive(id: String) = _live.update { it - id }

    private fun sizeOf(file: File): Long =
        if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf { sizeOf(it) }

    private companion object {
        val ACTIVE_STATES = setOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING)
    }
}
