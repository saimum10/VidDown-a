package com.viddown.engine.api

import kotlinx.coroutines.flow.Flow

sealed interface EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>
    data class Failure(val error: DownloadError) : EngineResult<Nothing>
}

/**
 * The only thing the browser, the download manager and the UI know about an engine.
 * UI code must never touch NewPipe or yt-dlp classes: each engine lives behind an adapter that implements this.
 *
 * Contract:
 *  * An engine that is not selected is never created, initialised or run. Creating one is the job of the EngineSelector.
 *  * [inspect] and [listFormats] return only what the engine really found; unknown values stay null.
 *  * [download] is cold: nothing happens until it is collected. Cancelling the collecting coroutine MUST stop the work promptly
 *    and release every handle, socket and child process. Files inside the destination directory belong to the download manager:
 *    it deletes them when the user cancels. An engine that can resume keeps its partial data there between attempts and never
 *    touches anything outside that directory. The flow ends after [DownloadEvent.Completed] or [DownloadEvent.Failed].
 *  * [DownloadEvent.Completed] may only be emitted after the whole transfer, any processing and the output validation succeeded.
 *  * Errors are values ([EngineResult.Failure], [DownloadEvent.Failed]), not exceptions.
 */
interface DownloadEngine {
    val id: EngineId
    val displayName: String

    /** One or two sentences shown under the engine's name in Settings. */
    val description: String

    val capabilities: EngineCapabilities

    /** Cheap and side-effect free: must not start a runtime. */
    fun availability(): EngineAvailability

    suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo>

    suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>>

    /**
     * Lists the videos of a playlist link (at most [maxEntries], in the site's order). Only the list is read: no video is inspected
     * or downloaded. Engines that cannot read playlists keep this default, which says so; a link that is not a playlist is also a
     * failure ([ErrorKind.UNSUPPORTED_RESOURCE]).
     */
    suspend fun inspectPlaylist(request: MediaRequest, maxEntries: Int = PlaylistInfo.MAX_ENTRIES): EngineResult<PlaylistInfo> =
        EngineResult.Failure(DownloadError.unsupportedResource(id, "This engine cannot list the videos of a playlist"))

    /** [destinationDir] is an app-private temporary directory; the download manager moves the verified result to its final place. */
    fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent>

    /** Releases everything the engine holds (processes, sockets, runtimes). Safe to call more than once. */
    suspend fun close()
}
