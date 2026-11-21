package com.viddown.download

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import java.io.File

/** Where download records live. The app adapts its Room repository to this. */
interface DownloadStore {
    suspend fun save(task: DownloadTask)
    suspend fun get(id: String): DownloadTask?

    /** Oldest first. */
    suspend fun tasksIn(states: Set<DownloadState>): List<DownloadTask>

    /** Removes the record only. */
    suspend fun deleteRecord(id: String)
}

sealed interface PublishResult {
    /** [location] identifies the saved file (a content URI or an absolute path); [displayName] is the name it ended up with. */
    data class Published(val location: String, val displayName: String) : PublishResult
    data class Failed(val error: DownloadError) : PublishResult
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object NotFound : DeleteResult
    data class Failed(val reason: String) : DeleteResult
}

/**
 * The final place of a finished download (MediaStore, a folder the user picked, or the app's own folder).
 * Implementations must not leave a half-written file behind when [publish] fails or the coroutine is cancelled.
 */
interface OutputStore {
    /** Copies or moves the verified [source] to its final destination. The manager deletes [source] afterwards. */
    suspend fun publish(source: File, displayName: String, mimeType: String?): PublishResult

    suspend fun delete(location: String): DeleteResult

    suspend fun exists(location: String): Boolean
}

/** Maps what Android reports about the default network to the three states the manager understands. */
fun classifyNetwork(hasInternet: Boolean, wifiOrEthernet: Boolean): NetworkState = when {
    !hasInternet -> NetworkState.OFFLINE
    wifiOrEthernet -> NetworkState.WIFI
    else -> NetworkState.MOBILE_OR_OTHER
}

enum class NetworkState {
    OFFLINE,

    /** Wi-Fi or Ethernet. */
    WIFI,

    /** Cellular or anything else that is online. */
    MOBILE_OR_OTHER,
}

data class ManagerSettings(val maxConcurrent: Int = 2, val wifiOnly: Boolean = false) {
    internal fun sanitized() = copy(maxConcurrent = maxConcurrent.coerceAtLeast(1))
}

enum class BlockReason { NO_NETWORK, WIFI_REQUIRED }

/** What the manager is doing right now; the foreground service lives exactly as long as there is work to do. */
data class WorkState(val running: Int, val queued: Int, val blockedBy: BlockReason?) {
    /** True while a download runs, or while queued downloads are allowed to start. */
    val hasWork: Boolean get() = running > 0 || (queued > 0 && blockedBy == null)
}

/** Measurements that are only kept in memory. Null means "not reliably measurable": never a guess. */
data class LiveProgress(val speedBytesPerSecond: Long?, val etaSeconds: Long?, val stage: String?)

sealed interface EnqueueResult {
    data class Queued(val id: String) : EnqueueResult
    data class Rejected(val error: DownloadError) : EnqueueResult
}

sealed interface DeleteFileResult {
    data object Deleted : DeleteFileResult

    /** The file was already gone; the record was removed. */
    data object AlreadyGone : DeleteFileResult
    data object NoRecord : DeleteFileResult
    data class Failed(val reason: String) : DeleteFileResult
}

/** A download reached its end. Emitted once, after the record was saved; cancelling is not an outcome. */
sealed interface DownloadOutcome {
    val task: DownloadTask
    data class Completed(override val task: DownloadTask) : DownloadOutcome
    data class Failed(override val task: DownloadTask, val error: DownloadError) : DownloadOutcome
}
