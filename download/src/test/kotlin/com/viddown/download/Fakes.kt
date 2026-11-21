package com.viddown.download

import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.selector.EngineDescriptor
import com.viddown.engine.selector.EngineRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class FakeStore : DownloadStore {
    val tasks = ConcurrentHashMap<String, DownloadTask>()

    /** Every saved state per id, in order: used to prove that only legal transitions happen. */
    val history = ConcurrentHashMap<String, CopyOnWriteArrayList<DownloadState>>()
    val saveCount = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun save(task: DownloadTask) {
        history.getOrPut(task.id) { CopyOnWriteArrayList() }.add(task.state)
        saveCount.incrementAndGet()
        tasks[task.id] = task // visible last, so a test that sees the state also sees its history entry
    }

    override suspend fun get(id: String): DownloadTask? = tasks[id]

    override suspend fun tasksIn(states: Set<DownloadState>): List<DownloadTask> =
        tasks.values.filter { it.state in states }.sortedBy { it.createdAtMillis }

    override suspend fun deleteRecord(id: String) {
        tasks.remove(id)
    }

    /** Seeds a record without going through the manager (for example one left over from a dead process). */
    fun seed(task: DownloadTask) {
        tasks[task.id] = task
    }
}

class FakeOutput : OutputStore {
    val published = CopyOnWriteArrayList<Triple<String, String, String?>>() // location, name, mime
    val contents = ConcurrentHashMap<String, ByteArray>()
    @Volatile var failWith: com.viddown.engine.api.DownloadError? = null
    @Volatile var deleteResult: DeleteResult = DeleteResult.Deleted
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val deleted = CopyOnWriteArrayList<String>()
    val existing = ConcurrentHashMap.newKeySet<String>()

    override suspend fun publish(source: File, displayName: String, mimeType: String?): PublishResult {
        gate?.await()
        failWith?.let { return PublishResult.Failed(it) }
        val location = "content://test/${published.size}/$displayName"
        published += Triple(location, displayName, mimeType)
        contents[location] = source.readBytes()
        existing += location
        return PublishResult.Published(location, displayName)
    }

    override suspend fun delete(location: String): DeleteResult {
        deleted += location
        if (deleteResult == DeleteResult.Deleted) existing -= location
        return deleteResult
    }

    override suspend fun exists(location: String): Boolean = location in existing
}

class FakeEngine(
    override val id: EngineId = EngineId.NEWPIPE,
    private val script: suspend FakeEngine.(DownloadTask, File) -> Flow<DownloadEvent>,
) : DownloadEngine {
    override val displayName = "Fake ${id.label}"
    override val description = "test"
    override val capabilities = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = true, listsFormats = false, mayNeedFfmpeg = false)
    val runs = CopyOnWriteArrayList<String>()
    val concurrent = java.util.concurrent.atomic.AtomicInteger()
    val maxConcurrent = java.util.concurrent.atomic.AtomicInteger()
    val cancelled = CopyOnWriteArrayList<String>()

    /** Ids whose flow body was left for any reason (cancelled, failed downstream, finished). */
    val stopped = CopyOnWriteArrayList<String>()
    val receivedHeaders = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile var closed = 0

    override fun availability(): EngineAvailability = EngineAvailability.Available
    override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> = EngineResult.Success(MediaInfo(id, request.url))
    override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())

    override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = flow {
        runs += task.id
        receivedHeaders[task.id] = task.request.headers
        val now = concurrent.incrementAndGet()
        maxConcurrent.updateAndGet { maxOf(it, now) }
        try {
            emitAll(script(task, File(destinationDir)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled += task.id
            throw e
        } finally {
            stopped += task.id
            concurrent.decrementAndGet()
        }
    }

    override suspend fun close() {
        closed++
    }

    companion object {
        /** An engine that succeeds at once with a file of [size] bytes. */
        fun succeeding(size: Int = 1000, name: String = "out.mp4", id: EngineId = EngineId.NEWPIPE) = FakeEngine(id) { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Progress(size / 2L, size.toLong()))
                dir.mkdirs()
                File(dir, name).writeBytes(ByteArray(size) { 1 })
                emit(DownloadEvent.Progress(size.toLong(), size.toLong()))
                emit(DownloadEvent.Completed(File(dir, name).absolutePath, size.toLong()))
            }
        }
    }
}

class FakeRegistry(private val engines: Map<EngineId, DownloadEngine>, private val unavailable: Map<EngineId, String> = emptyMap()) : EngineRegistry {
    override val descriptors: List<EngineDescriptor> = EngineId.entries.map { eid ->
        EngineDescriptor(eid, "test") {
            val why = unavailable[eid]
            if (why != null || eid !in engines) EngineAvailability.NotAvailable(why ?: "Not available in this build") else EngineAvailability.Available
        }
    }
    val created = CopyOnWriteArrayList<EngineId>()
    override fun create(id: EngineId): DownloadEngine {
        created += id
        return engines.getValue(id)
    }
}

suspend fun eventually(timeoutMs: Long = 10_000, message: String = "condition", cond: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!cond()) delay(5) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out waiting for: $message")
    }
}
